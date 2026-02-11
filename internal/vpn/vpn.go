package vpn

import (
	"bufio"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/parsend/pterovpn/internal/compression"
	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"

	core "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

type Options struct {
	TunFD        int
	MTU          int
	Token        string
	ServerAddrs  []string
	KeepaliveSec int // 0 = default 30
	Quiet        bool
	Obfuscate    bool
	Compression  bool
	Ready        func()
}

func Run(ctx context.Context, opt Options) error {
	if len(opt.ServerAddrs) == 0 {
		return errors.New("server addrs empty")
	}
	if !opt.Quiet {
		log.Printf("vpn: starting, servers=%v", opt.ServerAddrs)
	}

	keepaliveSec := opt.KeepaliveSec
	if keepaliveSec <= 0 {
		keepaliveSec = 30
	}
	udpMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, keepaliveSec, opt.Quiet, opt.Obfuscate, opt.Compression)
	if err != nil {
		return err
	}
	defer udpMux.Close()

	dev, err := fdbased.Open(strconv.Itoa(opt.TunFD), uint32(opt.MTU), 0)
	if err != nil {
		return err
	}
	defer dev.Close()

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
	if !opt.Quiet {
		log.Printf("vpn: netstack ready")
	}
	if opt.Ready != nil {
		opt.Ready()
	}
	select {
	case <-ctx.Done():
		if !opt.Quiet {
			log.Printf("vpn: stopping")
		}
		return nil
	case err := <-udpMux.Dead():
		return err
	}
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
	chans    []*udpChan
	assoc    map[udpAssocKey]*udpAssoc
	mu       sync.RWMutex
	deadChan chan error
}

func newUDPMux(addrs []string, token string, n int, keepaliveSec int, quiet, obfuscate, compression bool) (*udpMux, error) {
	m := &udpMux{
		chans:    make([]*udpChan, n),
		assoc:    make(map[udpAssocKey]*udpAssoc),
		deadChan: make(chan error, 1),
	}
	onDead := func(err error) {
		select {
		case m.deadChan <- err:
		default:
		}
	}
	for i := 0; i < n; i++ {
		c, err := newUDPChan(byte(i), addrs, token, keepaliveSec, quiet, obfuscate, compression, m.dispatch, onDead)
		if err != nil {
			if !quiet {
				log.Printf("vpn: udp channel %d failed: %v", i, err)
			}
			m.Close()
			return nil, err
		}
		if !quiet {
			log.Printf("vpn: udp channel %d connected", i)
		}
		m.chans[i] = c
	}
	return m, nil
}

func (m *udpMux) Dead() <-chan error { return m.deadChan }

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
	f := protocol.UDPFrame{MsgType: protocol.MsgUDP(), SrcPort: k.SrcPort, DstIP: ip, DstPort: k.DstPort, Payload: payload}
	return ch.Send(f)
}

func (m *udpMux) dispatch(f protocol.UDPFrame) {
	if protocol.IsPingPong(f.MsgType) {
		return
	}
	k := udpAssocKey{SrcPort: f.SrcPort, DstIP: f.DstIP.String(), DstPort: f.DstPort}
	m.mu.RLock()
	a := m.assoc[k]
	m.mu.RUnlock()
	if a == nil {
		log.Printf("vpn: udp dispatch no assoc for %d->%s:%d", f.SrcPort, f.DstIP.String(), f.DstPort)
		return
	}
	if _, err := a.c.WriteTo(f.Payload, nil); err != nil {
		log.Printf("vpn: udp dispatch write error: %v", err)
	}
}

type udpChan struct {
	conn          net.Conn
	r             *bufio.Reader
	w             *bufio.Writer
	mu            sync.Mutex
	stop          chan struct{}
	keepaliveSec  int
	quiet         bool
	cb            func(protocol.UDPFrame)
	onDead        func(error)
}

func newUDPChan(id byte, addrs []string, token string, keepaliveSec int, quiet, useObfuscate, useCompression bool, cb func(protocol.UDPFrame), onDead func(error)) (*udpChan, error) {
	var last error
	start := int(id) % len(addrs)
	for i := 0; i < len(addrs); i++ {
		a := addrs[(start+i)%len(addrs)]
		conn, err := dialTCP(a)
		if err != nil {
			last = err
			continue
		}
		if useObfuscate {
			if err := obfuscate.WriteMagic(conn, protocol.Magic()); err != nil {
				_ = conn.Close()
				last = err
				continue
			}
			conn = obfuscate.WrapAfterMagic(conn, token)
		}
		uc := &udpChan{
			conn:         conn,
			r:            bufio.NewReaderSize(conn, 64*1024),
			w:            bufio.NewWriterSize(conn, 64*1024),
			stop:         make(chan struct{}),
			keepaliveSec: keepaliveSec,
			quiet:        quiet,
			cb:           cb,
			onDead:       onDead,
		}
		if useObfuscate {
			err = protocol.WriteHandshakeBody(uc.w, protocol.RoleUDP(), id, token, useCompression)
		} else {
			err = protocol.WriteHandshake(uc.w, protocol.RoleUDP(), id, token, useCompression)
		}
		if err != nil {
			_ = conn.Close()
			last = err
			continue
		}
		if useCompression {
			conn, err = compression.WrapConn(conn)
			if err != nil {
				_ = conn.Close()
				last = err
				continue
			}
			uc.conn = conn
			uc.r = bufio.NewReaderSize(conn, 64*1024)
			uc.w = bufio.NewWriterSize(conn, 64*1024)
		}
		if !quiet {
			log.Printf("vpn: udp channel %d uses server %s", id, a)
		}
		go uc.pingLoop()
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
	return protocol.WriteUDPFrame(c.w, f)
}

func (c *udpChan) pingLoop() {
	ticker := time.NewTicker(time.Duration(c.keepaliveSec) * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.stop:
			return
		case <-ticker.C:
			c.mu.Lock()
			err := protocol.WriteUDPFrame(c.w, protocol.UDPFrame{MsgType: protocol.MsgPing()})
			if err == nil {
				err = c.w.Flush()
			}
			c.mu.Unlock()
			if err != nil {
				c.onDead(err)
				_ = c.conn.Close()
				return
			}
		}
	}
}

