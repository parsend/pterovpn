package config

import (
	"bytes"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var cloudConfigURLs = []string{
	"https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/mew/cloud-config.txt",
	"https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/main/cloud-config.txt",
	"https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/master/cloud-config.txt",
}
const cloudConfigFile = "cloud-config.txt"

const CloudDefaultQUICPort = "4433"

func QuicServerHostPortForCloudTCP(server string) string {
	server = strings.TrimSpace(server)
	h, _, err := net.SplitHostPort(server)
	if err != nil {
		if server == "" {
			return ""
		}
		return net.JoinHostPort(server, CloudDefaultQUICPort)
	}
	return net.JoinHostPort(h, CloudDefaultQUICPort)
}

func cloudQuicNeedsDefaultPort(tcpServer, quicServer string) bool {
	tcp := strings.TrimSpace(tcpServer)
	qs := strings.TrimSpace(quicServer)
	if tcp == "" {
		return false
	}
	if qs == "" {
		return true
	}
	return qs == tcp
}

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
	var lastErr error
	for _, u := range cloudConfigURLs {
		raw, err := fetchCloudRawFrom(u)
		if err == nil {
			return raw, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		return "", fmt.Errorf("fetch: no cloud sources configured")
	}
	return "", lastErr
}

func fetchCloudRawFrom(url string) (string, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(url)
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

func ParseCloudLineParts(line string) (conn, tunCIDR6, quicServer, transport string) {
	line = strings.TrimSpace(line)
	parts := strings.Fields(line)
	if len(parts) < 1 {
		return "", "", "", ""
	}
	conn = parts[0]
	for _, p := range parts[1:] {
		raw := strings.TrimSpace(p)
		low := strings.ToLower(raw)
		if strings.HasPrefix(low, "quic=") {
			if i := strings.IndexByte(raw, '='); i >= 0 && i < len(raw)-1 {
				quicServer = strings.TrimSpace(raw[i+1:])
			}
			continue
		}
		if strings.HasPrefix(low, "transport=") {
			if i := strings.IndexByte(raw, '='); i >= 0 && i < len(raw)-1 {
				v := strings.ToLower(strings.TrimSpace(raw[i+1:]))
				switch v {
				case "tcp", "quic":
					transport = v
				case "auto", "":
					transport = ""
				}
			}
			continue
		}
		if strings.Contains(raw, "/") {
			if _, _, err := net.ParseCIDR(raw); err == nil {
				if tunCIDR6 == "" {
					tunCIDR6 = raw
				}
				continue
			}
		}
		if _, _, err := net.SplitHostPort(raw); err == nil {
			if quicServer == "" {
				quicServer = raw
			}
		}
	}
	return conn, tunCIDR6, quicServer, transport
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
		conn, tun6, quicOpt, tr := ParseCloudLineParts(line)
		server, token, ok := ParseConnection(conn)
		if !ok {
			continue
		}
		c := Config{
			Server:     server,
			Token:      token,
			TunCIDR6:   tun6,
			Transport:  tr,
			QuicServer: QuicServerHostPortForCloudTCP(server),
		}
		if q := strings.TrimSpace(quicOpt); q != "" {
			c.QuicServer = q
		}
		if strings.EqualFold(strings.TrimSpace(tr), "tcp") {
			c.QuicServer = ""
		}
		cfgs = append(cfgs, c)
		names = append(names, fmt.Sprintf("cloud-%d", i+1))
	}
	return cfgs, names, nil
}
