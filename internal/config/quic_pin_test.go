package config

import (
	"testing"

	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

func TestEffectiveQuicCertPinManualWins(t *testing.T) {
	pin := make([]byte, 32)
	for i := range pin {
		pin[i] = 7
	}
	caps := &protocol.ServerHelloCaps{QuicLeafPinSHA256: pin}
	c := Config{QuicCertPinSHA256: "abc"}
	if got := EffectiveQuicCertPin(c, caps); got != "abc" {
		t.Fatalf("want manual, got %q", got)
	}
}

func TestEffectiveQuicCertPinFromCaps(t *testing.T) {
	pin := make([]byte, 32)
	for i := range pin {
		pin[i] = byte(i)
	}
	caps := &protocol.ServerHelloCaps{QuicLeafPinSHA256: pin}
	c := Config{}
	got := EffectiveQuicCertPin(c, caps)
	if len(got) != 64 {
		t.Fatalf("want hex len 64, got %d", len(got))
	}
}

func TestEffectiveQuicCertPinSkippedWithStrictCA(t *testing.T) {
	pin := make([]byte, 32)
	caps := &protocol.ServerHelloCaps{QuicLeafPinSHA256: pin}
	f := false
	c := Config{QuicSkipVerify: &f, QuicCaCert: "ca.pem"}
	if got := EffectiveQuicCertPin(c, caps); got != "" {
		t.Fatalf("want empty when strict+CA, got %q", got)
	}
}
