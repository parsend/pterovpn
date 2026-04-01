package config

import (
	"crypto/x509"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func LoadQUICCAPool(pemPath string) (*x509.CertPool, error) {
	b, err := os.ReadFile(pemPath)
	if err != nil {
		return nil, fmt.Errorf("read quicCaCert: %w", err)
	}
	pool, serr := x509.SystemCertPool()
	if serr != nil {
		pool = x509.NewCertPool()
	}
	if !pool.AppendCertsFromPEM(b) {
		return nil, fmt.Errorf("quicCaCert: no certificates in %s", pemPath)
	}
	return pool, nil
}

func ResolveQUICCAPath(configDir, quicCaCert string) string {
	p := strings.TrimSpace(quicCaCert)
	if p == "" {
		return ""
	}
	if filepath.IsAbs(p) {
		return filepath.Clean(p)
	}
	if configDir == "" {
		return filepath.Clean(p)
	}
	return filepath.Join(configDir, p)
}
