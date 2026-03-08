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
	ip := net.ParseIP(needle)
	if ip == nil {
		return ""
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP == nil {
				continue
			}
			if ipNet.IP.Equal(ip) {
				return iface.Name
			}
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
	DelBypass(serverIP)
	args := []string{"interface", "ipv4", "add", "route", "prefix=" + ip, "metric=1"}
	if dr.Dev != "" {
		args = append(args, "interface="+quoteNetshName(dr.Dev))
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
	DelDefaultViaTun(iface)
	args := []string{"interface", "ipv4", "add", "route", "prefix=0.0.0.0/0", "interface=" + quoteNetshName(iface), "nexthop=" + gateway, "metric=" + strconv.Itoa(metric), "store=active"}
	cmd := exec.Command("netsh", args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("netsh: %w: %s", err, bytes.TrimSpace(out))
	}
	return nil
}

func DelDefaultViaTun(iface string) {
	_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix=0.0.0.0/0", "interface="+quoteNetshName(iface), "store=active").Run()
}

func AddExcludeRoutes(dr DefaultRoute, cidrs []*net.IPNet) error {
	for _, n := range cidrs {
		if n.IP.To4() == nil {
			continue
		}
		_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+n.String(), "store=active").Run()
		args := []string{"interface", "ipv4", "add", "route", "prefix=" + n.String(), "metric=1"}
		if dr.Dev != "" {
			args = append(args, "interface="+quoteNetshName(dr.Dev))
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
		args := []string{"interface", "ipv4", "add", "route", "prefix=" + n.String(), "interface=" + quoteNetshName(iface), "nexthop=" + gateway, "metric=" + metricStr, "store=active"}
		if n.IP.To4() != nil {
			_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+n.String(), "interface="+quoteNetshName(iface), "store=active").Run()
			cmd := exec.Command("netsh", args...)
			if out, err := cmd.CombinedOutput(); err != nil {
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
			_ = exec.Command("netsh", "interface", "ipv4", "delete", "route", "prefix="+n.String(), "interface="+quoteNetshName(iface), "store=active").Run()
		}
	}
}

func tunGateway(iface string) string {
	dev, err := net.InterfaceByName(iface)
	if err != nil {
		return ""
	}
	addrs, err := dev.Addrs()
	if err != nil {
		return ""
	}
	for _, addr := range addrs {
		ipNet, ok := addr.(*net.IPNet)
		if !ok || ipNet.IP == nil {
			continue
		}
		ip4 := ipNet.IP.To4()
		if ip4 == nil {
			continue
		}
		gateway := make(net.IP, len(ip4))
		copy(gateway, ip4)
		gateway[3]--
		return gateway.String()
	}
	return ""
}

func quoteNetshName(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `\"`) + `"`
}
