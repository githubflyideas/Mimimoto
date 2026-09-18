package tts

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"sync"
	"time"
)

// Mock is a deterministic Synthesizer for tests and for running the scheduler
// end to end without a GPU.
//
// It produces real WAV bytes — silence shaped like speech — so that anything
// downstream which decodes audio is genuinely exercised.
type Mock struct {
	// Latency is how long each call takes, simulating GPU time.
	Latency time.Duration
	// FailOn makes the given segment texts fail, for testing the fallback
	// paths. The value is the error returned.
	FailOn map[string]error
	// SecondsPerChar controls generated duration.
	SecondsPerChar float64

	mu    sync.Mutex
	calls []Request
}

func (m *Mock) Name() string { return "mock" }

func (m *Mock) Synthesize(ctx context.Context, req Request) (*Result, error) {
	if err := req.validate(); err != nil {
		return nil, err
	}
	if err := CheckLanguage(req.Language); err != nil {
		return nil, err
	}

	m.mu.Lock()
	m.calls = append(m.calls, req)
	m.mu.Unlock()

	if m.Latency > 0 {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(m.Latency):
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err, bad := m.FailOn[req.Text]; bad {
		return nil, err
	}

	spc := m.SecondsPerChar
	if spc == 0 {
		spc = 0.12 // roughly a natural reading pace for CJK
	}
	dur := time.Duration(float64(len([]rune(req.Text))) * spc * float64(time.Second))
	const rate = 24000
	return &Result{
		Audio:         toneWAV(rate, dur),
		SampleRate:    rate,
		Duration:      dur,
		Engine:        m.Name(),
		EngineVersion: "mock-1",
	}, nil
}

// Calls returns a copy of the requests seen so far.
func (m *Mock) Calls() []Request {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]Request(nil), m.calls...)
}

// Reset clears recorded calls.
func (m *Mock) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls = nil
}

// toneWAV builds a 16-bit mono WAV of the given duration holding a quiet tone.
func toneWAV(rate int, d time.Duration) []byte {
	n := int(d.Seconds() * float64(rate))
	if n < 1 {
		n = 1
	}
	var b []byte
	put32 := func(v uint32) { b = binary.LittleEndian.AppendUint32(b, v) }
	put16 := func(v uint16) { b = binary.LittleEndian.AppendUint16(b, v) }

	b = append(b, "RIFF"...)
	put32(uint32(36 + n*2))
	b = append(b, "WAVEfmt "...)
	put32(16)
	put16(1)
	put16(1)
	put32(uint32(rate))
	put32(uint32(rate * 2))
	put16(2)
	put16(16)
	b = append(b, "data"...)
	put32(uint32(n * 2))
	for i := range n {
		v := 0.1 * math.Sin(2*math.Pi*220*float64(i)/float64(rate))
		b = binary.LittleEndian.AppendUint16(b, uint16(int16(v*32767)))
	}
	return b
}

// ErrSynthetic is a convenience for tests that need a retryable failure.
var ErrSynthetic = fmt.Errorf("tts: synthetic failure")
