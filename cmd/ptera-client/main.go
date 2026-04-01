package main

import (
	"context"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/netcfg"
	"github.com/unitdevgcc/pterovpn/internal/probe"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
)

func quicDualFromCaps(caps *protocol.ServerHelloCaps, transport, quicServer string) bool {
	if caps == nil || !tunnel.UsesQUICTransport(transport, quicServer) {
		return false
	}
	if strings.EqualFold(strings.TrimSpace(transport), "tcp") {
		return false
	}
	return probe.RecommendDualTunTransport(caps, true)
}

var version = "dev"

type runOpts struct {
	serverIP          net.IP
	token             string
	transport         string
	quicServer        string
	quicServerName    string
	quicSkipVerify    bool
	quicCertPinSHA256 string
	quicTLSRoots      *x509.CertPool
	quicTraceLog      bool
	tunName           string
	tunCIDR           string
	tunCIDR6          string
	mtu               int
	routeCIDRs        []*net.IPNet
	excludeCIDRs      []*net.IPNet
	protection        *config.ProtectionOptions
	proxy             bool
	proxyListen       string
	systemProxy       bool
	dualTransport     bool
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}

func run() error {
	var (
		tui            = flag.Bool("tui", false, "run TUI")
		server         = flag.String("server", "", "host:port or host")
		ports          = flag.String("ports", "", "csv ports for multiport")
		token          = flag.String("token", "", "token")
		transport      = flag.String("transport", "auto", "transport: auto|tcp|quic")
		quicServer     = flag.String("quic-server", "", "QUIC server host:port")
		quicServerName = flag.String("quic-server-name", "", "SNI/server name for QUIC TLS")
		quicSkipVerify = flag.Bool("quic-skip-verify", true, "QUIC TLS, default accept self-signed; false = system CA only")
		quicCertPin    = flag.String("quic-cert-pin", "", "SHA256 pin of QUIC leaf cert")
		quicCaCertFile = flag.String("quic-ca-cert", "", "PEM file, CA or server leaf, for verify when quic-skip-verify false")
		quicTrace      = flag.Bool("quic-trace", false, "verbose QUIC dial logging (or env PTERA_QUIC_TRACE=1)")
		tunName        = flag.String("tun", "ptera0", "tun name")
		tunCIDR        = flag.String("tun-cidr", "10.13.37.2/24", "tun cidr")
		tunCIDR6       = flag.String("tun-cidr6", "", "ipv6 tun cidr (e.g. fd00:13:37::2/64)")
		mtu            = flag.Int("mtu", 1420, "mtu")
		routes         = flag.String("routes", "", "cidrs to route via tunnel (default=all)")
		exclude        = flag.String("exclude", "", "cidrs to exclude from tunnel")
		proxy          = flag.Bool("proxy", false, "run SOCKS5 proxy instead of TUN (Windows fallback)")
		proxyListen    = flag.String("proxy-listen", "127.0.0.1:1080", "proxy listen addr")
		systemProxy    = flag.Bool("system-proxy", false, "set Windows system proxy (Windows only)")
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

	prot, _ := config.LoadProtection()
	var quicRoots *x509.CertPool
	if p := strings.TrimSpace(*quicCaCertFile); p != "" {
		pool, err := config.LoadQUICCAPool(p)
		if err != nil {
			return err
		}
		quicRoots = pool
	}
	var probeCaps *protocol.ServerHelloCaps
	if len(addrs) > 0 {
		_, _, probeCaps, _ = probe.ProbePterovpnWithCaps(addrs[0], *token, 5*time.Second)
	}
	skip := *quicSkipVerify
	effPin := config.EffectiveQuicCertPin(config.Config{
		QuicSkipVerify:    &skip,
		QuicCertPinSHA256: *quicCertPin,
		QuicCaCert:        strings.TrimSpace(*quicCaCertFile),
	}, probeCaps)
	opts := runOpts{
		serverIP:          sip,
		token:             *token,
		transport:         *transport,
		quicServer:        *quicServer,
		quicServerName:    *quicServerName,
		quicSkipVerify:    *quicSkipVerify,
		quicCertPinSHA256: effPin,
		quicTLSRoots:      quicRoots,
		quicTraceLog:      *quicTrace,
		tunName:           *tunName,
		tunCIDR:           *tunCIDR,
		tunCIDR6:          *tunCIDR6,
		mtu:               *mtu,
		routeCIDRs:        routeCIDRs,
		excludeCIDRs:      excludeCIDRs,
		protection:        &prot,
		proxy:             *proxy,
		proxyListen:       *proxyListen,
		systemProxy:       *systemProxy,
		dualTransport:     quicDualFromCaps(probeCaps, *transport, *quicServer),
	}
	return runPlatform(context.Background(), addrs, opts, nil)
}
