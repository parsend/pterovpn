
//go:build !windows
// +build !windows

package sysproxy

func Set(_ string) error {
	return nil
}

func Clear() error {
	return nil
}
