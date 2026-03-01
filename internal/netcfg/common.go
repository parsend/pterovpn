package netcfg

import (
	"errors"
	"fmt"
	"net"
	"strings"
)

type DefaultRoute struct {
	Dev     string
	Gateway string
}

func ParseCIDRs(s string) ([]*net.IPNet, error) {
	if s == "" {
		return nil, nil
	}
	var out []*net.IPNet
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		_, n, err := net.ParseCIDR(p)
		if err != nil {
			return nil, fmt.Errorf("bad cidr %q: %w", p, err)
		}
		out = append(out, n)
	}
	return out, nil
}

func ResolveHost(host string) (net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		return ip, nil
	}
	addrs, err := net.LookupIP(host)
	if err != nil {
		return nil, err
	}
	for _, a := range addrs {
		if v4 := a.To4(); v4 != nil {
			return v4, nil
		}
	}
	return addrs[0], nil
}

func SplitHostPorts(server string, portsCSV string) ([]string, error) {
	if portsCSV == "" {
		if _, _, err := net.SplitHostPort(server); err != nil {
			return nil, err
		}
		return []string{server}, nil
	}
	host := server
	if strings.Contains(server, ":") {
		h, _, err := net.SplitHostPort(server)
		if err == nil {
			host = h
		}
	}
	var out []string
	for _, p := range strings.Split(portsCSV, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		out = append(out, net.JoinHostPort(host, p))
	}
	if len(out) == 0 {
		return nil, errors.New("ports empty")
	}
	return out, nil
}
