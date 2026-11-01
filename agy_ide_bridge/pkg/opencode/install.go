package opencode

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// InstallState tracks a background install job.
type InstallState struct {
	Running bool   `json:"running"`
	Done    bool   `json:"done"`
	Error   string `json:"error,omitempty"`
	Log     string `json:"log,omitempty"`
	BinPath string `json:"bin_path,omitempty"`
}

var (
	installMu    sync.Mutex
	installState InstallState
)

// InstallDir returns where we place a managed opencode binary.
func InstallDir() string {
	home, _ := os.UserHomeDir()
	if home == "" {
		home = os.TempDir()
	}
	return filepath.Join(home, ".opencode", "bin")
}

// IsAndroid reports Termux / Android environment.
func IsAndroid() bool {
	if runtime.GOOS == "android" {
		return true
	}
	if os.Getenv("ANDROID_ROOT") != "" || os.Getenv("ANDROID_DATA") != "" {
		return true
	}
	if strings.Contains(strings.ToLower(os.Getenv("PREFIX")), "com.termux") {
		return true
	}
	// AntiGem / proot on phone sometimes still has android paths in PATH
	if strings.Contains(os.Getenv("PATH"), "/data/data/com.termux") {
		return true
	}
	return false
}

// InstallInfo is returned to the app for the install UI.
type InstallInfo struct {
	Installed bool         `json:"installed"`
	BinPath   string       `json:"bin_path,omitempty"`
	Version   string       `json:"version,omitempty"`
	Platform  string       `json:"platform"`
	Android   bool         `json:"android"`
	Hint      string       `json:"hint,omitempty"`
	Job       InstallState `json:"job"`
}

func (m *Manager) InstallInfo() InstallInfo {
	info := InstallInfo{
		Platform: runtime.GOOS + "/" + runtime.GOARCH,
		Android:  IsAndroid(),
		Job:      currentInstallState(),
	}
	bin := m.BinPath
	if bin == "" {
		bin = FindOpenCodeBinary()
	}
	if bin != "" {
		info.Installed = true
		info.BinPath = bin
		info.Version = probeVersion(bin)
		info.Hint = "Binary found. If status is not ONLINE, tap Refresh or restart the app."
	} else if IsAndroid() {
		info.Hint = "Binary missing. Tap Install OpenCode to download a native Android (aarch64) build into ~/.opencode/bin, then wait for ONLINE."
	} else {
		info.Hint = "Not found. Tap Install to download the official binary for this platform."
	}
	return info
}

func probeVersion(bin string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func currentInstallState() InstallState {
	installMu.Lock()
	defer installMu.Unlock()
	return installState
}

func setInstallState(s InstallState) {
	installMu.Lock()
	installState = s
	installMu.Unlock()
}

// StartInstall begins a background install (Linux official or Android native community build).
func (m *Manager) StartInstall() error {
	installMu.Lock()
	if installState.Running {
		installMu.Unlock()
		return fmt.Errorf("install already running")
	}
	installState = InstallState{Running: true, Log: "Starting install…\n"}
	installMu.Unlock()

	go m.runInstall()
	return nil
}

func (m *Manager) runInstall() {
	appendLog := func(line string) {
		installMu.Lock()
		installState.Log += line + "\n"
		installMu.Unlock()
	}
	fail := func(err error) {
		setInstallState(InstallState{
			Running: false,
			Done:    true,
			Error:   err.Error(),
			Log:     currentInstallState().Log + "ERROR: " + err.Error() + "\n",
		})
	}

	var asset *releaseAsset
	var err error
	if IsAndroid() {
		appendLog("Android detected — downloading native aarch64 OpenCode build…")
		asset, err = resolveAndroidReleaseAsset()
	} else {
		appendLog("Resolving official OpenCode release for " + runtime.GOOS + "/" + runtime.GOARCH + "…")
		asset, err = resolveReleaseAsset()
	}
	if err != nil {
		fail(err)
		return
	}
	appendLog("Downloading " + asset.Name + "…")

	dir := InstallDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(err)
		return
	}
	tmp := filepath.Join(dir, ".opencode.download")
	if err := downloadFile(asset.URL, tmp, appendLog); err != nil {
		fail(err)
		return
	}
	defer os.Remove(tmp)

	dest := filepath.Join(dir, "opencode")
	appendLog("Extracting / installing to " + dest + "…")
	if err := extractOrMove(tmp, asset.Name, dest); err != nil {
		fail(err)
		return
	}
	_ = os.Chmod(dest, 0o755)

	m.BinPath = dest
	appendLog("Installed: " + dest)
	if v := probeVersion(dest); v != "" {
		appendLog("Version: " + v)
	} else {
		appendLog("Warning: could not run --version (binary may still work for serve).")
	}
	appendLog("Restarting OpenCode serve…")
	if err := m.Restart(context.Background()); err != nil {
		appendLog("Restart warning: " + err.Error())
	} else {
		appendLog("Serve restart requested.")
	}
	setInstallState(InstallState{
		Running: false,
		Done:    true,
		BinPath: dest,
		Log:     currentInstallState().Log + "Done.\n",
	})
}

type releaseAsset struct {
	Name string
	URL  string
}

