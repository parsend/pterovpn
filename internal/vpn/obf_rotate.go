package vpn

import (
	"context"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/tunnel"
)

var remuxReq = make(chan struct{}, 1)

func RequestRemux() {
	select {
	case remuxReq <- struct{}{}:
	default:
	}
}

func (h *handler) obfRotateLoop(ctx context.Context) {
	base := h.opt.Protection
	interval := rotateInterval(base)
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-remuxReq:
			h.remuxUDPIfTCP()
		case <-t.C:
			cur := config.EffectiveProtection(base)
			if cur == nil || !cur.ObfAutoRotate {
				continue
			}
			newP := config.RandomizeObfuscation(*cur)
			if err := config.SaveProtection(newP); err != nil {
				clientlog.Drop("vpn: obf rotate save: %v", err)
				continue
			}
			config.SetLiveProtection(&newP)
			clientlog.Info("vpn: obfuscation profile rotated")
			h.remuxUDPIfTCP()
		}
	}
}

func rotateInterval(base *config.ProtectionOptions) time.Duration {
	p := config.EffectiveProtection(base)
	if p != nil && p.ObfRotateEveryM > 0 {
		return time.Duration(p.ObfRotateEveryM) * time.Minute
	}
	return 5 * time.Minute
}

func (h *handler) remuxUDPIfTCP() {
	if h.udpMux == nil {
		return
	}
	if tunnel.UsesQUICTransport(h.opt.Transport, h.opt.QuicServer) {
		return
	}
	if err := h.udpMux.remuxTCPChannels(h.opt.ServerAddrs, h.opt.Token, h.opt.Transport, h.opt.QuicServer, h.opt.Protection); err != nil {
		clientlog.Drop("vpn: udp mux remux: %v", err)
	}
}

func (m *udpMux) remuxTCPChannels(addrs []string, token, transport, quicServer string, base *config.ProtectionOptions) error {
	if m == nil || m.quicConn != nil {
		return nil
	}
	m.chansMu.Lock()
	defer m.chansMu.Unlock()
	for i := range m.chans {
		old := m.chans[i]
		if old != nil {
			_ = old.Close()
		}
		uc, err := newUDPChan(byte(i), addrs, token, m.dispatch, base, transport, quicServer)
		if err != nil {
			return err
		}
		m.chans[i] = uc
	}
	return nil
}
