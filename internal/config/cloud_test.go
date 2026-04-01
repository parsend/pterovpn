package config

import (
	"testing"
)

func TestParseCloudLineParts(t *testing.T) {
	for _, tc := range []struct {
		line, conn, tun6, quicOpt, tr string
	}{
		{"1.2.3.4:1:t", "1.2.3.4:1:t", "", "", ""},
		{"1.2.3.4:1:t fd00::2/64", "1.2.3.4:1:t", "fd00::2/64", "", ""},
		{"h:26771:k quic=5.6.7.8:4433", "h:26771:k", "", "5.6.7.8:4433", ""},
		{"h:26771:k transport=tcp", "h:26771:k", "", "", "tcp"},
		{"h:26771:k transport=auto quic=9.9.9.9:1", "h:26771:k", "", "9.9.9.9:1", ""},
	} {
		cn, tun, q, tr := ParseCloudLineParts(tc.line)
		if cn != tc.conn || tun != tc.tun6 || q != tc.quicOpt || tr != tc.tr {
			t.Errorf("ParseCloudLineParts(%q)\n got conn=%q tun=%q quic=%q tr=%q\nwant conn=%q tun=%q quic=%q tr=%q",
				tc.line, cn, tun, q, tr, tc.conn, tc.tun6, tc.quicOpt, tc.tr)
		}
	}
}

func TestApplyCloudConnectDefaultsTLSAndTun6(t *testing.T) {
	f := false
	cfg := Config{
		Server:         "5.42.123.155:26771",
		Token:          "k",
		QuicServer:     "5.42.123.155:26771",
		QuicSkipVerify: &f,
	}
	ApplyCloudConnectDefaults(&cfg, "quic/tcp", true)
	if cfg.QuicSkipVerify != nil {
		t.Error("want nil QuicSkipVerify after defaults without pin")
	}
	if !cfg.QuicSkipVerifyEffective() {
		t.Error("want lax TLS")
	}
	if cfg.TunCIDR6 != DefaultCloudTunCIDR6 {
		t.Errorf("tun6 %q", cfg.TunCIDR6)
	}
}

func TestApplyCloudConnectDefaultsTCPOnlyProbe(t *testing.T) {
	cfg := Config{
		Server:     "1.1.1.1:1",
		Token:      "t",
		QuicServer: "1.1.1.1:1",
	}
	ApplyCloudConnectDefaults(&cfg, "tcp only", false)
	if cfg.Transport != "tcp" || cfg.QuicServer != "" {
		t.Fatalf("got %+v", cfg)
	}
}

func TestApplyCloudConnectDefaultsForcedTCPKeepsTun6Auto(t *testing.T) {
	cfg := Config{Server: "x:1", Token: "t", Transport: "tcp", QuicServer: "x:1"}
	ApplyCloudConnectDefaults(&cfg, "quic/tcp", true)
	if cfg.QuicServer != "" {
		t.Error("quic off when transport=tcp in profile")
	}
	if cfg.TunCIDR6 != DefaultCloudTunCIDR6 {
		t.Errorf("tun6 %q", cfg.TunCIDR6)
	}
}
