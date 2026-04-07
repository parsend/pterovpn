package config

import (
	"sync/atomic"
)

var liveProt atomic.Pointer[ProtectionOptions]

func SetLiveProtection(p *ProtectionOptions) {
	if p == nil {
		return
	}
	c := *p
	liveProt.Store(&c)
}

func EffectiveProtection(base *ProtectionOptions) *ProtectionOptions {
	if p := liveProt.Load(); p != nil {
		return p
	}
	return base
}

func ClearLiveProtection() {
	liveProt.Store(nil)
}

func InitLiveProtectionFrom(base *ProtectionOptions) {
	ClearLiveProtection()
	if base != nil {
		c := *base
		SetLiveProtection(&c)
		return
	}
	p, err := LoadProtection()
	if err != nil || isEmptyProtection(p) {
		return
	}
	SetLiveProtection(&p)
}

func isEmptyProtection(p ProtectionOptions) bool {
	if p.ObfAutoRotate {
		return false
	}
	return p.JunkCount == 0 && p.JunkMin == 0 && p.JunkMax == 0 &&
		p.PadS1 == 0 && p.PadS2 == 0 && p.PadS3 == 0 && p.PadS4 == 0 &&
		p.Obfuscation == "" && !p.PreCheck && p.MagicSplit == "" &&
		p.JunkStyle == "" && p.FlushPolicy == ""
}
