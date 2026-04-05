package vpn

import (
	"context"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/probe"
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
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if !watchdogOnce(h, pingTO) {
				clientlog.Warn("vpn: watchdog: server TCP failed")
				opt.OnWatchdogFail()
				return
			}
		}
	}
}

func watchdogOnce(h *handler, pingTO time.Duration) bool {
	if len(h.opt.ServerAddrs) == 0 {
		return false
	}
	if _, err := probe.Ping(h.opt.ServerAddrs[0], pingTO); err != nil {
		clientlog.Warn("vpn: watchdog: server TCP: %v", err)
		return false
	}
	return true
}
