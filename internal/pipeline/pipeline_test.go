package pipeline

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/githubflyideas/mimimoto/internal/domain"
	"github.com/githubflyideas/mimimoto/internal/schedule"
	"github.com/githubflyideas/mimimoto/internal/tts"
)

func story(n int, src domain.StorySource) domain.Story {
	segs := make([]domain.Segment, n)
	for i := range segs {
		segs[i] = domain.Segment{
			Index: i,
			Text:  "小豆子走进了森林。树很高，风很轻。",
		}
	}
	return domain.Story{
		ID:       "story-1",
		Title:    "三只小猪",
		Language: "zh",
		AgeBand:  domain.AgePreschool,
		Source:   src,
		Segments: segs,
	}
}

func job(n int) Job {
	return Job{
		Plan: schedule.Plan{
			VoiceID:   "v1",
			StoryID:   "story-1",
			Tier:      schedule.TierFresh,
			DeliverAt: time.Date(2026, 9, 19, 11, 30, 0, 0, time.UTC),
		},
		Story:    story(n, domain.SourcePublicDomain),
		Ref:      tts.Reference{AudioURI: "s3://ref.wav", Transcript: "我是爸爸", Language: "zh"},
		Language: "zh",
	}
}

// A fake clock so latency assertions are exact rather than flaky.
type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time          { return c.t }
func (c *fakeClock) advance(d time.Duration) { c.t = c.t.Add(d) }

func newTestRenderer(m *tts.Mock, clk *fakeClock, perCall time.Duration) *Renderer {
	r := NewRenderer(m)
	r.Now = clk.now
	r.sleep = func(ctx context.Context, d time.Duration) error {
		clk.advance(d)
		return ctx.Err()
	}
	// Mock latency is simulated on the clock, not slept.
	m.Latency = 0
	orig := r.Synth
	r.Synth = clockAdvancing{orig, clk, perCall}
	return r
}

type clockAdvancing struct {
	inner   tts.Synthesizer
	clk     *fakeClock
	perCall time.Duration
}

func (c clockAdvancing) Name() string { return c.inner.Name() }
func (c clockAdvancing) Synthesize(ctx context.Context, req tts.Request) (*tts.Result, error) {
	c.clk.advance(c.perCall)
	return c.inner.Synthesize(ctx, req)
}

