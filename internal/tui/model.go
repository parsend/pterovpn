package tui

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/metrics"
	"github.com/parsend/pterovpn/internal/probe"
)

const (
	success  = "10"
	errCol   = "9"
	dim      = "8"
	pingGreen = "2"
	pingYellow = "11"
	pingRed   = "1"
	probeTimeout = 5 * time.Second
)

type tab int

const (
	tabHome tab = iota
	tabConfig
	tabCloud
	tabLogs
	tabProtection
	tabSettings
)

var tabNames = []string{"Главная", "Конфигурации", "Cloud", "Логи", "Защита", "Настройки"}

type status int

const (
	statusDisconnected status = iota
	statusConnecting
	statusConnected
)

type ConnectFn func(cfg config.Config, configName string, reconnectCount int) (stop func(), err error)

type Opts struct {
	ConnectFn ConnectFn
}

type item struct {
	cfg       config.Config
	name      string
	pingMs   int64
	pterovpn int
}

func (i item) Title() string { return i.name }
func (i item) Description() string {
	var ping, ptr string
	switch {
	case i.pingMs >= 0:
		s := fmt.Sprintf("%dms", i.pingMs)
		switch {
		case i.pingMs < 100:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingGreen)).Render("✓ " + s)
		case i.pingMs < 300:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingYellow)).Render("✓ " + s)
		default:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingRed)).Render("✓ " + s)
		}
	case i.pingMs == -2:
		ping = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("✗")
	default:
		ping = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("○")
	}
	switch i.pterovpn {
	case 1:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(success)).Render("✓")
	case 0:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("✗")
	case 2:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("⚠")
	default:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("○")
	}
	return fmt.Sprintf("[%s] [%s] %s", ping, ptr, i.cfg.Server)
}
func (i item) FilterValue() string { return i.name + " " + i.cfg.Server }

type Model struct {
	opts          Opts
	tab           tab
	status        status
	activeCfg     string
	err           string
	stop          func()
	cfgList       list.Model
	cfgs          []config.Config
	names         []string
	pingResults   map[string]time.Duration
	pingFailed    map[string]bool
	pterovpnRes   map[string]int
	logBuf        *bytes.Buffer
	logs          []string
	logsMu        sync.Mutex
	logViewport   viewport.Model
	logAutoScroll bool
	adding        bool
	addInputs     []textinput.Model
	addFocus      int
	editing       bool
	editingName   string
	editInputs    []textinput.Model
	editFocus     int
	deletingCfg   string

	cloudList     list.Model
	cloudCfgs     []config.Config
	cloudNames    []string
	cloudLoading  bool
	cloudFetchErr string

	protectionViewport  viewport.Model
	protectionEditing   bool
	protectionFormFocus int
	protectionInputs    []textinput.Model
	protectionTarget    string
	protectionClientIdx int

	connectCount int
}

var (
	titleStyle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("15"))
	statusStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(success))
	errStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(errCol))
	tabStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim)).
			Padding(0, 2)
	activeTabStyle = lipgloss.NewStyle().
			Padding(0, 2).
			Bold(true).
			Foreground(lipgloss.Color("15"))
	contentBox = lipgloss.NewStyle().
			Padding(0, 1).
			Border(lipgloss.RoundedBorder()).
			BorderForeground(lipgloss.Color(dim))
	logLineStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim))
	logErrStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(errCol))
	footerStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim))
	sectionTitle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("12")).
			Padding(0, 0, 0, 1).
			BorderLeft(true).
			BorderForeground(lipgloss.Color("12"))
	emptyState = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim)).
			Italic(true)
	hintKey = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("14"))
	hintText = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim))
	kvLabel = lipgloss.NewStyle().
			Foreground(lipgloss.Color("7"))
	kvValue = lipgloss.NewStyle().
			Foreground(lipgloss.Color("15"))
)

func NewModel(opts Opts) Model {
	m := Model{
		opts:               opts,
		tab:                tabHome,
		status:             statusDisconnected,
		logBuf:             bytes.NewBuffer(nil),
		logs:               []string{},
		pingResults:        make(map[string]time.Duration),
		pingFailed:         make(map[string]bool),
		pterovpnRes:        make(map[string]int),
		logViewport:        viewport.New(60, 14),
		logAutoScroll:      true,
		protectionViewport: viewport.New(60, 14),
	}
	m.reloadCfgs()
	m.reloadCloud(false)
	return m
}

