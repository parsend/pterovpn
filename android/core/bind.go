//go:build android

package core

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"crypto/x509"

	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/netcfg"
	"github.com/unitdevgcc/pterovpn/internal/probe"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
	"github.com/unitdevgcc/pterovpn/internal/proxy"
	"github.com/unitdevgcc/pterovpn/internal/sockprotect"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
	"github.com/unitdevgcc/pterovpn/internal/vpn"
)

type startResult struct {
	Handle uint64 `json:"handle"`
	Error  string `json:"error,omitempty"`
}

type stateResult struct {
	Ready    bool   `json:"ready"`
	Running  bool   `json:"running"`
	Error    string `json:"error,omitempty"`
	Watchdog bool   `json:"watchdog,omitempty"`
}

type probeResult struct {
	OK         bool   `json:"ok"`
	IPv6       bool   `json:"ipv6"`
	Mode       string `json:"mode"`
	LeafPin    string `json:"leafPin"`
	Error      string `json:"error,omitempty"`
	CapsNoQuic *bool  `json:"capsNoQuic,omitempty"`
}

type pingResult struct {
	RTTMs int64  `json:"rttMs"`
	Error string `json:"error,omitempty"`
}

type Protector interface {
	Protect(fd int64) bool
}

var socketProtector Protector

func SetSocketProtector(p Protector) {
	socketProtector = p
}

func installSocketProtect() func() {
	sockprotect.Protect = func(fd uintptr) error {
		if socketProtector == nil {
			return nil
		}
		if !socketProtector.Protect(int64(fd)) {
			return errors.New("socket protect failed")
		}
		return nil
	}
	return func() { sockprotect.Protect = nil }
}

type quicIPsResult struct {
	IPs   []string `json:"ips"`
	Error string   `json:"error,omitempty"`
}

type session struct {
	cancel context.CancelFunc

	done chan struct{}

	ready atomic.Bool

	watchdogTriggered atomic.Bool

	logsCh chan string

	readyOnLog []string

	stateMu sync.Mutex
	err     string
}

var (
	sessions sync.Map
	nextID   atomic.Uint64

	globalMu sync.Mutex
)

type sessionLogWriter struct {
	s *session

	mu  sync.Mutex
	buf []byte
}

func (w *sessionLogWriter) Write(p []byte) (n int, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	w.buf = append(w.buf, p...)
	for {
		idx := -1
		for i := 0; i < len(w.buf); i++ {
			if w.buf[i] == '\n' {
				idx = i
				break
			}
		}
		if idx < 0 {
			break
		}
		line := strings.TrimRight(string(w.buf[:idx]), "\r\n")
		w.buf = w.buf[idx+1:]

		select {
		case w.s.logsCh <- line:
		default:
		}

		if !w.s.ready.Load() && len(w.s.readyOnLog) > 0 {
			for _, s := range w.s.readyOnLog {
				if strings.Contains(line, s) {
					w.s.ready.Store(true)
					break
				}
			}
		}
	}

	return len(p), nil
}

func jsonString(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func setErr(s *session, errStr string) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	s.err = errStr
	fmt.Fprintf(os.Stderr, "ptera-core: %s\n", errStr)
}

func getSession(handle uint64) (*session, bool) {
	v, ok := sessions.Load(handle)
	if !ok {
		return nil, false
	}
	s, ok := v.(*session)
	return s, ok
}

func logSetup(s *session) (restore func()) {
	oldFlags := log.Flags()
	oldWriter := log.Writer()

	log.SetFlags(0)
	log.SetOutput(&sessionLogWriter{s: s})

	return func() {
		log.SetOutput(oldWriter)
		log.SetFlags(oldFlags)
	}
}

