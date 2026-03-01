package metrics

import (
	"os"
	"testing"
	"time"
)

func TestLoadAppendSave(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	store, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if store == nil {
		t.Fatal("store nil")
	}

	now := time.Now()
	r := SessionRecord{
		Start:       now,
		End:         now.Add(time.Second),
		Duration:    time.Second,
		Server:      "1.2.3.4:443",
		ConfigName:  "test",
		ErrorType:   "graceful",
		HandshakeOK: true,
		DNSOKBefore: true,
	}
	if err := store.Append(r); err != nil {
		t.Fatal(err)
	}

	store2, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(store2.Records) != 1 {
		t.Errorf("len(Records)=%d want 1", len(store2.Records))
	}
	if store2.Records[0].Server != r.Server {
		t.Errorf("got Server=%q", store2.Records[0].Server)
	}
}

func TestMaxRecords(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	store, _ := Load()
	now := time.Now()
	r := SessionRecord{Start: now, Server: "x", ConfigName: "c"}
	for i := 0; i < maxRecords+10; i++ {
		_ = store.Append(r)
	}
	if len(store.Records) != maxRecords {
		t.Errorf("len(Records)=%d want %d", len(store.Records), maxRecords)
	}
}