// resolveAndroidReleaseAsset picks a native Android/aarch64 build from
// Spectra010s/opencode-termux (Bionic — runs on Android without glibc).
func resolveAndroidReleaseAsset() (*releaseAsset, error) {
	api := "https://api.github.com/repos/Spectra010s/opencode-termux/releases/latest"
	req, err := http.NewRequest(http.MethodGet, api, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "antiGem-opencode-installer")
	client := &http.Client{Timeout: 45 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("GitHub API: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("GitHub releases API: HTTP %d", resp.StatusCode)
	}
	var rel struct {
		TagName string `json:"tag_name"`
		Assets  []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, err
	}

	// Prefer tar.gz android aarch64 source/binary packages.
	var candidates []releaseAsset
	for _, a := range rel.Assets {
		n := strings.ToLower(a.Name)
		if strings.Contains(n, "sha256") || strings.Contains(n, "checksum") {
			continue
		}
		if !(strings.Contains(n, "android") || strings.Contains(n, "termux") || strings.Contains(n, "aarch64") || strings.Contains(n, "arm64")) {
			continue
		}
		if strings.HasSuffix(n, ".tar.gz") || strings.HasSuffix(n, ".tgz") || strings.HasSuffix(n, ".zip") || !strings.Contains(n, ".") {
			candidates = append(candidates, releaseAsset{Name: a.Name, URL: a.BrowserDownloadURL})
		}
	}
	for _, c := range candidates {
		n := strings.ToLower(c.Name)
		if strings.Contains(n, "android") && (strings.Contains(n, "aarch64") || strings.Contains(n, "arm64")) &&
			(strings.HasSuffix(n, ".tar.gz") || strings.HasSuffix(n, ".tgz")) {
			return &c, nil
		}
	}
	if len(candidates) > 0 {
		return &candidates[0], nil
	}

	// Fallback: pinned known release if API shape changes
	fallback := "https://github.com/Spectra010s/opencode-termux/releases/download/v2.0.28/opencode-2.0.28-android-aarch64-source-bun140.tar.gz"
	return &releaseAsset{
		Name: "opencode-2.0.28-android-aarch64-source-bun140.tar.gz",
		URL:  fallback,
	}, nil
}

func resolveReleaseAsset() (*releaseAsset, error) {
	api := "https://api.github.com/repos/anomalyco/opencode/releases/latest"
	req, err := http.NewRequest(http.MethodGet, api, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "antiGem-opencode-installer")
	client := &http.Client{Timeout: 45 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("GitHub releases API: HTTP %d", resp.StatusCode)
	}
	var rel struct {
		Assets []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, err
	}

	want := releaseAssetName(runtime.GOOS, runtime.GOARCH)
	for _, a := range rel.Assets {
		n := strings.ToLower(a.Name)
		if strings.Contains(n, want) {
			return &releaseAsset{Name: a.Name, URL: a.BrowserDownloadURL}, nil
		}
	}
	for _, a := range rel.Assets {
		n := strings.ToLower(a.Name)
		if runtime.GOOS == "linux" && runtime.GOARCH == "arm64" && (strings.Contains(n, "linux-arm64") || strings.Contains(n, "linux_arm64") || strings.Contains(n, "aarch64")) {
			return &releaseAsset{Name: a.Name, URL: a.BrowserDownloadURL}, nil
		}
		if runtime.GOOS == "linux" && runtime.GOARCH == "amd64" && (strings.Contains(n, "linux-amd64") || strings.Contains(n, "linux_amd64") || strings.Contains(n, "linux-x64") || strings.Contains(n, "x86_64")) {
			return &releaseAsset{Name: a.Name, URL: a.BrowserDownloadURL}, nil
		}
	}
	return nil, fmt.Errorf("no release asset matching %s/%s (looked for %s)", runtime.GOOS, runtime.GOARCH, want)
}

func releaseAssetName(goos, goarch string) string {
	arch := goarch
	if arch == "amd64" {
		arch = "x64"
	}
	return "opencode-" + goos + "-" + arch
}

func downloadFile(url, dest string, log func(string)) error {
	client := &http.Client{Timeout: 10 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "antiGem-opencode-installer")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("download HTTP %d", resp.StatusCode)
	}
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	n, err := io.Copy(f, resp.Body)
	if err != nil {
		return err
	}
	log(fmt.Sprintf("Downloaded %d bytes", n))
	return nil
}

func extractOrMove(src, name, dest string) error {
	lower := strings.ToLower(name)
	switch {
	case strings.HasSuffix(lower, ".tar.gz") || strings.HasSuffix(lower, ".tgz"):
		return extractTarGzBinary(src, dest)
	default:
		return os.Rename(src, dest)
	}
}

func extractTarGzBinary(src, dest string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)

	candidate := dest + ".candidate"
	_ = os.Remove(candidate)
	foundPreferred := false

	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if hdr.Typeflag != tar.TypeReg {
			continue
		}
		base := filepath.Base(hdr.Name)
		lower := strings.ToLower(base)
		isPreferred := base == "opencode" || lower == "opencode.bin" ||
			(strings.HasPrefix(lower, "opencode") &&
				!strings.Contains(lower, ".json") &&
				!strings.Contains(lower, ".md") &&
				!strings.Contains(lower, ".txt") &&
				!strings.Contains(lower, ".sha"))
		isLarge := hdr.Size > 1_000_000
		if !isPreferred && !isLarge {
			continue
		}
		outPath := dest
		if !isPreferred {
			outPath = candidate
		}
		out, err := os.OpenFile(outPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, tr)
		out.Close()
		if err != nil {
			return err
		}
		if isPreferred {
			foundPreferred = true
			_ = os.Remove(candidate)
			return nil
		}
	}
	if foundPreferred {
		return nil
	}
	if st, err := os.Stat(candidate); err == nil && st.Size() > 0 {
		_ = os.Rename(candidate, dest)
		_ = os.Chmod(dest, 0o755)
		return nil
	}
	return fmt.Errorf("opencode binary not found inside archive")
}
