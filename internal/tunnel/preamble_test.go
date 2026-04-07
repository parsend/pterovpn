package tunnel

import (
	"testing"

	"github.com/unitdevgcc/pterovpn/internal/config"
)

func TestStreamObfSeedChangesJunkWindow(t *testing.T) {
	prot := &config.ProtectionOptions{JunkCount: 4, JunkMin: 100, JunkMax: 400}
	slot := int64(42)
	_, _, c0, min0, max0, _, _ := streamObf(prot, slot, false, nil)
	seed := make([]byte, 16)
	for i := range seed {
		seed[i] = byte(i + 1)
	}
	_, _, c1, min1, max1, _, _ := streamObf(prot, slot, false, seed)
	if c0 == c1 && min0 == min1 && max0 == max1 {
		t.Fatalf("seed should perturb junk params, got identical count=%d min=%d max=%d", c1, min1, max1)
	}
}

func TestPickMagicSplitRespectsConfigured(t *testing.T) {
	seed := make([]byte, 16)
	if pickMagicSplit(seed, 0, "2,3") != "2,3" {
		t.Fatal("configured split must win")
	}
}
