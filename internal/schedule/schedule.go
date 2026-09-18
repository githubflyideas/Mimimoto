// Package schedule turns a family's standing arrangement ("every night at
// 20:30, the child's time") into concrete, generatable jobs.
//
// Two rules drive everything here, and both come from product decisions rather
// than from engineering taste:
//
//   - Delivery is anchored to the CHILD's timezone, never the parent's and
//     never the server's (D-007). Once a family builds a ritual around 20:30,
//     a missed or mistimed delivery is a high-severity incident.
//
//   - The child's 20:30 is never empty (D-008), and a parent who did not
//     record today is never punished through their child (D-006). Missing
//     material degrades the experience one step at a time; it never cancels
//     it.
//
// Both rules are expressed as data — Tier and Plan.Fallbacks — so that the
// caller cannot accidentally implement a harsher policy than the one agreed.
package schedule

import (
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/githubflyideas/mimimoto/internal/domain"
)

// Tier is the degradation ladder. Lower is better; every tier still plays a
// story.
type Tier int

const (
	// TierFresh: the parent sent a voice note today. Opening plus the story
	// they asked for, in today's voice. This is the product working.
	TierFresh Tier = iota
	// TierInventory: no note today, but a pre-recorded opening is queued —
	// typically recorded in a batch before a trip.
	TierInventory
	// TierVoiceOnly: no opening available. The story is still told in the
	// parent's voice, and the child is NOT told that the parent did not come.
	TierVoiceOnly
	// TierCached: synthesis is unavailable or failed. Replay something already
	// on the device. The last line of defence for "never empty".
	TierCached
)

func (t Tier) String() string {
	switch t {
	case TierFresh:
		return "fresh"
	case TierInventory:
		return "inventory"
	case TierVoiceOnly:
		return "voice_only"
	case TierCached:
		return "cached"
	}
	return fmt.Sprintf("tier(%d)", int(t))
}

// HasOpening reports whether this tier plays a parent's spoken opening.
func (t Tier) HasOpening() bool { return t == TierFresh || t == TierInventory }

var (
	ErrDisabled    = errors.New("schedule: disabled")
	ErrNoWeekdays  = errors.New("schedule: no weekday ever matches")
	ErrBadWallTime = errors.New("schedule: hour or minute out of range")
)

// Spec is a standing delivery arrangement for one child.
type Spec struct {
	Child domain.FamilyChild
	// Hour and Minute are wall-clock in the child's timezone.
	Hour, Minute int
	// Weekdays restricts delivery; empty means every day.
	Weekdays []time.Weekday
	Enabled  bool
}

func (s Spec) valid() error {
	if !s.Enabled {
		return ErrDisabled
	}
	if s.Hour < 0 || s.Hour > 23 || s.Minute < 0 || s.Minute > 59 {
		return fmt.Errorf("%w: %02d:%02d", ErrBadWallTime, s.Hour, s.Minute)
	}
	return nil
}

func (s Spec) allows(d time.Weekday) bool {
	if len(s.Weekdays) == 0 {
		return true
	}
	return slices.Contains(s.Weekdays, d)
}

// Occurrence is a resolved delivery moment.
type Occurrence struct {
	// At is the instant to deliver, in UTC.
	At time.Time
	// LocalWall is the wall clock the family thinks in, for logs and UI.
	LocalWall time.Time
	// Kind flags the DST cases so they are visible in logs rather than
	// discovered from complaints.
	Kind WallKind
}

// NextOccurrence returns the first delivery strictly after `after`.
//
// It walks forward day by day in the child's local calendar. Scanning a bounded
// window (rather than computing an offset) is what makes the weekday filter and
// the DST cases compose correctly without special-casing either.
func NextOccurrence(s Spec, loc *time.Location, after time.Time) (Occurrence, error) {
	if err := s.valid(); err != nil {
		return Occurrence{}, err
	}
	if loc == nil {
		return Occurrence{}, fmt.Errorf("schedule: nil location")
	}

	// Start a day early: `after` may fall before today's delivery in local
	// terms even when UTC suggests otherwise.
	local := after.In(loc).AddDate(0, 0, -1)

	// Two weeks is more than enough for any weekday set; if nothing matches,
	// the set itself is broken.
	for range 15 {
		y, mo, d := local.Date()
		at, kind, err := resolveWallClock(loc, y, mo, d, s.Hour, s.Minute)
		if err != nil {
			return Occurrence{}, err
		}
		// Filter on the local weekday actually delivered on, which matters in
		// the skipped case where the instant can land past midnight.
		if s.allows(at.In(loc).Weekday()) && at.After(after) {
			return Occurrence{
				At:        at.UTC(),
				LocalWall: at.In(loc),
				Kind:      kind,
			}, nil
		}
		local = local.AddDate(0, 0, 1)
	}
	return Occurrence{}, ErrNoWeekdays
}

