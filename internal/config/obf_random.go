package config

import (
	crand "crypto/rand"
	"strings"
)

func obfRandByte() byte {
	var b [1]byte
	_, _ = crand.Read(b[:])
	return b[0]
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func RandomizeObfuscation(in ProtectionOptions) ProtectionOptions {
	out := in
	out.ObfSeed = ""

	jc := 2 + int(obfRandByte()%11)
	out.JunkCount = clampInt(jc, 1, 12)

	jmin := 64 + int(obfRandByte()%32)*8
	out.JunkMin = clampInt(jmin, 64, 512)

	span := 256 + int(obfRandByte())*8
	jmax := out.JunkMin + span
	out.JunkMax = clampInt(jmax, out.JunkMin+64, 2048)

	out.PadS1 = int(obfRandByte() % 49)
	out.PadS2 = int(obfRandByte() % 49)
	out.PadS3 = int(obfRandByte() % 49)
	out.PadS4 = 16 + int(obfRandByte()%49)

	if obfRandByte()%2 == 0 {
		out.Obfuscation = ""
	} else {
		out.Obfuscation = "enhanced"
	}

	patterns := []string{"", "1,1,3", "1,2,2", "2,2,1", "3,1,1", "1,4"}
	out.MagicSplit = patterns[int(obfRandByte())%len(patterns)]

	if obfRandByte()%2 == 0 {
		out.JunkStyle = "tls"
	} else {
		out.JunkStyle = ""
	}
	if obfRandByte()%3 == 0 {
		out.FlushPolicy = "perChunk"
	} else {
		out.FlushPolicy = ""
	}
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
	if strings.TrimSpace(p.JunkStyle) == "random" {
		p.JunkStyle = ""
	}
	if strings.TrimSpace(p.FlushPolicy) == "once" {
		p.FlushPolicy = ""
	}
}
