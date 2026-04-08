package tunnel

import (
	"bufio"
	"context"
	"crypto/x509"
	"errors"
	"net"
	"strings"
	"syscall"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
	"github.com/unitdevgcc/pterovpn/internal/sockprotect"
)

func Dial(serverAddrs []string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, tunPreferTCP bool) (net.Conn, error) {
	if !tunPreferTCP && UsesQUICTransport(transport, quicServer) {
		return dialQUIC(serverAddrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, targetIP, targetPort, token, prot, quicShared)
	}
	if len(serverAddrs) == 0 {
		return nil, errors.New("server addrs empty")
	}
	order := pickAddrOrder(serverAddrs, targetIP, targetPort)
	var lastErr error
	for i, addr := range order {
		c, err := dialServer(addr, token)
		if err != nil {
			lastErr = err
			continue
		}
		slot := protocol.TimeSlot()
		bufSize := protocol.BufSizeForConn(slot)
		r := bufio.NewReaderSize(c, bufSize)
		w := bufio.NewWriterSize(c, bufSize)
		if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
			_ = c.Close()
			lastErr = err
			continue
		}
		if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
			_ = c.Close()
			lastErr = err
			continue
		}
		if i > 0 {
			clientlog.Warn("tun tcp connected via fallback addr=%s attempt=%d", addr, i+1)
		}
		return &tunnelConn{Conn: c, r: r}, nil
	}
	if lastErr == nil {
		lastErr = errors.New("tcp dial failed")
	}
	return nil, lastErr
}

func UsesQUICTransport(transport, quicServer string) bool {
	if strings.EqualFold(transport, "tcp") {
		return false
	}
	if strings.EqualFold(transport, "quic") {
		return true
	}
	return strings.TrimSpace(quicServer) != ""
}

func ResolvedTransportLabel(transport, quicServer string) string {
	if UsesQUICTransport(transport, quicServer) {
		return "quic"
	}
	return "tcp"
}

func PteravpnTunnelTag(transport, quicServer string) string {
	if UsesQUICTransport(transport, quicServer) {
		return "QUIC"
	}
	return "TCP"
}

type tunnelConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *tunnelConn) Read(p []byte) (n int, err error) {
	return c.r.Read(p)
}

func dialServer(addr, token string) (net.Conn, error) {
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

func dialQUIC(serverAddrs []string, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, quicShared *QUICConn) (net.Conn, error) {
	ownsConn := quicShared == nil
	var conn *QUICConn
	var err error
	var closePC func()
	if ownsConn {
		addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
		if err != nil {
			return nil, err
		}
		conn, closePC, err = dialQUICConn(addr, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots)
		if err != nil {
			return nil, err
		}
	} else {
		conn = quicShared
	}
	if quicTraceOn() {
		clientlog.Trace("quic tun-tcp OpenStreamSync")
	}
	streamCtx, streamCancel := context.WithTimeout(context.Background(), 45*time.Second)
	stream, err := conn.OpenStreamSync(streamCtx)
	streamCancel()
	if err != nil {
		if quicTraceOn() {
			clientlog.Trace("quic tun-tcp OpenStreamSync err=%v", err)
		}
		if ownsConn {
			_ = conn.CloseWithError(0, "open stream failed")
			if closePC != nil {
				closePC()
			}
		}
		return nil, err
	}
	var sconn net.Conn
	if ownsConn {
		sconn = newQUICStreamConn(conn, stream, closePC)
	} else {
		sconn = &quicStreamOnlyConn{conn: conn, stream: stream}
	}
	slot := protocol.TimeSlot()
	w := bufio.NewWriterSize(sconn, protocol.BufSizeForConn(slot))
	r := bufio.NewReaderSize(sconn, protocol.BufSizeForConn(slot))
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	return &tunnelConn{Conn: sconn, r: r}, nil
}

func pickAddr(addrs []string, ip net.IP, port uint16) string {
	if len(addrs) == 1 {
		return addrs[0]
	}
	h := uint(0)
	for _, b := range []byte(ip.String()) {
		h = h*31 + uint(b)
	}
	h += uint(port)
	return addrs[int(h)%len(addrs)]
}

func pickAddrOrder(addrs []string, ip net.IP, port uint16) []string {
	if len(addrs) <= 1 {
		return append([]string(nil), addrs...)
	}
	first := pickAddr(addrs, ip, port)
	out := make([]string, 0, len(addrs))
	start := 0
	for i, a := range addrs {
		if a == first {
			start = i
			break
		}
	}
	for i := 0; i < len(addrs); i++ {
		out = append(out, addrs[(start+i)%len(addrs)])
	}
	return out
}
