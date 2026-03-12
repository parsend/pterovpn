package vpn

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/parsend/pterovpn/internal/clientlog"
	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"

	core "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

type Options struct {
	TunFD        int
	MTU          int
	Token        string
	Transport    string
	TLSName      string
	ServerAddrs  []string
	Ready        func()
	Device       device.Device
	CreateDevice func() (device.Device, func(), error)
	Protection   *config.ProtectionOptions
}

func Run(ctx context.Context, opt Options) error {
	if len(opt.ServerAddrs) == 0 {
		return errors.New("server addrs empty")
	}
	clientlog.Info("vpn: starting, servers=%v", opt.ServerAddrs)

	udpMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, opt.Transport, opt.TLSName, opt.Protection)
	if err != nil {
		return err
	}
	defer udpMux.Close()

	var dev device.Device
	var closeDev func()
	if opt.CreateDevice != nil {
		var err error
		dev, closeDev, err = opt.CreateDevice()
		if err != nil {
			return err
		}
		defer closeDev()
	} else if opt.Device != nil {
		dev = opt.Device
	} else {
		var err error
		dev, err = fdbased.Open(strconv.Itoa(opt.TunFD), uint32(opt.MTU), 0)
		if err != nil {
			return err
		}
		defer dev.Close()
	}

	h := &handler{
		opt:    opt,
		udpMux: udpMux,
	}

	if _, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: h,
	}); err != nil {
		return err
	}
	clientlog.OK("vpn: netstack ready")
	if opt.Ready != nil {
		opt.Ready()
	}
	<-ctx.Done()
	clientlog.Info("vpn: stopping")
	return nil
}

type udpAssocKey struct {
	SrcPort uint16
	DstIP   string
	DstPort uint16
}

type udpAssoc struct {
	c net.PacketConn
}

type udpMux struct {
	chans []*udpChan
	mu    sync.RWMutex
	assoc map[udpAssocKey]*udpAssoc
}

func newUDPMux(addrs []string, token string, n int, transport string, tlsName string, prot *config.ProtectionOptions) (*udpMux, error) {
	m := &udpMux{
		chans: make([]*udpChan, n),
		assoc: make(map[udpAssocKey]*udpAssoc),
	}
	for i := 0; i < n; i++ {
		c, err := newUDPChan(byte(i), addrs, token, transport, tlsName, m.dispatch, prot)
		if err != nil {
			clientlog.Drop("vpn: udp channel %d failed: %v", i, err)
			m.Close()
			return nil, err
		}
		clientlog.OK("vpn: udp channel %d connected", i)
		m.chans[i] = c
	}
	return m, nil
}

func (m *udpMux) Close() {
	for _, c := range m.chans {
		if c != nil {
			_ = c.Close()
		}
	}
}

func (m *udpMux) register(k udpAssocKey, a *udpAssoc) {
	m.mu.Lock()
	m.assoc[k] = a
	m.mu.Unlock()
}

func (m *udpMux) unregister(k udpAssocKey) {
	m.mu.Lock()
	delete(m.assoc, k)
	m.mu.Unlock()
}

func (m *udpMux) pick(k udpAssocKey) *udpChan {
	h := sha256.Sum256([]byte(fmt.Sprintf("%d|%s|%d", k.SrcPort, k.DstIP, k.DstPort)))
	idx := int(h[0]) % len(m.chans)
	return m.chans[idx]
}

func (m *udpMux) send(k udpAssocKey, payload []byte) error {
	ch := m.pick(k)
	ip := net.ParseIP(k.DstIP)
	f := protocol.UDPFrame{SrcPort: k.SrcPort, DstIP: ip, DstPort: k.DstPort, Payload: payload}
	return ch.Send(f)
}

func (m *udpMux) dispatch(f protocol.UDPFrame) {
	k := udpAssocKey{SrcPort: f.SrcPort, DstIP: f.DstIP.String(), DstPort: f.DstPort}
	m.mu.RLock()
	a := m.assoc[k]
	m.mu.RUnlock()
	if a == nil {
		clientlog.Warn("vpn: udp dispatch no assoc for %d->%s:%d", f.SrcPort, f.DstIP.String(), f.DstPort)
		return
	}
	if _, err := a.c.WriteTo(f.Payload, nil); err != nil {
		clientlog.Drop("vpn: udp dispatch write error: %v", err)
	}
}

type udpChan struct {
	conn   net.Conn
	r      *bufio.Reader
	w      *bufio.Writer
	maxPad int
	mu     sync.Mutex
	stop   chan struct{}
	cb     func(protocol.UDPFrame)
}

