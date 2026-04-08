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

func TestPickAddrOrderStartsWithPickedAddrAndContainsAll(t *testing.T) {
	addrs := []string{"a:1", "b:2", "c:3", "d:4"}
	ip := net.ParseIP("10.1.2.3")
	p := uint16(443)
	first := pickAddr(addrs, ip, p)
	order := pickAddrOrder(addrs, ip, p)
	if len(order) != len(addrs) {
		t.Fatalf("order len=%d want %d", len(order), len(addrs))
	}
	if order[0] != first {
		t.Fatalf("order[0]=%s want %s", order[0], first)
	}
	seen := map[string]int{}
	for _, a := range order {
		seen[a]++
	}
	for _, a := range addrs {
		if seen[a] != 1 {
			t.Fatalf("addr %s count=%d want 1", a, seen[a])
		}
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

func TestDualPathSelectorHealthyMix(t *testing.T) {
	s := newDualPathSelector(11)
	n := 0
	for i := 0; i < 2000; i++ {
		if s.PreferQUIC() {
			n++
		}
	}
	if n < 600 || n > 1900 {
		t.Errorf("want blended mix, got %d/2000 QUIC picks", n)
	}
}

func TestDualPathSelectorDegradedProbes(t *testing.T) {
	s := newDualPathSelector(22)
	s.RecordQuicOutcome(false)
	s.RecordQuicOutcome(false)
	n := 0
	for i := 0; i < 100; i++ {
		if s.PreferQUIC() {
			n++
		}
	}
	if n < 5 || n > 20 {
		t.Errorf("want ~10%% QUIC probes in degraded, got %d/100", n)
	}
}

func TestDualPathSelectorRecoveryClearsDegraded(t *testing.T) {
	s := newDualPathSelector(33)
	s.RecordQuicOutcome(false)
	s.RecordQuicOutcome(false)
	s.RecordQuicOutcome(true)
	n := 0
	for i := 0; i < 300; i++ {
		if s.PreferQUIC() {
			n++
		}
	}
	if n < 80 {
		t.Errorf("want restored QUIC preference, got %d/300", n)
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
