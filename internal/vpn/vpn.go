package vpn

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
	"github.com/unitdevgcc/pterovpn/internal/sockprotect"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"

	core "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

type Options struct {
	TunFD             int
	MTU               int
	Token             string
	ServerAddrs       []string
	Ready             func()
	Device            device.Device
	CreateDevice      func() (device.Device, func(), error)
	Protection        *config.ProtectionOptions
	Transport         string
	QuicServer        string
	QuicServerName    string
	QuicSkipVerify    bool
	QuicCertPinSHA256 string
	QuicTLSRoots      *x509.CertPool
	QuicTraceLog      bool
	DualTransport     bool
	WatchdogInterval time.Duration
	OnWatchdogFail   func()
}

func Run(ctx context.Context, opt Options) error {
	if len(opt.ServerAddrs) == 0 {
		return errors.New("server addrs empty")
	}
	runCtx, stopRun := context.WithCancel(ctx)
	defer stopRun()
	go func() {
		<-ctx.Done()
		stopRun()
	}()
	tunnel.SetQUICTrace(opt.QuicTraceLog)
	if opt.QuicTraceLog {
		clientlog.Info("vpn: quicTraceLog=true — строки TRACE в логе QUIC dial")
	}
	clientlog.Info("vpn: starting, servers=%v", opt.ServerAddrs)
	clientlog.OK("vpn: pteravpn link %s | %s",
		tunnel.PteravpnTunnelTag(opt.Transport, opt.QuicServer), pteravpnTunnelCfg(opt.Transport, opt.QuicServer))
	if opt.DualTransport {
		clientlog.Info("vpn: dual tun-tcp — ~70%% новых TCP через QUIC; SMB/RDP/WinRM/VNC всегда TCP; при ошибке QUIC — fallback TCP; QUIC idle 15m keepalive 8s")
	}
	if tunnel.UsesQUICTransport(opt.Transport, opt.QuicServer) {
		if ep, derived, err := tunnel.ResolveQUICDialAddr(opt.ServerAddrs, opt.QuicServer); err == nil {
			if derived {
				clientlog.Info("vpn: QUIC dial %s (host from tcp + udp port %s, set quicServer if different)", ep, tunnel.DefaultQUICPort)
			} else {
				clientlog.Info("vpn: QUIC dial %s", ep)
			}
		}
	}

	udpMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, opt.Protection, opt.Transport, opt.QuicServer, opt.QuicServerName, opt.QuicSkipVerify, opt.QuicCertPinSHA256, opt.QuicTLSRoots)
	if err != nil {
		return err
	}

	var dev device.Device
	var closeDev func()
	if opt.CreateDevice != nil {
		var err error
		dev, closeDev, err = opt.CreateDevice()
		if err != nil {
			udpMux.Close()
			return err
		}
		defer closeDev()
	} else if opt.Device != nil {
		dev = opt.Device
	} else {
		var err error
		dev, err = fdbased.Open(strconv.Itoa(opt.TunFD), uint32(opt.MTU), 0)
		if err != nil {
			udpMux.Close()
			return err
		}
		defer dev.Close()
	}

	h := &handler{
		opt:    opt,
		udpMux: udpMux,
	}

	st, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: h,
	})
	if err != nil {
		udpMux.Close()
		return err
	}

	defer st.Close()
	defer udpMux.Close()

	clientlog.OK("vpn: netstack ready")
	if opt.Ready != nil {
		opt.Ready()
	}
	if opt.WatchdogInterval > 0 {
		go runWatchdog(runCtx, stopRun, h, opt)
	}
	<-runCtx.Done()
	clientlog.Info("vpn: stopping")
	if h.watchdogTriggered {
		return errors.New("watchdog")
	}
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
	chans     []*udpChan
	assoc     sync.Map
	quicConn  *tunnel.QUICConn
	quicClose func()
}

func (m *udpMux) SharedQUICConn() *tunnel.QUICConn {
	return m.quicConn
}

