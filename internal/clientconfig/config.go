package clientconfig

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	Server        string
	Ports         string
	Servers       string // fallback: host1:port1,host2:port2
	Token         string
	Tun           string
	TunCIDR       string
	MTU           int
	KeepaliveSec  int
	Reconnect     bool
	IncludeRoutes string // csv CIDRs
	ExcludeRoutes string // csv CIDRs
	Quiet         bool
	Obfuscate     bool
	Compression   bool
}

func Load(configPath string) (Config, error) {
	var c Config
	if configPath != "" {
		err := loadFile(configPath, &c)
		if err != nil && !os.IsNotExist(err) {
			return c, err
		}
		return c, nil
	}
	for _, p := range defaultPaths() {
		if err := loadFile(p, &c); err == nil {
			return c, nil
		}
	}
	return c, nil
}

func defaultPaths() []string {
	dir, _ := os.UserConfigDir()
	return []string{
		"ptera-client.conf",
		filepath.Join(dir, "pteravpn", "client.conf"),
	}
}

func loadFile(path string, c *Config) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return parseProperties(f, c)
}

func parseProperties(f *os.File, c *Config) error {
	s := bufio.NewScanner(f)
	for s.Scan() {
		line := strings.TrimSpace(s.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		i := strings.Index(line, "=")
		if i < 0 {
			continue
		}
		k, v := strings.TrimSpace(line[:i]), strings.TrimSpace(line[i+1:])
		if k == "" {
			continue
		}
		set(c, k, v)
	}
	return s.Err()
}

func set(c *Config, k, v string) {
	switch k {
	case "server":
		c.Server = v
	case "ports":
		c.Ports = v
	case "servers":
		c.Servers = v
	case "token":
		c.Token = v
	case "tun":
		c.Tun = v
	case "tunCIDR":
		c.TunCIDR = v
	case "mtu":
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			c.MTU = n
		}
	case "keepaliveSec":
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			c.KeepaliveSec = n
		}
	case "reconnect":
		c.Reconnect = parseBool(v)
	case "includeRoutes":
		c.IncludeRoutes = v
	case "excludeRoutes":
		c.ExcludeRoutes = v
	case "quiet":
		c.Quiet = parseBool(v)
	case "obfuscate":
		c.Obfuscate = parseBool(v)
	case "compression":
		c.Compression = parseBool(v)
	}
}

func parseBool(v string) bool {
	v = strings.TrimSpace(strings.ToLower(v))
	return v == "1" || v == "true" || v == "yes"
}
