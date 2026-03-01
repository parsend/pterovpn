package update

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const apiURL = "https://api.github.com/repos/parsend/pterovpn/releases/latest"

type ghRelease struct {
	TagName string `json:"tag_name"`
}

func CheckLatest(current string) (latest string, err error) {
	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", apiURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/vnd.github.v3+json")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("github api: %s", resp.Status)
	}
	var r ghRelease
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return "", err
	}
	latest = strings.TrimSpace(r.TagName)
	if latest == "" {
		return "", fmt.Errorf("empty tag")
	}
	if current == "dev" || current == "" {
		return latest, nil
	}
	if Newer(latest, current) {
		return latest, nil
	}
	return "", nil
}

func Newer(a, b string) bool {
	va := parseVersion(a)
	vb := parseVersion(b)
	for i := 0; i < 3; i++ {
		na := 0
		nb := 0
		if i < len(va) {
			na = va[i]
		}
		if i < len(vb) {
			nb = vb[i]
		}
		if na > nb {
			return true
		}
		if na < nb {
			return false
		}
	}
	return false
}

func parseVersion(s string) []int {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	parts := strings.Split(s, ".")
	var out []int
	for _, p := range parts {
		n, _ := strconv.Atoi(strings.TrimSpace(p))
		out = append(out, n)
		if len(out) >= 3 {
			break
		}
	}
	return out
}