func resolveServerAddrs(server string) ([]string, error) {
	addrs, err := netcfg.SplitHostPorts(server, "")
	if err != nil {
		return nil, err
	}
	host, _, err := net.SplitHostPort(server)
	if err != nil {
		return nil, err
	}
	sip, err := netcfg.ResolveHost(host)
	if err != nil {
		return nil, err
	}
	return netcfg.ResolveAddrs(addrs, sip), nil
}

func loadQuicRoots(configDir string, caCert string) (*x509.CertPool, error) {
	p := strings.TrimSpace(caCert)
	if p == "" {
		return nil, nil
	}
	caPath := config.ResolveQUICCAPath(configDir, p)
	return config.LoadQUICCAPool(caPath)
}

func dualTunEnabled(probeCaps *protocol.ServerHelloCaps, transport, quicServer string) bool {
	if probeCaps == nil {
		return false
	}
	if tunnel.UsesQUICTransport(transport, quicServer) && !strings.EqualFold(strings.TrimSpace(transport), "tcp") {
		return probe.RecommendDualTunTransport(probeCaps, true)
	}
	return false
}

func StartTun(tunFd int, mtu int, cfgJSON string, configDir string) string {
	globalMu.Lock()
	defer globalMu.Unlock()

	if tunFd <= 0 || mtu < 576 {
		return jsonString(startResult{Handle: 0, Error: "bad tunFd/mtu"})
	}
	if strings.TrimSpace(cfgJSON) == "" {
		return jsonString(startResult{Handle: 0, Error: "empty cfgJSON"})
	}

	var cfg config.Config
	if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
		return jsonString(startResult{Handle: 0, Error: err.Error()})
	}

	addrs, err := resolveServerAddrs(cfg.Server)
	if err != nil {
		return jsonString(startResult{Handle: 0, Error: err.Error()})
	}
	if len(addrs) == 0 {
		return jsonString(startResult{Handle: 0, Error: "server addrs empty"})
	}

	probeOK, _, probeCaps, _ := probe.ProbePterovpnWithCaps(addrs[0], cfg.Token, 10*time.Second)
	if cfg.Protection != nil && cfg.Protection.PreCheck && !probeOK {
		return jsonString(startResult{Handle: 0, Error: "preCheck: server is not pterovpn"})
	}

	config.ApplyTcpOnlyIfServerHasNoQUIC(&cfg, probeCaps)

	quicRoots, err := loadQuicRoots(configDir, cfg.QuicCaCert)
	if err != nil {
		return jsonString(startResult{Handle: 0, Error: "quicCaCert: " + err.Error()})
	}

	effPin := config.EffectiveQuicCertPin(cfg, probeCaps)
	dual := dualTunEnabled(probeCaps, cfg.Transport, cfg.QuicServer)
	if cfg.DualTransport != nil && !*cfg.DualTransport {
		dual = false
	}

	s := &session{
		done:   make(chan struct{}),
		logsCh: make(chan string, 500),
	}
	handle := nextID.Add(1)
	sessions.Store(handle, s)

	restoreLogs := logSetup(s)

	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel

	opt := vpn.Options{
		TunFD:             tunFd,
		MTU:               mtu,
		Token:             cfg.Token,
		ServerAddrs:       addrs,
		Protection:        cfg.Protection,
		Transport:         cfg.Transport,
		QuicServer:        cfg.QuicServer,
		QuicServerName:    cfg.QuicServerName,
		QuicSkipVerify:    cfg.QuicSkipVerifyEffective(),
		QuicCertPinSHA256: effPin,
		QuicTLSRoots:      quicRoots,
		QuicTraceLog:      cfg.QuicTraceLog,
		DualTransport:     dual,
		Ready: func() {
			s.ready.Store(true)
		},
		WatchdogInterval:          time.Minute,
		WatchdogServerPingTimeout: 2 * time.Second,
		OnWatchdogFail: func() {
			s.watchdogTriggered.Store(true)
			cancel()
		},
	}

	go func() {
		defer close(s.done)
		defer sessions.Delete(handle)
		defer restoreLogs()
		uninstallProtect := installSocketProtect()
		defer uninstallProtect()

		err := vpn.Run(ctx, opt)
		if err != nil {
			setErr(s, err.Error())
			return
		}
		if ctx.Err() != nil {
			setErr(s, ctx.Err().Error())
		}
	}()

	return jsonString(startResult{Handle: handle})
}

