package tunnel

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"strings"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

func Dial(serverAddrs []string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicShared *QUICConn) (net.Conn, error) {
	if UsesQUICTransport(transport, quicServer) {
		return dialQUIC(serverAddrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, targetIP, targetPort, token, prot, quicShared)
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
	prefixLen, junkCount, junkMin, junkMax := 0, 0, 64, 1024
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
	if err := protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, junkStyle, flushPolicy, func() { _ = w.Flush() }); err != nil {
		c.Close()
		return nil, err
	}
	if !strings.EqualFold(flushPolicy, "perChunk") {
		_ = w.Flush()
	}
	var optsJSON []byte
	if prot != nil {
		optsJSON, _ = json.Marshal(prot)
	}
	if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, token, prefixLen, optsJSON, slot); err != nil {
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

func dialQUIC(serverAddrs []string, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, quicShared *QUICConn) (net.Conn, error) {
	ownsConn := quicShared == nil
	var conn *QUICConn
	var err error
	var closePC func()
	if ownsConn {
		addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
		if err != nil {
			return nil, err
		}
		conn, closePC, err = dialQUICConn(addr, quicServerName, quicSkipVerify, quicCertPinSHA256)
		if err != nil {
			return nil, err
		}
	} else {
		conn = quicShared
	}
	if quicTraceOn() {
		clientlog.Trace("quic tun-tcp OpenStreamSync")
	}
	stream, err := conn.OpenStreamSync(context.Background())
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
	prefixLen, junkCount, junkMin, junkMax := 0, 2, 64, 512
	if prot != nil {
		prefixLen = prot.PadS1 + prot.PadS2 + prot.PadS3
		if prefixLen > 64 {
			prefixLen = 64
		}
	}
	if err := protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, "", "", func() { _ = w.Flush() }); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	var optsJSON []byte
	if prot != nil {
		optsJSON, _ = json.Marshal(prot)
	}
	if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, token, prefixLen, optsJSON, slot); err != nil {
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
