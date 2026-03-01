//go:build linux

package tun

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

const (
	iffTUN    = 0x0001
	iffNoPI   = 0x1000
	tunSetIFF = 0x400454ca
)

func Create(name string) (*os.File, string, error) {
	_ = execIgnore("ip", "link", "delete", name)
	var lastErr error
	for attempt := 0; attempt < 6; attempt++ {
		f, err := createOnce(name)
		if err == nil {
			return f, name, nil
		}
		lastErr = err
		if errno, ok := err.(unix.Errno); !ok || errno != unix.EBUSY || attempt == 5 {
			return nil, "", err
		}
		time.Sleep(400 * time.Millisecond)
	}
	return nil, "", lastErr
}

func createOnce(name string) (*os.File, error) {
	f, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
	if err != nil {
		return nil, err
	}

	var ifr [unix.IFNAMSIZ + 64]byte
	copy(ifr[:unix.IFNAMSIZ-1], []byte(name))
	*(*uint16)(unsafe.Pointer(&ifr[unix.IFNAMSIZ])) = iffTUN | iffNoPI

	_, _, errno := unix.Syscall(unix.SYS_IOCTL, f.Fd(), tunSetIFF, uintptr(unsafe.Pointer(&ifr[0])))
	if errno != 0 {
		_ = f.Close()
		return nil, errno
	}

	got := ifr[:unix.IFNAMSIZ]
	n := 0
	for n < len(got) && got[n] != 0 {
		n++
	}
	if n == 0 {
		_ = f.Close()
		return nil, errors.New("tun name empty")
	}
	return f, nil
}

func Configure(name, cidr string, mtu int) error {
	if mtu < 576 || mtu > 9000 {
		return fmt.Errorf("bad mtu: %d", mtu)
	}
	if err := run("ip", "link", "set", "dev", name, "mtu", fmt.Sprintf("%d", mtu), "up"); err != nil {
		return err
	}
	_ = execIgnore("ip", "addr", "add", cidr, "dev", name)
	return nil
}

func Teardown(name, cidr string) {
	_ = execIgnore("ip", "route", "del", "default", "dev", name)
	_ = execIgnore("ip", "addr", "del", cidr, "dev", name)
	_ = execIgnore("ip", "link", "set", "dev", name, "down")
}

func run(args ...string) error {
	if err := execIgnore(args...); err != nil {
		return err
	}
	return nil
}

func execIgnore(args ...string) error {
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}
