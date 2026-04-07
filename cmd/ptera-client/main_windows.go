//go:build windows

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/netcfg"
	"github.com/unitdevgcc/pterovpn/internal/proxy"
	"github.com/unitdevgcc/pterovpn/internal/sysproxy"
	"github.com/unitdevgcc/pterovpn/internal/tun"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
	"github.com/unitdevgcc/pterovpn/internal/vpn"
	"golang.org/x/text/encoding/charmap"
)

func dedupeIPsWindows(ips []net.IP) []net.IP {
	seen := make(map[string]struct{})
	var out []net.IP
	for _, ip := range ips {
		if ip == nil {
			continue
		}
		k := ip.String()
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, ip)
	}
	return out
}

func runPlatform(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	if opts.proxy {
		return runProxy(ctx, addrs, opts, onReady)
	}
	var v4Routes, v6Routes []*net.IPNet
	for _, n := range opts.routeCIDRs {
		if n.IP.To4() != nil {
			v4Routes = append(v4Routes, n)
		} else {
			v6Routes = append(v6Routes, n)
		}
	}
	dr, err := netcfg.GetDefaultRoute()
	if err != nil {
		return err
	}
	bypassIPs := []net.IP{opts.serverIP}
	if tunnel.UsesQUICTransport(opts.transport, opts.quicServer) {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		extra, err := tunnel.QUICDialTargetIPs(ctx, addrs, opts.quicServer)
		cancel()
		if err != nil {
			clientlog.Warn("vpn: QUIC bypass resolve: %v", err)
		} else {
			bypassIPs = append(bypassIPs, extra...)
		}
	}
	bypassIPs = dedupeIPsWindows(bypassIPs)
	var bypassAdded []net.IP
	for _, ip := range bypassIPs {
		if ip.To4() == nil {
			continue
		}
		if err := netcfg.AddBypass(ip, dr); err != nil {
			return err
		}
		bypassAdded = append(bypassAdded, ip)
		clientlog.Info("bypass route for %s (metric 1), dr.Dev=%q dr.Gateway=%q", ip, dr.Dev, dr.Gateway)
	}
	if len(bypassAdded) == 0 && opts.serverIP.To4() != nil {
		clientlog.Warn("warning: no IPv4 bypass routes added")
	}
	if opts.serverIP.To4() == nil {
		clientlog.Warn("warning: server %s is IPv6, Windows bypass uses IPv4 only", opts.serverIP)
	}
	if err := netcfg.AddExcludeRoutes(dr, opts.excludeCIDRs); err != nil {
		return err
	}

	dev, name, err := tun.Create(opts.tunName, opts.mtu)
	if err != nil {
		return err
	}
	defer dev.Close()

	if err := configureTunWindows(name, opts.tunCIDR, opts.mtu); err != nil {
		return err
	}
	if opts.tunCIDR6 != "" {
		if err := configureTunWindowsV6(name, opts.tunCIDR6); err != nil {
			return err
		}
	}
	defer func() {
		delIPv6Routes(name, v6Routes)
		netcfg.DelRoutesViaTun(name, v4Routes)
		netcfg.DelExcludeRoutes(opts.excludeCIDRs)
		for _, ip := range bypassAdded {
			netcfg.DelBypass(ip)
		}
	}()

	sigCtx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	vpnCtx, vpnCancel := context.WithCancel(sigCtx)
	defer vpnCancel()
	go func() {
		vo := vpn.Options{
			Device:            dev,
			MTU:               opts.mtu,
			Token:             opts.token,
			ServerAddrs:       addrs,
			Transport:         opts.transport,
			QuicServer:        opts.quicServer,
			QuicServerName:    opts.quicServerName,
			QuicSkipVerify:    opts.quicSkipVerify,
			QuicCertPinSHA256: opts.quicCertPinSHA256,
			QuicTLSRoots:      opts.quicTLSRoots,
			QuicAlpn:          opts.quicAlpn,
			QuicTraceLog:      opts.quicTraceLog,
			DualTransport:     opts.dualTransport,
			Ready:             func() { close(ready) },
			Protection:        opts.protection,
		}
		if opts.watchdogFail != nil {
			vo.WatchdogInterval = time.Minute
			vo.WatchdogServerPingTimeout = 2 * time.Second
			vo.OnWatchdogFail = func() {
				if opts.watchdogMark != nil {
					opts.watchdogMark.Store(true)
				}
				if opts.watchdogFail != nil {
					select {
					case opts.watchdogFail <- struct{}{}:
					default:
					}
				}
				vpnCancel()
			}
		}
		errCh <- vpn.Run(vpnCtx, vo)
	}()

	select {
	case <-ready:
		clientlog.OK("Tunnel ready, switching routes to %s", name)
		if err := netcfg.AddRoutesViaTun(name, v4Routes, 5); err != nil {
			return err
		}
		if err := addIPv6Routes(name, v6Routes, 5); err != nil {
			return err
		}
		if onReady != nil {
			onReady()
		}
	case err := <-errCh:
		return err
	case <-sigCtx.Done():
		return nil
	}

	select {
	case <-sigCtx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func configureTunWindows(name, cidr string, _ int) error {
	ip, prefixLen, err := parseCIDR(cidr)
	if err != nil {
		return err
	}
	ifParam := netshIPv4NameParam(name)
	mask := ipv4MaskFromPrefixLen(prefixLen)
	var args []string
	if os.Getenv("PTERA_NETSH_STYLE") == "legacy" {
		args = []string{"interface", "ipv4", "set", "address", ifParam, "static", ip, mask, "store=active"}
	} else {
		args = []string{"interface", "ipv4", "set", "address", ifParam, "source=static", "address=" + ip, "mask=" + mask, "store=active"}
	}
	out, err := exec.Command("netsh", args...).CombinedOutput()
	if err == nil {
		return nil
	}
	msg := decodeNetshOutput(out)
	if isAlreadyExistsNetsh(msg) {
		return nil
	}
	if msg == "" {
		return err
	}
	return fmt.Errorf("netsh: %w: %s", err, msg)
}

func netshIPv4NameParam(ifName string) string {
	if ifName == "" {
		return `name=""`
	}
	if dev, err := net.InterfaceByName(ifName); err == nil {
		return "name=" + strconv.Itoa(dev.Index)
	}
	return `name="` + strings.ReplaceAll(ifName, `"`, `\"`) + `"`
}

func netshIPv6InterfaceParam(ifName string) string {
	if ifName == "" {
		return `interface=""`
	}
	if dev, err := net.InterfaceByName(ifName); err == nil {
		return "interface=" + strconv.Itoa(dev.Index)
	}
	return `interface="` + strings.ReplaceAll(ifName, `"`, `\"`) + `"`
}

func ipv4MaskFromPrefixLen(prefixLen string) string {
	n, err := strconv.Atoi(strings.TrimSpace(prefixLen))
	if err != nil || n < 0 || n > 32 {
		n = 24
	}
	if n == 0 {
		return "0.0.0.0"
	}
	m := uint32(^uint32(0) << (32 - n))
	return fmt.Sprintf("%d.%d.%d.%d", byte(m>>24), byte(m>>16), byte(m>>8), byte(m&0xff))
}

func configureTunWindowsV6(name, cidr string) error {
	ip, prefix, err := parseCIDRv6(cidr)
	if err != nil {
		return err
	}
	if ip == "" || prefix == "" {
		return errors.New("bad ipv6 cidr")
	}
	ifParam := netshIPv6InterfaceParam(name)
	addr := ip + "/" + prefix
	args := []string{
		"interface", "ipv6", "add", "address",
		ifParam,
		"address=" + addr,
		"store=active",
	}
	out, err := exec.Command("netsh", args...).CombinedOutput()
	if err == nil {
		return nil
	}
	msg := decodeNetshOutput(out)
	if isAlreadyExistsNetsh(msg) {
		return nil
	}
	if msg == "" {
		return err
	}
	return fmt.Errorf("netsh ipv6 add address: %w: %s", err, msg)
}

func runProxy(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	sigCtx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()
	if opts.systemProxy {
		if err := sysproxy.Set(opts.proxyListen); err != nil {
			return err
		}
		defer sysproxy.Clear()
	}
	clientlog.Info("proxy mode: listening on %s", opts.proxyListen)
	if onReady != nil {
		onReady()
	}
	tunnel.SetQUICTrace(opts.quicTraceLog)
	return proxy.Run(sigCtx, opts.proxyListen, addrs, opts.token, opts.protection, opts.transport, opts.quicServer, opts.quicServerName, opts.quicSkipVerify, opts.quicCertPinSHA256, opts.quicTLSRoots, opts.quicAlpn)
}

func parseCIDR(cidr string) (ip, prefixLen string, err error) {
	idx := strings.Index(cidr, "/")
	if idx < 0 {
		return "", "", errors.New("bad cidr")
	}
	ip = strings.TrimSpace(cidr[:idx])
	prefixLen = strings.TrimSpace(cidr[idx+1:])
	if prefixLen == "" {
		return "", "", errors.New("bad cidr")
	}
	if parsed := net.ParseIP(ip); parsed == nil || parsed.To4() == nil {
		return "", "", errors.New("tun cidr must be ipv4 (use -tun-cidr6 for ipv6)")
	}

	if n, e := strconv.Atoi(prefixLen); e == nil {
		if n < 0 {
			prefixLen = "0"
		} else if n > 32 {
			prefixLen = "32"
		}
	} else {
		prefixLen = "24"
	}
	return ip, prefixLen, nil
}

func parseCIDRv6(cidr string) (ip, prefix string, err error) {
	idx := strings.Index(cidr, "/")
	if idx < 0 {
		return "", "", errors.New("bad cidr")
	}
	ip = strings.TrimSpace(cidr[:idx])
	prefix = strings.TrimSpace(cidr[idx+1:])
	if ip == "" || prefix == "" {
		return "", "", errors.New("bad cidr")
	}
	if n, e := strconv.Atoi(prefix); e == nil {
		if n < 0 {
			prefix = "0"
		} else if n > 128 {
			prefix = "128"
		}
	} else {
		prefix = "64"
	}
	return ip, prefix, nil
}

func addIPv6Routes(iface string, cidrs []*net.IPNet, metric int) error {
	if len(cidrs) == 0 {
		return nil
	}
	ifParam := netshIPv6InterfaceParam(iface)
	metricStr := strconv.Itoa(metric)
	for _, n := range cidrs {
		args := []string{
			"interface", "ipv6", "add", "route",
			"prefix=" + n.String(),
			ifParam,
			"metric=" + metricStr,
			"store=active",
		}
		out, err := exec.Command("netsh", args...).CombinedOutput()
		if err == nil {
			continue
		}
		msg := decodeNetshOutput(out)
		if isAlreadyExistsNetsh(msg) {
			continue
		}
		if msg == "" {
			return err
		}
		return fmt.Errorf("netsh ipv6 add route %s: %w: %s", n.String(), err, msg)
	}
	return nil
}

func delIPv6Routes(iface string, cidrs []*net.IPNet) {
	if len(cidrs) == 0 {
		return
	}
	ifParam := netshIPv6InterfaceParam(iface)
	for _, n := range cidrs {
		args := []string{
			"interface", "ipv6", "delete", "route",
			"prefix=" + n.String(),
			ifParam,
			"store=active",
		}
		_ = exec.Command("netsh", args...).Run()
	}
}

func decodeNetshOutput(out []byte) string {
	out = bytes.TrimSpace(out)
	if len(out) == 0 {
		return ""
	}
	if utf8.Valid(out) {
		return string(out)
	}
	if decoded, err := charmap.CodePage866.NewDecoder().Bytes(out); err == nil {
		s := strings.TrimSpace(string(decoded))
		if s != "" {
			return s
		}
	}
	if decoded, err := charmap.Windows1251.NewDecoder().Bytes(out); err == nil {
		s := strings.TrimSpace(string(decoded))
		if s != "" {
			return s
		}
	}
	return string(out)
}

func isAlreadyExistsNetsh(msg string) bool {
	msg = strings.ToLower(strings.TrimSpace(msg))
	return strings.Contains(msg, "already exists") ||
		strings.Contains(msg, "уже существует") ||
		strings.Contains(msg, "объект уже существует")
}
