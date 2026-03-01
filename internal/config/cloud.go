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

const cloudConfigURL = "https://github.com/parsend/pterovpn/blob/mew/cloud-config.txt"
const cloudConfigFile = "cloud-config.txt"

func cloudConfigPath() (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, cloudConfigFile), nil
}


func FetchCloud() ([]string, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(cloudConfigURL)
	if err != nil {
		return nil, fmt.Errorf("fetch: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch: %s", resp.Status)
	}
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		return nil, fmt.Errorf("read: %w", err)
	}
	path, err := cloudConfigPath()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, buf.Bytes(), 0644); err != nil {
		return nil, err
	}
	return parseCloudLines(buf.String()), nil
}


func LoadCloud() ([]string, error) {
	path, err := cloudConfigPath()
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	return parseCloudLines(string(b)), nil
}

func parseCloudLines(s string) []string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		server, token, ok := ParseConnection(line)
		if !ok || server == "" || token == "" {
			continue
		}
		out = append(out, line)
	}
	return out
}


func CloudList(fetch bool) ([]Config, []string, error) {
	var lines []string
	var err error
	if fetch {
		lines, err = FetchCloud()
	} else {
		lines, err = LoadCloud()
	}
	if err != nil {
		return nil, nil, err
	}
	if len(lines) == 0 {
		return nil, nil, nil
	}
	var cfgs []Config
	var names []string
	for i, line := range lines {
		server, token, ok := ParseConnection(line)
		if !ok {
			continue
		}
		cfgs = append(cfgs, Config{Server: server, Token: token})
		names = append(names, fmt.Sprintf("cloud-%d", i+1))
	}
	return cfgs, names, nil
}
