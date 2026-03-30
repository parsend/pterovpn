package update

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	apiLatest = "https://api.github.com/repos/unitdevgcc/pterovpn/releases/latest"
	apiTags   = "https://api.github.com/repos/unitdevgcc/pterovpn/releases/tags/"
)

type ghReleaseFull struct {
	TagName string `json:"tag_name"`
	Assets  []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	} `json:"assets"`
}

func getRelease(endpoint string) (*ghReleaseFull, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest("GET", endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github.v3+json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("github api: %s", resp.Status)
	}
	var r ghReleaseFull
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return nil, err
	}
	return &r, nil
}

func CheckLatest(current string) (latest string, err error) {
	r, err := getRelease(apiLatest)
	if err != nil {
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

func AssetDownloadURLForTag(tag string) (string, error) {
	tag = strings.TrimSpace(tag)
	if tag == "" {
		return "", fmt.Errorf("empty tag")
	}
	u := apiTags + url.PathEscape(tag)
	r, err := getRelease(u)
	if err != nil {
		return "", err
	}
	return pickAssetURL(r)
}

func pickAssetURL(r *ghReleaseFull) (string, error) {
	want, err := expectedAssetName()
	if err != nil {
		return "", err
	}
	for _, a := range r.Assets {
		if a.Name == want {
			if a.BrowserDownloadURL == "" {
				return "", fmt.Errorf("empty download url for %s", want)
			}
			return a.BrowserDownloadURL, nil
		}
	}
	return "", fmt.Errorf("release has no %s", want)
}

func expectedAssetName() (string, error) {
	switch runtime.GOOS + "/" + runtime.GOARCH {
	case "windows/amd64":
		return "ptera-client-windows-amd64.exe", nil
	case "linux/amd64":
		return "ptera-client-linux-amd64", nil
	case "linux/arm64":
		return "ptera-client-linux-arm64", nil
	default:
		return "", fmt.Errorf("unsupported platform %s/%s", runtime.GOOS, runtime.GOARCH)
	}
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