func TestSegmentsArriveInOrder(t *testing.T) {
	clk := &fakeClock{t: time.Date(2026, 9, 19, 11, 25, 0, 0, time.UTC)}
	r := newTestRenderer(&tts.Mock{}, clk, 2*time.Second)

	var got []int
	out, err := r.Render(context.Background(), job(6), func(s SegmentResult) error {
		got = append(got, s.Index)
		if s.Total != 6 {
			t.Errorf("Total = %d, want 6", s.Total)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("render: %v", err)
	}
	for i, idx := range got {
		if idx != i {
			t.Fatalf("segment %d arrived at position %d", idx, i)
		}
	}
	if !out.Complete() {
		t.Errorf("outcome not complete: %+v", out)
	}
}

// The promise that removes the recording cutoff: playback can start long
// before the story is finished.
func TestFirstSegmentIsReadyLongBeforeTheRest(t *testing.T) {
	clk := &fakeClock{t: time.Date(2026, 9, 19, 11, 25, 0, 0, time.UTC)}
	r := newTestRenderer(&tts.Mock{}, clk, 3*time.Second)

	out, err := r.Render(context.Background(), job(20), func(SegmentResult) error { return nil })
	if err != nil {
		t.Fatalf("render: %v", err)
	}
	if out.FirstSegmentLatency != 3*time.Second {
		t.Errorf("FirstSegmentLatency = %v, want 3s", out.FirstSegmentLatency)
	}
	if out.Wall <= out.FirstSegmentLatency {
		t.Fatal("whole-story wall time should exceed first-segment latency")
	}
	// The whole point: waiting for the full story would have cost 20x as long.
	if ratio := out.Wall / out.FirstSegmentLatency; ratio < 10 {
		t.Errorf("first-segment advantage only %dx", ratio)
	}
}

// Generation must outrun playback or the child hears a gap mid-story.
func TestRealtimeFactorDetectsFallingBehind(t *testing.T) {
	// Each segment is ~34 runes at 0.12 s/rune ≈ 4.1 s of audio.
	t.Run("keeps up", func(t *testing.T) {
		clk := &fakeClock{}
		r := newTestRenderer(&tts.Mock{}, clk, 1*time.Second)
		out, err := r.Render(context.Background(), job(10), func(SegmentResult) error { return nil })
		if err != nil {
			t.Fatalf("render: %v", err)
		}
		if !out.KeepsUp() {
			t.Errorf("RealtimeFactor = %.2f, expected comfortably above 1", out.RealtimeFactor)
		}
	})

	t.Run("falls behind", func(t *testing.T) {
		clk := &fakeClock{}
		r := newTestRenderer(&tts.Mock{}, clk, 30*time.Second)
		out, err := r.Render(context.Background(), job(10), func(SegmentResult) error { return nil })
		if err != nil {
			t.Fatalf("render: %v", err)
		}
		if out.KeepsUp() {
			t.Errorf("RealtimeFactor = %.2f, should have been flagged as too slow", out.RealtimeFactor)
		}
	})
}

// D-002 has teeth: text with no approved provenance cannot be spoken in a
// parent's voice, whatever the caller does.
func TestUnapprovedSourceIsRefused(t *testing.T) {
	clk := &fakeClock{}
	r := newTestRenderer(&tts.Mock{}, clk, time.Second)

	j := job(3)
	j.Story.Source = "llm_generated"

	_, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil })
	if err == nil {
		t.Fatal("expected refusal for unapproved source")
	}
	if !errors.Is(err, ErrUnapprovedSource) {
		t.Errorf("err = %v, want unapproved source", err)
	}
}

func TestTransientFailureIsRetried(t *testing.T) {
	clk := &fakeClock{}
	m := &tts.Mock{FailOn: map[string]error{}}
	r := newTestRenderer(m, clk, time.Second)

	// Fail the first two attempts on one specific text, then let it through.
	target := "第二段。"
	j := job(1)
	j.Story.Segments = []domain.Segment{{Index: 0, Text: target}}

	calls := 0
	r.Synth = flaky{r.Synth, target, 2, &calls}

	out, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil })
	if err != nil {
		t.Fatalf("render should have recovered: %v", err)
	}
	if out.Retries != 2 {
		t.Errorf("Retries = %d, want 2", out.Retries)
	}
	if !out.Complete() {
		t.Error("outcome should be complete after retries")
	}
}

type flaky struct {
	inner     tts.Synthesizer
	failText  string
	failTimes int
	calls     *int
}

func (f flaky) Name() string { return f.inner.Name() }
func (f flaky) Synthesize(ctx context.Context, req tts.Request) (*tts.Result, error) {
	if req.Text == f.failText {
		*f.calls++
		if *f.calls <= f.failTimes {
			return nil, tts.ErrSynthetic
		}
	}
	return f.inner.Synthesize(ctx, req)
}

// A language the engine cannot speak must fail at once rather than burning
// three GPU slots on its way to the same answer.
func TestTerminalErrorIsNotRetried(t *testing.T) {
	clk := &fakeClock{}
	m := &tts.Mock{}
	r := newTestRenderer(m, clk, time.Second)

	j := job(2)
	j.Language = "xx" // not in tts.Supported

	out, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil })
	if err == nil {
		t.Fatal("expected failure for unsupported language")
	}
	if !errors.Is(err, tts.ErrUnsupportedLanguage) {
		t.Errorf("err = %v, want unsupported language", err)
	}
	if out.Retries != 0 {
		t.Errorf("Retries = %d, terminal errors must not be retried", out.Retries)
	}
}

