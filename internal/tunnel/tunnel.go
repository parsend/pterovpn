package tunnel

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"net"
	"strings"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

func tunTCPPreferPlainTCP(port uint16) bool {
	switch port {
	case 135, 139, 445, 3389, 5900, 5985, 5986:
		return true
	default:
		return false
	}
}

const dualTunQUICStreamByteThreshold = 180

func PickQUICForTunTCPFlow(dstIP net.IP, dstPort uint16) bool {
	if dstIP == nil || tunTCPPreferPlainTCP(dstPort) {
		return false
	}
	ip := dstIP.To16()
	if ip == nil {
		return false
	}
	var b []byte
	b = append(b, ip...)
	b = append(b, byte(dstPort>>8), byte(dstPort))
	h := sha256.Sum256(b)
	return h[0] < dualTunQUICStreamByteThreshold
}

func Dial(serverAddrs []string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, tunPreferTCP bool) (net.Conn, error) {
	if !tunPreferTCP && UsesQUICTransport(transport, quicServer) {
		return dialQUIC(serverAddrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, targetIP, targetPort, token, prot, quicShared)
	}
	addr := pickAddr(serverAddrs, targetIP, targetPort)
	c, err := dialServer(addr, token)
	if err != nil {
		return nil, err
	}
	slot := protocol.TimeSlot()
	bufSize := protocol.BufSizeForConn(slot)
	r := bufio.NewReaderSize(c, bufSize)
	w := bufio.NewWriterSize(c, bufSize)
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		c.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		c.Close()
		return nil, err
	}
	return &tunnelConn{Conn: c, r: r}, nil
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
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
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
