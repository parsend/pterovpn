package vpn

import (
	"context"
	"io"
	"net"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/probe"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
)

func runWatchdog(ctx context.Context, h *handler, opt Options) {
	interval := opt.WatchdogInterval
	if interval <= 0 || opt.OnWatchdogFail == nil {
		return
	}
	pingTO := opt.WatchdogServerPingTimeout
	if pingTO <= 0 {
		pingTO = 2 * time.Second
	}
	httpTO := opt.WatchdogHTTPTimeout
	if httpTO <= 0 {
		httpTO = 2 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if !watchdogOnce(h, pingTO, httpTO) {
				clientlog.Warn("vpn: watchdog: server TCP or 1.1.1.1:80 via tunnel failed")
				opt.OnWatchdogFail()
				return
			}
		}
	}
}

func watchdogOnce(h *handler, pingTO, httpTO time.Duration) bool {
	if len(h.opt.ServerAddrs) == 0 {
		return false
	}
	if _, err := probe.Ping(h.opt.ServerAddrs[0], pingTO); err != nil {
		clientlog.Warn("vpn: watchdog: server TCP: %v", err)
		return false
	}
	dst := net.IPv4(1, 1, 1, 1)
	c, _, err := tunnel.DialTunFlow(
		h.opt.ServerAddrs,
		dst,
		80,
		h.opt.Token,
		h.opt.Protection,
		h.opt.Transport,
		h.opt.QuicServer,
		h.opt.QuicServerName,
		h.opt.QuicSkipVerify,
		h.opt.QuicCertPinSHA256,
		h.opt.QuicTLSRoots,
		h.udpMux.SharedQUICConn(),
		h.opt.DualTransport,
	)
	if err != nil {
		clientlog.Warn("vpn: watchdog: tun path to 1.1.1.1:80: %v", err)
		return false
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(httpTO))
	if _, err := io.WriteString(c, "GET / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n"); err != nil {
		clientlog.Warn("vpn: watchdog: http write: %v", err)
		return false
	}
	buf := make([]byte, 128)
	n, err := c.Read(buf)
	if err != nil || n == 0 {
		clientlog.Warn("vpn: watchdog: http read: n=%d err=%v", n, err)
		return false
	}
	return true
}
