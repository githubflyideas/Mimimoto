package schedule

import (
	"fmt"
	"time"
)

// WallKind records what the requested local time turned out to be on a given
// date. Twice a year in most of our markets it is not simply "a time".
type WallKind string

const (
	// WallNormal: the requested wall time occurs exactly once.
	WallNormal WallKind = "normal"
	// WallSkipped: the clock jumped over it (spring forward). Policy: deliver
	// at the instant the clock passes the requested time.
	WallSkipped WallKind = "skipped"
	// WallAmbiguous: the wall time occurs twice (autumn back). Policy: the
	// earlier occurrence, so the story never arrives after the child is
	// already asleep.
	WallAmbiguous WallKind = "ambiguous"
)

// resolveWallClock maps a local wall-clock date and time in loc to a unique
// instant, and says which of the three cases applied.
//
// Go's time.Date silently normalises both the skipped and the ambiguous case,
// and its choice in the ambiguous case is explicitly not guaranteed. Neither
// is acceptable here: getting this wrong means a bedtime story arrives an hour
// late, twice a year, for every family in a DST market. So we resolve it
// ourselves and record which case it was, and the DST days become something we
// can assert on in tests rather than something we discover from support
// tickets.
func resolveWallClock(loc *time.Location, y int, mo time.Month, d, hh, mm int) (time.Time, WallKind, error) {
	if loc == nil {
		return time.Time{}, "", fmt.Errorf("schedule: nil location")
	}
	// The requested wall clock, held as a UTC value purely so it can be
	// compared and offset arithmetic stays explicit.
	want := time.Date(y, mo, d, hh, mm, 0, 0, time.UTC)

	// Candidate UTC offsets in force around this date. Probing a day either
	// side covers any transition, including the 30- and 45-minute ones
	// (Lord Howe, and the odd historical change in our Asian markets).
	seen := map[int]bool{}
	var offsets []int
	for _, probe := range []time.Time{
		want.Add(-24 * time.Hour), want, want.Add(24 * time.Hour),
	} {
		_, off := probe.In(loc).Zone()
		if !seen[off] {
			seen[off] = true
			offsets = append(offsets, off)
		}
	}

	// An offset is valid if applying it reproduces exactly the wall clock we
	// asked for.
	var valid []time.Time
	for _, off := range offsets {
		inst := want.Add(-time.Duration(off) * time.Second)
		if wallOf(inst, loc).Equal(want) {
			valid = append(valid, inst)
		}
	}

	switch len(valid) {
	case 1:
		return valid[0], WallNormal, nil
	case 0:
		// Skipped. Find the instant at which the local clock first reaches or
		// passes the requested wall time.
		t := firstInstantAtOrAfterWall(loc, want)
		return t, WallSkipped, nil
	default:
		earliest := valid[0]
		for _, t := range valid[1:] {
			if t.Before(earliest) {
				earliest = t
			}
		}
		return earliest, WallAmbiguous, nil
	}
}

// wallOf returns t's local wall clock in loc, expressed as a UTC-stamped value
// so two wall clocks can be compared without offsets confusing the issue.
func wallOf(t time.Time, loc *time.Location) time.Time {
	l := t.In(loc)
	return time.Date(l.Year(), l.Month(), l.Day(), l.Hour(), l.Minute(), l.Second(), 0, time.UTC)
}

// firstInstantAtOrAfterWall binary-searches for the earliest instant whose
// local wall clock is >= want. Used only for the skipped case, where no
// instant has exactly that wall clock.
//
// Across a forward transition the local wall clock is strictly increasing in
// real time, so the predicate is monotone and the search is well-defined.
func firstInstantAtOrAfterWall(loc *time.Location, want time.Time) time.Time {
	lo := want.Add(-48 * time.Hour)
	hi := want.Add(48 * time.Hour)
	for hi.Sub(lo) > time.Second {
		mid := lo.Add(hi.Sub(lo) / 2)
		if wallOf(mid, loc).Before(want) {
			lo = mid
		} else {
			hi = mid
		}
	}
	return hi.Truncate(time.Second)
}
