//go:build windows

package sysproxy

import (
	"log"

	"golang.org/x/sys/windows/registry"
)

const (
	keyPath  = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`
	proxyKey = "ProxyServer"
	enableKey = "ProxyEnable"
)

func Set(addr string) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, keyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if err := k.SetStringValue(proxyKey, addr); err != nil {
		return err
	}
	if err := k.SetDWordValue(enableKey, 1); err != nil {
		return err
	}
	log.Printf("sysproxy: set %s", addr)
	return nil
}

func Clear() error {
	k, err := registry.OpenKey(registry.CURRENT_USER, keyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	_ = k.DeleteValue(proxyKey)
	if err := k.SetDWordValue(enableKey, 0); err != nil {
		return err
	}
	log.Printf("sysproxy: cleared")
	return nil
}
