package tunnel

import (
	"net"
	"testing"
)

func TestPickAddr(t *testing.T) {
	addrs := []string{"a:1", "b:2", "c:3"}
	for _, ip := range []string{"1.2.3.4", "::1"} {
		addr := pickAddr(addrs, net.ParseIP(ip), 443)
		if addr == "" {
			t.Errorf("pickAddr empty for %s", ip)
		}
		found := false
		for _, a := range addrs {
			if a == addr {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("pickAddr returned %s not in addrs", addr)
		}
	}
}

func TestPickAddrSingle(t *testing.T) {
	addrs := []string{"single:999"}
	addr := pickAddr(addrs, net.ParseIP("8.8.8.8"), 53)
	if addr != "single:999" {
		t.Errorf("single addr: got %s", addr)
	}
}

func TestResolveQUICDialAddr(t *testing.T) {
	a, d, err := ResolveQUICDialAddr([]string{"10.0.0.1:26771"}, "")
	if err != nil || !d || a != "10.0.0.1:"+DefaultQUICPort {
		t.Fatalf("derived: got %q %v %v", a, d, err)
	}
	a, d, err = ResolveQUICDialAddr([]string{"10.0.0.1:26771"}, "10.0.0.1:9443")
	if err != nil || d || a != "10.0.0.1:9443" {
		t.Fatalf("explicit: got %q %v %v", a, d, err)
	}
	a, d, err = ResolveQUICDialAddr([]string{"10.0.0.1:26771"}, "z.example")
	if err != nil || !d || a != "z.example:"+DefaultQUICPort {
		t.Fatalf("host only: got %q %v %v", a, d, err)
	}
}

func TestUsesQUICTransport(t *testing.T) {
	qs := "h:4433"
	cases := []struct {
		tr, qs string
		want   bool
	}{
		{"quic", "", true},
		{"quic", qs, true},
		{"tcp", qs, false},
		{"tcp", "", false},
		{"auto", qs, true},
		{"auto", "", false},
		{"AUTO", qs, true},
		{"", qs, true},
		{"", "", false},
		{"  ", qs, true},
	}
	for _, tc := range cases {
		if g := UsesQUICTransport(tc.tr, tc.qs); g != tc.want {
			t.Errorf("UsesQUICTransport(%q,%q)=%v want %v", tc.tr, tc.qs, g, tc.want)
		}
	}
}
