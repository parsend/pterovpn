//go:build windows

package main

import (
	"context"
	"errors"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"

	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/proxy"
	"github.com/parsend/pterovpn/internal/sysproxy"
	"github.com/parsend/pterovpn/internal/tun"
	"github.com/parsend/pterovpn/internal/vpn"
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
		log.Printf("warning: server %s is IPv6, bypass route not added (Windows)", opts.serverIP)
	} else if err := netcfg.AddBypass(opts.serverIP, dr); err != nil {
		return err
	} else {
		log.Printf("bypass route for %s (metric 1), dr.Dev=%q dr.Gateway=%q", opts.serverIP, dr.Dev, dr.Gateway)
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
			Device:       dev,
			MTU:          opts.mtu,
			Token:        opts.token,
			ServerAddrs:  addrs,
			Ready:        func() { close(ready) },
			Protection:   opts.protection,
			OnUDPSupport: opts.onUDPSupport,
		})
	}()

	select {
	case <-ready:
		log.Printf("Tunnel ready, switching routes to %s", name)
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
	args := []string{"interface", "ipv4", "set", "address", "name=" + name, "static", ip, mask, "store=active"}
	_, err = exec.Command("netsh", args...).CombinedOutput()
	return err
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
	log.Printf("proxy mode: listening on %s", opts.proxyListen)
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
