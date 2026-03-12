package tunnel

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"net"
	"strings"
	"time"

	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"
)

func Dial(
	serverAddrs []string,
	targetIP net.IP,
	targetPort uint16,
	token string,
	prot *config.ProtectionOptions,
	transport string,
	tlsName string,
) (net.Conn, error) {
	addr := pickAddr(serverAddrs, targetIP, targetPort)
	handshakeConn, rawConn, err := dialServer(addr, token)
	if err != nil {
		return nil, err
	}
	slot := protocol.TimeSlot()
	bufSize := protocol.BufSizeForConn(slot)
	r := bufio.NewReaderSize(handshakeConn, bufSize)
	w := bufio.NewWriterSize(handshakeConn, bufSize)
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
		_ = rawConn.Close()
		return nil, err
	}
	if !strings.EqualFold(flushPolicy, "perChunk") {
		if err := w.Flush(); err != nil {
			_ = rawConn.Close()
			return nil, err
		}
	}
	var optsJSON []byte
	if prot != nil || strings.EqualFold(transport, "tls") {
		opts := effectiveTransportOptions(prot, transport, tlsName)
		optsJSON, _ = json.Marshal(opts)
	}
	if err := protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, token, prefixLen, optsJSON, slot); err != nil {
		_ = rawConn.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = rawConn.Close()
		return nil, err
	}
	if err := w.Flush(); err != nil {
		_ = rawConn.Close()
		return nil, err
	}
	connForTraffic := handshakeConn
	if strings.EqualFold(transport, "tls") {
		tlsConn, err := upgradeToTLS(rawConn, tlsName)
		if err != nil {
			_ = rawConn.Close()
			return nil, err
		}
		connForTraffic = tlsConn
		r = bufio.NewReaderSize(connForTraffic, bufSize)
	}
	return &tunnelConn{Conn: connForTraffic, r: r}, nil
}

type tunnelConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *tunnelConn) Read(p []byte) (n int, err error) {
	return c.r.Read(p)
}

func dialServer(addr, token string) (net.Conn, net.Conn, error) {
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	rawConn, wrappedConn := c, obfuscate.WrapConn(c, token)
	return wrappedConn, rawConn, nil
}

func effectiveTransportOptions(
	prot *config.ProtectionOptions,
	transport string,
	tlsName string,
) *config.ProtectionOptions {
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
