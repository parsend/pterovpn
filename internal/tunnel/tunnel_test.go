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

func TestDialTcpUpgradesToTLS(t *testing.T) {}
