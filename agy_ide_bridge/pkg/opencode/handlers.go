package opencode

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// ServeHTTP routes /api/opencode/* for the Android app.
//
//	GET  /api/opencode/status
//	GET  /api/opencode/sessions
//	POST /api/opencode/sessions              body: {"title":"..."}
//	DELETE /api/opencode/sessions/{id}
//	POST /api/opencode/sessions/{id}/message body: {"content":"..."}
//	POST /api/opencode/sessions/{id}/abort
//	GET  /api/opencode/sessions/{id}/messages
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if m.Disabled {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"OpenCode is turned off in the app"}`))
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/opencode")
	path = strings.TrimPrefix(path, "/")

	switch {
	case path == "status" && r.Method == http.MethodGet:
		m.handleStatus(w, r)
	case path == "install" && r.Method == http.MethodGet:
		_ = json.NewEncoder(w).Encode(m.InstallInfo())
	case path == "install" && r.Method == http.MethodPost:
		m.handleStartInstall(w, r)
	case path == "sessions" && r.Method == http.MethodGet:
		m.handleListSessions(w, r)
	case path == "sessions" && r.Method == http.MethodPost:
		m.handleCreateSession(w, r)
	case strings.HasPrefix(path, "sessions/") && strings.HasSuffix(path, "/message") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(path, "sessions/"), "/message")
		m.handlePrompt(w, r, id)
	case strings.HasPrefix(path, "sessions/") && strings.HasSuffix(path, "/abort") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(path, "sessions/"), "/abort")
		m.handleAbort(w, r, id)
	case strings.HasPrefix(path, "sessions/") && strings.HasSuffix(path, "/messages") && r.Method == http.MethodGet:
		id := strings.TrimSuffix(strings.TrimPrefix(path, "sessions/"), "/messages")
		m.handleListMessages(w, r, id)
	case strings.HasPrefix(path, "sessions/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(path, "sessions/")
		m.handleDeleteSession(w, r, id)
	default:
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"success":false,"error":"unknown opencode endpoint"}`))
	}
}

func (m *Manager) handleStartInstall(w http.ResponseWriter, _ *http.Request) {
	if err := m.StartInstall(); err != nil {
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "job": currentInstallState()})
}

func (m *Manager) client() *Client {
	return NewClient(m.BaseURL())
}

func (m *Manager) handleStatus(w http.ResponseWriter, _ *http.Request) {
	info := m.InstallInfo()
	snap := StatusSnapshot{
		Status:   m.Status(),
		Error:    m.LastError(),
		Port:     m.Port,
		BaseURL:  m.BaseURL(),
		BinPath:  info.BinPath,
		Disabled: m.Disabled,
	}
	// include install details for the Settings UI
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status":    snap.Status,
		"error":     snap.Error,
		"port":      snap.Port,
		"base_url":  snap.BaseURL,
		"bin_path":  snap.BinPath,
		"disabled":  snap.Disabled,
		"installed": info.Installed,
		"version":   info.Version,
		"platform":  info.Platform,
		"android":   info.Android,
		"hint":      info.Hint,
		"job":       info.Job,
	})
}

func (m *Manager) handleListSessions(w http.ResponseWriter, _ *http.Request) {
	if m.Status() != StatusOnline {
		w.WriteHeader(http.StatusServiceUnavailable)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "OpenCode serve is not online", "status": m.Status()})
		return
	}
	sessions, err := m.client().ListSessions()
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "sessions": sessions})
}

func (m *Manager) handleCreateSession(w http.ResponseWriter, r *http.Request) {
	if m.Status() != StatusOnline {
		w.WriteHeader(http.StatusServiceUnavailable)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "OpenCode serve is not online", "status": m.Status()})
		return
	}
	var body struct {
		Title string `json:"title"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	session, err := m.client().CreateSession(body.Title)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "session": session})
}

func (m *Manager) handleDeleteSession(w http.ResponseWriter, _ *http.Request, id string) {
	if id == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"success":false,"error":"missing session id"}`))
		return
	}
	if err := m.client().DeleteSession(id); err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
}

func (m *Manager) handleAbort(w http.ResponseWriter, _ *http.Request, id string) {
	if id == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"success":false,"error":"missing session id"}`))
		return
	}
	if err := m.client().AbortSession(id); err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
}

func (m *Manager) handlePrompt(w http.ResponseWriter, r *http.Request, id string) {
	if id == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"success":false,"error":"missing session id"}`))
		return
	}
	if m.Status() != StatusOnline {
		w.WriteHeader(http.StatusServiceUnavailable)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "OpenCode serve is not online", "status": m.Status()})
		return
	}
	var body struct {
		Content string `json:"content"`
	}
	raw, _ := io.ReadAll(r.Body)
	_ = json.Unmarshal(raw, &body)
	if body.Content == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"success":false,"error":"content is required"}`))
		return
	}
	result, err := m.client().Prompt(id, body.Content)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"text":    result.AssistantText(),
		"parts":   result.Parts,
		"info":    result.Info,
	})
}

func (m *Manager) handleListMessages(w http.ResponseWriter, _ *http.Request, id string) {
	if id == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"success":false,"error":"missing session id"}`))
		return
	}
	data, err := m.client().ListMessages(id)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"success":true,"messages":`))
	_, _ = w.Write(data)
	_, _ = w.Write([]byte(`}`))
}
