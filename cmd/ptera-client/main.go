package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"

	"github.com/parsend/pterovpn/internal/netcfg"
)

type runOpts struct {
	serverIP    net.IP
	token       string
	tunName     string
	tunCIDR     string
	mtu         int
	routeCIDRs  []*net.IPNet
	excludeCIDRs []*net.IPNet
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}

func run() error {
	var (
		tui     = flag.Bool("tui", false, "run TUI")
		server  = flag.String("server", "", "host:port or host")
		ports   = flag.String("ports", "", "csv ports for multiport")
		token   = flag.String("token", "", "token")
		tunName = flag.String("tun", "ptera0", "tun name")
		tunCIDR = flag.String("tun-cidr", "10.13.37.2/24", "tun cidr")
		mtu     = flag.Int("mtu", 1420, "mtu")
		routes  = flag.String("routes", "", "cidrs to route via tunnel (default=all)")
		exclude = flag.String("exclude", "", "cidrs to exclude from tunnel")
	)
	flag.Parse()

	if *tui || (*server == "" && *token == "") {
		return runTUI()
	}

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
	addrs = netcfg.ResolveAddrs(addrs, sip)

	routeCIDRs, err := netcfg.ParseCIDRs(*routes)
	if err != nil {
		return err
	}
	excludeCIDRs, err := netcfg.ParseCIDRs(*exclude)
	if err != nil {
		return err
	}

	opts := runOpts{
		serverIP:     sip,
		token:        *token,
		tunName:      *tunName,
		tunCIDR:      *tunCIDR,
		mtu:          *mtu,
		routeCIDRs:   routeCIDRs,
		excludeCIDRs: excludeCIDRs,
	}
	return runPlatform(context.Background(), addrs, opts, nil)
}
