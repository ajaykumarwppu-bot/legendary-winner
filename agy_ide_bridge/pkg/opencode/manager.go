// Package opencode runs the OpenCode headless server (`opencode serve`) for the app.
//
// OpenCode is managed at Antigravity-level depth: one long-lived `opencode serve` process,
// HTTP + SSE for sessions/events, status reporting, and clean disable via --no-opencode.
package opencode

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"gemini-server/pkg/config"
)

const (
	StatusIdle     = "idle"
	StatusStarting = "starting"
	StatusOnline   = "online"
	StatusError    = "error"
	StatusStopped  = "stopped"

	defaultPort = "1240"
	healthPath  = "/global/health"
)

// Manager supervises a single background `opencode serve` process.
type Manager struct {
	Port         string // e.g. "1240"
	WorkspaceDir string
	BinPath      string // path to opencode binary (empty = look up)
	// Disabled: OpenCode is turned off in the app (--no-opencode). Never start the process.
	Disabled bool

	OnStatusChange func(status string, errorMsg string, logs []string)

	cmd         *exec.Cmd
	cancel      context.CancelFunc
	status      string
	lastError   string
	isRunning   bool
	processDone chan struct{}
	recentLogs  []string
	mu          sync.Mutex
}

// FindOpenCodeBinary looks for an existing opencode binary in common locations and PATH.
func FindOpenCodeBinary() string {
	home, _ := os.UserHomeDir()
	candidates := []string{}

	if home != "" {
		candidates = append(candidates,
			filepath.Join(home, ".opencode", "bin", "opencode"),
			filepath.Join(home, ".local", "bin", "opencode"),
			filepath.Join(home, "usr", "bin", "opencode"),
		)
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		candidates = append(candidates, filepath.Join(prefix, "bin", "opencode"))
	}
	if p := config.SafeLookPath("opencode"); p != "" {
		candidates = append(candidates, p)
	}

	for _, c := range candidates {
		if c == "" {
			continue
		}
		abs, err := filepath.Abs(c)
		if err != nil {
			abs = c
		}
		abs = filepath.Clean(abs)
		if fi, err := os.Stat(abs); err == nil && !fi.IsDir() {
			return abs
		}
	}
	return ""
}

// ResolveBinary returns the binary to use. Prefer explicit path, otherwise discover.
func ResolveBinary(customPath string) (string, error) {
	if customPath != "" {
		expanded := config.ExpandHome(customPath)
		clean, err := filepath.Abs(expanded)
		if err != nil {
			clean = expanded
		}
		clean = filepath.Clean(clean)
		if fi, err := os.Stat(clean); err != nil || fi.IsDir() {
			return "", fmt.Errorf("opencode binary not found at %s", clean)
		}
		return clean, nil
	}
	if found := FindOpenCodeBinary(); found != "" {
		return found, nil
	}
	return "", fmt.Errorf("opencode binary not found (install from Settings or put it on PATH)")
}

// Status returns the current status string.
func (m *Manager) Status() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status
}

// LastError returns the last error message, if any.
func (m *Manager) LastError() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastError
}

// IsRunning reports whether the process is considered running.
func (m *Manager) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.isRunning
}

// BaseURL returns the local HTTP base for the serve process.
func (m *Manager) BaseURL() string {
	port := m.Port
	if port == "" {
		port = defaultPort
	}
	return "http://127.0.0.1:" + port
}

func (m *Manager) setStatus(status, errMsg string) {
	m.mu.Lock()
	m.status = status
	if errMsg != "" {
		m.lastError = errMsg
	}
	logs := append([]string(nil), m.recentLogs...)
	cb := m.OnStatusChange
	m.mu.Unlock()
	if cb != nil {
		cb(status, errMsg, logs)
	}
}

func (m *Manager) appendLog(line string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.recentLogs = append(m.recentLogs, line)
	if len(m.recentLogs) > 80 {
		m.recentLogs = m.recentLogs[len(m.recentLogs)-80:]
	}
}

