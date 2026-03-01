//go:build linux

package main

import (
	"context"
	"log"
	"os/signal"
	"syscall"

	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/tun"
	"github.com/parsend/pterovpn/internal/vpn"
)

func runPlatform(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	sigCtx, stop := signal.NotifyContext(ctx, syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	dr, err := netcfg.GetDefaultRoute()
	if err != nil {
		return err
	}
	if err := netcfg.AddBypass(opts.serverIP, dr); err != nil {
		return err
	}
	if err := netcfg.AddExcludeRoutes(dr, opts.excludeCIDRs); err != nil {
		return err
	}

	f, name, err := tun.Create(opts.tunName)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := tun.Configure(name, opts.tunCIDR, opts.mtu); err != nil {
		return err
	}
	defer func() {
		netcfg.DelRoutesViaTun(name, opts.routeCIDRs)
		netcfg.DelExcludeRoutes(opts.excludeCIDRs)
		netcfg.DelBypass(opts.serverIP)
		tun.Teardown(name, opts.tunCIDR)
	}()

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- vpn.Run(sigCtx, vpn.Options{
			TunFD:       int(f.Fd()),
			MTU:         opts.mtu,
			Token:       opts.token,
			ServerAddrs: addrs,
			Ready:       func() { close(ready) },
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