func (c *udpChan) readLoop() {
	timeout := time.Duration(c.keepaliveSec*3) * time.Second
	for {
		select {
		case <-c.stop:
			return
		default:
		}
		c.conn.SetReadDeadline(time.Now().Add(timeout))
		f, err := protocol.ReadUDPFrame(c.r)
		if err != nil {
			if !c.quiet {
				log.Printf("vpn: udp channel read failed: %v", err)
			}
			c.onDead(err)
			_ = c.conn.Close()
			return
		}
		c.conn.SetReadDeadline(time.Now().Add(timeout))
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
	if !h.opt.Quiet {
		log.Printf("vpn: udp assoc %d -> %s:%d", srcPort, dstIP.String(), dstPort)
	}

	k := udpAssocKey{SrcPort: srcPort, DstIP: dstIP.String(), DstPort: dstPort}
	a := &udpAssoc{c: uc}
	h.udpMux.register(k, a)
	defer h.udpMux.unregister(k)

	buf := make([]byte, 64*1024)
	for {
		n, _, err := uc.ReadFrom(buf)
		if err != nil {
			if !h.opt.Quiet {
				log.Printf("vpn: udp read failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			}
			return
		}
		p := make([]byte, n)
		copy(p, buf[:n])
		if err := h.udpMux.send(k, p); err != nil {
			if !h.opt.Quiet {
				log.Printf("vpn: udp send failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			}
			return
		}
	}
}

func (h *handler) handleTCP(tc adapter.TCPConn) {
	defer tc.Close()

	id := tc.ID()
	dstIP := tcpipToIP(id.LocalAddress)
	dstPort := uint16(id.LocalPort)

	addr := pickAddr(h.opt.ServerAddrs, dstIP, dstPort)
	if !h.opt.Quiet {
		log.Printf("vpn: tcp connect %s:%d via %s", dstIP.String(), dstPort, addr)
	}
	sconn, err := dialTCP(addr)
	if err != nil {
		if !h.opt.Quiet {
			log.Printf("vpn: tcp dial server failed: %v", err)
		}
		return
	}
	defer sconn.Close()

	if h.opt.Obfuscate {
		if err := obfuscate.WriteMagic(sconn, protocol.Magic()); err != nil {
			if !h.opt.Quiet {
				log.Printf("vpn: tcp handshake failed: %v", err)
			}
			return
		}
		sconn = obfuscate.WrapAfterMagic(sconn, h.opt.Token)
	}

	r := bufio.NewReaderSize(sconn, 64*1024)
	w := bufio.NewWriterSize(sconn, 64*1024)
	var handshakeErr error
	if h.opt.Obfuscate {
		handshakeErr = protocol.WriteHandshakeBody(w, protocol.RoleTCP(), 0, h.opt.Token, h.opt.Compression)
	} else {
		handshakeErr = protocol.WriteHandshake(w, protocol.RoleTCP(), 0, h.opt.Token, h.opt.Compression)
	}
	if handshakeErr != nil {
		if !h.opt.Quiet {
			log.Printf("vpn: tcp handshake failed: %v", handshakeErr)
		}
		return
	}
	if h.opt.Compression {
		wrapped, err := compression.WrapConn(sconn)
		if err != nil {
			if !h.opt.Quiet {
				log.Printf("vpn: tcp compression wrap failed: %v", err)
			}
			return
		}
		sconn = wrapped
		r = bufio.NewReaderSize(sconn, 64*1024)
		w = bufio.NewWriterSize(sconn, 64*1024)
	}
	if err := protocol.WriteTcpConnect(w, dstIP, dstPort); err != nil {
		if !h.opt.Quiet {
			log.Printf("vpn: tcp connect frame failed: %v", err)
		}
		return
	}

	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(sconn, tc)
		_ = sconn.Close()
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(tc, r)
		_ = tc.Close()
		done <- struct{}{}
	}()
	<-done
	if !h.opt.Quiet {
		log.Printf("vpn: tcp closed %s:%d", dstIP.String(), dstPort)
	}
}

func dialTCP(addr string) (net.Conn, error) {
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return c, nil
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
