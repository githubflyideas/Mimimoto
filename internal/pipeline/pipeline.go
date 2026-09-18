// Package pipeline renders a story segment by segment.
//
// Rendering is incremental on purpose (docs/DECISIONS.md D-009). Fifteen
// minutes of audio takes minutes to synthesise; if delivery waited for the
// whole file, parents would face a recording cutoff hours before bedtime, and
// that cutoff is exactly the friction the product exists to remove. Instead the
// first segment is enough to start playback, and the rest is produced while the
// child listens.
//
// The consequence is a race the renderer has to win: audio must be produced
// faster than it is consumed. Outcome.RealtimeFactor reports the margin, and a
// value at or below 1 means the buffer drains and the child hears a gap — a
// capacity problem, visible as a number, before it is a complaint.
package pipeline

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/githubflyideas/mimimoto/internal/domain"
	"github.com/githubflyideas/mimimoto/internal/schedule"
	"github.com/githubflyideas/mimimoto/internal/tts"
)

// Job is one story to render in one parent's voice.
type Job struct {
	Plan  schedule.Plan
	Story domain.Story
	Ref   tts.Reference
	// Language for synthesis; may differ from the reference language, though
	// quality is better when they match.
	Language string
	Speed    float64
}

// SegmentResult is one rendered segment, delivered in index order.
type SegmentResult struct {
	Index int
	Total int
	Audio *tts.Result
	// Elapsed is measured from the start of the render, so the first result's
	// value is the latency that decides whether playback can begin on time.
	Elapsed time.Duration
	// Attempts taken, for capacity and reliability monitoring.
	Attempts int
}

// Outcome summarises a render.
type Outcome struct {
	Segments  int
	Completed int

	// FirstSegmentLatency is the number that matters for the promise that a
	// parent can record at 20:25 for a 20:30 delivery.
	FirstSegmentLatency time.Duration

	// AudioDuration is how much audio was produced; Wall is how long it took.
	AudioDuration time.Duration
	Wall          time.Duration

	// RealtimeFactor is AudioDuration/Wall. Above 1 means generation outruns
	// playback and the buffer grows. At or below 1 the child hears gaps.
	RealtimeFactor float64

	Engine  string
	Retries int
}

// Complete reports whether every segment rendered.
func (o Outcome) Complete() bool { return o.Segments > 0 && o.Completed == o.Segments }

// KeepsUp reports whether generation outran playback with margin to spare.
func (o Outcome) KeepsUp() bool { return o.RealtimeFactor > 1.2 }

var (
	// ErrUnapprovedSource enforces D-002 in code rather than in policy: text
	// may only be spoken in a parent's voice if a human chose or wrote it.
	ErrUnapprovedSource = errors.New("pipeline: story source is not approved for synthesis")
	ErrNoVoice          = errors.New("pipeline: plan has no voice to synthesise with")
	ErrAborted          = errors.New("pipeline: aborted by consumer")
)

// Renderer turns jobs into audio.
type Renderer struct {
	Synth tts.Synthesizer

	// Retries is the number of extra attempts per segment. Transient GPU and
	// network failures are common enough that zero retries would push families
	// to the fallback tier for no good reason.
	Retries int
	// Backoff is the base delay between attempts; it doubles each time.
	Backoff time.Duration

	// Now is injectable for tests.
	Now func() time.Time
	// sleep is injectable for tests so backoff does not cost real time.
	sleep func(context.Context, time.Duration) error
}

// NewRenderer returns a renderer with sensible retry behaviour.
func NewRenderer(s tts.Synthesizer) *Renderer {
	return &Renderer{Synth: s, Retries: 2, Backoff: 500 * time.Millisecond}
}

func (r *Renderer) now() time.Time {
	if r.Now != nil {
		return r.Now()
	}
	return time.Now()
}

