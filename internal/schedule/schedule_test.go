package schedule

import (
	"testing"
	"time"

	_ "time/tzdata" // embed the zone database: never depend on the host image

	"github.com/githubflyideas/mimimoto/internal/domain"
)

func mustLoad(t *testing.T, name string) *time.Location {
	t.Helper()
	loc, err := time.LoadLocation(name)
	if err != nil {
		t.Fatalf("load %s: %v", name, err)
	}
	return loc
}

func bedtime(h, m int) Spec {
	return Spec{
		Child:   domain.FamilyChild{FamilyID: "f1", ChildID: "c1"},
		Hour:    h,
		Minute:  m,
		Enabled: true,
	}
}

// ---------- plain timezones ----------

func TestTokyoBedtimeIsExact(t *testing.T) {
	loc := mustLoad(t, "Asia/Tokyo")
	after := time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)

	occ, err := NextOccurrence(bedtime(20, 30), loc, after)
	if err != nil {
		t.Fatalf("next: %v", err)
	}
	// 20:30 JST == 11:30 UTC, no DST ever.
	want := time.Date(2026, 9, 19, 11, 30, 0, 0, time.UTC)
	if !occ.At.Equal(want) {
		t.Errorf("At = %s, want %s", occ.At, want)
	}
	if occ.Kind != WallNormal {
		t.Errorf("Kind = %s, want normal", occ.Kind)
	}
}

// Kathmandu is UTC+05:45. Any code that assumes whole-hour offsets breaks here.
func TestFractionalOffsetZone(t *testing.T) {
	loc := mustLoad(t, "Asia/Kathmandu")
	after := time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)

	occ, err := NextOccurrence(bedtime(20, 30), loc, after)
	if err != nil {
		t.Fatalf("next: %v", err)
	}
	want := time.Date(2026, 9, 19, 14, 45, 0, 0, time.UTC)
	if !occ.At.Equal(want) {
		t.Errorf("At = %s, want %s", occ.At, want)
	}
	if occ.LocalWall.Hour() != 20 || occ.LocalWall.Minute() != 30 {
		t.Errorf("LocalWall = %s, want 20:30 local", occ.LocalWall)
	}
}

// ---------- DST: the product guarantee ----------

// The thing families actually care about: bedtime stays at 20:30 on their
// clock through a DST change, even though the UTC instant moves.
func TestBedtimeHoldsWallClockAcrossDST(t *testing.T) {
	for _, zone := range []string{
		"America/Los_Angeles", "Europe/Berlin", "Australia/Sydney",
		"Asia/Tokyo", "America/Sao_Paulo",
	} {
		t.Run(zone, func(t *testing.T) {
			loc := mustLoad(t, zone)
			cur := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
			var prev time.Time
			// A full year covers every transition in both hemispheres.
			for range 365 {
				occ, err := NextOccurrence(bedtime(20, 30), loc, cur)
				if err != nil {
					t.Fatalf("next: %v", err)
				}
				if occ.LocalWall.Hour() != 20 || occ.LocalWall.Minute() != 30 {
					t.Fatalf("on %s local wall clock drifted to %s",
						occ.LocalWall.Format("2006-01-02"), occ.LocalWall.Format("15:04"))
				}
				if !prev.IsZero() {
					gap := occ.At.Sub(prev)
					// 23h on spring forward, 25h on autumn back, 24h otherwise.
					if gap < 23*time.Hour || gap > 25*time.Hour {
						t.Fatalf("gap of %v before %s is not a day", gap, occ.LocalWall)
					}
				}
				prev = occ.At
				cur = occ.At
			}
		})
	}
}

