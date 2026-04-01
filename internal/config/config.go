package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

const DefaultCloudTunCIDR6 = "fd00:13:37::2/64"

type Config struct {
	Server            string `json:"server"`
	QuicServer        string `json:"quicServer,omitempty"`
	Token             string `json:"token"`
	Transport         string `json:"transport,omitempty"`
	QuicServerName    string `json:"quicServerName,omitempty"`
	QuicSkipVerify    *bool  `json:"quicSkipVerify,omitempty"`
	QuicCertPinSHA256 string `json:"quicCertPinSHA256,omitempty"`
	QuicCaCert        string `json:"quicCaCert,omitempty"`

	QuicTraceLog bool               `json:"quicTraceLog,omitempty"`
	Routes       string             `json:"routes,omitempty"`
	Exclude      string             `json:"exclude,omitempty"`
	TunCIDR6     string             `json:"tunCIDR6,omitempty"`
	Protection   *ProtectionOptions `json:"protection,omitempty"`
}

type ProtectionOptions struct {
	Obfuscation string `json:"obfuscation,omitempty"`
	JunkCount   int    `json:"junkCount,omitempty"`
	JunkMin     int    `json:"junkMin,omitempty"`
	JunkMax     int    `json:"junkMax,omitempty"`
	PadS1       int    `json:"padS1,omitempty"`
	PadS2       int    `json:"padS2,omitempty"`
	PadS3       int    `json:"padS3,omitempty"`
	PadS4       int    `json:"padS4,omitempty"`
	PreCheck    bool   `json:"preCheck,omitempty"`
	MagicSplit  string `json:"magicSplit,omitempty"`
	JunkStyle   string `json:"junkStyle,omitempty"`
	FlushPolicy string `json:"flushPolicy,omitempty"`
}

func Dir() (string, error) {
	d, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(d, "pteravpn"), nil
}

func List() ([]Config, []string, error) {
	dir, err := Dir()
	if err != nil {
		return nil, nil, err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, nil, err
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, nil, err
	}
	var cfgs []Config
	var names []string
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		if e.Name() == "metrics.json" || e.Name() == "protection.json" || e.Name() == "settings.json" {
			continue
		}
		c, err := Load(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		cfgs = append(cfgs, c)
		names = append(names, strings.TrimSuffix(e.Name(), ".json"))
	}
	return cfgs, names, nil
}

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return Config{}, err
	}
	return c, nil
}

func (c Config) QuicSkipVerifyEffective() bool {
	if c.QuicSkipVerify == nil {
		return true
	}
	return *c.QuicSkipVerify
}

func (c Config) QuicSkipVerifyFormField() string {
	if c.QuicSkipVerify == nil {
		return ""
	}
	if *c.QuicSkipVerify {
		return "true"
	}
	return "false"
}

func ApplyCloudConnectDefaults(cfg *Config, serverMode string, probeIPv6 bool) {
	if strings.TrimSpace(cfg.QuicCertPinSHA256) == "" {
		cfg.QuicSkipVerify = nil
	}
	mode := strings.ToLower(strings.TrimSpace(serverMode))
	forcedTCP := strings.EqualFold(strings.TrimSpace(cfg.Transport), "tcp")
	if forcedTCP {
		cfg.QuicServer = ""
	} else {
		switch mode {
		case "tcp only":
			cfg.Transport = "tcp"
			cfg.QuicServer = ""
		case "quic only", "quic/tcp":
			if strings.TrimSpace(cfg.QuicServer) == "" {
				cfg.QuicServer = cfg.Server
			}
		default:
			if strings.TrimSpace(cfg.QuicServer) == "" {
				cfg.QuicServer = cfg.Server
			}
		}
	}
	if strings.TrimSpace(cfg.TunCIDR6) == "" && probeIPv6 {
		cfg.TunCIDR6 = DefaultCloudTunCIDR6
	}
}

func Save(name string, c Config) error {
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	path := filepath.Join(dir, name+".json")
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0600)
}

func pathFor(name string) (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, name+".json"), nil
}

func LoadByName(name string) (Config, error) {
	path, err := pathFor(name)
	if err != nil {
		return Config{}, err
	}
	return Load(path)
}

func SaveByName(name string, c Config) error {
	return Save(name, c)
}

func Delete(name string) error {
	path, err := pathFor(name)
	if err != nil {
		return err
	}
	return os.Remove(path)
}

const (
	protectionFileName = "protection.json"
	settingsFileName   = "settings.json"
)

type ClientSettings struct {
	Mode        string `json:"mode,omitempty"`
	SystemProxy bool   `json:"systemProxy,omitempty"`
	ProxyListen string `json:"proxyListen,omitempty"`
}

func LoadClientSettings() (ClientSettings, error) {
	dir, err := Dir()
	if err != nil {
		return ClientSettings{}, err
	}
	b, err := os.ReadFile(filepath.Join(dir, settingsFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return ClientSettings{Mode: "tun", ProxyListen: "127.0.0.1:1080"}, nil
		}
		return ClientSettings{}, err
	}
	var s ClientSettings
	if err := json.Unmarshal(b, &s); err != nil {
		return ClientSettings{}, err
	}
	if s.Mode != "proxy" {
		s.Mode = "tun"
	}
	if s.ProxyListen == "" {
		s.ProxyListen = "127.0.0.1:1080"
	}
	return s, nil
}

func SaveClientSettings(s ClientSettings) error {
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, settingsFileName), b, 0600)
}

func LoadProtection() (ProtectionOptions, error) {
	dir, err := Dir()
	if err != nil {
		return ProtectionOptions{}, err
	}
	b, err := os.ReadFile(filepath.Join(dir, protectionFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return ProtectionOptions{}, nil
		}
		return ProtectionOptions{}, err
	}
	var p ProtectionOptions
	if err := json.Unmarshal(b, &p); err != nil {
		return ProtectionOptions{}, err
	}
	return p, nil
}

func SaveProtection(p ProtectionOptions) error {
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, protectionFileName), b, 0600)
}

func ParseConnection(s string) (server, token string, ok bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", "", false
	}
	i := strings.LastIndex(s, ":")
	if i < 0 || i == len(s)-1 {
		return "", "", false
	}
	server = strings.TrimSpace(s[:i])
	token = strings.TrimSpace(s[i+1:])
	if server == "" || token == "" {
		return "", "", false
	}
	return server, token, true
}

func SanitizeName(s string) string {
	s = strings.TrimSpace(s)
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		}
	}
	out := b.String()
	if out == "" {
		out = "default"
	}
	return out
}
