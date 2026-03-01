//go:build windows

package netcfg

import (
	"bytes"
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
)

func GetDefaultRoute() (DefaultRoute, error) {
	out, err := exec.Command("route", "print", "-4", "0.0.0.0").Output()
	if err != nil {
		out, err = exec.Command("route", "print", "0.0.0.0").Output()
		if err != nil {
			return DefaultRoute{}, err
		}
	}
	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		f := strings.Fields(line)
		if len(f) >= 5 && f[0] == "0.0.0.0" && f[1] == "0.0.0.0" {
			dr := DefaultRoute{Gateway: f[2]}
			if iface := interfaceByIP(strings.TrimSpace(f[3])); iface != "" {
				dr.Dev = iface
			}
			return dr, nil
		}
	}
	return DefaultRoute{}, fmt.Errorf("no default route")
}

func interfaceByIP(needle string) string {
	out, err := exec.Command("netsh", "interface", "ipv4", "show", "addresses").Output()
	if err != nil {
		return ""
	}
	var cur string
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "Configuration for interface ") {
			cur = strings.Trim(strings.TrimPrefix(line, "Configuration for interface "), "\"")
			continue
		}
		if cur != "" && strings.Contains(line, needle) && net.ParseIP(needle) != nil {
			return cur
		}
	}
	return ""
}

func AddBypass(serverIP net.IP, dr DefaultRoute) error {
	ip := serverIP.String()
	if serverIP.To4() != nil {
		ip += "/32"
	} else {
		return nil
	}
	args := []string{"interface", "ipv4", "add", "route", "prefix=" + ip, "metric=1"}
	if dr.Dev != "" {
		args = append(args, "interface=\""+dr.Dev+"\"")
	}
	if dr.Gateway != "" {
		args = append(args, "nexthop="+dr.Gateway)
	}
	args = append(args, "store=active")
	cmd := exec.Command("netsh", args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("netsh: %w: %s", err, bytes.TrimSpace(out))
	}
	return nil
}

func DelBypass(serverIP net.IP) {
	ip := serverIP.String()
	if serverIP.To4() != nil {
		ip += "/32"
	} else {
		return
	}
	_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+ip, "store=active").Run()
}

func AddDefaultViaTun(iface string, metric int) error {
	gateway := tunGateway(iface)
	if gateway == "" {
		gateway = "10.13.37.1"
	}
	args := []string{"interface", "ipv4", "add", "route", "prefix=0.0.0.0/0", "interface=" + iface, "nexthop=" + gateway, "metric=" + strconv.Itoa(metric), "store=active"}
	cmd := exec.Command("netsh", args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		if bytes.Contains(out, []byte("already exists")) {
			return nil
		}
		return fmt.Errorf("netsh: %w: %s", err, bytes.TrimSpace(out))
	}
	return nil
}

func DelDefaultViaTun(iface string) {
	_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix=0.0.0.0/0", "interface="+iface, "store=active").Run()
}

func AddExcludeRoutes(dr DefaultRoute, cidrs []*net.IPNet) error {
	for _, n := range cidrs {
		if n.IP.To4() == nil {
			continue
		}
		args := []string{"interface", "ipv4", "add", "route", "prefix=" + n.String(), "metric=1"}
		if dr.Dev != "" {
			args = append(args, "interface=\""+dr.Dev+"\"")
		}
		if dr.Gateway != "" {
			args = append(args, "nexthop="+dr.Gateway)
		}
		args = append(args, "store=active")
		if out, err := exec.Command("netsh", args...).CombinedOutput(); err != nil {
			return fmt.Errorf("exclude %s: %w: %s", n.String(), err, bytes.TrimSpace(out))
		}
	}
	return nil
}

func DelExcludeRoutes(cidrs []*net.IPNet) {
	for _, n := range cidrs {
		if n.IP.To4() == nil {
			continue
		}
		_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+n.String(), "store=active").Run()
	}
}

func AddRoutesViaTun(iface string, cidrs []*net.IPNet, metric int) error {
	gateway := tunGateway(iface)
	if gateway == "" {
		gateway = "10.13.37.1"
	}
	metricStr := strconv.Itoa(metric)
	if len(cidrs) == 0 {
		return AddDefaultViaTun(iface, metric)
	}
	for _, n := range cidrs {
		args := []string{"interface", "ipv4", "add", "route", "prefix=" + n.String(), "interface=" + iface, "nexthop=" + gateway, "metric=" + metricStr, "store=active"}
		if n.IP.To4() != nil {
			cmd := exec.Command("netsh", args...)
			if out, err := cmd.CombinedOutput(); err != nil && !bytes.Contains(out, []byte("already exists")) {
				return fmt.Errorf("route %s: %w: %s", n.String(), err, bytes.TrimSpace(out))
			}
		}
	}
	return nil
}

func DelRoutesViaTun(iface string, cidrs []*net.IPNet) {
	if len(cidrs) == 0 {
		DelDefaultViaTun(iface)
		return
	}
	for _, n := range cidrs {
		if n.IP.To4() != nil {
			_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+n.String(), "interface="+iface, "store=active").Run()
		}
	}
}

func tunGateway(iface string) string {
	out, err := exec.Command("netsh", "interface", "ipv4", "show", "address", "name="+iface).Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		if strings.Contains(line, "IP Address") {
			f := strings.Fields(line)
			for i, s := range f {
				if s == "Address" && i+1 < len(f) {
					ip := net.ParseIP(f[i+1])
					if ip != nil && ip.To4() != nil {
						ip[3]--
						return ip.String()
					}
				}
			}
		}
	}
	return ""
}