// Start launches `opencode serve` if not disabled and not already running.
func (m *Manager) Start(ctx context.Context) error {
	if m.Disabled {
		m.setStatus(StatusStopped, "OpenCode disabled (--no-opencode)")
		return nil
	}

	m.mu.Lock()
	if m.isRunning {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()

	bin := m.BinPath
	if bin == "" {
		resolved, err := ResolveBinary("")
		if err != nil {
			m.setStatus(StatusError, err.Error())
			return err
		}
		bin = resolved
		m.BinPath = bin
	}

	port := m.Port
	if port == "" {
		port = defaultPort
		m.Port = port
	}

	m.setStatus(StatusStarting, "")

	runCtx, cancel := context.WithCancel(ctx)
	m.cancel = cancel

	args := []string{
		"serve",
		"--port", port,
		"--hostname", "127.0.0.1",
	}

	cmd := exec.CommandContext(runCtx, bin, args...)
	cmd.Dir = m.WorkspaceDir
	cmd.Env = os.Environ()
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		cancel()
		m.setStatus(StatusError, "stdout pipe: "+err.Error())
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		cancel()
		m.setStatus(StatusError, "stderr pipe: "+err.Error())
		return err
	}

	if err := cmd.Start(); err != nil {
		cancel()
		m.setStatus(StatusError, "start failed: "+err.Error())
		return err
	}

	m.mu.Lock()
	m.cmd = cmd
	m.isRunning = true
	m.processDone = make(chan struct{})
	m.mu.Unlock()

	// Drain logs
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := stdout.Read(buf)
			if n > 0 {
				for _, line := range strings.Split(strings.TrimRight(string(buf[:n]), "\n"), "\n") {
					if line != "" {
						m.appendLog("[out] " + line)
					}
				}
			}
			if err != nil {
				break
			}
		}
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := stderr.Read(buf)
			if n > 0 {
				for _, line := range strings.Split(strings.TrimRight(string(buf[:n]), "\n"), "\n") {
					if line != "" {
						m.appendLog("[err] " + line)
					}
				}
			}
			if err != nil {
				break
			}
		}
	}()

	// Wait for process exit
	go func() {
		err := cmd.Wait()
		m.mu.Lock()
		m.isRunning = false
		m.cmd = nil
		if m.processDone != nil {
			close(m.processDone)
			m.processDone = nil
		}
		m.mu.Unlock()
		if err != nil && runCtx.Err() == nil {
			m.setStatus(StatusError, "process exited: "+err.Error())
		} else {
			m.setStatus(StatusStopped, "")
		}
	}()

	// Health wait
	go m.waitUntilOnline(runCtx, port)

	return nil
}

func (m *Manager) waitUntilOnline(ctx context.Context, port string) {
	client := &http.Client{Timeout: 2 * time.Second}
	url := "http://127.0.0.1:" + port + healthPath
	deadline := time.Now().Add(25 * time.Second)

	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return
		default:
		}
		resp, err := client.Get(url)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 500 {
				m.setStatus(StatusOnline, "")
				return
			}
		}
		// Also accept if the port is listening (some versions may not have /global/health yet)
		if conn, err := net.DialTimeout("tcp", "127.0.0.1:"+port, 500*time.Millisecond); err == nil {
			conn.Close()
			// Give it a moment then mark online
			time.Sleep(400 * time.Millisecond)
			m.setStatus(StatusOnline, "")
			return
		}
		time.Sleep(600 * time.Millisecond)
	}
	m.setStatus(StatusError, "OpenCode serve did not become healthy in time")
}

// Stop terminates the serve process.
func (m *Manager) Stop() {
	m.mu.Lock()
	cancel := m.cancel
	cmd := m.cmd
	done := m.processDone
	m.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if cmd != nil && cmd.Process != nil {
		// Kill process group
		_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
		time.AfterFunc(3*time.Second, func() {
			_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		})
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(4 * time.Second):
		}
	}
	m.setStatus(StatusStopped, "")
}

// Restart stops then starts again.
func (m *Manager) Restart(ctx context.Context) error {
	m.Stop()
	time.Sleep(500 * time.Millisecond)
	return m.Start(ctx)
}