func (m *Model) buildItems() []list.Item {
	items := make([]list.Item, len(m.cfgs))
	for i := range m.cfgs {
		pingMs := int64(-1)
		if m.pingFailed[m.names[i]] {
			pingMs = -2
		} else if d, ok := m.pingResults[m.names[i]]; ok {
			pingMs = d.Milliseconds()
		}
		pterovpn := -1
		if v, exists := m.pterovpnRes[m.names[i]]; exists {
			pterovpn = v
		}
		items[i] = item{cfg: m.cfgs[i], name: m.names[i], pingMs: pingMs, pterovpn: pterovpn}
	}
	return items
}

func (m *Model) reloadCfgs() {
	cfgs, names, _ := config.List()
	m.cfgs = cfgs
	m.names = names
	items := m.buildItems()
	l := list.New(items, list.NewDefaultDelegate(), 40, 14)
	l.Title = "Конфигурации"
	l.SetShowStatusBar(false)
	m.cfgList = l
}

func (m *Model) refreshCfgItems() {
	idx := m.cfgList.Index()
	if idx < 0 {
		idx = 0
	}
	m.cfgList.SetItems(m.buildItems())
	m.cfgList.Select(idx)
}

func (m *Model) buildCloudItems() []list.Item {
	items := make([]list.Item, len(m.cloudCfgs))
	for i := range m.cloudCfgs {
		pingMs := int64(-1)
		if m.pingFailed[m.cloudNames[i]] {
			pingMs = -2
		} else if d, ok := m.pingResults[m.cloudNames[i]]; ok {
			pingMs = d.Milliseconds()
		}
		pterovpn := -1
		if v, exists := m.pterovpnRes[m.cloudNames[i]]; exists {
			pterovpn = v
		}
		items[i] = item{cfg: m.cloudCfgs[i], name: m.cloudNames[i], pingMs: pingMs, pterovpn: pterovpn}
	}
	return items
}

func (m *Model) reloadCloud(fetch bool) tea.Cmd {
	if fetch {
		m.cloudLoading = true
		m.cloudFetchErr = ""
		return runFetchCloud()
	}
	cfgs, names, _ := config.CloudList(false)
	m.cloudCfgs = cfgs
	m.cloudNames = names
	items := m.buildCloudItems()
	l := list.New(items, list.NewDefaultDelegate(), 40, 14)
	l.Title = "Cloud конфиги"
	l.SetShowStatusBar(false)
	m.cloudList = l
	return nil
}

func (m *Model) refreshCloudItems() {
	if len(m.cloudCfgs) == 0 {
		return
	}
	idx := m.cloudList.Index()
	if idx < 0 {
		idx = 0
	}
	m.cloudList.SetItems(m.buildCloudItems())
	m.cloudList.Select(idx)
}

func newAddInputs() []textinput.Model {
	return newInputsWithValues("", "", "", "")
}

func newProtectionInputs(opts config.ProtectionOptions) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	obf := opts.Obfuscation
	if obf == "" {
		obf = "default"
	}
	return []textinput.Model{
		ti("default|enhanced", obf),
		ti("0-12", strconv.Itoa(opts.JunkCount)),
		ti("64-1024", strconv.Itoa(opts.JunkMin)),
		ti("64-1024", strconv.Itoa(opts.JunkMax)),
		ti("0-64", strconv.Itoa(opts.PadS1)),
		ti("0-64", strconv.Itoa(opts.PadS2)),
		ti("0-64", strconv.Itoa(opts.PadS3)),
		ti("0-64", strconv.Itoa(opts.PadS4)),
		ti("true|false", strconv.FormatBool(opts.PreCheck)),
	}
}

func protectionOptsFromInputs(inputs []textinput.Model) config.ProtectionOptions {
	clamp := func(v, lo, hi int) int {
		if v < lo {
			return lo
		}
		if v > hi {
			return hi
		}
		return v
	}
	atoi := func(s string) int {
		v, _ := strconv.Atoi(strings.TrimSpace(s))
		return v
	}
	obf := strings.TrimSpace(inputs[0].Value())
	if obf != "enhanced" && obf != "default" {
		obf = "default"
	}
	return config.ProtectionOptions{
		Obfuscation: obf,
		JunkCount:   clamp(atoi(inputs[1].Value()), 0, 12),
		JunkMin:     clamp(atoi(inputs[2].Value()), 64, 1024),
		JunkMax:     clamp(atoi(inputs[3].Value()), 64, 1024),
		PadS1:       clamp(atoi(inputs[4].Value()), 0, 64),
		PadS2:       clamp(atoi(inputs[5].Value()), 0, 64),
		PadS3:       clamp(atoi(inputs[6].Value()), 0, 64),
		PadS4:       clamp(atoi(inputs[7].Value()), 0, 64),
		PreCheck:    strings.ToLower(strings.TrimSpace(inputs[8].Value())) == "true",
	}
}