func TestConsumerCanStopTheRender(t *testing.T) {
	clk := &fakeClock{}
	r := newTestRenderer(&tts.Mock{}, clk, time.Second)

	stop := errors.New("delivery window passed")
	out, err := r.Render(context.Background(), job(10), func(s SegmentResult) error {
		if s.Index == 2 {
			return stop
		}
		return nil
	})
	if !errors.Is(err, ErrAborted) {
		t.Fatalf("err = %v, want aborted", err)
	}
	if out.Completed != 3 {
		t.Errorf("Completed = %d, want 3", out.Completed)
	}
}

func TestContextCancellationStopsWork(t *testing.T) {
	clk := &fakeClock{}
	r := newTestRenderer(&tts.Mock{}, clk, time.Second)

	ctx, cancel := context.WithCancel(context.Background())
	_, err := r.Render(ctx, job(10), func(s SegmentResult) error {
		if s.Index == 1 {
			cancel()
		}
		return nil
	})
	if err == nil {
		t.Fatal("expected cancellation error")
	}
	if !errors.Is(err, context.Canceled) {
		t.Errorf("err = %v, want context.Canceled", err)
	}
}

// Prosody continuity: each call after the first carries the previous
// segment's tail, and every call carries the same reference.
func TestPrevTailIsCarriedForward(t *testing.T) {
	clk := &fakeClock{}
	m := &tts.Mock{}
	r := newTestRenderer(m, clk, time.Second)

	j := job(1)
	j.Story.Segments = []domain.Segment{
		{Index: 0, Text: "从前有三只小猪。他们住在森林边上。"},
		{Index: 1, Text: "有一天，狼来了。"},
		{Index: 2, Text: "小豆子跑得最快。"},
	}

	if _, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil }); err != nil {
		t.Fatalf("render: %v", err)
	}

	calls := m.Calls()
	if len(calls) != 3 {
		t.Fatalf("got %d calls, want 3", len(calls))
	}
	if calls[0].PrevTail != "" {
		t.Errorf("first call should have no tail, got %q", calls[0].PrevTail)
	}
	if calls[1].PrevTail != "他们住在森林边上。" {
		t.Errorf("PrevTail = %q, want the last sentence of segment 0", calls[1].PrevTail)
	}
	if calls[2].PrevTail != "有一天，狼来了。" {
		t.Errorf("PrevTail = %q, want segment 1", calls[2].PrevTail)
	}
	for i, c := range calls {
		if c.Ref.AudioURI != "s3://ref.wav" {
			t.Errorf("call %d lost the reference audio", i)
		}
	}
}

func TestTailSentence(t *testing.T) {
	cases := map[string]string{
		"从前有三只小猪。他们住在森林边上。":                "他们住在森林边上。",
		"Once upon a time. The wolf came.": "The wolf came.",
		"どうしたの？おやすみ。":                      "おやすみ。",
		"no terminator here":               "no terminator here",
		"":                                 "",
		"   ":                              "",
		"One.":                             "One.",
	}
	for in, want := range cases {
		if got := tailSentence(in); got != want {
			t.Errorf("tailSentence(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestEmptyStoryIsRejected(t *testing.T) {
	clk := &fakeClock{}
	r := newTestRenderer(&tts.Mock{}, clk, time.Second)

	j := job(0)
	if _, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil }); err == nil {
		t.Fatal("expected error for empty story")
	}
}

func TestPlanWithoutVoiceIsRejected(t *testing.T) {
	clk := &fakeClock{}
	r := newTestRenderer(&tts.Mock{}, clk, time.Second)

	j := job(3)
	j.Plan.VoiceID = ""
	if _, err := r.Render(context.Background(), j, func(SegmentResult) error { return nil }); !errors.Is(err, ErrNoVoice) {
		t.Fatalf("err = %v, want ErrNoVoice", err)
	}
}
