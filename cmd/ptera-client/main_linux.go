//go:build linux

package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/parsend/pterovpn/internal/clientlog"
	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/proxy"
	"github.com/parsend/pterovpn/internal/tun"
	"github.com/parsend/pterovpn/internal/vpn"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
)

func runPlatform(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("run as root: sudo ptera-client ...")
	}
	if opts.proxy {
		sigCtx, stop := signal.NotifyContext(ctx, syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		clientlog.Info("proxy mode: listening on %s", opts.proxyListen)
		if onReady != nil {
			onReady()
		}
		return proxy.Run(sigCtx, opts.proxyListen, addrs, opts.token, opts.protection)
	}
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

	defer func() {
		netcfg.DelExcludeRoutes(opts.excludeCIDRs)
		netcfg.DelBypass(opts.serverIP)
	}()

	createDevice := func() (device.Device, func(), error) {
		f, name, err := tun.Create(opts.tunName)
		if err != nil {
			return nil, nil, err
		}
		if err := tun.Configure(name, opts.tunCIDR, opts.mtu); err != nil {
			_ = f.Close()
			return nil, nil, err
		}
		dev, err := fdbased.Open(strconv.Itoa(int(f.Fd())), uint32(opts.mtu), 0)
		if err != nil {
			_ = f.Close()
			return nil, nil, err
		}
		cleanup := func() {
			netcfg.DelRoutesViaTun(name, opts.routeCIDRs)
			tun.Teardown(name, opts.tunCIDR)
			dev.Close()
			_ = f.Close()
		}
		return dev, cleanup, nil
	}

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- vpn.Run(sigCtx, vpn.Options{
			CreateDevice: createDevice,
			Token:       opts.token,
			ServerAddrs: addrs,
			Ready:       func() { close(ready) },
			Protection:  opts.protection,
		})
	}()

	select {
	case <-ready:
		clientlog.OK("Tunnel ready, switching routes to %s", opts.tunName)
		if err := netcfg.AddRoutesViaTun(opts.tunName, opts.routeCIDRs, 5); err != nil {
			return err
		}
		if onReady != nil {
			onReady()
		}
	case err := <-errCh:
		return err
	case <-sigCtx.Done():
		<-errCh
		return nil
	}

	select {
	case <-sigCtx.Done():
		<-errCh
		return nil
	case err := <-errCh:
		return err
	}
}