// Spring forward: 02:30 does not exist. Policy is the instant the clock
// passes it (D-007), i.e. the transition itself, not an hour later.
func TestSkippedWallTime(t *testing.T) {
	cases := []struct {
		zone    string
		date    time.Time // local date of the transition
		wantUTC time.Time
		hh, mm  int
	}{
		{
			zone: "America/Los_Angeles",
			date: time.Date(2026, 3, 8, 0, 0, 0, 0, time.UTC),
			hh:   2, mm: 30,
			// 02:00 PST jumps to 03:00 PDT == 10:00 UTC.
			wantUTC: time.Date(2026, 3, 8, 10, 0, 0, 0, time.UTC),
		},
		{
			zone: "Europe/Berlin",
			date: time.Date(2026, 3, 29, 0, 0, 0, 0, time.UTC),
			hh:   2, mm: 30,
			// 02:00 CET jumps to 03:00 CEST == 01:00 UTC.
			wantUTC: time.Date(2026, 3, 29, 1, 0, 0, 0, time.UTC),
		},
	}

	for _, c := range cases {
		t.Run(c.zone, func(t *testing.T) {
			loc := mustLoad(t, c.zone)
			got, kind, err := resolveWallClock(loc, c.date.Year(), c.date.Month(), c.date.Day(), c.hh, c.mm)
			if err != nil {
				t.Fatalf("resolve: %v", err)
			}
			if kind != WallSkipped {
				t.Fatalf("Kind = %s, want skipped", kind)
			}
			if !got.UTC().Equal(c.wantUTC) {
				t.Errorf("At = %s, want %s (local %s)",
					got.UTC(), c.wantUTC, got.In(loc))
			}
		})
	}
}

// Autumn back: 01:30 happens twice. Policy is the earlier one, so the story
// never lands after the child is asleep.
func TestAmbiguousWallTimeTakesEarlier(t *testing.T) {
	cases := []struct {
		zone   string
		y      int
		mo     time.Month
		d      int
		hh, mm int
	}{
		{"America/Los_Angeles", 2026, time.November, 1, 1, 30},
		{"Europe/Berlin", 2026, time.October, 25, 2, 30},
		{"Australia/Sydney", 2026, time.April, 5, 2, 30},
	}

	for _, c := range cases {
		t.Run(c.zone, func(t *testing.T) {
			loc := mustLoad(t, c.zone)
			got, kind, err := resolveWallClock(loc, c.y, c.mo, c.d, c.hh, c.mm)
			if err != nil {
				t.Fatalf("resolve: %v", err)
			}
			if kind != WallAmbiguous {
				t.Fatalf("Kind = %s, want ambiguous (got %s)", kind, got.In(loc))
			}
			// The chosen instant must have the requested wall clock...
			l := got.In(loc)
			if l.Hour() != c.hh || l.Minute() != c.mm {
				t.Fatalf("local wall = %s, want %02d:%02d", l.Format("15:04"), c.hh, c.mm)
			}
			// ...and the same wall clock must recur an hour later, proving we
			// picked the first of the two.
			later := got.Add(time.Hour).In(loc)
			if later.Hour() != c.hh || later.Minute() != c.mm {
				t.Errorf("chose the later occurrence: +1h gives %s", later.Format("15:04"))
			}
		})
	}
}

// ---------- weekdays ----------

func TestWeekdayFilter(t *testing.T) {
	loc := mustLoad(t, "Asia/Tokyo")
	s := bedtime(20, 30)
	s.Weekdays = []time.Weekday{time.Saturday, time.Sunday}

	// 2026-09-19 is a Saturday in Tokyo at 20:30 JST.
	after := time.Date(2026, 9, 16, 0, 0, 0, 0, time.UTC) // Wednesday
	occs, err := Upcoming(s, loc, after, 4)
	if err != nil {
		t.Fatalf("upcoming: %v", err)
	}
	if len(occs) != 4 {
		t.Fatalf("got %d occurrences", len(occs))
	}
	for i, o := range occs {
		wd := o.LocalWall.Weekday()
		if wd != time.Saturday && wd != time.Sunday {
			t.Errorf("occurrence %d falls on %s", i, wd)
		}
		if i > 0 && !o.At.After(occs[i-1].At) {
			t.Errorf("occurrence %d not strictly after previous", i)
		}
	}
}

