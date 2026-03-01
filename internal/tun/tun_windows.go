//go:build windows

package tun

import (
	"fmt"

	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/tun"
	wg "golang.zx2c4.com/wireguard/tun"
)

func init() {
	wg.WintunTunnelType = "pteravpn"
}

func Create(name string, mtu int) (device.Device, string, error) {
	mtuVal := uint32(0)
	if mtu > 0 {
		mtuVal = uint32(mtu)
	}
	dev, err := tun.Open(name, mtuVal)
	if err != nil {
		return nil, "", fmt.Errorf("create tun: %w", err)
	}
	n := dev.Name()
	return dev, n, nil
}