// Upcoming returns the next n occurrences. Used to warm the generation queue
// and to show parents what is scheduled.
func Upcoming(s Spec, loc *time.Location, after time.Time, n int) ([]Occurrence, error) {
	out := make([]Occurrence, 0, n)
	cur := after
	for range n {
		occ, err := NextOccurrence(s, loc, cur)
		if err != nil {
			return out, err
		}
		out = append(out, occ)
		cur = occ.At
	}
	return out, nil
}

// ---------- degradation ----------

// Availability is everything known at planning time about what this family can
// actually play tonight.
type Availability struct {
	Now time.Time

	// Voice is the parent's enrolled voiceprint. Nil, or revoked, means no
	// synthesis may happen at all.
	Voice *domain.VoicePrint

	// DailySample is today's voice note, already through audioqc. Nil if the
	// parent did not send one, or if it did not pass.
	DailySample *domain.VoiceSample

	// InventoryOpening is a pre-recorded opening waiting in the queue.
	InventoryOpening *domain.Opening

	// RequestedStory is what the parent asked for in today's note.
	RequestedStory domain.StoryID
	// DefaultStory is tonight's pick from the age-appropriate rotation.
	DefaultStory domain.StoryID
	// CachedStory is already rendered and on the device. The never-empty
	// guarantee rests on this.
	CachedStory domain.StoryID
}

// Lead describes how far ahead of delivery generation must start.
//
// Because segments are generated and streamed in order (D-009), only the FIRST
// segment has to exist at delivery time. That is what removes the "record
// before 18:30" cutoff that would otherwise be forced on parents.
type Lead struct {
	// FirstSegment is the expected time to synthesise segment 0.
	FirstSegment time.Duration
	// Safety absorbs queue wait, upload and the child device's fetch.
	Safety time.Duration
}

// DefaultLead is a starting point; replace with measured p95 per language and
// per GPU class once there is data.
var DefaultLead = Lead{FirstSegment: 45 * time.Second, Safety: 90 * time.Second}

func (l Lead) total() time.Duration { return l.FirstSegment + l.Safety }

// Fallback is something already playable, used when generation fails.
type Fallback struct {
	Tier    Tier
	StoryID domain.StoryID
	Reason  string
}

// Plan is one night's delivery for one child.
type Plan struct {
	Child     domain.FamilyChild
	DeliverAt time.Time
	LocalWall time.Time
	WallKind  WallKind

	Tier    Tier
	StoryID domain.StoryID

	// VoiceID and ReferenceSample condition synthesis. Empty VoiceID means no
	// synthesis is possible and the plan is cache-only.
	VoiceID         domain.VoiceID
	ReferenceSample domain.SampleID

	// Opening is played before the story. Nil at TierVoiceOnly and below —
	// and its absence is silent, never announced to the child (D-006).
	Opening *domain.Opening

	// StartGenerationAt is when this job must enter the GPU queue. Sorting
	// pending plans by this field is the whole scheduling policy: across
	// twelve languages bedtime rolls around the clock, so ordering by deadline
	// fills the troughs without any explicit balancing.
	StartGenerationAt time.Time

	// Fallbacks are ordered, best first.
	Fallbacks []Fallback

	// Notes explains the tier choice, for support and for tests.
	Notes string
}

// Playable reports whether this plan will put sound in the room. It must be
// true for every plan we ever emit; a false value is a bug, not a state.
func (p Plan) Playable() bool {
	return p.StoryID != "" || len(p.Fallbacks) > 0
}