func TestDisabledScheduleErrors(t *testing.T) {
	loc := mustLoad(t, "Asia/Tokyo")
	s := bedtime(20, 30)
	s.Enabled = false
	if _, err := NextOccurrence(s, loc, time.Now()); err == nil {
		t.Fatal("expected error for disabled schedule")
	}
}

func TestInvalidWallTimeErrors(t *testing.T) {
	loc := mustLoad(t, "Asia/Tokyo")
	if _, err := NextOccurrence(bedtime(25, 0), loc, time.Now()); err == nil {
		t.Fatal("expected error for hour 25")
	}
}

// ---------- degradation ladder ----------

func goodSample(id domain.SampleID, kind domain.SampleKind, snr float64) domain.VoiceSample {
	return domain.VoiceSample{
		ID:      id,
		VoiceID: "v1",
		Kind:    kind,
		URI:     "s3://samples/" + string(id),
		Quality: domain.QualityStamp{Passed: true, SNRdB: snr},
	}
}

func baseAvail() Availability {
	return Availability{
		Now: time.Date(2026, 9, 19, 10, 0, 0, 0, time.UTC),
		Voice: &domain.VoicePrint{
			ID:       "v1",
			MemberID: "m1",
			Samples:  []domain.VoiceSample{goodSample("s-enrol", domain.SampleEnrolment, 40)},
		},
		DefaultStory: "story-default",
		CachedStory:  "story-cached",
	}
}

func testOcc() Occurrence {
	at := time.Date(2026, 9, 19, 11, 30, 0, 0, time.UTC)
	return Occurrence{At: at, LocalWall: at, Kind: WallNormal}
}

func TestTierFreshUsesTodaysNote(t *testing.T) {
	av := baseAvail()
	daily := goodSample("s-today", domain.SampleDaily, 32)
	av.DailySample = &daily
	av.RequestedStory = "story-three-pigs"

	p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
	if err != nil {
		t.Fatalf("decide: %v", err)
	}
	if p.Tier != TierFresh {
		t.Fatalf("Tier = %s, want fresh", p.Tier)
	}
	if p.StoryID != "story-three-pigs" {
		t.Errorf("StoryID = %s, want the requested story", p.StoryID)
	}
	if p.Opening == nil || p.Opening.SampleID != "s-today" {
		t.Errorf("Opening = %+v, want today's note", p.Opening)
	}
	if p.ReferenceSample != "s-today" {
		t.Errorf("ReferenceSample = %s, want today's note", p.ReferenceSample)
	}
}

// A sample that failed audioqc must never be used, even when it is the only
// thing from today. A bad reference is worse than an older good one.
func TestFailedQCSampleIsIgnored(t *testing.T) {
	av := baseAvail()
	bad := goodSample("s-bad", domain.SampleDaily, 5)
	bad.Quality.Passed = false
	bad.Quality.Reason = "band_limited"
	av.DailySample = &bad

	p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
	if err != nil {
		t.Fatalf("decide: %v", err)
	}
	if p.Tier == TierFresh {
		t.Error("a sample that failed QC must not produce a fresh tier")
	}
	if p.ReferenceSample != "s-enrol" {
		t.Errorf("ReferenceSample = %s, want the enrolment sample", p.ReferenceSample)
	}
}

func TestTierInventoryWhenNoNoteToday(t *testing.T) {
	av := baseAvail()
	av.InventoryOpening = &domain.Opening{SampleID: "s-inv", URI: "s3://openings/inv"}

	p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
	if err != nil {
		t.Fatalf("decide: %v", err)
	}
	if p.Tier != TierInventory {
		t.Fatalf("Tier = %s, want inventory", p.Tier)
	}
	if p.Opening == nil || p.Opening.SampleID != "s-inv" {
		t.Errorf("Opening = %+v, want the inventory opening", p.Opening)
	}
}

