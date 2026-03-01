package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	Server  string `json:"server"`
	Token   string `json:"token"`
	Routes  string `json:"routes,omitempty"`
	Exclude string `json:"exclude,omitempty"`
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
