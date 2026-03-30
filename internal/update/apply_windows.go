package update

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

func Apply(destExe string, downloadURL string) error {
	destExe, err := filepath.EvalSymlinks(destExe)
	if err != nil {
		return err
	}
	destAbs, err := filepath.Abs(destExe)
	if err != nil {
		return err
	}
	dir := filepath.Dir(destAbs)
	base := filepath.Base(destAbs)
	newPath := filepath.Join(dir, base+".new")
	_ = os.Remove(newPath)
	if err := downloadToFile(downloadURL, newPath); err != nil {
		return err
	}
	batName := fmt.Sprintf("ptera-self-update-%d.bat", os.Getpid())
	batPath := filepath.Join(dir, batName)
	argsLine := batchArgs(os.Args[1:])
	bat := "@echo off\r\n" +
		"ping -n 3 127.0.0.1 >nul\r\n" +
		fmt.Sprintf(`move /Y "%s" "%s"`, escapeBatchMeta(newPath), escapeBatchMeta(destAbs)) + "\r\n" +
		fmt.Sprintf(`start "" "%s"%s`, escapeBatchMeta(destAbs), argsLine) + "\r\n" +
		fmt.Sprintf(`del "%s"`, escapeBatchMeta(batPath)) + "\r\n"
	if err := os.WriteFile(batPath, []byte(bat), 0600); err != nil {
		_ = os.Remove(newPath)
		return err
	}
	cmd := exec.Command("cmd.exe", "/C", batPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		_ = os.Remove(newPath)
		_ = os.Remove(batPath)
		return err
	}
	return nil
}

func escapeBatchMeta(s string) string {
	return strings.ReplaceAll(s, `"`, `""`)
}

func batchArgs(osArgs []string) string {
	if len(osArgs) == 0 {
		return ""
	}
	var b strings.Builder
	for _, a := range osArgs {
		if strings.ContainsAny(a, " \t\"") {
			b.WriteString(` "`)
			b.WriteString(strings.ReplaceAll(a, `"`, `\"`))
			b.WriteString(`"`)
			continue
		}
		b.WriteString(" ")
		b.WriteString(a)
	}
	return b.String()
}