func fillConnectionFromAnyField(inputs []textinput.Model) []textinput.Model {
	if len(inputs) < 2 {
		return inputs
	}
	connIdx := 1
	for i, in := range inputs {
		v := strings.TrimSpace(in.Value())
		if _, _, ok := config.ParseConnection(v); !ok || v == "" {
			continue
		}
		if i == connIdx {
			return inputs
		}
		inputs[connIdx].SetValue(v)
		inputs[i].SetValue("")
		break
	}
	return inputs
}

func newInputsWithValues(name, connection, routes, exclude string) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	return []textinput.Model{
		ti("имя", name),
		ti("host:port:key", connection),
		ti("routes (пусто=all)", routes),
		ti("exclude", exclude),
	}
}

type connectedMsg struct{ stop func() }
type disconnectedMsg struct{}
type errMsg string
type logMsg string

type pingResultMsg struct {
	name  string
	d     time.Duration
	failed bool
}
type pterovpnResultMsg struct {
	name string
	ok   bool
	err  bool
}

type cloudFetchedMsg struct {
	cfgs  []config.Config
	names []string
	err   string
}

func LogMessage(s string) tea.Msg { return logMsg(s) }

func runFetchCloud() tea.Cmd {
	return func() tea.Msg {
		cfgs, names, err := config.CloudList(true)
		if err != nil {
			return cloudFetchedMsg{err: err.Error()}
		}
		return cloudFetchedMsg{cfgs: cfgs, names: names}
	}
}

func runPing(addr, name string) tea.Cmd {
	return func() tea.Msg {
		d, err := probe.Ping(addr, probeTimeout)
		if err != nil {
			return pingResultMsg{name: name, failed: true}
		}
		return pingResultMsg{name: name, d: d}
	}
}

func runProbePterovpn(addr, name string) tea.Cmd {
	return func() tea.Msg {
		ok, err := probe.ProbePterovpn(addr, probeTimeout)
		if err != nil {
			return pterovpnResultMsg{name: name, ok: false, err: true}
		}
		return pterovpnResultMsg{name: name, ok: ok, err: false}
	}
}

func runPingAll(cfgs []config.Config, names []string) tea.Cmd {
	if len(cfgs) == 0 {
		return nil
	}
	cmds := make([]tea.Cmd, len(cfgs))
	for i := range cfgs {
		cmds[i] = runPing(cfgs[i].Server, names[i])
	}
	return tea.Batch(cmds...)
}

func runProbeAll(cfgs []config.Config, names []string) tea.Cmd {
	if len(cfgs) == 0 {
		return nil
	}
	cmds := make([]tea.Cmd, len(cfgs))
	for i := range cfgs {
		cmds[i] = runProbePterovpn(cfgs[i].Server, names[i])
	}
	return tea.Batch(cmds...)
}

func autoProbeCmds(cfgs []config.Config, names []string) tea.Cmd {
	return tea.Batch(runPingAll(cfgs, names), runProbeAll(cfgs, names))
}

