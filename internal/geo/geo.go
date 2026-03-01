package geo

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"
)

const apiURL = "http://ip-api.com/json/%s?fields=status,country,countryCode,isp,org,as"

type Info struct {
	Country     string `json:"country"`
	CountryCode string `json:"countryCode"`
	Org         string `json:"org"`
	ASN         string `json:"as"`
}

type apiResp struct {
	Status      string `json:"status"`
	Country     string `json:"country"`
	CountryCode string `json:"countryCode"`
	Org         string `json:"org"`
	ASN         string `json:"as"`
}

var (
	cache   = make(map[string]Info)
	cacheMu sync.RWMutex
	client  = &http.Client{Timeout: 5 * time.Second}
)

func Fetch(host string) (Info, error) {
	ip, _, err := net.SplitHostPort(host)
	if err != nil {
		ip = host
	}
	if ip == "" || net.ParseIP(ip) == nil {
		return Info{}, fmt.Errorf("invalid host: %s", host)
	}

	cacheMu.RLock()
	if v, ok := cache[ip]; ok {
		cacheMu.RUnlock()
		return v, nil
	}
	cacheMu.RUnlock()

	url := fmt.Sprintf(apiURL, ip)
	resp, err := client.Get(url)
	if err != nil {
		return Info{}, err
	}
	defer resp.Body.Close()

	var r apiResp
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return Info{}, err
	}
	if r.Status != "success" {
		return Info{}, fmt.Errorf("ip-api: %s", r.Status)
	}

	info := Info{
		Country:     r.Country,
		CountryCode: r.CountryCode,
		Org:         r.Org,
		ASN:         r.ASN,
	}

	cacheMu.Lock()
	cache[ip] = info
	cacheMu.Unlock()
	return info, nil
}
