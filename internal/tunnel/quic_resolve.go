package tunnel

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
)

const DefaultQUICPort = "4433"

func ResolveQUICDialAddr(serverAddrs []string, quicServer string) (addr string, usedDefaultPort bool, err error) {
	s := strings.TrimSpace(quicServer)
	if s != "" {
		if _, _, splitErr := net.SplitHostPort(s); splitErr != nil {
			return net.JoinHostPort(s, DefaultQUICPort), true, nil
		}
		return s, false, nil
	}
	if len(serverAddrs) == 0 {
		return "", false, errors.New("quic: no tcp server address to derive QUIC host")
	}
	h, _, splitErr := net.SplitHostPort(serverAddrs[0])
	if splitErr != nil {
		return "", false, fmt.Errorf("quic: parse %q: %w", serverAddrs[0], splitErr)
	}
	return net.JoinHostPort(h, DefaultQUICPort), true, nil
}

func LookupHostIPsPreferV4(ctx context.Context, host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			return []net.IP{v4}, nil
		}
		return []net.IP{ip}, nil
	}
	addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	var v4, v6 []net.IP
	for _, a := range addrs {
		if a.IP == nil {
			continue
		}
		if v := a.IP.To4(); v != nil {
			v4 = append(v4, v)
		} else {
			v6 = append(v6, a.IP)
		}
	}
	out := append(v4, v6...)
	if len(out) == 0 {
		return nil, fmt.Errorf("no addresses for %q", host)
	}
	return out, nil
}

func QUICDialTargetIPs(ctx context.Context, serverAddrs []string, quicServer string) ([]net.IP, error) {
	addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
	if err != nil {
		return nil, err
	}
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("quic dial addr %q: %w", addr, err)
	}
	return LookupHostIPsPreferV4(ctx, host)
}
