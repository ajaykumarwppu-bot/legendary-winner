package opencode

// StatusSnapshot is returned to the Android app for the OpenCode agent.
type StatusSnapshot struct {
	Status    string   `json:"status"`
	Error     string   `json:"error,omitempty"`
	Port      string   `json:"port,omitempty"`
	BaseURL   string   `json:"base_url,omitempty"`
	BinPath   string   `json:"bin_path,omitempty"`
	Logs      []string `json:"logs,omitempty"`
	Disabled  bool     `json:"disabled"`
}

// HealthResponse matches OpenCode's /global/health when available.
type HealthResponse struct {
	Healthy bool   `json:"healthy"`
	Version string `json:"version,omitempty"`
}