func StartProxy(listenAddr string, cfgJSON string, configDir string) string {
	globalMu.Lock()
	defer globalMu.Unlock()

	if strings.TrimSpace(listenAddr) == "" {
		return jsonString(startResult{Handle: 0, Error: "empty listenAddr"})
	}
	if strings.TrimSpace(cfgJSON) == "" {
		return jsonString(startResult{Handle: 0, Error: "empty cfgJSON"})
	}

	var cfg config.Config
	if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
		return jsonString(startResult{Handle: 0, Error: err.Error()})
	}

	addrs, err := resolveServerAddrs(cfg.Server)
	if err != nil {
		return jsonString(startResult{Handle: 0, Error: err.Error()})
	}
	if len(addrs) == 0 {
		return jsonString(startResult{Handle: 0, Error: "server addrs empty"})
	}

	probeOK, _, probeCaps, _ := probe.ProbePterovpnWithCaps(addrs[0], cfg.Token, 10*time.Second)
	if cfg.Protection != nil && cfg.Protection.PreCheck && !probeOK {
		return jsonString(startResult{Handle: 0, Error: "preCheck: server is not pterovpn"})
	}

	config.ApplyTcpOnlyIfServerHasNoQUIC(&cfg, probeCaps)

	quicRoots, err := loadQuicRoots(configDir, cfg.QuicCaCert)
	if err != nil {
		return jsonString(startResult{Handle: 0, Error: "quicCaCert: " + err.Error()})
	}

	effPin := config.EffectiveQuicCertPin(cfg, probeCaps)

	s := &session{
		done:       make(chan struct{}),
		logsCh:     make(chan string, 500),
		readyOnLog: []string{"proxy: listening"},
	}
	handle := nextID.Add(1)
	sessions.Store(handle, s)

	restoreLogs := logSetup(s)

	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel

	go func() {
		defer close(s.done)
		defer sessions.Delete(handle)
		defer restoreLogs()
		uninstallProtect := installSocketProtect()
		defer uninstallProtect()

		err := proxy.Run(ctx, listenAddr, addrs, cfg.Token, cfg.Protection, cfg.Transport, cfg.QuicServer, cfg.QuicServerName, cfg.QuicSkipVerifyEffective(), effPin, quicRoots)
		if err != nil {
			setErr(s, err.Error())
		}
	}()

	return jsonString(startResult{Handle: handle})
}

func Stop(handle int64) bool {
	if handle < 0 {
		return false
	}
	s, ok := getSession(uint64(handle))
	if !ok {
		return false
	}
	if s.cancel != nil {
		s.cancel()
		return true
	}
	return false
}

func PollLogs(handle int64, max int) string {
	if max <= 0 {
		max = 200
	}
	if handle < 0 {
		return jsonString([]string{})
	}
	s, ok := getSession(uint64(handle))
	if !ok {
		return jsonString([]string{})
	}

	out := make([]string, 0, max)
	for i := 0; i < max; i++ {
		select {
		case line := <-s.logsCh:
			out = append(out, line)
		default:
			return jsonString(out)
		}
	}
	return jsonString(out)
}

func PollState(handle int64) string {
	if handle < 0 {
		return jsonString(stateResult{Ready: false, Running: false, Error: "bad handle"})
	}
	s, ok := getSession(uint64(handle))
	if !ok {
		return jsonString(stateResult{Ready: false, Running: false, Error: "no session"})
	}

	running := true
	select {
	case <-s.done:
		running = false
	default:
	}

	s.stateMu.Lock()
	errStr := s.err
	s.stateMu.Unlock()

	return jsonString(stateResult{
		Ready:    s.ready.Load(),
		Running:  running,
		Error:    errStr,
		Watchdog: s.watchdogTriggered.Load(),
	})
}

