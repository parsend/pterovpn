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
		routes       = flag.String("routes", "", "cidrs to route via tunnel (default=all). e.g. 0.0.0.0/0,::/0")
		exclude      = flag.String("exclude", "", "cidrs to exclude from tunnel (use default gw). e.g. 192.168.0.0/16,10.0.0.0/8")
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
	mergeFlags(&cfg, server, ports, token, tunName, tunCIDR, mtu, routes, exclude, keepaliveSec, reconnect, quiet, obfuscate, compression)

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

func mergeFlags(cfg *clientconfig.Config, server, ports, token, tunName, tunCIDR *string, mtu *int, routes, exclude *string, keepaliveSec *int, reconnect, quiet, obfuscate, compression *bool) {
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
		case "routes":
			if *routes != "" {
				cfg.IncludeRoutes = *routes
			}
		case "exclude":
			if *exclude != "" {
				cfg.ExcludeRoutes = *exclude
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

func parseRouteConfig(includeRoutes, excludeRoutes string) (routeCIDRs, excludeCIDRs []*net.IPNet, err error) {
	excludeCIDRs, err = netcfg.ParseCIDRs(excludeRoutes)
	if err != nil {
		return nil, nil, err
	}
	if includeRoutes != "" {
		routes, err := netcfg.RoutesToAdd(includeRoutes, excludeRoutes)
		if err != nil {
			return nil, nil, err
		}
		routeCIDRs, err = netcfg.ParseCIDRs(strings.Join(routes, ","))
		if err != nil {
			return nil, nil, err
		}
	}
	return routeCIDRs, excludeCIDRs, nil
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

	routeCIDRs, excludeCIDRs, err := parseRouteConfig(opts.includeRoutes, opts.excludeRoutes)
	if err != nil {
		return err
	}
	if err := netcfg.AddExcludeRoutes(dr, excludeCIDRs); err != nil {
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
		netcfg.DelRoutesViaTun(name, routeCIDRs)
		netcfg.DelExcludeRoutes(excludeCIDRs)
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
			log.Printf("Tunnel ready, switching routes to %s", name)
		}
		if err := netcfg.AddRoutesViaTun(name, routeCIDRs, 5); err != nil {
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
