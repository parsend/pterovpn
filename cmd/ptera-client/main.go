package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/tun"
	"github.com/parsend/pterovpn/internal/vpn"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}

func run() error {
	var (
		server  = flag.String("server", "", "host:port or host")
		ports   = flag.String("ports", "", "csv ports for multiport")
		token   = flag.String("token", "", "token")
		tunName = flag.String("tun", "ptera0", "tun name")
		tunCIDR = flag.String("tun-cidr", "10.13.37.2/24", "tun cidr")
		mtu     = flag.Int("mtu", 1420, "mtu")
	)
	flag.Parse()

	if *server == "" || *token == "" {
		return errors.New("need --server and --token")
	}

	addrs, err := netcfg.SplitHostPorts(*server, *ports)
	if err != nil {
		return err
	}

	host := *server
	if *ports == "" {
		h, _, err := net.SplitHostPort(*server)
		if err != nil {
			return err
		}
		host = h
	}
	sip, err := netcfg.ResolveHost(host)
	if err != nil {
		return err
	}
	dr, err := netcfg.GetDefaultRoute()
	if err != nil {
		return err
	}
	if err := netcfg.AddBypass(sip, dr); err != nil {
		return err
	}

	f, name, err := tun.Create(*tunName)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := tun.Configure(name, *tunCIDR, *mtu); err != nil {
		return err
	}
	defer func() {
		netcfg.DelDefaultViaTun(name)
		netcfg.DelBypass(sip)
		tun.Teardown(name, *tunCIDR)
	}()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- vpn.Run(ctx, vpn.Options{
			TunFD:       int(f.Fd()),
			MTU:         *mtu,
			Token:       *token,
			ServerAddrs: addrs,
			Ready:       func() { close(ready) },
		})
	}()

	select {
	case <-ready:
		log.Printf("Tunnel ready, switching default route to %s", name)
		if err := netcfg.AddDefaultViaTun(name, 5); err != nil {
			return err
		}
	case err := <-errCh:
		return err
	case <-ctx.Done():
		return nil
	}

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}
