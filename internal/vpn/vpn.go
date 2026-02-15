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

	"github.com/parsend/pterovpn/internal/protocol"

	core "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

type Options struct {
	TunFD       int
	MTU         int
	Token       string
	ServerAddrs []string
	Ready       func()
}

func Run(ctx context.Context, opt Options) error {
	if len(opt.ServerAddrs) == 0 {
		return errors.New("server addrs empty")
	}
	log.Printf("vpn: starting, servers=%v", opt.ServerAddrs)

	udpMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, protocol.RoleUDP(), "udp")
	if err != nil {
		return err
	}
	defer udpMux.Close()
	quicMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, protocol.RoleQUIC(), "quic")
	if err != nil {
		return err
	}
	defer quicMux.Close()

	dev, err := fdbased.Open(strconv.Itoa(opt.TunFD), uint32(opt.MTU), 0)
	if err != nil {
		return err
	}
	defer dev.Close()

	h := &handler{
		opt:     opt,
		udpMux:  udpMux,
		quicMux: quicMux,
	}

	if _, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: h,
	}); err != nil {
		return err
	}
	log.Printf("vpn: netstack ready")
	if opt.Ready != nil {
		opt.Ready()
	}
	<-ctx.Done()
	log.Printf("vpn: stopping")
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
	name  string
	role  byte
	chans []*udpChan
	mu    sync.RWMutex
	assoc map[udpAssocKey]*udpAssoc
}

func newUDPMux(addrs []string, token string, n int, role byte, name string) (*udpMux, error) {
	m := &udpMux{
		name:  name,
		role:  role,
		chans: make([]*udpChan, n),
		assoc: make(map[udpAssocKey]*udpAssoc),
	}
	for i := 0; i < n; i++ {
		c, err := newUDPChan(byte(i), addrs, token, m.dispatch, role, name)
		if err != nil {
			log.Printf("vpn: %s channel %d failed: %v", name, i, err)
			m.Close()
			return nil, err
		}
		log.Printf("vpn: %s channel %d connected", name, i)
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
		log.Printf("vpn: %s dispatch no assoc for %d->%s:%d", m.name, f.SrcPort, f.DstIP.String(), f.DstPort)
		return
	}
	if _, err := a.c.WriteTo(f.Payload, nil); err != nil {
		log.Printf("vpn: %s dispatch write error: %v", m.name, err)
	}
}

type udpChan struct {
	name string
	conn net.Conn
	r    *bufio.Reader
	w    *bufio.Writer
	mu   sync.Mutex
	stop chan struct{}
	cb   func(protocol.UDPFrame)
}

func newUDPChan(id byte, addrs []string, token string, cb func(protocol.UDPFrame), role byte, name string) (*udpChan, error) {
	var last error
	start := int(id) % len(addrs)
	for i := 0; i < len(addrs); i++ {
		a := addrs[(start+i)%len(addrs)]
		c, err := dialTCP(a)
		if err != nil {
			last = err
			continue
		}
		uc := &udpChan{
			name: name,
			conn: c,
			r:    bufio.NewReaderSize(c, 64*1024),
			w:    bufio.NewWriterSize(c, 64*1024),
			stop: make(chan struct{}),
			cb:   cb,
		}
		if err := protocol.WriteHandshake(uc.w, role, id, token); err != nil {
			_ = c.Close()
			last = err
			continue
		}
		log.Printf("vpn: %s channel %d uses server %s", name, id, a)
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

func (c *udpChan) readLoop() {
	for {
		select {
		case <-c.stop:
			return
		default:
		}
		f, err := protocol.ReadUDPFrame(c.r)
		if err != nil {
			log.Printf("vpn: %s channel read failed: %v", c.name, err)
			return
		}
		c.cb(f)
	}
}

type handler struct {
	opt     Options
	udpMux  *udpMux
	quicMux *udpMux
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
	targetMux := h.udpMux
	if dstPort == 443 {
		targetMux = h.quicMux
	}
	log.Printf("vpn: %s assoc %d -> %s:%d", targetMux.name, srcPort, dstIP.String(), dstPort)

	k := udpAssocKey{SrcPort: srcPort, DstIP: dstIP.String(), DstPort: dstPort}
	a := &udpAssoc{c: uc}
	targetMux.register(k, a)
	defer targetMux.unregister(k)

	buf := make([]byte, 64*1024)
	for {
		n, _, err := uc.ReadFrom(buf)
		if err != nil {
			log.Printf("vpn: %s read failed %d->%s:%d: %v", targetMux.name, srcPort, dstIP.String(), dstPort, err)
			return
		}
		p := make([]byte, n)
		copy(p, buf[:n])
		if err := targetMux.send(k, p); err != nil {
			log.Printf("vpn: %s send failed %d->%s:%d: %v", targetMux.name, srcPort, dstIP.String(), dstPort, err)
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
	log.Printf("vpn: tcp connect %s:%d via %s", dstIP.String(), dstPort, addr)
	sconn, err := dialTCP(addr)
	if err != nil {
		log.Printf("vpn: tcp dial server failed: %v", err)
		return
	}
	defer sconn.Close()

	r := bufio.NewReaderSize(sconn, 64*1024)
	w := bufio.NewWriterSize(sconn, 64*1024)
	if err := protocol.WriteHandshake(w, protocol.RoleTCP(), 0, h.opt.Token); err != nil {
		log.Printf("vpn: tcp handshake failed: %v", err)
		return
	}
	if err := protocol.WriteTcpConnect(w, dstIP, dstPort); err != nil {
		log.Printf("vpn: tcp connect frame failed: %v", err)
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
	log.Printf("vpn: tcp closed %s:%d", dstIP.String(), dstPort)
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
