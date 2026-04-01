package tunnel

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	quic "github.com/quic-go/quic-go"
	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

type QUICConn = quic.Conn

type quicStreamConn struct {
	conn       *quic.Conn
	stream     *quic.Stream
	udpCleanup func()
}

func newQUICStreamConn(conn *quic.Conn, stream *quic.Stream, udpCleanup func()) net.Conn {
	if udpCleanup == nil {
		udpCleanup = func() {}
	}
	return &quicStreamConn{conn: conn, stream: stream, udpCleanup: udpCleanup}
}

func (c *quicStreamConn) Read(p []byte) (int, error)         { return c.stream.Read(p) }
func (c *quicStreamConn) Write(p []byte) (int, error)        { return c.stream.Write(p) }
func (c *quicStreamConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *quicStreamConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }
func (c *quicStreamConn) SetDeadline(t time.Time) error      { return c.stream.SetDeadline(t) }
func (c *quicStreamConn) SetReadDeadline(t time.Time) error  { return c.stream.SetReadDeadline(t) }
func (c *quicStreamConn) SetWriteDeadline(t time.Time) error { return c.stream.SetWriteDeadline(t) }
func (c *quicStreamConn) Close() error {
	_ = c.stream.Close()
	err := c.conn.CloseWithError(0, "close")
	c.udpCleanup()
	return err
}

type quicStreamOnlyConn struct {
	conn   *quic.Conn
	stream *quic.Stream
}

func (c *quicStreamOnlyConn) Read(p []byte) (int, error)         { return c.stream.Read(p) }
func (c *quicStreamOnlyConn) Write(p []byte) (int, error)        { return c.stream.Write(p) }
func (c *quicStreamOnlyConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *quicStreamOnlyConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }
func (c *quicStreamOnlyConn) SetDeadline(t time.Time) error      { return c.stream.SetDeadline(t) }
func (c *quicStreamOnlyConn) SetReadDeadline(t time.Time) error  { return c.stream.SetReadDeadline(t) }
func (c *quicStreamOnlyConn) SetWriteDeadline(t time.Time) error { return c.stream.SetWriteDeadline(t) }
func (c *quicStreamOnlyConn) Close() error                       { return c.stream.Close() }

type quicNoopTokenStore struct{}

func (quicNoopTokenStore) Pop(string) *quic.ClientToken  { return nil }
func (quicNoopTokenStore) Put(string, *quic.ClientToken) {}