func newUDPChan(id byte, addrs []string, token string, transport string, tlsName string, cb func(protocol.UDPFrame), prot *config.ProtectionOptions) (*udpChan, error) {
	var last error
	start := int(id) % len(addrs)
	for i := 0; i < len(addrs); i++ {
		a := addrs[(start+i)%len(addrs)]
		rawConn, handshakeConn, err := dialTCP(a, token)
		if err != nil {
			last = err
			continue
		}
		maxPad := 32
		if prot != nil && prot.PadS4 > 0 && prot.PadS4 <= 64 {
			maxPad = prot.PadS4
		}
		slot := protocol.TimeSlot()
		maxPad += int(slot % 16)
		if maxPad > 64 {
			maxPad = 64
		}
		bufSize := protocol.BufSizeForConn(slot)
		prefixLen := 0
		junkCount, junkMin, junkMax := 0, 64, 1024
		if prot != nil {
			prefixLen = prot.PadS1 + prot.PadS2 + prot.PadS3
			if prefixLen > 64 {
				prefixLen = 64
			}
			prefixLen += int(slot % 8)
			if prefixLen > 64 {
				prefixLen = 64
			}
			if prot.JunkCount > 0 {
				junkCount = prot.JunkCount
				if prot.JunkMin > 0 {
					junkMin = prot.JunkMin
				}
				if prot.JunkMax > junkMin {
					junkMax = prot.JunkMax
				}
			}
			if strings.EqualFold(prot.Obfuscation, "enhanced") && junkCount > 0 {
				junkCount += 3
				if junkCount > 12 {
					junkCount = 12
				}
			}
		}
		if junkCount == 0 {
			junkCount, junkMin, junkMax = 2, 64, 512
		}
		junkCount, junkMin, junkMax = protocol.ApplyTimeVariation(junkCount, junkMin, junkMax, slot)
		junkStyle, flushPolicy := "", ""
		if prot != nil {
			junkStyle, flushPolicy = prot.JunkStyle, prot.FlushPolicy
		}
		w := bufio.NewWriterSize(handshakeConn, bufSize)
		if err := protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, junkStyle, flushPolicy, func() { _ = w.Flush() }); err != nil {
			_ = rawConn.Close()
			last = err
			continue
		}
		if !strings.EqualFold(flushPolicy, "perChunk") {
			if err := w.Flush(); err != nil {
				_ = rawConn.Close()
				last = err
				continue
			}
		}
		var optsJSON []byte
		if prot != nil || strings.EqualFold(transport, "tls") {
			opts, _ := json.Marshal(effectiveTransportOptions(prot, transport, tlsName))
			optsJSON = opts
		}
		if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleUDP(), id, token, prefixLen, optsJSON, slot); err != nil {
			_ = rawConn.Close()
			last = err
			continue
		}
		if err := w.Flush(); err != nil {
			_ = rawConn.Close()
			last = err
			continue
		}
		connForTraffic := handshakeConn
		if strings.EqualFold(transport, "tls") {
			conn, err := upgradeToTLS(rawConn, tlsName)
			if err != nil {
				_ = rawConn.Close()
				last = err
				continue
			}
			connForTraffic = conn
		}
		uc := &udpChan{
			conn:   connForTraffic,
			r:      bufio.NewReaderSize(connForTraffic, bufSize),
			w:      bufio.NewWriterSize(connForTraffic, bufSize),
			maxPad: maxPad,
			stop:   make(chan struct{}),
			cb:     cb,
		}
		clientlog.Traffic("vpn: udp channel %d uses server %s", id, a)
		go uc.readLoop()
		return uc, nil
	}
	if last == nil {
		last = errors.New("dial failed")
	}
	return nil, last
}

func (c *udpChan) Close() error {
	close(c.stop)
	return c.conn.Close()
}

func (c *udpChan) Send(f protocol.UDPFrame) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return protocol.WriteUDPFrameWithPad(c.w, f, c.maxPad)
}

func (c *udpChan) readLoop() {
	for {
		select {
		case <-c.stop:
			return
		default:
		}
		f, err := protocol.ReadUDPFrame(c.r)
		if err != nil {
			clientlog.Drop("vpn: udp channel read failed: %v", err)
			return
		}
		c.cb(f)
	}
}

type handler struct {
	opt    Options
	udpMux *udpMux
}

func (h *handler) HandleTCP(c adapter.TCPConn) {
	go h.handleTCP(c)
}

func (h *handler) HandleUDP(c adapter.UDPConn) {
	go h.handleUDP(c)
}

