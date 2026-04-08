package config

import (
	crand "crypto/rand"
	"encoding/binary"
	"strings"
)

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

const (
	junkMinLo = 64
	junkMinHi = 1024
	junkMaxHi = 1024
	padMax    = 64
)

func RandomizeObfuscation(in ProtectionOptions) ProtectionOptions {
	out := in
	out.ObfSeed = ""

	var rb [32]byte
	_, _ = crand.Read(rb[:])
	u8 := func(i int) byte { return rb[i%32] }
	u16 := func(i int) uint16 {
		i = i % 31
		return binary.LittleEndian.Uint16(rb[i : i+2])
	}

	out.JunkCount = 1 + int(u8(0))%12

	out.JunkMin = junkMinLo + int(u16(1))%(junkMinHi-junkMinLo+1)
	span := junkMaxHi - out.JunkMin - 64 + 1
	if span < 1 {
		out.JunkMax = junkMaxHi
	} else {
		out.JunkMax = out.JunkMin + 64 + int(u16(3))%span
	}
	out.JunkMin = clampInt(out.JunkMin, junkMinLo, junkMinHi)
	out.JunkMax = clampInt(out.JunkMax, out.JunkMin+64, junkMaxHi)

	// 1..padMax — без нулей
	out.PadS1 = 1 + int(u8(5))%padMax
	out.PadS2 = 1 + int(u8(6))%padMax
	out.PadS3 = 1 + int(u8(7))%padMax
	out.PadS4 = 1 + int(u8(8))%padMax

	out.Obfuscation = "enhanced"
	out.JunkStyle = "random"
	out.FlushPolicy = "perChunk"

	patterns := []string{
		"1,1,3", "1,2,2", "2,1,2", "2,2,1", "3,1,1", "1,4", "4,1", "5",
	}
	out.MagicSplit = patterns[int(u8(10))%len(patterns)]
	return out
}

func loadProtectionScoped(configName string) (ProtectionOptions, error) {
	if strings.TrimSpace(configName) == "" {
		return LoadProtection()
	}
	cfg, err := LoadByName(configName)
	if err != nil {
		return ProtectionOptions{}, err
	}
	if cfg.Protection == nil {
		return ProtectionOptions{}, nil
	}
	return *cfg.Protection, nil
}

func saveProtectionScoped(configName string, p ProtectionOptions) error {
	if strings.TrimSpace(configName) == "" {
		return SaveProtection(p)
	}
	cfg, err := LoadByName(configName)
	if err != nil {
		return err
	}
	cfg.Protection = &p
	return Save(configName, cfg)
}

func ApplyObfuscationRotationNow() error {
	return ApplyObfuscationRotationNowScoped("")
}

func ApplyObfuscationRotationNowScoped(configName string) error {
	p, err := loadProtectionScoped(configName)
	if err != nil {
		return err
	}
	newP := RandomizeObfuscation(p)
	if err := saveProtectionScoped(configName, newP); err != nil {
		return err
	}
	SetLiveProtection(&newP)
	return nil
}

func ToggleObfAutoRotate() (bool, error) {
	return ToggleObfAutoRotateScoped("")
}

func ToggleObfAutoRotateScoped(configName string) (bool, error) {
	p, err := loadProtectionScoped(configName)
	if err != nil {
		return false, err
	}
	p.ObfAutoRotate = !p.ObfAutoRotate
	if err := saveProtectionScoped(configName, p); err != nil {
		return false, err
	}
	SetLiveProtection(&p)
	return p.ObfAutoRotate, nil
}

func SanitizeObfRotateFields(p *ProtectionOptions) {
	if p == nil {
		return
	}
	if p.ObfRotateEveryM < 0 {
		p.ObfRotateEveryM = 0
	}
	if p.ObfRotateEveryM > 24*60 {
		p.ObfRotateEveryM = 24 * 60
	}
	if strings.TrimSpace(p.FlushPolicy) == "once" {
		p.FlushPolicy = ""
	}
}
