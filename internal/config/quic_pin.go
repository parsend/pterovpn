package config

import (
	"encoding/hex"
	"strings"

	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

func (c Config) QuicTLSVerifyStrict() bool {
	return c.QuicSkipVerify != nil && !*c.QuicSkipVerify
}

func EffectiveQuicCertPin(c Config, caps *protocol.ServerHelloCaps) string {
	manual := strings.TrimSpace(strings.ReplaceAll(c.QuicCertPinSHA256, ":", ""))
	if manual != "" {
		return c.QuicCertPinSHA256
	}
	if caps == nil || len(caps.QuicLeafPinSHA256) != 32 {
		return ""
	}
	if c.QuicTLSVerifyStrict() && strings.TrimSpace(c.QuicCaCert) != "" {
		return ""
	}
	return hex.EncodeToString(caps.QuicLeafPinSHA256)
}

func ApplyTcpOnlyIfServerHasNoQUIC(cfg *Config, caps *protocol.ServerHelloCaps) {
	if cfg == nil || caps == nil {
		return
	}
	if caps.TransportMask&protocol.TransportQUIC != 0 {
		return
	}
	cfg.Transport = "tcp"
	cfg.QuicServer = ""
}