func (h *handler) handleUDP(uc adapter.UDPConn) {
	defer uc.Close()

	id := uc.ID()
	srcPort := uint16(id.RemotePort)
	dstIP := tcpipToIP(id.LocalAddress)
	dstPort := uint16(id.LocalPort)
	clientlog.Traffic("vpn: udp assoc %d -> %s:%d", srcPort, dstIP.String(), dstPort)

	k := udpAssocKey{SrcPort: srcPort, DstIP: dstIP.String(), DstPort: dstPort}
	kAlt := udpAssocKey{SrcPort: dstPort, DstIP: dstIP.String(), DstPort: srcPort}
	a := &udpAssoc{c: uc}
	h.udpMux.register(k, a)
	if kAlt != k {
		h.udpMux.register(kAlt, a)
	}
	defer h.udpMux.unregister(k)
	if kAlt != k {
		defer h.udpMux.unregister(kAlt)
	}

	buf := make([]byte, 64*1024)
	for {
		n, _, err := uc.ReadFrom(buf)
		if err != nil {
			clientlog.Drop("vpn: udp read failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			return
		}
		if err := h.udpMux.send(k, buf[:n]); err != nil {
			clientlog.Drop("vpn: udp send failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			return
		}
	}
}

func (h *handler) handleTCP(tc adapter.TCPConn) {
	defer tc.Close()

	id := tc.ID()
	dstIP := tcpipToIP(id.LocalAddress)
	dstPort := uint16(id.LocalPort)

	if h.isServerAddr(dstIP, dstPort) {
		return
	}

	addr := pickAddr(h.opt.ServerAddrs, dstIP, dstPort)
	clientlog.Traffic("vpn: tcp connect %s:%d via %s", dstIP.String(), dstPort, addr)

	var sconn net.Conn
	var r *bufio.Reader
	slot := protocol.TimeSlot()
	for try := 0; try < 2; try++ {
		var err error
		sconn, r, _, err = h.dialAndHandshakeTCP(addr, dstIP, dstPort, slot)
		if err == nil {
			break
		}
		if try == 1 {
			if sconn != nil {
				_ = sconn.Close()
			}
			clientlog.DPI("vpn: tcp connect frame failed: %v", err)
			return
		}
		if sconn != nil {
			_ = sconn.Close()
			sconn = nil
		}
		msg := err.Error()
		if !strings.Contains(msg, "aborted by the software") && !strings.Contains(msg, "broken pipe") && !strings.Contains(msg, "connection reset") {
			if sconn != nil {
				_ = sconn.Close()
			}
			clientlog.DPI("vpn: tcp connect frame failed: %v", err)
			return
		}
	}
	defer sconn.Close()

	deadline := time.Now().Add(5 * time.Minute)
	_ = tc.SetReadDeadline(deadline)
	_ = tc.SetWriteDeadline(deadline)
	_ = sconn.SetReadDeadline(deadline)
	_ = sconn.SetWriteDeadline(deadline)

	copyBufSize := protocol.CopyBufSize(slot)
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, copyBufSize)
		_, _ = io.CopyBuffer(sconn, tc, buf)
		done <- struct{}{}
	}()
	go func() {
		buf := make([]byte, copyBufSize)
		_, _ = io.CopyBuffer(tc, r, buf)
		done <- struct{}{}
	}()
	<-done
	_ = tc.Close()
	_ = sconn.Close()
	<-done
	clientlog.Traffic("vpn: tcp closed %s:%d", dstIP.String(), dstPort)
}

