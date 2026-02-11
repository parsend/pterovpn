package netcfg

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
)

type DefaultRoute struct {
	Dev     string
	Gateway string
}

func GetDefaultRoute() (DefaultRoute, error) {
	out, err := exec.Command("ip", "-j", "route", "show", "default").Output()
	if err != nil {
		return DefaultRoute{}, err
	}
	var routes []map[string]any
	if err := json.Unmarshal(out, &routes); err != nil {
		return DefaultRoute{}, err
	}
	if len(routes) == 0 {
		return DefaultRoute{}, errors.New("no default route")
	}
	dev, _ := routes[0]["dev"].(string)
	gw, _ := routes[0]["gateway"].(string)
	if dev == "" {
		return DefaultRoute{}, errors.New("default route without dev")
	}
	return DefaultRoute{Dev: dev, Gateway: gw}, nil
}

func AddBypass(serverIP net.IP, dr DefaultRoute) error {
	ip := serverIP.String()
	if serverIP.To4() != nil {
		ip += "/32"
	} else {
		ip += "/128"
	}
	if dr.Gateway != "" {
		return run("ip", "route", "replace", ip, "via", dr.Gateway, "dev", dr.Dev)
	}
	return run("ip", "route", "replace", ip, "dev", dr.Dev)
}

func DelBypass(serverIP net.IP) {
	ip := serverIP.String()
	if serverIP.To4() != nil {
		ip += "/32"
	} else {
		ip += "/128"
	}
	_ = execIgnore("ip", "route", "del", ip)
}

func AddDefaultViaTun(tun string, metric int) error {
	cmd := exec.Command("ip", "route", "add", "default", "dev", tun, "metric", fmt.Sprintf("%d", metric))
	out, err := cmd.CombinedOutput()
	if err == nil {
		return nil
	}
	if bytes.Contains(out, []byte("File exists")) {
		return nil
	}
	return fmt.Errorf("ip route add default: %w: %s", err, strings.TrimSpace(string(out)))
}

func DelDefaultViaTun(tun string) {
	_ = execIgnore("ip", "route", "del", "default", "dev", tun)
}

func AddRouteViaTun(tun, cidr string, metric int) error {
	args := []string{"route", "add", cidr, "dev", tun, "metric", fmt.Sprintf("%d", metric)}
	cmd := exec.Command("ip", args...)
	out, err := cmd.CombinedOutput()
	if err == nil {
		return nil
	}
	if bytes.Contains(out, []byte("File exists")) {
		return nil
	}
	return fmt.Errorf("ip route add %s: %w: %s", cidr, err, strings.TrimSpace(string(out)))
}

func DelRouteViaTun(tun, cidr string) {
	_ = execIgnore("ip", "route", "del", cidr, "dev", tun)
}

// RoutesToAdd returns include CIDRs minus any that fall inside exclude CIDRs.
func RoutesToAdd(includeCSV, excludeCSV string) ([]string, error) {
	include := splitCIDRs(includeCSV)
	if len(include) == 0 {
		return nil, nil
	}
	exclude := splitCIDRs(excludeCSV)
	var out []string
	for _, cidr := range include {
		if cidr == "" {
			continue
		}
		incNet, err := parseCIDR(cidr)
		if err != nil {
			return nil, fmt.Errorf("include %q: %w", cidr, err)
		}
		skip := false
		for _, exc := range exclude {
			if exc == "" {
				continue
			}
			excNet, err := parseCIDR(exc)
			if err != nil {
				continue
			}
			if cidrInCidr(incNet, excNet) {
				skip = true
				break
			}
		}
		if !skip {
			out = append(out, cidr)
		}
	}
	return out, nil
}

func splitCIDRs(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func parseCIDR(s string) (*net.IPNet, error) {
	_, n, err := net.ParseCIDR(s)
	return n, err
}

func cidrInCidr(inc, exc *net.IPNet) bool {
	incOnes, _ := inc.Mask.Size()
	excOnes, _ := exc.Mask.Size()
	return exc.Contains(inc.IP) && incOnes >= excOnes
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

func run(args ...string) error {
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func execIgnore(args ...string) error {
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}
