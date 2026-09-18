// Package tts is the synthesis boundary.
//
// The engine behind this interface is expected to change. FireRedTTS3 is the
// current choice on quality grounds (see docs/DECISIONS.md D-010), but its
// licensing is unresolved — the repository is Apache-2.0 while the model card
// restricts voice cloning to academic research. Keeping synthesis behind a
// narrow interface is what makes that a procurement problem rather than a
// rewrite.
package tts

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// Reference is the conditioning material for zero-shot cloning: a short
// recording of the parent plus its transcript.
//
// Both matter. Zero-shot models condition on reference text as well as
// reference audio, and a missing or wrong transcript degrades output in a way
// that is easy to mistake for a model problem.
type Reference struct {
	AudioURI   string
	Transcript string
	// Language of the reference recording. Cloning is noticeably better when
	// this matches the synthesis language, so the caller should prefer a
	// same-language reference when it has one.
	Language string
}

func (r Reference) validate() error {
	if r.AudioURI == "" {
		return errors.New("tts: reference has no audio")
	}
	if r.Transcript == "" {
		return errors.New("tts: reference has no transcript")
	}
	return nil
}

// Request is one segment of synthesis.
type Request struct {
	Text     string
	Language string // BCP-47; the adapter maps it to the engine's tag
	Ref      Reference

	// PrevTail is the last sentence of the preceding segment. Passing it lets
	// the engine continue the prosodic contour rather than restarting it, which
	// is what stops segment-wise generation from sounding like a sequence of
	// separate takes (D-009).
	PrevTail string

	// Style is an optional hint drawn from the story's own markup (a character
	// line, a whisper). It never changes whose voice is used.
	Style string

	// Speed multiplies the default rate. Bedtime stories want slightly slow.
	Speed float64
}

func (r Request) validate() error {
	if r.Text == "" {
		return errors.New("tts: empty text")
	}
	if r.Language == "" {
		return errors.New("tts: empty language")
	}
	return r.Ref.validate()
}

// Result is synthesised audio.
type Result struct {
	// Audio is WAV bytes. Segments are small enough (a few seconds to a
	// minute) that holding one in memory is fine; whole stories are never
	// assembled in memory.
	Audio      []byte
	SampleRate int
	Duration   time.Duration
	// Engine and EngineVersion are recorded so a regression can be traced to a
	// model change.
	Engine        string
	EngineVersion string
}

// Synthesizer renders one segment.
//
// Implementations must honour ctx: a job whose delivery window has passed is
// cancelled, and continuing to occupy a GPU for it delays someone else's
// bedtime.
type Synthesizer interface {
	Synthesize(ctx context.Context, req Request) (*Result, error)
	// Name identifies the backend in logs and metrics.
	Name() string
}

// ErrUnsupportedLanguage is returned when the engine cannot render a language.
// Callers treat it as terminal — retrying will not help.
var ErrUnsupportedLanguage = errors.New("tts: unsupported language")

// Terminal reports whether an error is worth retrying. Anything that is a
// property of the request rather than of the moment is terminal.
func Terminal(err error) bool {
	return errors.Is(err, ErrUnsupportedLanguage) ||
		errors.Is(err, context.Canceled) ||
		errors.Is(err, context.DeadlineExceeded)
}

// Supported lists the languages FireRedTTS3 handles, which is the set the
// product may advertise. Kept here rather than in config because shipping a
// language the engine cannot speak is a support incident, not a setting.
var Supported = map[string]bool{
	"ar": true, "cs": true, "de": true, "el": true, "en": true,
	"es": true, "fi": true, "fr": true, "hi": true, "id": true,
	"it": true, "ja": true, "ko": true, "nl": true, "pl": true,
	"pt": true, "ro": true, "ru": true, "th": true, "tr": true,
	"uk": true, "vi": true, "yue": true, "zh": true,
}

// CheckLanguage validates a BCP-47 tag against the engine's coverage, matching
// on the primary subtag so that "pt-BR" and "zh-Hans" resolve correctly.
func CheckLanguage(tag string) error {
	if primary(tag) == "" {
		return fmt.Errorf("%w: %q", ErrUnsupportedLanguage, tag)
	}
	return nil
}

func primary(tag string) string {
	for i := range len(tag) {
		if tag[i] == '-' || tag[i] == '_' {
			tag = tag[:i]
			break
		}
	}
	lower := make([]byte, len(tag))
	for i := range len(tag) {
		c := tag[i]
		if c >= 'A' && c <= 'Z' {
			c += 'a' - 'A'
		}
		lower[i] = c
	}
	s := string(lower)
	if Supported[s] {
		return s
	}
	return ""
}
