package probe

import (
	"bufio"
	"context"
	"io"
	"log"
	"net"
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

func ProbePterovpn(addr string, timeout time.Duration) (bool, error) {
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	}
	badToken := "probe-bad-token"

	c, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return false, err
	}
	defer c.Close()

	wrapped := obfuscate.WrapConn(c, badToken)
	r := bufio.NewReader(wrapped)
	w := bufio.NewWriter(wrapped)

	if err := protocol.WriteHandshake(w, protocol.RoleUDP(), 0, badToken); err != nil {
		log.Printf("probe: handshake write: %v", err)
		return false, err
	}

	_ = c.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 1)
	_, err = r.Read(buf)
	if err == io.EOF {
		return true, nil
	}
	if err != nil {
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return false, nil
		}
		return false, err
	}
	return false, nil
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
