package netcfg

import (
	"net"
	"testing"
)

func TestParseCIDRs(t *testing.T) {
	tests := []struct {
		in   string
		want int
		err  bool
	}{
		{"", 0, false},
		{"0.0.0.0/0", 1, false},
		{"192.168.0.0/16,10.0.0.0/8", 2, false},
		{"::/0", 1, false},
		{"1.2.3.4/32, 5.6.7.8/24", 2, false},
		{"bad", 0, true},
		{"1.2.3.4", 0, true},
	}
	for _, tc := range tests {
		got, err := ParseCIDRs(tc.in)
		if tc.err {
			if err == nil {
				t.Errorf("ParseCIDRs(%q) want err", tc.in)
			}
			continue
		}
		if err != nil {
			t.Errorf("ParseCIDRs(%q): %v", tc.in, err)
			continue
		}
		if len(got) != tc.want {
			t.Errorf("ParseCIDRs(%q) got %d", tc.in, len(got))
		}
	}
}

func TestSplitHostPorts(t *testing.T) {
	tests := []struct {
		srv, ports string
		want       []string
		err        bool
	}{
		{"1.2.3.4:25565", "", []string{"1.2.3.4:25565"}, false},
		{"1.2.3.4", "80,443", []string{"1.2.3.4:80", "1.2.3.4:443"}, false},
		{"host.example", "25565,25566", []string{"host.example:25565", "host.example:25566"}, false},
		{"", "", nil, true},
		{"1.2.3.4", "", nil, true},
		{"1.2.3.4", "  ", nil, true},
	}
	for _, tc := range tests {
		got, err := SplitHostPorts(tc.srv, tc.ports)
		if tc.err {
			if err == nil {
				t.Errorf("SplitHostPorts(%q,%q) want err", tc.srv, tc.ports)
			}
			continue
		}
		if err != nil {
			t.Errorf("SplitHostPorts(%q,%q): %v", tc.srv, tc.ports, err)
			continue
		}
		if len(got) != len(tc.want) {
			t.Errorf("SplitHostPorts got %v", got)
			continue
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("got %v", got)
			}
		}
	}
}

func TestParseCIDRsContents(t *testing.T) {
	n, err := ParseCIDRs("10.0.0.0/8")
	if err != nil {
		t.Fatal(err)
	}
	if len(n) != 1 {
		t.Fatal("want 1")
	}
	ones, bits := n[0].Mask.Size()
	if ones != 8 || bits != 32 {
		t.Errorf("mask %d/%d", ones, bits)
	}
	if !n[0].IP.Equal(net.ParseIP("10.0.0.0")) {
		t.Error("wrong ip")
	}
}
