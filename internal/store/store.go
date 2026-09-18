// Package store persists families, voiceprints and stories.
//
// The interface is the point; the implementation here is not. Memory is
// adequate for the pilot this project is actually at — a handful of families,
// observed by hand — and pretending otherwise would mean building a schema
// around guesses. What the interface does encode is the one requirement that
// will not change: deleting a voice must remove its samples with it, in one
// call, so that a deletion request has a single place it can be honoured (and
// a single place it can be audited).
package store

import (
	"context"
	"errors"
	"maps"
	"slices"
	"sync"
	"time"

	"github.com/githubflyideas/mimimoto/internal/domain"
)

var (
	ErrNotFound = errors.New("store: not found")
	ErrExists   = errors.New("store: already exists")
)

// Store is the persistence boundary.
type Store interface {
	PutFamily(ctx context.Context, f domain.Family) error
	Family(ctx context.Context, id domain.FamilyID) (domain.Family, error)

	PutVoice(ctx context.Context, v domain.VoicePrint) error
	Voice(ctx context.Context, id domain.VoiceID) (domain.VoicePrint, error)
	VoiceForMember(ctx context.Context, id domain.MemberID) (domain.VoicePrint, error)
	AppendSample(ctx context.Context, id domain.VoiceID, s domain.VoiceSample) error

	// RevokeVoice stops all synthesis with this voice, immediately.
	RevokeVoice(ctx context.Context, id domain.VoiceID, at time.Time) error
	// DeleteVoice removes the voiceprint and every sample belonging to it.
	// This is what a deletion request resolves to; partial deletion is not an
	// option the interface offers.
	DeleteVoice(ctx context.Context, id domain.VoiceID) error

	PutStory(ctx context.Context, s domain.Story) error
	Story(ctx context.Context, id domain.StoryID) (domain.Story, error)
	// StoriesFor returns the rotation for a language and age band.
	StoriesFor(ctx context.Context, lang string, band domain.AgeBand) ([]domain.Story, error)

	// ExpireSamples deletes samples past their retention date and reports how
	// many went. Daily notes are short-lived by design (D-001), and expiry
	// must happen whether or not anyone remembers to ask.
	ExpireSamples(ctx context.Context, now time.Time) (int, error)
}

// Memory is an in-process Store.
type Memory struct {
	mu       sync.RWMutex
	families map[domain.FamilyID]domain.Family
	voices   map[domain.VoiceID]domain.VoicePrint
	stories  map[domain.StoryID]domain.Story
}

func NewMemory() *Memory {
	return &Memory{
		families: map[domain.FamilyID]domain.Family{},
		voices:   map[domain.VoiceID]domain.VoicePrint{},
		stories:  map[domain.StoryID]domain.Story{},
	}
}

var _ Store = (*Memory)(nil)

func (m *Memory) PutFamily(_ context.Context, f domain.Family) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.families[f.ID] = f
	return nil
}

func (m *Memory) Family(_ context.Context, id domain.FamilyID) (domain.Family, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	f, ok := m.families[id]
	if !ok {
		return domain.Family{}, ErrNotFound
	}
	return f, nil
}

func (m *Memory) PutVoice(_ context.Context, v domain.VoicePrint) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.voices[v.ID] = v
	return nil
}

func (m *Memory) Voice(_ context.Context, id domain.VoiceID) (domain.VoicePrint, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	v, ok := m.voices[id]
	if !ok {
		return domain.VoicePrint{}, ErrNotFound
	}
	return v, nil
}

func (m *Memory) VoiceForMember(_ context.Context, id domain.MemberID) (domain.VoicePrint, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, v := range m.voices {
		if v.MemberID == id {
			return v, nil
		}
	}
	return domain.VoicePrint{}, ErrNotFound
}

func (m *Memory) AppendSample(_ context.Context, id domain.VoiceID, s domain.VoiceSample) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	v, ok := m.voices[id]
	if !ok {
		return ErrNotFound
	}
	// Newest first, matching what callers expect when they scan for a
	// same-language reference.
	v.Samples = append([]domain.VoiceSample{s}, v.Samples...)
	m.voices[id] = v
	return nil
}

func (m *Memory) RevokeVoice(_ context.Context, id domain.VoiceID, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	v, ok := m.voices[id]
	if !ok {
		return ErrNotFound
	}
	v.RevokedAt = at
	m.voices[id] = v
	return nil
}

func (m *Memory) DeleteVoice(_ context.Context, id domain.VoiceID) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.voices[id]; !ok {
		return ErrNotFound
	}
	// Samples live inside the voiceprint precisely so that this cannot leave
	// orphans behind.
	delete(m.voices, id)
	return nil
}

func (m *Memory) PutStory(_ context.Context, s domain.Story) error {
	if err := s.Validate(); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stories[s.ID] = s
	return nil
}

func (m *Memory) Story(_ context.Context, id domain.StoryID) (domain.Story, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.stories[id]
	if !ok {
		return domain.Story{}, ErrNotFound
	}
	return s, nil
}

func (m *Memory) StoriesFor(_ context.Context, lang string, band domain.AgeBand) ([]domain.Story, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var out []domain.Story
	for _, s := range slices.SortedFunc(maps.Values(m.stories), func(a, b domain.Story) int {
		return slices.Compare([]string{string(a.ID)}, []string{string(b.ID)})
	}) {
		if s.Language == lang && s.AgeBand == band {
			out = append(out, s)
		}
	}
	return out, nil
}

func (m *Memory) ExpireSamples(_ context.Context, now time.Time) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	removed := 0
	for id, v := range m.voices {
		kept := v.Samples[:0:0]
		for _, s := range v.Samples {
			if !s.ExpiresAt.IsZero() && now.After(s.ExpiresAt) {
				removed++
				continue
			}
			kept = append(kept, s)
		}
		v.Samples = kept
		m.voices[id] = v
	}
	return removed, nil
}
