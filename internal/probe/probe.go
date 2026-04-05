package probe

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"log"
	"net"
	"strings"
	"syscall"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

var defaultDNSAddrs = []string{"8.8.8.8:53", "1.1.1.1:53"}
var defaultInternetAddrs = []string{"8.8.8.8:443", "1.1.1.1:443"}

const defaultProbeTimeout = 5 * time.Second

func Ping(addr string, timeout time.Duration) (time.Duration, error) {
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	start := time.Now()
	c, err := net.DialTimeout("tcp", addr, timeout)
	d := time.Since(start)
	if err != nil {
		return 0, err
	}
	_ = c.Close()
	return d, nil
}

func ProbePterovpn(addr string, wireToken string, timeout time.Duration) (ok bool, ipv6 bool, err error) {
	ok, ipv6, _, err = ProbePterovpnWithCaps(addr, wireToken, timeout)
	return ok, ipv6, err
}

func ProbePterovpnWithCaps(addr string, wireToken string, timeout time.Duration) (ok bool, ipv6 bool, caps *protocol.ServerHelloCaps, err error) {
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	if strings.TrimSpace(wireToken) == "" {
		return false, false, nil, errors.New("probe: server token required for XOR stream")
	}
	badToken := "probe-bad-token"

	c, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return false, false, nil, err
	}
	defer c.Close()

	wrapped := obfuscate.WrapConn(c, wireToken)
	w := bufio.NewWriter(wrapped)

	slot := protocol.TimeSlot()
	junkCount, junkMin, junkMax := 2, 64, 512
	junkCount, junkMin, junkMax = protocol.ApplyTimeVariation(junkCount, junkMin, junkMax, slot)
	_ = protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, "", "", func() { _ = w.Flush() })
	if err := protocol.WriteHandshake(w, protocol.RoleUDP(), 0, badToken); err != nil {
		log.Printf("probe: handshake write: %v", err)
		return false, false, nil, err
	}
	if err := w.Flush(); err != nil {
		return false, false, nil, err
	}
	if tcp, ok := c.(*net.TCPConn); ok {
		_ = tcp.CloseWrite()
	}

	raw, err := readProbeHelloPayload(c, timeout)
	if err == io.EOF {
		return true, false, nil, nil
	}
	if err != nil {
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return false, false, nil, nil
		}
		if errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.EPIPE) {
			return true, false, nil, nil
		}
		return false, false, nil, err
	}
	return parseProbeCaps(raw)
}

func readProbeHelloPayload(c net.Conn, timeout time.Duration) ([]byte, error) {
	deadline := time.Now().Add(timeout)
	_ = c.SetReadDeadline(deadline)
	buf := make([]byte, protocol.HelloCapsHeaderLen+protocol.HelloCapsMaxNonceLen)
	if _, err := io.ReadFull(c, buf[:1]); err != nil {
		return nil, err
	}
	_ = c.SetReadDeadline(deadline)
	_, err := io.ReadFull(c, buf[1:protocol.HelloCapsHeaderLen])
	switch err {
	case nil:
		nl := int(buf[protocol.HelloCapsHeaderLen-1])
		if nl < 0 || nl > protocol.HelloCapsMaxNonceLen {
			return buf[:protocol.HelloCapsHeaderLen], nil
		}
		if nl == 0 {
			return appendCapsExtensions(c, deadline, buf[:protocol.HelloCapsHeaderLen])
		}
		_ = c.SetReadDeadline(deadline)
		if _, err := io.ReadFull(c, buf[protocol.HelloCapsHeaderLen:protocol.HelloCapsHeaderLen+nl]); err != nil {
			return nil, err
		}
		raw := buf[:protocol.HelloCapsHeaderLen+nl]
		return appendCapsExtensions(c, deadline, raw)
	case io.EOF, io.ErrUnexpectedEOF:
		return buf[:1], nil
	default:
		return nil, err
	}
}

func appendCapsExtensions(c net.Conn, deadline time.Time, raw []byte) ([]byte, error) {
	for {
		_ = c.SetReadDeadline(deadline)
		var tag [1]byte
		n, err := c.Read(tag[:])
		if n == 0 {
			if err == io.EOF {
				return raw, nil
			}
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				return raw, nil
			}
			if err != nil {
				return raw, nil
			}
			return raw, nil
		}
		if n != 1 {
			return raw, nil
		}
		switch tag[0] {
		case 1:
			pin := make([]byte, 32)
			_ = c.SetReadDeadline(deadline)
			if _, err := io.ReadFull(c, pin); err != nil {
				return raw, err
			}
			raw = append(raw, tag[0])
			raw = append(raw, pin...)
		case 2:
			peer := make([]byte, 4)
			_ = c.SetReadDeadline(deadline)
			if _, err := io.ReadFull(c, peer); err != nil {
				return raw, err
			}
			raw = append(raw, tag[0])
			raw = append(raw, peer...)
		default:
			return raw, nil
		}
	}
}

func parseProbeCaps(raw []byte) (bool, bool, *protocol.ServerHelloCaps, error) {
	if len(raw) == 0 {
		return false, false, nil, nil
	}
	if len(raw) == 1 {
		return true, raw[0] == 1, nil, nil
	}
	c, err := protocol.ReadServerHelloCaps(bytes.NewReader(raw))
	if err != nil {
		return true, raw[0] == 1, nil, nil
	}
	return true, c.LegacyIPv6, &c, nil
}

func ServerModeFromCaps(caps *protocol.ServerHelloCaps) string {
	if caps == nil {
		return "tcp only"
	}
	hasTCP := (caps.TransportMask & protocol.TransportTCP) != 0
	hasQUIC := (caps.TransportMask & protocol.TransportQUIC) != 0
	if hasTCP && hasQUIC {
		return "quic/tcp"
	}
	if hasQUIC {
		return "quic only"
	}
	return "tcp only"
}

func RecommendDualTunTransport(caps *protocol.ServerHelloCaps, quicMuxWillBeUsed bool) bool {
	return quicMuxWillBeUsed && ServerModeFromCaps(caps) == "quic/tcp"
}

func DNSOK(addrs []string, timeout time.Duration) bool {
	if len(addrs) == 0 {
		addrs = defaultDNSAddrs
	}
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			for _, a := range addrs {
				d := net.Dialer{Timeout: timeout}
				c, err := d.DialContext(ctx, "udp", a)
				if err == nil {
					return c, nil
				}
			}
			return nil, context.DeadlineExceeded
		},
	}
	_, err := r.LookupHost(ctx, "google.com")
	return err == nil
}

func InternetOK(timeout time.Duration) bool {
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	for _, addr := range defaultInternetAddrs {
		c, err := net.DialTimeout("tcp", addr, timeout)
		if err == nil {
			_ = c.Close()
			return true
		}
	}
	return false
}
