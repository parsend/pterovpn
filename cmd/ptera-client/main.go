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
	"strings"
	"syscall"
	"time"

	"github.com/parsend/pterovpn/internal/clientconfig"
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
		configPath   = flag.String("config", "", "config file path")
		server       = flag.String("server", "", "host:port or host")
		ports        = flag.String("ports", "", "csv ports for multiport")
		token        = flag.String("token", "", "token")
		tunName      = flag.String("tun", "ptera0", "tun name")
		tunCIDR      = flag.String("tun-cidr", "10.13.37.2/24", "tun cidr")
		mtu          = flag.Int("mtu", 1420, "mtu")
		keepaliveSec = flag.Int("keepalive", 30, "udp keepalive interval (seconds)")
		reconnect    = flag.Bool("reconnect", false, "reconnect on failure")
		quiet        = flag.Bool("quiet", false, "minimal output, only tunnel up and errors")
		obfuscate    = flag.Bool("obfuscate", false, "xor obfuscation (must match server)")
		compression  = flag.Bool("compression", false, "gzip compression (must match server)")
	)
	flag.Parse()

	cfg, err := clientconfig.Load(*configPath)
	if err != nil {
		return err
	}
	mergeFlags(&cfg, server, ports, token, tunName, tunCIDR, mtu, keepaliveSec, reconnect, quiet, obfuscate, compression)

	if cfg.Server == "" && cfg.Servers == "" {
		return errors.New("need server or servers in config or --server")
	}
	if cfg.Token == "" {
		return errors.New("need token in config or --token")
	}

	var addrs []string
	if cfg.Servers != "" {
		addrs = splitServers(cfg.Servers)
	} else {
		addrs, err = netcfg.SplitHostPorts(cfg.Server, cfg.Ports)
		if err != nil {
			return err
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	opts := sessionOpts{
		addrs:         addrs,
		token:         cfg.Token,
		tunName:       cfg.Tun,
		tunCIDR:       cfg.TunCIDR,
		mtu:           cfg.MTU,
		keepaliveSec:  cfg.KeepaliveSec,
		reconnect:     cfg.Reconnect,
		includeRoutes: cfg.IncludeRoutes,
		excludeRoutes: cfg.ExcludeRoutes,
		quiet:         cfg.Quiet || *quiet,
		obfuscate:     cfg.Obfuscate || *obfuscate,
		compression:   cfg.Compression || *compression,
	}
	if opts.tunName == "" {
		opts.tunName = "ptera0"
	}
	if opts.tunCIDR == "" {
		opts.tunCIDR = "10.13.37.2/24"
	}
	if opts.mtu <= 0 {
		opts.mtu = 1420
	}
	if opts.keepaliveSec <= 0 {
		opts.keepaliveSec = 30
	}

	for {
		err := runSession(ctx, opts)
		if ctx.Err() != nil {
			return nil
		}
		if !opts.reconnect {
			return err
		}
		if len(opts.addrs) > 1 {
			opts.addrs = append(opts.addrs[1:], opts.addrs[0])
		}
		if opts.quiet {
			fmt.Fprintln(os.Stderr, "reconnecting in 3s:", err)
		} else {
			log.Printf("reconnecting in 3s: %v", err)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(3 * time.Second):
		}
	}
}

func mergeFlags(cfg *clientconfig.Config, server, ports, token, tunName, tunCIDR *string, mtu, keepaliveSec *int, reconnect, quiet, obfuscate, compression *bool) {
	flag.Visit(func(f *flag.Flag) {
		switch f.Name {
		case "server":
			if *server != "" {
				cfg.Server = *server
			}
		case "ports":
			cfg.Ports = *ports
		case "token":
			if *token != "" {
				cfg.Token = *token
			}
		case "tun":
			cfg.Tun = *tunName
		case "tun-cidr":
			cfg.TunCIDR = *tunCIDR
		case "mtu":
			if *mtu > 0 {
				cfg.MTU = *mtu
			}
		case "keepalive":
			if *keepaliveSec > 0 {
				cfg.KeepaliveSec = *keepaliveSec
			}
		case "reconnect":
			cfg.Reconnect = *reconnect
		case "quiet":
			cfg.Quiet = *quiet
		case "obfuscate":
			cfg.Obfuscate = *obfuscate
		case "compression":
			cfg.Compression = *compression
		}
	})
}

func splitServers(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}

type sessionOpts struct {
	addrs         []string
	token         string
	tunName       string
	tunCIDR       string
	mtu           int
	keepaliveSec  int
	reconnect     bool
	includeRoutes string
	excludeRoutes string
	quiet         bool
	obfuscate     bool
	compression   bool
}

func runSession(ctx context.Context, opts sessionOpts) error {
	host := opts.addrs[0]
	if h, _, err := net.SplitHostPort(host); err == nil {
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
	defer netcfg.DelBypass(sip)

	f, name, err := tun.Create(opts.tunName)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := tun.Configure(name, opts.tunCIDR, opts.mtu); err != nil {
		return err
	}
	var addedRoutes []string
	defer func() {
		for _, cidr := range addedRoutes {
			netcfg.DelRouteViaTun(name, cidr)
		}
		netcfg.DelDefaultViaTun(name)
		tun.Teardown(name, opts.tunCIDR)
	}()

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- vpn.Run(ctx, vpn.Options{
			TunFD:        int(f.Fd()),
			MTU:          opts.mtu,
			Token:        opts.token,
			ServerAddrs:  opts.addrs,
			KeepaliveSec: opts.keepaliveSec,
			Quiet:        opts.quiet,
			Obfuscate:    opts.obfuscate,
			Compression:  opts.compression,
			Ready:        func() { close(ready) },
		})
	}()

	select {
	case <-ready:
		if opts.quiet {
			fmt.Fprintln(os.Stderr, "Tunnel up")
		} else {
			log.Printf("Tunnel ready, switching default route to %s", name)
		}
		if opts.includeRoutes != "" {
			routes, err := netcfg.RoutesToAdd(opts.includeRoutes, opts.excludeRoutes)
			if err != nil {
				return err
			}
			for _, cidr := range routes {
				if err := netcfg.AddRouteViaTun(name, cidr, 5); err != nil {
					return err
				}
				addedRoutes = append(addedRoutes, cidr)
			}
		} else {
			if err := netcfg.AddDefaultViaTun(name, 5); err != nil {
				return err
			}
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
