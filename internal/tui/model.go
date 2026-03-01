package tui

import (
	"bytes"
	"strings"
	"sync"

	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/bubbles/textinput"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/parsend/pterovpn/internal/config"
)

type tab int

const (
	tabHome tab = iota
	tabConfig
	tabLogs
	tabSettings
)

var tabNames = []string{"Главная", "Конфигурации", "Логи", "Настройки"}

type status int

const (
	statusDisconnected status = iota
	statusConnecting
	statusConnected
)

type ConnectFn func(cfg config.Config) (stop func(), err error)

type Opts struct {
	ConnectFn ConnectFn
}

type item struct {
	cfg  config.Config
	name string
}

func (i item) Title() string       { return i.name }
func (i item) Description() string { return i.cfg.Server }
func (i item) FilterValue() string { return i.name + " " + i.cfg.Server }

type Model struct {
	opts       Opts
	tab        tab
	status     status
	activeCfg  string
	err        string
	stop       func()
	cfgList    list.Model
	cfgs       []config.Config
	names      []string
	logBuf     *bytes.Buffer
	logs       []string
	logsMu     sync.Mutex
	adding     bool
	addInputs  []textinput.Model
	addFocus   int
}

var (
	titleStyle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("15"))
	statusStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("10"))
	errStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("9"))
	tabStyle = lipgloss.NewStyle().
			Padding(0, 2)
	activeTabStyle = lipgloss.NewStyle().
			Padding(0, 2).
			Bold(true).
			Foreground(lipgloss.Color("15"))
)

func NewModel(opts Opts) Model {
	m := Model{
		opts:    opts,
		tab:     tabHome,
		status:  statusDisconnected,
		logBuf:  bytes.NewBuffer(nil),
		logs:    []string{},
	}
	m.reloadCfgs()
	return m
}

func (m *Model) reloadCfgs() {
	cfgs, names, _ := config.List()
	m.cfgs = cfgs
	m.names = names
	items := make([]list.Item, len(cfgs))
	for i := range cfgs {
		items[i] = item{cfg: cfgs[i], name: names[i]}
	}
	l := list.New(items, list.NewDefaultDelegate(), 40, 14)
	l.Title = "Конфигурации"
	l.SetShowStatusBar(false)
	m.cfgList = l
}

func newAddInputs() []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	return []textinput.Model{
		ti("имя", ""),
		ti("server:port", ""),
		ti("token", ""),
		ti("routes (пусто=all)", ""),
		ti("exclude", ""),
	}
}

type connectedMsg struct{ stop func() }
type disconnectedMsg struct{}
type errMsg string
type logMsg string

func (m Model) Init() tea.Cmd {
	return nil
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "q", "esc":
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
			if m.tab == tabConfig && !m.adding {
				m.adding = true
				m.addInputs = newAddInputs()
				m.addFocus = 0
				m.addInputs[0].Focus()
				return m, nil
			}
		case "tab", "right":
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
			m.tab = tab((int(m.tab) + 1) % 4)
			return m, nil
		case "shift+tab", "left":
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
			m.tab = tab((int(m.tab) + 3) % 4)
			return m, nil
		case "enter":
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
					server := strings.TrimSpace(m.addInputs[1].Value())
					token := strings.TrimSpace(m.addInputs[2].Value())
					if name == "" {
						name = "default"
					}
					if server == "" || token == "" {
						m.err = "server и token обязательны"
					} else if err := config.Save(name, config.Config{Server: server, Token: token, Routes: strings.TrimSpace(m.addInputs[3].Value()), Exclude: strings.TrimSpace(m.addInputs[4].Value())}); err != nil {
						m.err = err.Error()
					} else {
						m.reloadCfgs()
						m.adding = false
						m.err = ""
					}
				}
				return m, nil
			}
			if m.tab == tabConfig && m.opts.ConnectFn != nil && len(m.cfgs) > 0 {
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
						cfg := m.cfgs[idx]
						return m, waitConnect(cfg, m.opts.ConnectFn)
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
		if len(m.logs) > 100 {
			m.logs = m.logs[len(m.logs)-100:]
		}
		m.logsMu.Unlock()
		return m, nil
	}

	var cmd tea.Cmd
	if m.adding && m.addFocus < len(m.addInputs) {
		m.addInputs[m.addFocus], cmd = m.addInputs[m.addFocus].Update(msg)
		return m, cmd
	}
	if m.tab == tabConfig {
		m.cfgList, cmd = m.cfgList.Update(msg)
	}
	return m, cmd
}

func waitConnect(cfg config.Config, fn ConnectFn) tea.Cmd {
	return func() tea.Msg {
		stop, err := fn(cfg)
		if err != nil {
			return errMsg(err.Error())
		}
		return connectedMsg{stop: stop}
	}
}

func (m Model) View() string {
	var b strings.Builder
	b.WriteString(titleStyle.Render("pteravpn"))
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

	switch m.tab {
	case tabHome:
		b.WriteString("Статус\n")
		switch m.status {
		case statusConnected:
			b.WriteString(statusStyle.Render("Ядро: Подключено"))
		case statusConnecting:
			b.WriteString("Ядро: Подключение...")
		default:
			b.WriteString("Ядро: Отключено")
		}
		b.WriteString("\n")
		if m.activeCfg != "" {
			idx := -1
			for i, n := range m.names {
				if n == m.activeCfg {
					idx = i
					break
				}
			}
			if idx >= 0 && idx < len(m.cfgs) {
				b.WriteString("Конфигурация: " + m.cfgs[idx].Server + "\n")
			}
		}
		b.WriteString("Tun: Вкл\n")
		if m.err != "" {
			b.WriteString(errStyle.Render("Ошибка: " + m.err))
		}
	case tabConfig:
		if m.adding {
			b.WriteString("Новая конфигурация\n\n")
			labels := []string{"Имя:", "Server:", "Token:", "Routes:", "Exclude:"}
			for i := range m.addInputs {
				b.WriteString(labels[i] + " ")
				b.WriteString(m.addInputs[i].View())
				b.WriteString("\n")
			}
			b.WriteString("\nTab/Enter - следующее  Esc - отмена")
			if m.err != "" {
				b.WriteString("\n")
				b.WriteString(errStyle.Render(m.err))
			}
		} else {
			b.WriteString(m.cfgList.View())
		}
	case tabLogs:
		m.logsMu.Lock()
		for _, line := range m.logs {
			b.WriteString(line)
			b.WriteString("\n")
		}
		m.logsMu.Unlock()
	case tabSettings:
		b.WriteString("q/Esc - выход\n")
	}

	b.WriteString("\n\nTab/Shift+Tab или ←/→ - вкладки  ")
	b.WriteString("q/Esc - выход  ")
	b.WriteString("Enter - подключиться/отключиться")
	if m.tab == tabConfig && !m.adding {
		b.WriteString("  ↑/↓ - выбор  N - добавить")
	}
	return b.String()
}
