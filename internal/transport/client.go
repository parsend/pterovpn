package transport

import (
	"crypto/tls"
	"net"
	"time"

	"github.com/parsend/pterovpn/internal/obfuscate"
)

type DialResult struct {
	Conn net.Conn
	Raw  net.Conn
}

func Dial(addr, token, name string) (DialResult, error) {
	return dial(addr, token, Normalize(name), true)
}

func dial(addr, token, name string, allowRefresh bool) (DialResult, error) {
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	raw, err := d.Dial("tcp", addr)
	if err != nil {
		return DialResult{}, err
	}
	if tc, ok := raw.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	if err := WritePreface(raw, name); err != nil {
		_ = raw.Close()
		return DialResult{}, err
	}
	if name == NameMTLS {
		cfg, err := ClientTLSConfig(addr, token)
		if err != nil {
			_ = raw.Close()
			return DialResult{}, err
		}
		tlsConn := tls.Client(raw, cfg)
		if err := tlsConn.Handshake(); err != nil {
			_ = tlsConn.Close()
			if allowRefresh {
				_ = RemoveClientBundle(addr, token)
				return dial(addr, token, name, false)
			}
			return DialResult{}, err
		}
		return DialResult{Conn: tlsConn, Raw: raw}, nil
	}
	return DialResult{Conn: obfuscate.WrapConn(raw, token), Raw: raw}, nil
}
