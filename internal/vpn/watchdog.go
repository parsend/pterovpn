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

func runWatchdog(ctx context.Context, stopRun context.CancelFunc, h *handler, opt Options) {
	interval := opt.WatchdogInterval
	if interval <= 0 {
		interval = time.Minute
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if !watchdogOnce(h) {
				clientlog.Warn("vpn: watchdog: server unreachable or no HTTP via tunnel (1.1.1.1:80)")
				if opt.OnWatchdogFail != nil {
					opt.OnWatchdogFail()
				}
				h.watchdogTriggered = true
				stopRun()
				return
			}
		}
	}
}

func watchdogOnce(h *handler) bool {
	if len(h.opt.ServerAddrs) == 0 {
		return false
	}
	if _, err := probe.Ping(h.opt.ServerAddrs[0], 8*time.Second); err != nil {
		clientlog.Warn("vpn: watchdog: server TCP ping: %v", err)
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
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
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