func ProbePterovpn(server string, token string, timeoutMs int) string {
	server = strings.TrimSpace(server)
	token = strings.TrimSpace(token)
	if server == "" || token == "" {
		return jsonString(probeResult{OK: false, IPv6: false, Mode: "", LeafPin: "", Error: "server/token required"})
	}
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}

	ok, ipv6, caps, err := probe.ProbePterovpnWithCaps(server, token, time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return jsonString(probeResult{OK: ok, IPv6: ipv6, Mode: "", LeafPin: "", Error: err.Error()})
	}

	res := probeResult{
		OK:   ok,
		IPv6: ipv6,
		Mode: probe.ServerModeFromCaps(caps),
	}
	if caps != nil && len(caps.QuicLeafPinSHA256) == 32 {
		res.LeafPin = hex.EncodeToString(caps.QuicLeafPinSHA256)
	}
	if caps != nil {
		noQuic := (caps.TransportMask & protocol.TransportQUIC) == 0
		res.CapsNoQuic = &noQuic
	}
	return jsonString(res)
}

func Ping(server string, timeoutMs int) string {
	server = strings.TrimSpace(server)
	if server == "" {
		return jsonString(pingResult{RTTMs: 0, Error: "empty server"})
	}
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}

	d, err := probe.Ping(server, time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return jsonString(pingResult{RTTMs: 0, Error: err.Error()})
	}
	return jsonString(pingResult{RTTMs: d.Milliseconds()})
}

type protectionOut struct {
	OK         bool            `json:"ok"`
	Error      string          `json:"error,omitempty"`
	Protection json.RawMessage `json:"protection,omitempty"`
}

func RandomizeLiveFromProtectionJSON(jsonStr string) string {
	var p config.ProtectionOptions
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return jsonString(map[string]string{"error": err.Error()})
	}
	newP := config.RandomizeObfuscation(p)
	config.SetLiveProtection(&newP)
	vpn.RequestRemux()
	b, err := json.Marshal(newP)
	if err != nil {
		return jsonString(map[string]string{"error": err.Error()})
	}
	return jsonString(protectionOut{OK: true, Protection: b})
}

func ToggleObfAutoLiveFromProtectionJSON(jsonStr string) string {
	var p config.ProtectionOptions
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return jsonString(map[string]string{"error": err.Error()})
	}
	p.ObfAutoRotate = !p.ObfAutoRotate
	config.SetLiveProtection(&p)
	vpn.RequestRemux()
	b, err := json.Marshal(p)
	if err != nil {
		return jsonString(map[string]string{"error": err.Error()})
	}
	return jsonString(protectionOut{OK: true, Protection: b})
}

func ApplyLiveProtectionJSON(jsonStr string) string {
	var p config.ProtectionOptions
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return jsonString(map[string]string{"error": err.Error()})
	}
	config.SanitizeObfRotateFields(&p)
	config.SetLiveProtection(&p)
	vpn.RequestRemux()
	return jsonString(map[string]bool{"ok": true})
}

func QuicDialTargetIPs(server string, quicServer string) string {
	server = strings.TrimSpace(server)
	quicServer = strings.TrimSpace(quicServer)

	addrs, err := netcfg.SplitHostPorts(server, "")
	if err != nil {
		return jsonString(quicIPsResult{IPs: nil, Error: err.Error()})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	ips, err := tunnel.QUICDialTargetIPs(ctx, addrs, quicServer)
	if err != nil {
		return jsonString(quicIPsResult{IPs: nil, Error: err.Error()})
	}
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		if ip == nil {
			continue
		}
		out = append(out, ip.String())
	}
	return jsonString(quicIPsResult{IPs: out})
}