func dialQUICConn(addr, serverName string, skipVerify bool, certPinSHA256 string) (*quic.Conn, func(), error) {
	sniHost := serverName
	if sniHost == "" {
		h, _, err := net.SplitHostPort(addr)
		if err != nil {
			sniHost = addr
		} else {
			sniHost = h
		}
	}
	pin := strings.ToLower(strings.ReplaceAll(strings.TrimSpace(certPinSHA256), ":", ""))
	hasPin := pin != ""

	tlsCfg := &tls.Config{
		MinVersion:         tls.VersionTLS13,
		ServerName:         sniHost,
		NextProtos:         []string{"pteravpn"},
		ClientSessionCache: nil,
	}
	if hasPin {

		tlsCfg.InsecureSkipVerify = true
		tlsCfg.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("empty peer cert")
			}
			sum := sha256.Sum256(rawCerts[0])
			got := strings.ToLower(hex.EncodeToString(sum[:]))
			if got != pin {
				return errors.New("quic cert pin mismatch")
			}
			return nil
		}
	} else if skipVerify {
		tlsCfg.InsecureSkipVerify = true
	} else {
		tlsCfg.InsecureSkipVerify = false
	}

	dialHost, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, nil, fmt.Errorf("quic: dial addr %q: %w", addr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return nil, nil, fmt.Errorf("quic: bad udp port %q", portStr)
	}

	resolveCtx, cancelRes := context.WithTimeout(context.Background(), 12*time.Second)
	ips, err := LookupHostIPsPreferV4(resolveCtx, dialHost)
	cancelRes()
	if err != nil {
		return nil, nil, fmt.Errorf("quic: resolve %q: %w", dialHost, err)
	}
	if quicTraceOn() {
		ipStrs := make([]string, 0, len(ips))
		for _, ip := range ips {
			ipStrs = append(ipStrs, ip.String())
		}
		clientlog.Trace("quic dial addr=%q sni=%q ips=%v", addr, sniHost, ipStrs)
	}

	qconf := &quic.Config{
		EnableDatagrams:      false,
		MaxIdleTimeout:       5 * time.Minute,
		HandshakeIdleTimeout: 45 * time.Second,
		KeepAlivePeriod:      15 * time.Second,
		TokenStore:           quicNoopTokenStore{},
	}

	var lastErr error
	noop := func() {}
	for _, ip := range ips {
		dialCtx, cancelDial := context.WithTimeout(context.Background(), 50*time.Second)
		if v4 := ip.To4(); v4 != nil {
			target := net.JoinHostPort(v4.String(), portStr)
			if quicTraceOn() {
				clientlog.Trace("quic dial try DialAddr %s", target)
			}
			conn, derr := quic.DialAddr(dialCtx, target, tlsCfg, qconf)
			cancelDial()
			if derr == nil {
				if quicTraceOn() {
					clientlog.Trace("quic dial ok local=%s remote=%s", conn.LocalAddr(), conn.RemoteAddr())
				}
				return conn, noop, nil
			}
			if quicTraceOn() {
				clientlog.Trace("quic dial fail DialAddr %s: %v", target, derr)
			}
			lastErr = derr
			continue
		}
		pc, lerr := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6zero, Port: 0})
		if lerr != nil {
			cancelDial()
			if quicTraceOn() {
				clientlog.Trace("quic dial ListenUDP udp6: %v", lerr)
			}
			lastErr = lerr
			continue
		}
		udpAddr := &net.UDPAddr{IP: ip, Port: port}
		if quicTraceOn() {
			clientlog.Trace("quic dial try Dial udp6 local=%s -> %s", pc.LocalAddr(), udpAddr)
		}
		conn, derr := quic.Dial(dialCtx, pc, udpAddr, tlsCfg, qconf)
		cancelDial()
		if derr == nil {
			if quicTraceOn() {
				clientlog.Trace("quic dial ok local=%s remote=%s", conn.LocalAddr(), conn.RemoteAddr())
			}
			closePC := func() { _ = pc.Close() }
			return conn, closePC, nil
		}
		_ = pc.Close()
		if quicTraceOn() {
			clientlog.Trace("quic dial fail Dial %s: %v", udpAddr, derr)
		}
		lastErr = derr
	}
	return nil, nil, wrapQUICDialTLS(lastErr, addr, skipVerify, hasPin)
}

func wrapQUICDialTLS(err error, dialAddr string, skipVerify, hasPin bool) error {
	if err == nil {
		return nil
	}
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "x509") || strings.Contains(msg, "certificate") || strings.Contains(msg, "unknown authority") || strings.Contains(msg, "verify") {
		if !skipVerify && !hasPin {
			return fmt.Errorf("%w - для self-signed: quicSkipVerify true, пустой ключ в JSON или quicCertPinSHA256; false только для CA", err)
		}
	}
	if strings.Contains(msg, "no recent network activity") {

		return fmt.Errorf("%w (dial %s) - UDP не доходит до quicListenPort или ответ не приходит (файрвол, NAT, маршрут к VPN-серверу, serverMode без QUIC); после первого сеанса проверь что bypass к серверу жив и сокет QUIC реально закрыт", err, dialAddr)
	}
	return err
}

type UDPMuxQUICStream struct {
	Conn   net.Conn
	R      *bufio.Reader
	W      *bufio.Writer
	MaxPad int
}

