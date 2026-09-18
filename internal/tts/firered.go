package tts

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// FireRed talks to the Python worker in worker/, which wraps FireRedTTS3.
//
// The wire format is deliberately dull: one JSON request, one JSON response
// with base64 audio. Segments are short, so the simplicity is worth more than
// streaming would be — the streaming that matters happens a level up, between
// segments (D-009).
type FireRed struct {
	BaseURL string
	HTTP    *http.Client
	// Version is reported in results so a quality regression can be pinned to
	// a model change.
	Version string
}

// NewFireRed returns a client with timeouts sized for segment synthesis.
func NewFireRed(baseURL string) *FireRed {
	return &FireRed{
		BaseURL: baseURL,
		HTTP: &http.Client{
			// A segment is seconds of audio; a minute of wall clock means the
			// worker is wedged, and waiting longer only delays the fallback.
			Timeout: 60 * time.Second,
		},
	}
}

func (f *FireRed) Name() string { return "firered-tts3" }

type fireRedRequest struct {
	Text        string  `json:"text"`
	Language    string  `json:"language"`
	PromptAudio string  `json:"prompt_audio_uri"`
	PromptText  string  `json:"prompt_text"`
	PrevTail    string  `json:"prev_tail,omitempty"`
	Style       string  `json:"style,omitempty"`
	Speed       float64 `json:"speed,omitempty"`
}

type fireRedResponse struct {
	AudioB64   string  `json:"audio_b64"`
	SampleRate int     `json:"sample_rate"`
	DurationS  float64 `json:"duration_s"`
	Version    string  `json:"model_version"`
	Error      string  `json:"error"`
}

func (f *FireRed) Synthesize(ctx context.Context, req Request) (*Result, error) {
	if err := req.validate(); err != nil {
		return nil, err
	}
	if err := CheckLanguage(req.Language); err != nil {
		return nil, err
	}

	body, err := json.Marshal(fireRedRequest{
		Text:        req.Text,
		Language:    primary(req.Language),
		PromptAudio: req.Ref.AudioURI,
		PromptText:  req.Ref.Transcript,
		PrevTail:    req.PrevTail,
		Style:       req.Style,
		Speed:       req.Speed,
	})
	if err != nil {
		return nil, fmt.Errorf("tts: marshal: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		f.BaseURL+"/synthesize", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("tts: build request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := f.client().Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("tts: call worker: %w", err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return nil, fmt.Errorf("tts: read response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("tts: worker returned %s: %s", resp.Status, truncate(raw, 256))
	}

	var out fireRedResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("tts: decode response: %w", err)
	}
	if out.Error != "" {
		return nil, fmt.Errorf("tts: worker error: %s", out.Error)
	}

	audio, err := base64.StdEncoding.DecodeString(out.AudioB64)
	if err != nil {
		return nil, fmt.Errorf("tts: decode audio: %w", err)
	}
	if len(audio) == 0 {
		return nil, fmt.Errorf("tts: worker returned no audio")
	}

	version := out.Version
	if version == "" {
		version = f.Version
	}
	return &Result{
		Audio:         audio,
		SampleRate:    out.SampleRate,
		Duration:      time.Duration(out.DurationS * float64(time.Second)),
		Engine:        f.Name(),
		EngineVersion: version,
	}, nil
}

func (f *FireRed) client() *http.Client {
	if f.HTTP != nil {
		return f.HTTP
	}
	return http.DefaultClient
}

func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "…"
}
