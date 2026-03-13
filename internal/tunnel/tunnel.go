package tunnel

import (
	"bufio"
	"encoding/json"
	"net"
	"strings"

	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/protocol"
	"github.com/parsend/pterovpn/internal/transport"
)

func Dial(serverAddrs []string, targetIP net.IP, targetPort uint16, token string, transportName string, prot *config.ProtectionOptions) (net.Conn, error) {
	addr := pickAddr(serverAddrs, targetIP, targetPort)
	res, err := transport.Dial(addr, token, transportName)
	if err != nil {
		return nil, err
	}
	c := res.Conn
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

type tunnelConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *tunnelConn) Read(p []byte) (n int, err error) {
	return c.r.Read(p)
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