func newUDPMux(addrs []string, token string, n int, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool) (*udpMux, error) {
	m := &udpMux{
		chans: make([]*udpChan, n),
	}
	if tunnel.UsesQUICTransport(transport, quicServer) {
		closeConn, sharedConn, streams, err := tunnel.DialUDPMuxQUIC(addrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, n, token, prot)
		if err != nil {
			clientlog.Drop("vpn: QUIC UDP mux failed: %v", err)
			return nil, err
		}
		m.quicClose = closeConn
		m.quicConn = sharedConn
		for i := 0; i < n; i++ {
			s := streams[i]
			uc := &udpChan{
				conn:   s.Conn,
				r:      s.R,
				w:      s.W,
				maxPad: s.MaxPad,
				stop:   make(chan struct{}),
				cb:     m.dispatch,
			}
			clientlog.Traffic("vpn: udp ch %d  [%s]  %s", i, tunnel.PteravpnTunnelTag(transport, quicServer), pteravpnTunnelCfg(transport, quicServer))
			go uc.readLoop()
			clientlog.OK("vpn: udp channel %d connected", i)
			m.chans[i] = uc
		}
		return m, nil
	}
	for i := 0; i < n; i++ {
		c, err := newUDPChan(byte(i), addrs, token, m.dispatch, prot, transport, quicServer)
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
	if m.quicClose != nil {
		m.quicClose()
		m.quicClose = nil
	}
	m.quicConn = nil
}

func (m *udpMux) register(k udpAssocKey, a *udpAssoc) {
	m.assoc.Store(k, a)
}

func (m *udpMux) unregister(k udpAssocKey) {
	m.assoc.Delete(k)
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
	v, ok := m.assoc.Load(k)
	if !ok {
		clientlog.Warn("vpn: udp dispatch no assoc for %d->%s:%d", f.SrcPort, f.DstIP.String(), f.DstPort)
		return
	}
	a := v.(*udpAssoc)
	if _, err := a.c.WriteTo(f.Payload, nil); err != nil {
		clientlog.Drop("vpn: udp dispatch write error: %v", err)
	}
}

type udpChan struct {
	conn     net.Conn
	r        *bufio.Reader
	w        *bufio.Writer
	maxPad   int
	mu       sync.Mutex
	stopOnce sync.Once
	stop     chan struct{}
	cb       func(protocol.UDPFrame)
}

func newUDPChan(id byte, addrs []string, token string, cb func(protocol.UDPFrame), prot *config.ProtectionOptions, transport, quicServer string) (*udpChan, error) {
	var last error
	start := int(id) % len(addrs)
	for i := 0; i < len(addrs); i++ {
		a := addrs[(start+i)%len(addrs)]
		c, err := dialTCP(a, token)
		if err != nil {
			last = err
			continue
		}
		slot := protocol.TimeSlot()
		bufSize := protocol.BufSizeForConn(slot)
		uc := &udpChan{
			conn: c,
			r:    bufio.NewReaderSize(c, bufSize),
			w:    bufio.NewWriterSize(c, bufSize),
			stop: make(chan struct{}),
			cb:   cb,
		}
		maxPad, err := tunnel.WriteUDPChannelPreambleSlot(uc.w, id, token, prot, slot)
		if err != nil {
			_ = c.Close()
			last = err
			continue
		}
		uc.maxPad = maxPad
		clientlog.Traffic("vpn: udp ch %d  [%s]  server=%s  %s", id, tunnel.PteravpnTunnelTag(transport, quicServer), a, pteravpnTunnelCfg(transport, quicServer))
		go uc.readLoop()
		return uc, nil
	}
	if last == nil {
		last = errors.New("dial failed")
	}
	return nil, last
}

func (c *udpChan) Close() error {
	c.stopOnce.Do(func() { close(c.stop) })
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
	opt               Options
	udpMux            *udpMux
	watchdogTriggered bool
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
	clientlog.Traffic("vpn: udp assoc %d -> %s:%d  [%s]  %s", srcPort, dstIP.String(), dstPort, tunnel.PteravpnTunnelTag(h.opt.Transport, h.opt.QuicServer), pteravpnTunnelCfg(h.opt.Transport, h.opt.QuicServer))

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

	shared := h.udpMux.SharedQUICConn()
	tunPreferTCP := false
	if h.opt.DualTransport && shared != nil && tunnel.UsesQUICTransport(h.opt.Transport, h.opt.QuicServer) {
		tunPreferTCP = !tunnel.PickQUICForTunTCPFlow(dstIP, dstPort)
	}
	tag := tunnel.PteravpnTunnelTag(h.opt.Transport, h.opt.QuicServer)
	if h.opt.DualTransport && shared != nil {
		if tunPreferTCP {
			tag = "TCP"
		} else {
			tag = "QUIC"
		}
	}
	clientlog.Traffic("vpn: tun-tcp %s:%d  [%s]  %s", dstIP.String(), dstPort, tag, pteravpnTunnelCfg(h.opt.Transport, h.opt.QuicServer))

	var sconn net.Conn
	var r *bufio.Reader
	slot := protocol.TimeSlot()
	var err error
	var fellBackTCP bool
	sconn, fellBackTCP, err = tunnel.DialTunFlow(h.opt.ServerAddrs, dstIP, dstPort, h.opt.Token, h.opt.Protection, h.opt.Transport, h.opt.QuicServer, h.opt.QuicServerName, h.opt.QuicSkipVerify, h.opt.QuicCertPinSHA256, h.opt.QuicTLSRoots, shared, h.opt.DualTransport)
	if fellBackTCP {
		tag = "TCP"
	}
	if err != nil {
		clientlog.DPI("vpn: tcp connect frame failed: %v", err)
		return
	}
	r = bufio.NewReaderSize(sconn, protocol.BufSizeForConn(slot))
	defer sconn.Close()

	deadline := time.Now().Add(30 * time.Minute)
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
	clientlog.Traffic("vpn: tun-tcp closed %s:%d  [%s]", dstIP.String(), dstPort, tag)
}

func pteravpnTunnelCfg(transport, quicServer string) string {
	t := strings.TrimSpace(transport)
	qs := strings.TrimSpace(quicServer)
	if t == "" {
		if qs != "" {
			return fmt.Sprintf("transport=implicit-quic quicServer=%q", qs)
		}
		return "transport=empty"
	}
	if qs != "" {
		return fmt.Sprintf("transport=%s quicServer=%q", t, qs)
	}
	return fmt.Sprintf("transport=%s", t)
}

func dialTCP(addr string, token string) (net.Conn, error) {
	d := net.Dialer{Timeout: 22 * time.Second, KeepAlive: 30 * time.Second}
	if p := sockprotect.Protect; p != nil {
		d.Control = func(network, address string, c syscall.RawConn) error {
			var err error
			e := c.Control(func(fd uintptr) {
				err = p(fd)
			})
			if e != nil {
				return e
			}
			return err
		}
	}
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return obfuscate.WrapConn(c, token), nil
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