// Decide applies the degradation ladder.
//
// Deliberately total: for any input it returns a plan that plays something, or
// an error naming a configuration problem the family must fix. There is no
// "nothing tonight" outcome, because that outcome is the one that gets the app
// deleted.
func Decide(occ Occurrence, child domain.FamilyChild, av Availability, lead Lead) (Plan, error) {
	p := Plan{
		Child:             child,
		DeliverAt:         occ.At,
		LocalWall:         occ.LocalWall,
		WallKind:          occ.Kind,
		StartGenerationAt: occ.At.Add(-lead.total()),
	}

	// Fallback chain first, so it exists no matter which branch we take below.
	if av.CachedStory != "" {
		p.Fallbacks = append(p.Fallbacks, Fallback{
			Tier:    TierCached,
			StoryID: av.CachedStory,
			Reason:  "previously delivered, already on device",
		})
	}

	voiceUsable := av.Voice != nil && !av.Voice.Revoked()

	// No usable voice: replay only. This is the enrolment-pending and the
	// revoked case; both are legitimate and neither may silence the evening.
	if !voiceUsable {
		p.Tier = TierCached
		p.Notes = "no usable voiceprint; replay only"
		if !p.Playable() {
			return Plan{}, fmt.Errorf("schedule: child %s has neither voice nor cached story", child.ChildID)
		}
		p.StoryID = p.Fallbacks[0].StoryID
		return p, nil
	}

	p.VoiceID = av.Voice.ID

	// Reference sample: today's note if it passed QC, else the best enrolled
	// sample. A failed sample must never reach synthesis.
	ref := pickReference(av)
	if ref == nil {
		p.Tier = TierCached
		p.Notes = "voiceprint has no usable reference sample"
		if !p.Playable() {
			return Plan{}, fmt.Errorf("schedule: voice %s has no usable sample and no cached story", av.Voice.ID)
		}
		p.StoryID = p.Fallbacks[0].StoryID
		return p, nil
	}
	p.ReferenceSample = ref.ID

	switch {
	case av.DailySample != nil && av.DailySample.Usable(av.Now):
		p.Tier = TierFresh
		p.StoryID = firstNonEmpty(av.RequestedStory, av.DefaultStory)
		p.Opening = &domain.Opening{
			SampleID: av.DailySample.ID,
			URI:      av.DailySample.URI,
		}
		p.Notes = "today's voice note used as opening and reference"

	case av.InventoryOpening != nil:
		p.Tier = TierInventory
		p.StoryID = av.DefaultStory
		op := *av.InventoryOpening
		p.Opening = &op
		p.Notes = "pre-recorded opening from inventory"

	default:
		p.Tier = TierVoiceOnly
		p.StoryID = av.DefaultStory
		p.Notes = "no opening available; story only, absence not surfaced to the child"
	}

	if p.StoryID == "" {
		// No story to tell but a voice to tell it with: fall back rather than
		// fail. A rotation that runs dry is our bug, not the family's problem.
		if !p.Playable() {
			return Plan{}, fmt.Errorf("schedule: no story selected and no cached story for child %s", child.ChildID)
		}
		p.Tier = TierCached
		p.StoryID = p.Fallbacks[0].StoryID
		p.Notes = "no story available; replaying cached"
	}

	// The story we are about to generate is itself a fallback for next time,
	// but never a fallback for tonight — drop it if it somehow appears there.
	p.Fallbacks = slices.DeleteFunc(p.Fallbacks, func(f Fallback) bool {
		return f.StoryID == p.StoryID && p.Tier == TierCached
	})

	return p, nil
}

// pickReference chooses the conditioning sample. Today's note is preferred
// because it carries the parent's current voice, but any usable enrolled
// sample works — cloning quality barely moves day to day, which is precisely
// why daily recording must not be a gate (D-006).
func pickReference(av Availability) *domain.VoiceSample {
	if av.DailySample != nil && av.DailySample.Usable(av.Now) {
		return av.DailySample
	}
	var best *domain.VoiceSample
	for i := range av.Voice.Samples {
		s := &av.Voice.Samples[i]
		if !s.Usable(av.Now) {
			continue
		}
		if best == nil || rank(s.Kind) < rank(best.Kind) ||
			(rank(s.Kind) == rank(best.Kind) && s.Quality.SNRdB > best.Quality.SNRdB) {
			best = s
		}
	}
	return best
}

func rank(k domain.SampleKind) int {
	switch k {
	case domain.SampleEnrolment:
		return 0
	case domain.SampleInventory:
		return 1
	default:
		return 2
	}
}

func firstNonEmpty(ids ...domain.StoryID) domain.StoryID {
	for _, id := range ids {
		if id != "" {
			return id
		}
	}
	return ""
}

// ByDeadline orders plans for the GPU queue: earliest generation start first.
func ByDeadline(plans []Plan) {
	slices.SortStableFunc(plans, func(a, b Plan) int {
		return a.StartGenerationAt.Compare(b.StartGenerationAt)
	})
}
