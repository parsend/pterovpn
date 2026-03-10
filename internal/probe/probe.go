package probe

import (
	"bufio"
	"context"
	"errors"
	"io"
	"log"
	"net"
	"syscall"
	"time"

	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"
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

func ProbePterovpn(addr string, timeout time.Duration) (ok bool, ipv6 bool, err error) {
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	badToken := "probe-bad-token"

	c, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return false, false, err
	}
	defer c.Close()

	wrapped := obfuscate.WrapConn(c, badToken)
	w := bufio.NewWriter(wrapped)

	slot := protocol.TimeSlot()
	junkCount, junkMin, junkMax := 2, 64, 512
	junkCount, junkMin, junkMax = protocol.ApplyTimeVariation(junkCount, junkMin, junkMax, slot)
	_ = protocol.WriteJunkOrTLSLike(w, junkCount, junkMin, junkMax, "", "", func() { _ = w.Flush() })
	if err := protocol.WriteHandshake(w, protocol.RoleUDP(), 0, badToken); err != nil {
		log.Printf("probe: handshake write: %v", err)
		return false, false, err
	}
	if err := w.Flush(); err != nil {
		return false, false, err
	}
	if tcp, ok := c.(*net.TCPConn); ok {
		_ = tcp.CloseWrite()
	}

	_ = c.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 1)
	n, err := c.Read(buf)
	if n == 1 {
		return true, buf[0] == 1, nil
	}
	if err == io.EOF {
		return true, false, nil
	}
	if err != nil {
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return false, false, nil
		}
		if errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.EPIPE) {
			return true, false, nil
		}
		return false, false, err
	}
	return false, false, nil
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