func openUDPChannelOnQUICStream(conn *quic.Conn, stream *quic.Stream, channelID byte, token string, prot *config.ProtectionOptions, streamClosesConn bool, udpCleanup func()) (net.Conn, *bufio.Reader, *bufio.Writer, int, error) {
	var sconn net.Conn
	if streamClosesConn {
		sconn = newQUICStreamConn(conn, stream, udpCleanup)
	} else {
		sconn = &quicStreamOnlyConn{conn: conn, stream: stream}
	}
	slot := protocol.TimeSlot()
	bufSize := protocol.BufSizeForConn(slot)
	r := bufio.NewReaderSize(sconn, bufSize)
	w := bufio.NewWriterSize(sconn, bufSize)
	maxPad := 32 + int(slot%16)
	if maxPad > 64 {
		maxPad = 64
	}
	prefixLen := 0
	if prot != nil {
		prefixLen = prot.PadS1 + prot.PadS2 + prot.PadS3
		if prefixLen > 64 {
			prefixLen = 64
		}
	}
	if err := protocol.WriteJunkOrTLSLike(w, 2, 64, 512, "", "", func() { _ = w.Flush() }); err != nil {
		_ = sconn.Close()
		return nil, nil, nil, 0, err
	}
	var optsJSON []byte
	if prot != nil {
		optsJSON, _ = json.Marshal(prot)
	}
	if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleUDP(), channelID, token, prefixLen, optsJSON, slot); err != nil {
		_ = sconn.Close()
		return nil, nil, nil, 0, err
	}
	return sconn, r, w, maxPad, nil
}

func DialUDPMuxQUIC(serverAddrs []string, quicServer, serverName string, skipVerify bool, certPinSHA256 string, n int, token string, prot *config.ProtectionOptions) (closeAll func(), sharedConn *quic.Conn, streams []UDPMuxQUICStream, err error) {
	if n < 1 || n > 4 {
		return nil, nil, nil, fmt.Errorf("quic udp mux: bad n=%d", n)
	}
	addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
	if err != nil {
		return nil, nil, nil, err
	}
	conn, closePC, err := dialQUICConn(addr, serverName, skipVerify, certPinSHA256)
	if err != nil {
		return nil, nil, nil, err
	}
	shutdown := func() {
		_ = conn.CloseWithError(0, "udp mux shutdown")
		closePC()
	}
	var opened []*quic.Stream
	defer func() {
		if err != nil {
			for _, st := range opened {
				_ = st.Close()
			}
			shutdown()
		}
	}()
	streams = make([]UDPMuxQUICStream, 0, n)
	for i := 0; i < n; i++ {
		if quicTraceOn() {
			clientlog.Trace("quic mux OpenStreamSync channel=%d", i)
		}
		st, e := conn.OpenStreamSync(context.Background())
		if e != nil {
			if quicTraceOn() {
				clientlog.Trace("quic mux OpenStreamSync channel=%d err=%v", i, e)
			}
			err = e
			return nil, nil, nil, err
		}
		opened = append(opened, st)
		sconn, r, w, maxPad, e := openUDPChannelOnQUICStream(conn, st, byte(i), token, prot, false, nil)
		if e != nil {
			if quicTraceOn() {
				clientlog.Trace("quic mux openUDPChannel stream=%d err=%v", i, e)
			}
			err = e
			return nil, nil, nil, err
		}
		if quicTraceOn() {
			clientlog.Trace("quic mux channel=%d pteravpn handshake prefix sent", i)
		}
		streams = append(streams, UDPMuxQUICStream{Conn: sconn, R: r, W: w, MaxPad: maxPad})
	}
	if quicTraceOn() {
		clientlog.Trace("quic mux ready n=%d streams", n)
	}
	return shutdown, conn, streams, nil
}

func DialUDPChannelQUIC(serverAddrs []string, quicServer, serverName string, skipVerify bool, certPinSHA256 string, channelID byte, token string, prot *config.ProtectionOptions) (net.Conn, *bufio.Reader, *bufio.Writer, int, error) {
	addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
	if err != nil {
		return nil, nil, nil, 0, err
	}
	conn, closePC, err := dialQUICConn(addr, serverName, skipVerify, certPinSHA256)
	if err != nil {
		return nil, nil, nil, 0, err
	}
	stream, err := conn.OpenStreamSync(context.Background())
	if err != nil {
		_ = conn.CloseWithError(0, "open stream failed")
		closePC()
		return nil, nil, nil, 0, err
	}
	sconn, r, w, maxPad, err := openUDPChannelOnQUICStream(conn, stream, channelID, token, prot, true, closePC)
	if err != nil {
		return nil, nil, nil, 0, err
	}
	return sconn, r, w, maxPad, nil
}
