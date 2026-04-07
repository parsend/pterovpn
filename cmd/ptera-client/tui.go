package main

import (
	"bytes"
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/metrics"
	"github.com/unitdevgcc/pterovpn/internal/netcfg"
	"github.com/unitdevgcc/pterovpn/internal/probe"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
	"github.com/unitdevgcc/pterovpn/internal/tui"
)

type logWriter struct {
	ch  chan<- string
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
	log.SetOutput(&logWriter{ch: logCh})
	defer func() {
		log.SetOutput(os.Stderr)
		close(logCh)
	}()

	watchdogCh := make(chan struct{}, 1)
	opts := tui.Opts{
		ConnectFn: func(cfg config.Config, configName string, reconnectCount int, settings config.ClientSettings) (stop func(), err error) {
			return connectVPN(cfg, configName, reconnectCount, settings, watchdogCh)
		},
		Version: version,
	}
	p := tea.NewProgram(tui.NewModel(opts), tea.WithAltScreen())
	go func() {
		for range watchdogCh {
			p.Send(tui.WatchdogReconnectMsg{})
		}
	}()
	go func() {
		for line := range logCh {
			p.Send(tui.LogMessage(line))
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

func connectVPN(cfg config.Config, configName string, reconnectCount int, settings config.ClientSettings, watchdogFail chan struct{}) (stop func(), err error) {
	if cfg.Server == "" || cfg.Token == "" {
		return nil, fmt.Errorf("server и token обязательны")
	}
	start := time.Now()
	dnsOK := checkDNS()
	rttBefore, _ := probe.Ping(cfg.Server, probeTimeout)

	record := metrics.SessionRecord{
		Start:          start,
		Server:         cfg.Server,
		ConfigName:     configName,
		DNSOKBefore:    dnsOK,
		RTTBefore:      rttBefore,
		ProbeOK:        false,
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
	probeOK, _, probeCaps, _ := probe.ProbePterovpnWithCaps(addrs[0], cfg.Token, probeTimeout)
	record.ProbeOK = probeOK

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
	mergedProt := config.MergeProtectionWithCaps(*prot, probeCaps)
	prot = &mergedProt
	if prot != nil && prot.PreCheck && len(addrs) > 0 {
		if !probeOK {
			record.ErrorType = "preCheck"
			record.End = time.Now()
			record.Duration = time.Since(start)
			if store, _ := metrics.Load(); store != nil {
				_ = store.Append(record)
			}
			return nil, errors.New("preCheck: server is not pterovpn")
		}
	}
	config.ApplyTcpOnlyIfServerHasNoQUIC(&cfg, probeCaps)
	tunCIDR6 := strings.TrimSpace(cfg.TunCIDR6)
	var quicRoots *x509.CertPool
	if ca := strings.TrimSpace(cfg.QuicCaCert); ca != "" {
		dir, derr := config.Dir()
		if derr != nil {
			return nil, fmt.Errorf("config dir: %w", derr)
		}
		caPath := config.ResolveQUICCAPath(dir, ca)
		pool, lerr := config.LoadQUICCAPool(caPath)
		if lerr != nil {
			return nil, fmt.Errorf("quicCaCert: %w", lerr)
		}
		quicRoots = pool
	}
	var watchdogMark atomic.Bool
	opts := runOpts{
		serverIP:          sip,
		token:             cfg.Token,
		transport:         cfg.Transport,
		quicServer:        cfg.QuicServer,
		quicServerName:    cfg.QuicServerName,
		quicSkipVerify:    cfg.QuicSkipVerifyEffective(),
		quicCertPinSHA256: config.EffectiveQuicCertPin(cfg, probeCaps),
		quicAlpn:          config.EffectiveQuicAlpn(cfg, probeCaps),
		quicTLSRoots:      quicRoots,
		quicTraceLog:      cfg.QuicTraceLog,
		tunName:           "ptera0",
		tunCIDR:           "10.13.37.2/24",
		tunCIDR6:          tunCIDR6,
		mtu:               1420,
		routeCIDRs:        routeCIDRs,
		excludeCIDRs:      excludeCIDRs,
		protection:        prot,
		proxy:             settings.Mode == "proxy",
		proxyListen:       settings.ProxyListen,
		systemProxy:       settings.SystemProxy,
		dualTransport: func() bool {
			if probeCaps == nil || !tunnel.UsesQUICTransport(cfg.Transport, cfg.QuicServer) {
				return false
			}
			if strings.EqualFold(strings.TrimSpace(cfg.Transport), "tcp") {
				return false
			}
			return probe.RecommendDualTunTransport(probeCaps, true)
		}(),
		watchdogFail: watchdogFail,
		watchdogMark: &watchdogMark,
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

	const connectTimeout = 90 * time.Second
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
			if watchdogMark.Swap(false) {
				record.ErrorType = "watchdog"
			}
			record.DNSOKAfter = checkDNS()
			if store, loadErr := metrics.Load(); loadErr == nil {
				_ = store.Append(record)
			}
		}
		return stop, nil
	case err := <-errCh:
		cancel()
		<-done
		record.ErrorType = classifyError(err)
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, loadErr := metrics.Load(); loadErr == nil {
			_ = store.Append(record)
		}
		return nil, err
	case <-time.After(connectTimeout):
		cancel()
		<-done
		record.ErrorType = "timeout"
		record.End = time.Now()
		record.Duration = time.Since(start)
		if store, loadErr := metrics.Load(); loadErr == nil {
			_ = store.Append(record)
		}
		return nil, fmt.Errorf("таймаут подключения %v (часто после повторного коннекта без корректного shutdown netstack)", connectTimeout)
	}
}
