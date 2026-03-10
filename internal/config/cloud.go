package config

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const cloudConfigURL = "https://raw.githubusercontent.com/parsend/pterovpn/refs/heads/mew/cloud-config.txt"
const cloudConfigFile = "cloud-config.txt"

func cloudConfigPath() (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, cloudConfigFile), nil
}


func FetchCloud() ([]string, error) {
	raw, err := fetchCloudRaw()
	if err != nil {
		return nil, err
	}
	return parseCloudLines(raw), nil
}

func fetchCloudRaw() (string, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(cloudConfigURL)
	if err != nil {
		return "", fmt.Errorf("fetch: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("fetch: %s", resp.Status)
	}
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		return "", fmt.Errorf("read: %w", err)
	}
	path, err := cloudConfigPath()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, buf.Bytes(), 0644); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func LoadCloud() ([]string, error) {
	raw, err := loadCloudRaw()
	if err != nil {
		return nil, err
	}
	if raw == "" {
		return nil, nil
	}
	return parseCloudLines(raw), nil
}

func loadCloudRaw() (string, error) {
	path, err := cloudConfigPath()
	if err != nil {
		return "", err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", err
	}
	return string(b), nil
}

func parseCloudLines(s string) []string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) < 1 {
			continue
		}
		server, token, ok := ParseConnection(parts[0])
		if !ok || server == "" || token == "" {
			continue
		}
		out = append(out, line)
	}
	return out
}

func parseCloudLine(line string) (conn string, tunCIDR6 string) {
	line = strings.TrimSpace(line)
	parts := strings.Fields(line)
	if len(parts) < 1 {
		return "", ""
	}
	conn = parts[0]
	if len(parts) >= 2 {
		tunCIDR6 = strings.TrimSpace(parts[1])
	}
	return conn, tunCIDR6
}

func CloudList(fetch bool) ([]Config, []string, error) {
	var raw string
	var err error
	if fetch {
		raw, err = fetchCloudRaw()
	} else {
		raw, err = loadCloudRaw()
	}
	if err != nil {
		return nil, nil, err
	}
	if raw == "" {
		return nil, nil, nil
	}
	lines := parseCloudLines(raw)
	if len(lines) == 0 {
		return nil, nil, nil
	}
	var cfgs []Config
	var names []string
	for i, line := range lines {
		conn, tunCIDR6 := parseCloudLine(line)
		server, token, ok := ParseConnection(conn)
		if !ok {
			continue
		}
		cfgs = append(cfgs, Config{Server: server, Token: token, TunCIDR6: tunCIDR6})
		names = append(names, fmt.Sprintf("cloud-%d", i+1))
	}
	return cfgs, names, nil
}
