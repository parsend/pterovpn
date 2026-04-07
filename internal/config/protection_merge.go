package config

import (
	"strings"

	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

var magicSplitPatterns = []string{"1,1,3", "1,2,2", "1,3,1", "2,1,2", "2,2,1", "3,1,1"}

func MergeProtectionWithCaps(base ProtectionOptions, caps *protocol.ServerHelloCaps) ProtectionOptions {
	out := base
	if caps == nil || caps.ObfsProfileID < 1 {
		return out
	}
	if out.JunkCount == 0 {
		out.JunkCount = 4
	}
	if out.JunkMin == 0 {
		out.JunkMin = 96
	}
	if out.JunkMax == 0 {
		out.JunkMax = 960
	}
	if strings.TrimSpace(out.JunkStyle) == "" {
		out.JunkStyle = "tls"
	}
	if strings.TrimSpace(out.FlushPolicy) == "" {
		out.FlushPolicy = "perChunk"
	}
	if out.PadS1+out.PadS2+out.PadS3 == 0 {
		out.PadS1, out.PadS2, out.PadS3 = 3, 3, 3
	}
	if out.JunkSplitMax == 0 {
		out.JunkSplitMax = 320
	}
	if strings.TrimSpace(out.MagicSplit) == "" && len(caps.Nonce) > 0 {
		out.MagicSplit = magicSplitPatterns[int(caps.Nonce[0])%len(magicSplitPatterns)]
	}
	return out
}

func EffectiveQuicAlpn(c Config, caps *protocol.ServerHelloCaps) string {
	if s := strings.TrimSpace(c.QuicAlpn); s != "" {
		return s
	}
	if caps != nil && strings.TrimSpace(caps.QuicAlpn) != "" {
		return strings.TrimSpace(caps.QuicAlpn)
	}
	return "pteravpn"
}
