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
	if os.Getenv("ANDROID_ROOT") != "" || os.Getenv("ANDROID_DATA") != "" {
		return true
	}
	if strings.Contains(strings.ToLower(os.Getenv("PREFIX")), "com.termux") {
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
	} else if IsAndroid() {
		info.Hint = "On Termux/Android use a community opencode build (e.g. pkg install opencode if available), or place a binary on PATH. Official Linux binaries need glibc and will not run on Android."
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

// StartInstall begins a background install of the official Linux binary (non-Android).
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

	if IsAndroid() {
		fail(fmt.Errorf("official OpenCode binaries are Linux/glibc and do not run on Android. Install a Termux community build or place a native binary on PATH, then restart the bridge"))
		return
	}

	asset, err := resolveReleaseAsset()
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
	}
	// restart serve with the new binary when possible
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

func resolveReleaseAsset() (*releaseAsset, error) {
	api := "https://api.github.com/repos/anomalyco/opencode/releases/latest"
	req, err := http.NewRequest(http.MethodGet, api, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "antiGem-opencode-installer")
	resp, err := http.DefaultClient.Do(req)
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
	resp, err := http.Get(url)
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
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		base := filepath.Base(hdr.Name)
		if hdr.Typeflag == tar.TypeReg && (base == "opencode" || strings.HasPrefix(base, "opencode")) {
			out, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
			if err != nil {
				return err
			}
			_, err = io.Copy(out, tr)
			out.Close()
			return err
		}
	}
	return fmt.Errorf("opencode binary not found inside archive")
}
