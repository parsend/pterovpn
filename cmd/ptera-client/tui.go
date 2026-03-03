package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"strings"
	"syscall"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/parsend/pterovpn/internal/clientlog"
	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/metrics"
	"github.com/parsend/pterovpn/internal/netcfg"
	"github.com/parsend/pterovpn/internal/probe"
	"github.com/parsend/pterovpn/internal/tui"
)

type logWriter struct {
	ch chan<- string
	buf bytes.Buffer
}

func (w *logWriter) Write(p []byte) (n int, err error) {
	n = len(p)
	w.buf.Write(p)
	for {
		idx := bytes.IndexByte(w.buf.Bytes(), '\n')
		if idx < 0 {
			break
		}
		line := w.buf.Next(idx + 1)
		line = bytes.TrimSuffix(line, []byte{'\n'})
		select {
		case w.ch <- string(line):
		default:
		}
	}
	return n, nil
}

func runTUI() error {
	logCh := make(chan string, 200)
	udpCh := make(chan uint16, 1)
	log.SetOutput(&logWriter{ch: logCh})
	defer func() {
		log.SetOutput(os.Stderr)
		close(logCh)
		close(udpCh)
	}()

	opts := tui.Opts{
		ConnectFn:    connectVPN,
		UDPSupportCh: udpCh,
		Version:      version,
	}
	p := tea.NewProgram(tui.NewModel(opts), tea.WithAltScreen())
	go func() {
		for line := range logCh {
			p.Send(tui.LogMessage(line))
		}
	}()
	go func() {
		for port := range udpCh {
			p.Send(tui.UDPSupportMsg(port))
		}
	}()
	_, err := p.Run()
	return err
}

const probeTimeout = 5 * time.Second

func checkDNS() bool {
	return probe.DNSOK(nil, probeTimeout)
}

func classifyError(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return "timeout"
	}
	if errors.Is(err, syscall.ECONNRESET) {
		return "reset"
	}
	s := strings.ToLower(err.Error())
	if strings.Contains(s, "connection reset") {
		return "reset"
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return "dns"
	}
	if strings.Contains(s, "resource busy") || strings.Contains(s, "device busy") || strings.Contains(s, "address already in use") {
		return "device"
	}
	return "unknown"
}

func connectVPN(cfg config.Config, configName string, reconnectCount int, settings config.ClientSettings, udpCh chan<- uint16) (stop func(), err error) {
	if cfg.Server == "" || cfg.Token == "" {
		return nil, fmt.Errorf("server и token обязательны")
	}
	start := time.Now()
	dnsOK := checkDNS()
	rttBefore, _ := probe.Ping(cfg.Server, probeTimeout)
	probeOK, _ := probe.ProbePterovpn(cfg.Server, probeTimeout)

	record := metrics.SessionRecord{
		Start:          start,
		Server:         cfg.Server,
		ConfigName:     configName,
		DNSOKBefore:    dnsOK,
		RTTBefore:      rttBefore,
		ProbeOK:        probeOK,
		ReconnectCount: reconnectCount,
		HandshakeOK:    false,
	}

	addrs, err := netcfg.SplitHostPorts(cfg.Server, "")
	if err != nil {
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, _ := metrics.Load(); store != nil {
			_ = store.Append(record)
		}
		return nil, err
	}
	host := cfg.Server
	if h, _, e := net.SplitHostPort(cfg.Server); e == nil {
		host = h
	}
	sip, err := netcfg.ResolveHost(host)
	if err != nil {
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, _ := metrics.Load(); store != nil {
			_ = store.Append(record)
		}
		return nil, err
	}
	addrs = netcfg.ResolveAddrs(addrs, sip)
	routeCIDRs, err := netcfg.ParseCIDRs(cfg.Routes)
	if err != nil {
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, _ := metrics.Load(); store != nil {
			_ = store.Append(record)
		}
		return nil, err
	}
	excludeCIDRs, err := netcfg.ParseCIDRs(cfg.Exclude)
	if err != nil {
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, _ := metrics.Load(); store != nil {
			_ = store.Append(record)
		}
		return nil, err
	}
	prot := cfg.Protection
	if prot == nil {
		p, _ := config.LoadProtection()
		prot = &p
	}
	if prot != nil && prot.PreCheck && len(addrs) > 0 {
		ok, err := probe.ProbePterovpn(addrs[0], probeTimeout)
		if err != nil || !ok {
			record.ErrorType = "preCheck"
			record.End = time.Now()
			record.Duration = time.Since(start)
			if store, _ := metrics.Load(); store != nil {
				_ = store.Append(record)
			}
			if err != nil {
				return nil, fmt.Errorf("preCheck: %w", err)
			}
			return nil, errors.New("preCheck: server is not pterovpn")
		}
	}
	onUDP := func(port uint16) {
		if udpCh != nil {
			select {
			case udpCh <- port:
			default:
			}
		}
	}
	opts := runOpts{
		serverIP:     sip,
		token:        cfg.Token,
		tunName:      "ptera0",
		tunCIDR:      "10.13.37.2/24",
		mtu:          1420,
		routeCIDRs:   routeCIDRs,
		excludeCIDRs: excludeCIDRs,
		protection:   prot,
		proxy:        settings.Mode == "proxy",
		onUDPSupport: onUDP,
		proxyListen:  settings.ProxyListen,
		systemProxy:  settings.SystemProxy,
	}

	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan struct{})
	errCh := make(chan error, 1)
	done := make(chan struct{})
	go func() {
		errCh <- runPlatform(ctx, addrs, opts, func() {
			close(ready)
		})
		close(done)
	}()

	select {
	case <-ready:
		record.HandshakeOK = true
		clientlog.OK("TUI: connected to %s", cfg.Server)
		stop := func() {
			cancel()
			<-done
			record.End = time.Now()
			record.Duration = time.Since(start)
			record.ErrorType = "graceful"
			record.DNSOKAfter = checkDNS()
			if store, loadErr := metrics.Load(); loadErr == nil {
				_ = store.Append(record)
			}
		}
		return stop, nil
	case err := <-errCh:
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, loadErr := metrics.Load(); loadErr == nil {
			_ = store.Append(record)
		}
		return nil, err
	}
}
