//go:build windows

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"unicode/utf8"

	"github.com/parsend/pterovpn/internal/clientlog"
	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/proxy"
	"github.com/parsend/pterovpn/internal/sysproxy"
	"github.com/parsend/pterovpn/internal/tun"
	"github.com/parsend/pterovpn/internal/vpn"
	"golang.org/x/text/encoding/charmap"
)

func runPlatform(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	if opts.proxy {
		return runProxy(ctx, addrs, opts, onReady)
	}
	dr, err := netcfg.GetDefaultRoute()
	if err != nil {
		return err
	}
	if opts.serverIP.To4() == nil {
		clientlog.Warn("warning: server %s is IPv6, bypass route not added (Windows)", opts.serverIP)
	} else if err := netcfg.AddBypass(opts.serverIP, dr); err != nil {
		return err
	} else {
		clientlog.Info("bypass route for %s (metric 1), dr.Dev=%q dr.Gateway=%q", opts.serverIP, dr.Dev, dr.Gateway)
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
	defer func() {
		netcfg.DelRoutesViaTun(name, opts.routeCIDRs)
		netcfg.DelExcludeRoutes(opts.excludeCIDRs)
		netcfg.DelBypass(opts.serverIP)
	}()

	sigCtx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- vpn.Run(sigCtx, vpn.Options{
			Device:      dev,
			MTU:         opts.mtu,
			Token:       opts.token,
			ServerAddrs: addrs,
			Ready:       func() { close(ready) },
			Protection:  opts.protection,
		})
	}()

	select {
	case <-ready:
		clientlog.OK("Tunnel ready, switching routes to %s", name)
		if err := netcfg.AddRoutesViaTun(name, opts.routeCIDRs, 5); err != nil {
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
	ip, mask, err := parseCIDR(cidr)
	if err != nil {
		return err
	}
	args := []string{"interface", "ipv4", "set", "address", "name=" + `"` + strings.ReplaceAll(name, `"`, `\"`) + `"`, "static", ip, mask, "store=active"}
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
	return proxy.Run(sigCtx, opts.proxyListen, addrs, opts.token, opts.protection)
}

func parseCIDR(cidr string) (ip, mask string, err error) {
	idx := strings.Index(cidr, "/")
	if idx < 0 {
		return "", "", errors.New("bad cidr")
	}
	ip = strings.TrimSpace(cidr[:idx])
	maskLen := cidr[idx+1:]
	switch maskLen {
	case "24":
		mask = "255.255.255.0"
	case "16":
		mask = "255.255.0.0"
	case "8":
		mask = "255.0.0.0"
	default:
		mask = "255.255.255.0"
	}
	return ip, mask, nil
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
