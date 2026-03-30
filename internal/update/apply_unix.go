//go:build !windows

package update

import (
	"os"
	"path/filepath"
	"syscall"
)

func Apply(destExe string, downloadURL string) error {
	dest, err := filepath.EvalSymlinks(destExe)
	if err != nil {
		dest = destExe
	}
	destAbs, err := filepath.Abs(dest)
	if err != nil {
		return err
	}
	tmp := destAbs + ".new"
	_ = os.Remove(tmp)
	if err := downloadToFile(downloadURL, tmp); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0755); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, destAbs); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return syscall.Exec(destAbs, os.Args, os.Environ())
}