func (h *handler) dialAndHandshakeTCP(addr string, dstIP net.IP, dstPort uint16, slot int64) (net.Conn, *bufio.Reader, *bufio.Writer, error) {
	handshakeConn, rawConn, err := dialTCP(addr, h.opt.Token)
	if err != nil {
		return nil, nil, nil, err
	}
	conn := handshakeConn
	bufSize := protocol.BufSizeForConn(slot)
	r := bufio.NewReaderSize(handshakeConn, bufSize)
	w := bufio.NewWriterSize(handshakeConn, bufSize)
	prefixLen, junkCount, junkMin, junkMax := 0, 0, 64, 1024
	if h.opt.Protection != nil {
		prefixLen = h.opt.Protection.PadS1 + h.opt.Protection.PadS2 + h.opt.Protection.PadS3
		if prefixLen > 64 {
			prefixLen = 64
		}
		if h.opt.Protection.JunkCount > 0 {
			junkCount = h.opt.Protection.JunkCount
			if h.opt.Protection.JunkMin > 0 {
				junkMin = h.opt.Protection.JunkMin
			}
			if h.opt.Protection.JunkMax > junkMin {
				junkMax = h.opt.Protection.JunkMax
			}
		}
		if strings.EqualFold(h.opt.Protection.Obfuscation, "enhanced") && junkCount > 0 {
			junkCount += 3
			if junkCount > 12 {
				junkCount = 12
			}
		}
	}
	if junkCount == 0 {
		junkCount, junkMin, junkMax = 2, 64, 512
	}
	junkCount, junkMin, junkMax = protocol.ApplyTimeVariation(junkCount, junkMin, junkMax, slot)
	junkStyle, flushPolicy := "", ""
	if h.opt.Protection != nil {
		junkStyle, flushPolicy = h.opt.Protection.JunkStyle, h.opt.Protection.FlushPolicy
	}
	if err := protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, junkStyle, flushPolicy, func() { _ = w.Flush() }); err != nil {
		_ = rawConn.Close()
		return nil, nil, nil, err
	}
	if !strings.EqualFold(flushPolicy, "perChunk") {
		if err := w.Flush(); err != nil {
			_ = rawConn.Close()
			return nil, nil, nil, err
		}
	}
	var optsJSON []byte
	if h.opt.Protection != nil || strings.EqualFold(h.opt.Transport, "tls") {
		opts := effectiveTransportOptions(h.opt.Protection, h.opt.Transport, h.opt.TLSName)
		optsJSON, _ = json.Marshal(opts)
	}
	if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, h.opt.Token, prefixLen, optsJSON, slot); err != nil {
		_ = rawConn.Close()
		return nil, nil, nil, err
	}
	if err := protocol.WriteTcpConnect(w, dstIP, dstPort); err != nil {
		_ = rawConn.Close()
		return nil, nil, nil, err
	}
	if err := w.Flush(); err != nil {
		_ = rawConn.Close()
		return nil, nil, nil, err
	}
	if strings.EqualFold(h.opt.Transport, "tls") {
		tlsConn, err := upgradeToTLS(rawConn, h.opt.TLSName)
		if err != nil {
			_ = rawConn.Close()
			return nil, nil, nil, err
		}
		conn = tlsConn
		r = bufio.NewReaderSize(conn, bufSize)
		w = bufio.NewWriterSize(conn, bufSize)
	}
	return conn, r, w, nil
}

func dialTCP(addr string, token string) (net.Conn, net.Conn, error) {
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return obfuscate.WrapConn(c, token), c, nil
}

func effectiveTransportOptions(prot *config.ProtectionOptions, transport string, tlsName string) *config.ProtectionOptions {
	effective := normalizeTransportOptions(prot)
	effective.Transport = strings.ToLower(strings.TrimSpace(transport))
	if effective.Transport != "tls" {
		effective.Transport = "xor"
	}
	effective.TLSName = strings.TrimSpace(tlsName)
	return effective
}

func normalizeTransportOptions(prot *config.ProtectionOptions) *config.ProtectionOptions {
	if prot == nil {
		return &config.ProtectionOptions{}
	}
	return &config.ProtectionOptions{
		Obfuscation: prot.Obfuscation,
		JunkCount:   prot.JunkCount,
		JunkMin:     prot.JunkMin,
		JunkMax:     prot.JunkMax,
		PadS1:       prot.PadS1,
		PadS2:       prot.PadS2,
		PadS3:       prot.PadS3,
		PadS4:       prot.PadS4,
		PreCheck:    prot.PreCheck,
		MagicSplit:  prot.MagicSplit,
		JunkStyle:   prot.JunkStyle,
		FlushPolicy: prot.FlushPolicy,
		Transport:   prot.Transport,
		TLSName:     prot.TLSName,
	}
}

func upgradeToTLS(rawConn net.Conn, tlsName string) (net.Conn, error) {
	serverName := strings.TrimSpace(tlsName)
	if serverName == "" && rawConn != nil && rawConn.RemoteAddr() != nil {
		host, _, err := net.SplitHostPort(rawConn.RemoteAddr().String())
		if err == nil {
			serverName = host
		} else {
			serverName = rawConn.RemoteAddr().String()
		}
	}
	cfg := &tls.Config{
		ServerName:         serverName,
		InsecureSkipVerify: true,
	}
	tlsConn := tls.Client(rawConn, cfg)
	if err := tlsConn.Handshake(); err != nil {
		_ = tlsConn.Close()
		return nil, err
	}
	return tlsConn, nil
}

func (h *handler) isServerAddr(dstIP net.IP, dstPort uint16) bool {
	for _, a := range h.opt.ServerAddrs {
		host, port, err := net.SplitHostPort(a)
		if err != nil || port != strconv.Itoa(int(dstPort)) {
			continue
		}
		ip := net.ParseIP(host)
		if ip != nil && ip.Equal(dstIP) {
			return true
		}
	}
	return false
}

func pickAddr(addrs []string, ip net.IP, port uint16) string {
	if len(addrs) == 1 {
		return addrs[0]
	}
	h := sha256.Sum256([]byte(ip.String() + ":" + fmt.Sprintf("%d", port)))
	return addrs[int(h[0])%len(addrs)]
}

func tcpipToIP(a tcpip.Address) net.IP {
	b := append([]byte(nil), a.AsSlice()...)
	return net.IP(b)
}
