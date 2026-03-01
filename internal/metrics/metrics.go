package metrics

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const maxRecords = 500

type SessionRecord struct {
	Start         time.Time     `json:"start"`
	End           time.Time     `json:"end"`
	Duration      time.Duration `json:"duration"`
	Server        string        `json:"server"`
	ConfigName    string        `json:"configName"`
	ErrorType     string        `json:"errorType,omitempty"`
	HandshakeOK   bool          `json:"handshakeOK"`
	ReconnectCount int          `json:"reconnectCount"`
	RTTBefore     time.Duration `json:"rttBefore,omitempty"`
	RTTDuring     time.Duration `json:"rttDuring,omitempty"`
	DNSOKBefore   bool          `json:"dnsOKBefore"`
	DNSOKAfter    bool          `json:"dnsOKAfter"`
	ProbeOK       bool          `json:"probeOK"`
}

type Store struct {
	Records []SessionRecord `json:"records"`
	mu      sync.Mutex
}

func path() (string, error) {
	d, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(d, "pteravpn", "metrics.json"), nil
}

func Load() (*Store, error) {
	p, err := path()
	if err != nil {
		return &Store{Records: []SessionRecord{}}, nil
	}
	b, err := os.ReadFile(p)
	if err != nil {
		if os.IsNotExist(err) {
			return &Store{Records: []SessionRecord{}}, nil
		}
		return nil, err
	}
	var s Store
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, err
	}
	if s.Records == nil {
		s.Records = []SessionRecord{}
	}
	return &s, nil
}

func (s *Store) Append(r SessionRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Records = append(s.Records, r)
	if len(s.Records) > maxRecords {
		s.Records = s.Records[len(s.Records)-maxRecords:]
	}
	return s.save()
}

func (s *Store) save() error {
	p, err := path()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(p, b, 0600)
}