func (m Model) Init() tea.Cmd {
	return autoProbeCmds(m.cfgs, m.names)
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "q", "esc":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
				return m, nil
			}
			if m.protectionEditing {
				m.protectionEditing = false
				m.protectionFormFocus = 0
				return m, nil
			}
			if m.editing {
				m.editing = false
				m.editingName = ""
				m.err = ""
				return m, nil
			}
			if m.adding {
				m.adding = false
				m.err = ""
				return m, nil
			}
			if m.stop != nil {
				m.stop()
				m.stop = nil
			}
			return m, tea.Quit
		case "n", "N":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
				return m, nil
			}
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" {
				m.adding = true
				m.addInputs = newAddInputs()
				m.addFocus = 0
				m.addInputs[0].Focus()
				return m, nil
			}
		case "p", "P":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) {
					return m, runPing(m.cfgs[idx].Server, m.names[idx])
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx >= 0 && idx < len(m.cloudCfgs) {
					return m, runPing(m.cloudCfgs[idx].Server, m.cloudNames[idx])
				}
			}
		case "t", "T":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) {
					return m, runProbePterovpn(m.cfgs[idx].Server, m.names[idx])
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx >= 0 && idx < len(m.cloudCfgs) {
					return m, runProbePterovpn(m.cloudCfgs[idx].Server, m.cloudNames[idx])
				}
			}
		case "r", "R":
			if m.tab == tabCloud && !m.cloudLoading {
				return m, m.reloadCloud(true)
			}
		case "d", "D", "delete":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx >= 0 && idx < len(m.names) {
					m.deletingCfg = m.names[idx]
				}
				return m, nil
			}
		case "y", "Y":
			if m.deletingCfg != "" {
				_ = config.Delete(m.deletingCfg)
				m.deletingCfg = ""
				m.reloadCfgs()
				return m, autoProbeCmds(m.cfgs, m.names)
			}
		case "b", "B":
			if m.tab == tabSettings && len(m.cfgs) > 0 {
				return m, runPingAll(m.cfgs, m.names)
			}
		case "e", "E":
			if m.tab == tabProtection && !m.protectionEditing {
				var opts config.ProtectionOptions
				if m.protectionTarget == "" {
					opts, _ = config.LoadProtection()
				} else {
					cfg, _ := config.LoadByName(m.protectionTarget)
					if cfg.Protection != nil {
						opts = *cfg.Protection
					}
				}
				m.protectionInputs = newProtectionInputs(opts)
				m.protectionEditing = true
				m.protectionFormFocus = 0
				m.protectionInputs[0].Focus()
				return m, nil
			}
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx >= 0 && idx < len(m.cfgs) {
					m.editing = true
					m.editingName = m.names[idx]
					cfg := m.cfgs[idx]
					m.editInputs = newInputsWithValues(m.names[idx], cfg.Server+":"+cfg.Token, cfg.Routes, cfg.Exclude)
					m.editFocus = 0
					m.editInputs[0].Focus()
					m.err = ""
				}
				return m, nil
			}
		case "ctrl+left", "ctrl+h":
			if m.tab == tabProtection && !m.protectionEditing && len(m.names) > 0 {
				m.protectionClientIdx--
				if m.protectionClientIdx < 0 {
					m.protectionClientIdx = len(m.names)
				}
				m.protectionTarget = ""
				if m.protectionClientIdx > 0 {
					m.protectionTarget = m.names[m.protectionClientIdx-1]
				}
				return m, nil
			}
		case "ctrl+right", "ctrl+l":
			if m.tab == tabProtection && !m.protectionEditing && len(m.names) > 0 {
				m.protectionClientIdx = (m.protectionClientIdx + 1) % (len(m.names) + 1)
				m.protectionTarget = ""
				if m.protectionClientIdx > 0 {
					m.protectionTarget = m.names[m.protectionClientIdx-1]
				}
				return m, nil
			}
		case "tab", "right":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			if m.protectionEditing && len(m.protectionInputs) == 9 {
				m.protectionFormFocus = (m.protectionFormFocus + 1) % 9
				for i := range m.protectionInputs {
					if i == m.protectionFormFocus {
						m.protectionInputs[i].Focus()
					} else {
						m.protectionInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.editing {
				m.editFocus = (m.editFocus + 1) % len(m.editInputs)
				for i := range m.editInputs {
					if i == m.editFocus {
						m.editInputs[i].Focus()
					} else {
						m.editInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.adding {
				m.addFocus = (m.addFocus + 1) % len(m.addInputs)
				for i := range m.addInputs {
					if i == m.addFocus {
						m.addInputs[i].Focus()
					} else {
						m.addInputs[i].Blur()
					}
				}
				return m, nil
			}
			m.tab = tab((int(m.tab) + 1) % 6)
			if m.tab == tabConfig && len(m.cfgs) > 0 {
				return m, autoProbeCmds(m.cfgs, m.names)
			}
			if m.tab == tabCloud && len(m.cloudCfgs) > 0 {
				return m, autoProbeCmds(m.cloudCfgs, m.cloudNames)
			}
			return m, nil
		case "shift+tab", "left":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			if m.protectionEditing && len(m.protectionInputs) == 9 {
				m.protectionFormFocus = (m.protectionFormFocus + 8) % 9
				for i := range m.protectionInputs {
					if i == m.protectionFormFocus {
						m.protectionInputs[i].Focus()
					} else {
						m.protectionInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.editing {
				m.editFocus = (m.editFocus + len(m.editInputs) - 1) % len(m.editInputs)
				for i := range m.editInputs {
					if i == m.editFocus {
						m.editInputs[i].Focus()
					} else {
						m.editInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.adding {
				m.addFocus = (m.addFocus + len(m.addInputs) - 1) % len(m.addInputs)
				for i := range m.addInputs {
					if i == m.addFocus {
						m.addInputs[i].Focus()
					} else {
						m.addInputs[i].Blur()
					}
				}
				return m, nil
			}
			m.tab = tab((int(m.tab) + 3) % 6)
			if m.tab == tabConfig && len(m.cfgs) > 0 {
				return m, autoProbeCmds(m.cfgs, m.names)
			}
			if m.tab == tabCloud && len(m.cloudCfgs) > 0 {
				return m, autoProbeCmds(m.cloudCfgs, m.cloudNames)
			}
			return m, nil
		case "enter":
			if m.protectionEditing && len(m.protectionInputs) == 9 {
				opts := protectionOptsFromInputs(m.protectionInputs)
				if m.protectionTarget == "" {
					_ = config.SaveProtection(opts)
				} else {
					cfg, err := config.LoadByName(m.protectionTarget)
					if err == nil {
						cfg.Protection = &opts
						_ = config.Save(m.protectionTarget, cfg)
					}
				}
				m.protectionEditing = false
				m.protectionFormFocus = 0
				return m, nil
			}
			if m.editing {
				if m.editFocus < len(m.editInputs)-1 {
					m.editFocus++
					for i := range m.editInputs {
						if i == m.editFocus {
							m.editInputs[i].Focus()
						} else {
							m.editInputs[i].Blur()
						}
					}
				} else {
					oldName := m.editingName
					newName := config.SanitizeName(strings.TrimSpace(m.editInputs[0].Value()))
					conn := strings.TrimSpace(m.editInputs[1].Value())
					if newName == "" {
						newName = "default"
					}
					server, token, ok := config.ParseConnection(conn)
					if !ok {
						m.err = "connection: host:port:key"
					} else {
						cfg := config.Config{Server: server, Token: token, Routes: strings.TrimSpace(m.editInputs[2].Value()), Exclude: strings.TrimSpace(m.editInputs[3].Value())}
						if newName != oldName {
							_ = config.Delete(oldName)
						}
						if err := config.Save(newName, cfg); err != nil {
							m.err = err.Error()
						} else {
							m.reloadCfgs()
							m.editing = false
							m.editingName = ""
							m.err = ""
							return m, autoProbeCmds(m.cfgs, m.names)
						}
					}
				}
				return m, nil
			}
			if m.adding {
				if m.addFocus < len(m.addInputs)-1 {
					m.addFocus++
					for i := range m.addInputs {
						if i == m.addFocus {
							m.addInputs[i].Focus()
						} else {
							m.addInputs[i].Blur()
						}
					}
				} else {
					name := config.SanitizeName(strings.TrimSpace(m.addInputs[0].Value()))
					conn := strings.TrimSpace(m.addInputs[1].Value())
					if name == "" {
						name = "default"
					}
					server, token, ok := config.ParseConnection(conn)
					if !ok {
						m.err = "connection: host:port:key"
					} else if err := config.Save(name, config.Config{Server: server, Token: token, Routes: strings.TrimSpace(m.addInputs[2].Value()), Exclude: strings.TrimSpace(m.addInputs[3].Value())}); err != nil {
						m.err = err.Error()
					} else {
						m.reloadCfgs()
						m.adding = false
						m.err = ""
						return m, autoProbeCmds(m.cfgs, m.names)
					}
				}
				return m, nil
			}
			if m.tab == tabConfig && m.deletingCfg == "" && m.opts.ConnectFn != nil && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) && m.status != statusConnecting {
					if m.status == statusConnected {
						if m.stop != nil {
							m.stop()
							m.stop = nil
						}
						m.status = statusDisconnected
						m.activeCfg = ""
					} else {
						m.status = statusConnecting
						m.err = ""
						m.activeCfg = m.names[idx]
						m.connectCount++
						cfg := m.cfgs[idx]
						return m, waitConnect(cfg, m.activeCfg, m.connectCount-1, m.opts.ConnectFn)
					}
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && m.opts.ConnectFn != nil && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cloudCfgs) && m.status != statusConnecting {
					if m.status == statusConnected {
						if m.stop != nil {
							m.stop()
							m.stop = nil
						}
						m.status = statusDisconnected
						m.activeCfg = ""
					} else {
						m.status = statusConnecting
						m.err = ""
						m.activeCfg = m.cloudNames[idx]
						m.connectCount++
						cfg := m.cloudCfgs[idx]
						return m, waitConnect(cfg, m.activeCfg, m.connectCount-1, m.opts.ConnectFn)
					}
				}
			}
		}
	case connectedMsg:
		m.status = statusConnected
		m.stop = msg.stop
		return m, nil
	case disconnectedMsg:
		m.status = statusDisconnected
		m.stop = nil
		m.activeCfg = ""
		return m, nil
	case errMsg:
		m.status = statusDisconnected
		m.stop = nil
		m.err = string(msg)
		m.activeCfg = ""
		return m, nil
	case logMsg:
		m.logsMu.Lock()
		m.logs = append(m.logs, string(msg))
		if len(m.logs) > 500 {
			m.logs = m.logs[len(m.logs)-500:]
		}
		m.logsMu.Unlock()
		return m, nil
	case pingResultMsg:
		if msg.failed {
			m.pingFailed[msg.name] = true
			delete(m.pingResults, msg.name)
		} else {
			m.pingResults[msg.name] = msg.d
			delete(m.pingFailed, msg.name)
		}
		m.refreshCfgItems()
		m.refreshCloudItems()
		return m, nil
	case tea.WindowSizeMsg:
		m.logViewport.Width = msg.Width - 4
		m.logViewport.Height = msg.Height - 10
		if m.logViewport.Height < 5 {
			m.logViewport.Height = 5
		}
		if m.logViewport.Width < 20 {
			m.logViewport.Width = 20
		}
		m.protectionViewport.Width = msg.Width - 4
		m.protectionViewport.Height = msg.Height - 10
		if m.protectionViewport.Height < 5 {
			m.protectionViewport.Height = 5
		}
		if m.protectionViewport.Width < 20 {
			m.protectionViewport.Width = 20
		}
		return m, nil
	case pterovpnResultMsg:
		if msg.err {
			m.pterovpnRes[msg.name] = 2
		} else if msg.ok {
			m.pterovpnRes[msg.name] = 1
		} else {
			m.pterovpnRes[msg.name] = 0
		}
		m.refreshCfgItems()
		m.refreshCloudItems()
		return m, nil
	case cloudFetchedMsg:
		m.cloudLoading = false
		if msg.err != "" {
			m.cloudFetchErr = msg.err
			return m, nil
		}
		m.cloudFetchErr = ""
		m.cloudCfgs = msg.cfgs
		m.cloudNames = msg.names
		items := m.buildCloudItems()
		l := list.New(items, list.NewDefaultDelegate(), 40, 14)
		l.Title = "Cloud конфиги"
		l.SetShowStatusBar(false)
		m.cloudList = l
		return m, autoProbeCmds(m.cloudCfgs, m.cloudNames)
	}

	var cmd tea.Cmd
	if m.protectionEditing && m.protectionFormFocus < len(m.protectionInputs) {
		m.protectionInputs[m.protectionFormFocus], cmd = m.protectionInputs[m.protectionFormFocus].Update(msg)
		return m, cmd
	}
	if m.editing && m.editFocus < len(m.editInputs) {
		m.editInputs[m.editFocus], cmd = m.editInputs[m.editFocus].Update(msg)
		m.editInputs = fillConnectionFromAnyField(m.editInputs)
		return m, cmd
	}
	if m.adding && m.addFocus < len(m.addInputs) {
		m.addInputs[m.addFocus], cmd = m.addInputs[m.addFocus].Update(msg)
		m.addInputs = fillConnectionFromAnyField(m.addInputs)
		return m, cmd
	}
	if m.tab == tabLogs {
		m.logViewport, cmd = m.logViewport.Update(msg)
		return m, cmd
	}
	if m.tab == tabProtection {
		m.protectionViewport, cmd = m.protectionViewport.Update(msg)
		return m, cmd
	}
	if m.tab == tabConfig {
		m.cfgList, cmd = m.cfgList.Update(msg)
	}
	if m.tab == tabCloud {
		m.cloudList, cmd = m.cloudList.Update(msg)
	}
	return m, cmd
}

func waitConnect(cfg config.Config, configName string, reconnectCount int, fn ConnectFn) tea.Cmd {
	return func() tea.Msg {
		stop, err := fn(cfg, configName, reconnectCount)
		if err != nil {
			return errMsg(err.Error())
		}
		return connectedMsg{stop: stop}
	}
}

const protectionAnalyticsLimit = 20

func (m Model) protectionView() string {
	var b strings.Builder

	b.WriteString(sectionTitle.Render("Аналитика") + "\n")
	store, err := metrics.Load()
	if err == nil && len(store.Records) > 0 {
		start := len(store.Records) - protectionAnalyticsLimit
		if start < 0 {
			start = 0
		}
		for i := len(store.Records) - 1; i >= start; i-- {
			r := store.Records[i]
			hs := "-"
			if r.HandshakeOK {
				hs = lipgloss.NewStyle().Foreground(lipgloss.Color(success)).Render("ok")
			} else {
				hs = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("fail")
			}
			errType := r.ErrorType
			if errType == "" {
				errType = "-"
			}
			dur := r.Duration.Round(time.Second).String()
			if r.Duration == 0 && !r.End.IsZero() {
				dur = "-"
			}
			b.WriteString("  ")
			b.WriteString(kvLabel.Render(r.Start.Format("02.01 15:04")) + " ")
			b.WriteString(kvValue.Render(fmt.Sprintf("%s  %s  HS:%s  R:%d  RTT:%s/%s  DNS:%v/%v",
				dur, errType, hs, r.ReconnectCount,
				formatRTT(r.RTTBefore), formatRTT(r.RTTDuring), r.DNSOKBefore, r.DNSOKAfter)))
			b.WriteString("\n")
		}
	} else {
		b.WriteString("  ")
		b.WriteString(emptyState.Render("нет данных"))
		b.WriteString("\n")
	}

	b.WriteString("\n")
	b.WriteString(sectionTitle.Render("Настройки защиты") + "\n")
	if m.protectionEditing && len(m.protectionInputs) == 9 {
		labels := []string{"obfuscation", "junkCount", "junkMin", "junkMax", "padS1", "padS2", "padS3", "padS4", "preCheck"}
		for i := range m.protectionInputs {
			b.WriteString("  ")
			b.WriteString(kvLabel.Render(labels[i]+":") + " ")
			b.WriteString(m.protectionInputs[i].View())
			b.WriteString("\n")
		}
		b.WriteString("\n  ")
		b.WriteString(hintKey.Render("Tab") + " ")
		b.WriteString(hintText.Render("след.  ") + hintKey.Render("Enter") + " ")
		b.WriteString(hintText.Render("сохранить  ") + hintKey.Render("Esc") + " ")
		b.WriteString(hintText.Render("отмена") + "\n")
	} else {
		var opts config.ProtectionOptions
		if m.protectionTarget == "" {
			opts, _ = config.LoadProtection()
		} else {
			cfg, _ := config.LoadByName(m.protectionTarget)
			if cfg.Protection != nil {
				opts = *cfg.Protection
			}
		}
		obf := opts.Obfuscation
		if obf == "" {
			obf = "default"
		}
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("obfuscation:") + " ")
		b.WriteString(kvValue.Render(obf) + "   ")
		b.WriteString(kvLabel.Render("junkCount:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkCount)) + "   ")
		b.WriteString(kvLabel.Render("junkMin:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkMin)) + "   ")
		b.WriteString(kvLabel.Render("junkMax:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkMax)) + "\n")
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("padS1-4:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%d/%d/%d/%d", opts.PadS1, opts.PadS2, opts.PadS3, opts.PadS4)) + "   ")
		b.WriteString(kvLabel.Render("preCheck:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%v", opts.PreCheck)) + "\n")
		b.WriteString("  ")
		b.WriteString(hintKey.Render("E") + " ")
		b.WriteString(hintText.Render("редактировать") + "\n")
	}

	b.WriteString("\n")
	b.WriteString(sectionTitle.Render("Клиентские опции") + "\n")
	target := "Глобально"
	if m.protectionTarget != "" {
		target = m.protectionTarget
	}
	b.WriteString("  ")
	b.WriteString(kvLabel.Render("Цель:") + " ")
	b.WriteString(kvValue.Render(target))
	if len(m.names) > 0 {
		b.WriteString("   ")
		b.WriteString(hintKey.Render("Ctrl+←/→") + hintText.Render(" переключить"))
	}
	b.WriteString("\n")

	full := b.String()
	m.protectionViewport.SetContent(full)
	return m.protectionViewport.View()
}

func formatRTT(d time.Duration) string {
	if d == 0 {
		return "-"
	}
	return d.Round(time.Millisecond).String()
}

func (m Model) View() string {
	var b strings.Builder
	b.WriteString(titleStyle.Render("pteravpn  dev - c0redev(parsend)"))
	b.WriteString("  ")
	switch m.status {
	case statusConnected:
		b.WriteString(statusStyle.Render("Ядро: Подключено"))
	case statusConnecting:
		b.WriteString("Ядро: Подключение...")
	default:
		b.WriteString("Ядро: Отключено")
	}
	b.WriteString("  Tun: Вкл  ")
	if m.activeCfg != "" {
		b.WriteString("Конфигурация: " + m.activeCfg)
	}
	b.WriteString("\n\n")

	for i, name := range tabNames {
		if i == int(m.tab) {
			b.WriteString(activeTabStyle.Render("[ " + name + " ]"))
		} else {
			b.WriteString(tabStyle.Render(name))
		}
	}
	b.WriteString("\n\n")

	var content strings.Builder
	switch m.tab {
	case tabHome:
		content.WriteString("Статус\n")
		switch m.status {
		case statusConnected:
			content.WriteString(statusStyle.Render("Ядро: Подключено"))
		case statusConnecting:
			content.WriteString("Ядро: Подключение...")
		default:
			content.WriteString("Ядро: Отключено")
		}
		content.WriteString("\n")
		if m.activeCfg != "" {
			idx := -1
			for i, n := range m.names {
				if n == m.activeCfg {
					idx = i
					break
				}
			}
			if idx >= 0 && idx < len(m.cfgs) {
				content.WriteString("Конфигурация: " + m.cfgs[idx].Server + "\n")
			} else {
				for i, n := range m.cloudNames {
					if n == m.activeCfg && i < len(m.cloudCfgs) {
						content.WriteString("Конфигурация: " + m.cloudCfgs[i].Server + "\n")
						break
					}
				}
			}
		}
		content.WriteString("Tun: Вкл\n")
		if m.err != "" {
			content.WriteString(errStyle.Render("Ошибка: " + m.err))
		}
	case tabConfig:
		if m.deletingCfg != "" {
			content.WriteString("Удалить конфигурацию \"" + m.deletingCfg + "\"? y/n")
		} else if m.editing {
			content.WriteString("Редактирование: " + m.editingName + "\n\n")
			labels := []string{"Имя:", "Connection (host:port:key):", "Routes:", "Exclude:"}
			for i := range m.editInputs {
				content.WriteString(labels[i] + " ")
				content.WriteString(m.editInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\nTab/Enter - следующее  Esc - отмена")
			if m.err != "" {
				content.WriteString("\n")
				content.WriteString(errStyle.Render(m.err))
			}
		} else if m.adding {
			content.WriteString("Новая конфигурация\n\n")
			labels := []string{"Имя:", "Connection (host:port:key):", "Routes:", "Exclude:"}
			for i := range m.addInputs {
				content.WriteString(labels[i] + " ")
				content.WriteString(m.addInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\nTab/Enter - следующее  Esc - отмена")
			if m.err != "" {
				content.WriteString("\n")
				content.WriteString(errStyle.Render(m.err))
			}
		} else {
			content.WriteString(m.cfgList.View())
		}
	case tabCloud:
		if m.cloudLoading {
			content.WriteString("Загрузка cloud конфигов...")
		} else if m.cloudFetchErr != "" {
			content.WriteString(errStyle.Render("Ошибка: " + m.cloudFetchErr))
			content.WriteString("\n\nR - обновить")
		} else if len(m.cloudCfgs) == 0 {
			content.WriteString(emptyState.Render("Нет конфигов. R - загрузить с реп"))
		} else {
			content.WriteString(m.cloudList.View())
		}
	case tabLogs:
		m.logsMu.Lock()
		var logLines strings.Builder
		for _, line := range m.logs {
			line = strings.TrimRight(line, "\r\n")
			if strings.Contains(strings.ToLower(line), "failed") || strings.Contains(strings.ToLower(line), "error") {
				logLines.WriteString(logErrStyle.Render(line))
			} else {
				logLines.WriteString(logLineStyle.Render(line))
			}
			logLines.WriteString("\n")
		}
		logStr := logLines.String()
		m.logsMu.Unlock()
		m.logViewport.SetContent(logStr)
		if m.logAutoScroll {
			m.logViewport.GotoBottom()
		}
		content.WriteString(m.logViewport.View())
	case tabProtection:
		content.WriteString(m.protectionView())
	case tabSettings:
		content.WriteString("Утилиты\n\n")
		if m.activeCfg != "" {
			idx := -1
			for i, n := range m.names {
				if n == m.activeCfg {
					idx = i
					break
				}
			}
			if idx >= 0 && idx < len(m.cfgs) {
				content.WriteString("Активный конфиг: " + m.activeCfg + "\n\n")
				raw, _ := json.MarshalIndent(m.cfgs[idx], "", "  ")
				content.WriteString(string(raw))
				content.WriteString("\n\n")
			} else {
				for i, n := range m.cloudNames {
					if n == m.activeCfg && i < len(m.cloudCfgs) {
						content.WriteString("Активный конфиг: " + m.activeCfg + "\n\n")
						raw, _ := json.MarshalIndent(m.cloudCfgs[i], "", "  ")
						content.WriteString(string(raw))
						content.WriteString("\n\n")
						break
					}
				}
			}
		}
		content.WriteString("B - тест всех конфигов (ping)\n")
		content.WriteString("q/Esc - выход\n")
	}

	b.WriteString(contentBox.Render(content.String()))
	b.WriteString("\n\n")
	footer := "Tab/Shift+Tab или ←/→ - вкладки  q/Esc - выход  Enter - подключиться/отключиться"
	if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" {
		footer += "  ↑/↓ - выбор  N - добавить  P - ping  T - pterovpn  E - ред.  D - удалить"
	}
	if m.tab == tabCloud && !m.cloudLoading {
		footer += "  ↑/↓ - выбор  P - ping  T - pterovpn  R - обновить"
	}
	if m.tab == tabLogs {
		footer += "  ↑/↓ PgUp/PgDn Home/End - прокрутка"
	}
	if m.tab == tabProtection {
		footer += "  E - редактировать  Ctrl+←/→ - цель  ↑/↓ PgUp/PgDn - прокрутка"
	}
	if m.tab == tabSettings {
		footer += "  B - тест всех конфигов"
	}
	b.WriteString(footerStyle.Render(footer))
	return b.String()
}
