package main

import (
	"context"
	"fmt"
	"log"
	"net"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/tui"
)

func runTUI() error {
	opts := tui.Opts{
		ConnectFn: connectVPN,
	}
	p := tea.NewProgram(tui.NewModel(opts), tea.WithAltScreen())
	_, err := p.Run()
	return err
}

func connectVPN(cfg config.Config) (stop func(), err error) {
	if cfg.Server == "" || cfg.Token == "" {
		return nil, fmt.Errorf("server и token обязательны")
	}
	addrs, err := netcfg.SplitHostPorts(cfg.Server, "")
	if err != nil {
		return nil, err
	}
	host := cfg.Server
	if h, _, e := net.SplitHostPort(cfg.Server); e == nil {
		host = h
	}
	sip, err := netcfg.ResolveHost(host)
	if err != nil {
		return nil, err
	}
	addrs = netcfg.ResolveAddrs(addrs, sip)
	routeCIDRs, err := netcfg.ParseCIDRs(cfg.Routes)
	if err != nil {
		return nil, err
	}
	excludeCIDRs, err := netcfg.ParseCIDRs(cfg.Exclude)
	if err != nil {
		return nil, err
	}
	opts := runOpts{
		serverIP:     sip,
		token:        cfg.Token,
		tunName:      "ptera0",
		tunCIDR:      "10.13.37.2/24",
		mtu:          1420,
		routeCIDRs:   routeCIDRs,
		excludeCIDRs: excludeCIDRs,
	}

	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan struct{})
	errCh := make(chan error, 1)
	go func() {
		errCh <- runPlatform(ctx, addrs, opts, func() {
			close(ready)
		})
	}()

	select {
	case <-ready:
		log.Printf("TUI: connected to %s", cfg.Server)
		return cancel, nil
	case err := <-errCh:
		return nil, err
	}
}