// The core of D-006: a parent who sent nothing still gets a story told in
// their voice, and nothing announces their absence.
func TestTierVoiceOnlyStillTellsAStory(t *testing.T) {
	av := baseAvail()

	p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
	if err != nil {
		t.Fatalf("decide: %v", err)
	}
	if p.Tier != TierVoiceOnly {
		t.Fatalf("Tier = %s, want voice_only", p.Tier)
	}
	if p.StoryID != "story-default" {
		t.Errorf("StoryID = %s, want the default story", p.StoryID)
	}
	if p.Opening != nil {
		t.Error("voice_only must have no opening")
	}
	if p.Tier.HasOpening() {
		t.Error("voice_only must not claim to have an opening")
	}
	if !p.Playable() {
		t.Error("every plan must be playable")
	}
}

// Never empty (D-008), whatever is missing.
func TestPlanIsAlwaysPlayable(t *testing.T) {
	variants := map[string]func(*Availability){
		"no voice":           func(a *Availability) { a.Voice = nil },
		"revoked voice":      func(a *Availability) { a.Voice.RevokedAt = a.Now.Add(-time.Hour) },
		"no usable samples":  func(a *Availability) { a.Voice.Samples[0].Quality.Passed = false },
		"no default story":   func(a *Availability) { a.DefaultStory = "" },
		"expired sample":     func(a *Availability) { a.Voice.Samples[0].ExpiresAt = a.Now.Add(-time.Hour) },
		"everything missing": func(a *Availability) { a.Voice = nil; a.DefaultStory = "" },
	}
	for name, mut := range variants {
		t.Run(name, func(t *testing.T) {
			av := baseAvail()
			mut(&av)
			p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
			if err != nil {
				t.Fatalf("decide: %v", err)
			}
			if !p.Playable() {
				t.Fatalf("plan is not playable: %+v", p)
			}
			if p.StoryID == "" {
				t.Fatalf("no story chosen: %+v", p)
			}
		})
	}
}

// The only acceptable error: nothing to play at all. It must be an explicit
// error so it pages someone, rather than a silent empty evening.
func TestTotallyEmptyFamilyErrors(t *testing.T) {
	av := baseAvail()
	av.Voice = nil
	av.CachedStory = ""
	av.DefaultStory = ""
	if _, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead); err == nil {
		t.Fatal("expected an error when there is genuinely nothing to play")
	}
}

func TestGenerationLeadPrecedesDelivery(t *testing.T) {
	av := baseAvail()
	p, err := Decide(testOcc(), domain.FamilyChild{FamilyID: "f1", ChildID: "c1"}, av, DefaultLead)
	if err != nil {
		t.Fatalf("decide: %v", err)
	}
	lead := p.DeliverAt.Sub(p.StartGenerationAt)
	if lead != DefaultLead.total() {
		t.Errorf("lead = %v, want %v", lead, DefaultLead.total())
	}
	// Segment-wise generation is what keeps this small; if it ever grows past
	// a few minutes the "record any time before bedtime" promise is broken.
	if lead > 5*time.Minute {
		t.Errorf("lead of %v reintroduces a recording cutoff for parents", lead)
	}
}

func TestByDeadlineOrdersQueue(t *testing.T) {
	base := time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)
	plans := []Plan{
		{StartGenerationAt: base.Add(3 * time.Hour)},
		{StartGenerationAt: base.Add(1 * time.Hour)},
		{StartGenerationAt: base.Add(2 * time.Hour)},
	}
	ByDeadline(plans)
	for i := 1; i < len(plans); i++ {
		if plans[i].StartGenerationAt.Before(plans[i-1].StartGenerationAt) {
			t.Fatalf("queue out of order at %d", i)
		}
	}
}