func (r *Renderer) wait(ctx context.Context, d time.Duration) error {
	if r.sleep != nil {
		return r.sleep(ctx, d)
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Render synthesises every segment in order, calling emit as each one
// completes. It blocks until the story is done, emit returns an error, or ctx
// is cancelled.
//
// emit is called from the calling goroutine, in index order, exactly once per
// segment. Returning an error from emit stops the render — that is how a
// consumer whose delivery window has passed releases the GPU.
func (r *Renderer) Render(ctx context.Context, job Job, emit func(SegmentResult) error) (Outcome, error) {
	out := Outcome{Segments: len(job.Story.Segments)}
	if r.Synth != nil {
		out.Engine = r.Synth.Name()
	}

	// The red line comes first, before any other validation: nothing reaches
	// synthesis unless a human chose or wrote it (D-002). Checking provenance
	// ahead of everything else means a story with unapproved text is refused
	// for that reason and reported as that reason, rather than being masked by
	// whatever else happens to be wrong with it.
	if !job.Story.Source.Valid() {
		return out, fmt.Errorf("%w: %q", ErrUnapprovedSource, job.Story.Source)
	}
	if err := job.Story.Validate(); err != nil {
		return out, err
	}
	if job.Plan.VoiceID == "" {
		return out, ErrNoVoice
	}
	if r.Synth == nil {
		return out, errors.New("pipeline: no synthesizer configured")
	}

	start := r.now()
	var prevTail string

	for i, seg := range job.Story.Segments {
		req := tts.Request{
			Text:     seg.Text,
			Language: job.Language,
			Ref:      job.Ref,
			PrevTail: prevTail,
			Style:    seg.Role,
			Speed:    job.Speed,
		}

		res, attempts, err := r.synthesizeWithRetry(ctx, req)
		out.Retries += attempts - 1
		if err != nil {
			return r.finish(out, start), fmt.Errorf("pipeline: segment %d: %w", i, err)
		}

		elapsed := r.now().Sub(start)
		if i == 0 {
			out.FirstSegmentLatency = elapsed
		}
		out.Completed++
		out.AudioDuration += res.Duration

		if err := emit(SegmentResult{
			Index:    i,
			Total:    len(job.Story.Segments),
			Audio:    res,
			Elapsed:  elapsed,
			Attempts: attempts,
		}); err != nil {
			return r.finish(out, start), fmt.Errorf("%w: %v", ErrAborted, err)
		}

		prevTail = tailSentence(seg.Text)
	}

	return r.finish(out, start), nil
}

func (r *Renderer) finish(out Outcome, start time.Time) Outcome {
	out.Wall = r.now().Sub(start)
	if out.Wall > 0 {
		out.RealtimeFactor = out.AudioDuration.Seconds() / out.Wall.Seconds()
	}
	return out
}

func (r *Renderer) synthesizeWithRetry(ctx context.Context, req tts.Request) (*tts.Result, int, error) {
	backoff := r.Backoff
	if backoff <= 0 {
		backoff = 500 * time.Millisecond
	}

	var lastErr error
	for attempt := 1; attempt <= r.Retries+1; attempt++ {
		if err := ctx.Err(); err != nil {
			return nil, attempt, err
		}
		res, err := r.Synth.Synthesize(ctx, req)
		if err == nil {
			return res, attempt, nil
		}
		lastErr = err
		// A request the engine will never accept is not worth a second GPU
		// slot; fail now so the fallback tier takes over sooner.
		if tts.Terminal(err) {
			return nil, attempt, err
		}
		if attempt <= r.Retries {
			if werr := r.wait(ctx, backoff); werr != nil {
				return nil, attempt, werr
			}
			backoff *= 2
		}
	}
	return nil, r.Retries + 1, lastErr
}

// tailSentence returns the last sentence of a segment, used to carry prosody
// across the segment boundary.
//
// Splitting on terminators from every script we ship matters: a Japanese
// segment ending in 。 or a Thai one with no terminator at all must still hand
// something useful to the next call.
func tailSentence(text string) string {
	const terminators = ".!?。！？…"
	const maxTailRunes = 80

	trimmed := strings.TrimSpace(text)
	if trimmed == "" {
		return ""
	}
	tail := trimmed
	// Drop any trailing terminator so the search finds the *previous* one.
	body := strings.TrimRight(trimmed, terminators)
	if idx := strings.LastIndexAny(body, terminators); idx >= 0 {
		// idx is the byte offset of a terminator rune, which may be multi-byte.
		_, size := utf8.DecodeRuneInString(body[idx:])
		if t := strings.TrimSpace(trimmed[idx+size:]); t != "" {
			tail = t
		}
	}
	// A whole paragraph is no better as conditioning than its end, and costs
	// context on every call.
	if r := []rune(tail); len(r) > maxTailRunes {
		tail = string(r[len(r)-maxTailRunes:])
	}
	return tail
}
