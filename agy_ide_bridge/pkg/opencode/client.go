package opencode

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client talks to a local `opencode serve` instance.
type Client struct {
	BaseURL    string
	HTTPClient *http.Client
}

func NewClient(baseURL string) *Client {
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		HTTPClient: &http.Client{
			Timeout: 10 * time.Minute, // long prompts
		},
	}
}

func (c *Client) do(method, path string, body any) ([]byte, int, error) {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, 0, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, c.BaseURL+path, rdr)
	if err != nil {
		return nil, 0, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	if resp.StatusCode >= 400 {
		return data, resp.StatusCode, fmt.Errorf("opencode %s %s: %s", method, path, string(data))
	}
	return data, resp.StatusCode, nil
}

// Session is a minimal OpenCode session object.
type Session struct {
	ID        string `json:"id"`
	Title     string `json:"title,omitempty"`
	ParentID  string `json:"parentID,omitempty"`
	Time      any    `json:"time,omitempty"`
	UpdatedAt any    `json:"updatedAt,omitempty"`
}

// TextPart is a message part for prompts.
type TextPart struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

// PromptBody is the body for POST /session/:id/message
type PromptBody struct {
	Parts []TextPart `json:"parts"`
}

// MessageResult is the response from POST /session/:id/message
type MessageResult struct {
	Info  json.RawMessage `json:"info"`
	Parts []struct {
		Type string `json:"type"`
		Text string `json:"text,omitempty"`
	} `json:"parts"`
}

func (c *Client) Health() error {
	_, _, err := c.do(http.MethodGet, "/global/health", nil)
	if err != nil {
		// older builds may not have this path; try root
		_, code, err2 := c.do(http.MethodGet, "/session", nil)
		if err2 != nil && code == 0 {
			return err
		}
		return nil
	}
	return nil
}

func (c *Client) ListSessions() ([]Session, error) {
	data, _, err := c.do(http.MethodGet, "/session", nil)
	if err != nil {
		return nil, err
	}
	var sessions []Session
	if err := json.Unmarshal(data, &sessions); err != nil {
		return nil, err
	}
	return sessions, nil
}

func (c *Client) CreateSession(title string) (*Session, error) {
	body := map[string]any{}
	if title != "" {
		body["title"] = title
	}
	data, _, err := c.do(http.MethodPost, "/session", body)
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

func (c *Client) DeleteSession(id string) error {
	_, _, err := c.do(http.MethodDelete, "/session/"+id, nil)
	return err
}

func (c *Client) AbortSession(id string) error {
	_, _, err := c.do(http.MethodPost, "/session/"+id+"/abort", nil)
	return err
}

// Prompt sends a text message and waits for the full assistant response.
func (c *Client) Prompt(sessionID, text string) (*MessageResult, error) {
	body := PromptBody{
		Parts: []TextPart{{Type: "text", Text: text}},
	}
	data, _, err := c.do(http.MethodPost, "/session/"+sessionID+"/message", body)
	if err != nil {
		return nil, err
	}
	var result MessageResult
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ListMessages returns messages for a session.
func (c *Client) ListMessages(sessionID string) (json.RawMessage, error) {
	data, _, err := c.do(http.MethodGet, "/session/"+sessionID+"/message", nil)
	if err != nil {
		return nil, err
	}
	return data, nil
}

// AssistantText extracts concatenated text parts from a MessageResult.
func (r *MessageResult) AssistantText() string {
	var b strings.Builder
	for _, p := range r.Parts {
		if p.Type == "text" && p.Text != "" {
			if b.Len() > 0 {
				b.WriteByte('\n')
			}
			b.WriteString(p.Text)
		}
	}
	return b.String()
}
