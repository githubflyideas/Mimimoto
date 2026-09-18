// Package domain holds the core entities. It has no dependencies on storage,
// transport or synthesis so that the rules stay readable in one place.
//
// Note the deliberate asymmetry: Member (a parent) carries credentials and a
// voiceprint; Child carries neither. A child is a data object, never an
// account, and no field here can hold child audio. See docs/DECISIONS.md D-003.
package domain

import (
	"errors"
	"fmt"
	"time"
)

// ---------- identifiers ----------

type (
	FamilyID  string
	MemberID  string
	ChildID   string
	VoiceID   string
	StoryID   string
	SampleID  string
	JobID     string
	ProfileID string
)

// ---------- family ----------

// Family is the billing and permission boundary. Everything else hangs off it.
type Family struct {
	ID        FamilyID
	OwnerID   MemberID // the adult who pays; always a Member
	CreatedAt time.Time
	Members   []Member
	Children  []Child
}

// Role determines what a member may do. Only Owner may change billing or
// remove other members.
type Role string

const (
	RoleOwner  Role = "owner"
	RoleParent Role = "parent"
	// RoleRelative is a grandparent or similar: may record and send, may not
	// change family settings or see delivery analytics.
	RoleRelative Role = "relative"
)

// Member is an adult with credentials. Only members are ever recorded.
type Member struct {
	ID       MemberID
	FamilyID FamilyID
	Role     Role
	// DisplayName is what the child's device shows ("爸爸", "おじいちゃん").
	DisplayName string
	Locale      string // BCP-47, drives TTS language tag selection
	// VoiceID is empty until the member completes liveness enrolment (D-005).
	VoiceID   VoiceID
	CreatedAt time.Time
}

// Enrolled reports whether this member may have stories synthesised in their
// voice.
func (m Member) Enrolled() bool { return m.VoiceID != "" }

// Child is a listener, not a user. No credentials, no biometric data, no
// audio — by design, and this must stay true (D-003).
type Child struct {
	ID       ChildID
	FamilyID FamilyID
	// DisplayName is used only to address the child inside story text.
	DisplayName string
	// AgeBand selects story difficulty. Deliberately coarse: we do not store a
	// birth date, because a birth date is what turns a listener into a
	// data subject under GDPR-K.
	AgeBand AgeBand
	// Language is the language stories are told in, which is a property of the
	// child rather than of the parent: migrant families routinely want the
	// child kept in the home language while the parent's own locale is the
	// country they work in.
	Language string
	// TZ is an IANA location name. Delivery is anchored here, never to the
	// parent's timezone or the server's (D-007).
	TZ string
}

type AgeBand string

const (
	AgeToddler     AgeBand = "2-4"
	AgePreschool   AgeBand = "4-6"
	AgeEarlySchool AgeBand = "6-9"
	AgeOlder       AgeBand = "9-12"
)

func (a AgeBand) Valid() bool {
	switch a {
	case AgeToddler, AgePreschool, AgeEarlySchool, AgeOlder:
		return true
	}
	return false
}

// Location resolves the child's timezone. A child whose TZ fails to load is a
// configuration error we must surface loudly: silently falling back to UTC
// would deliver a bedtime story at the wrong hour, which is the one failure
// this product cannot have.
func (c Child) Location() (*time.Location, error) {
	if c.TZ == "" {
		return nil, fmt.Errorf("child %s: empty timezone", c.ID)
	}
	loc, err := time.LoadLocation(c.TZ)
	if err != nil {
		return nil, fmt.Errorf("child %s: load timezone %q: %w", c.ID, c.TZ, err)
	}
	return loc, nil
}

// ---------- voice ----------

// VoicePrint is the reference material used to condition synthesis.
//
// It intentionally does not hold raw audio bytes. The store decides where
// audio lives and for how long; see docs/DECISIONS.md "未决" on storage.
type VoicePrint struct {
	ID       VoiceID
	MemberID MemberID
	FamilyID FamilyID
	// Language of the enrolment prompt. Cloning quality drops when the
	// reference language differs from the synthesis language, so we keep the
	// enrolment language and prefer same-language references.
	Language string
	// EnrolledAt is when liveness enrolment succeeded.
	EnrolledAt time.Time
	// RevokedAt, when non-zero, disables all synthesis with this voice.
	// Revocation must be honoured before any generation begins.
	RevokedAt time.Time
	// Samples are the references available, newest first. Multiple samples let
	// the pipeline pick one matching the target language.
	Samples []VoiceSample
}

func (v VoicePrint) Revoked() bool { return !v.RevokedAt.IsZero() }

// SampleKind distinguishes how a reference was obtained, which governs how
// long it may be kept and whether it may be used at all.
type SampleKind string

const (
	// SampleEnrolment came from the liveness challenge. Long-lived.
	SampleEnrolment SampleKind = "enrolment"
	// SampleDaily came from a parent's daily voice note. Short-lived; it is
	// both an instruction and a fresh reference (D-001).
	SampleDaily SampleKind = "daily"
	// SampleInventory was recorded ahead of time to cover absence (D-006).
	SampleInventory SampleKind = "inventory"
)

// VoiceSample is one reference recording belonging to a VoicePrint.
type VoiceSample struct {
	ID       SampleID
	VoiceID  VoiceID
	Kind     SampleKind
	Language string
	// Transcript is required: zero-shot cloning conditions on reference text
	// as well as reference audio.
	Transcript string
	// URI points at the stored audio. Opaque to this package.
	URI string
	// Quality is the audioqc verdict recorded at intake. A sample that did not
	// pass must never reach synthesis — bad references are how the product
	// fails its "sounds like dad" test.
	Quality QualityStamp
	// ExpiresAt, when non-zero, is when this sample must be deleted.
	ExpiresAt time.Time
	CreatedAt time.Time
}

// QualityStamp is the subset of the audioqc report we persist.
type QualityStamp struct {
	Passed        bool
	SNRdB         float64
	SpeechSeconds float64
	CutoffHz      float64
	Reason        string
}

func (s VoiceSample) Usable(now time.Time) bool {
	if !s.Quality.Passed {
		return false
	}
	if !s.ExpiresAt.IsZero() && now.After(s.ExpiresAt) {
		return false
	}
	return true
}

// ---------- story ----------

// Story is text to be spoken. Every Story must trace back to a Source that a
// human chose or wrote; nothing here may be model-invented speech in a
// parent's voice (D-002).
type Story struct {
	ID       StoryID
	Title    string
	Language string
	AgeBand  AgeBand
	Source   StorySource
	// Segments are the synthesis units. Splitting at authoring time (rather
	// than at generation time) keeps段 boundaries at sentence ends, which is
	// what makes segment-wise generation sound continuous (D-009).
	Segments []Segment
}

// StorySource records provenance. Public-domain and parent-authored are the
// only two ways text may enter the system.
type StorySource string

const (
	// SourcePublicDomain — Grimm, Andersen, Aesop and the like.
	SourcePublicDomain StorySource = "public_domain"
	// SourceParentAuthored — the parent wrote or dictated it.
	SourceParentAuthored StorySource = "parent_authored"
	// SourceLicensed — content we hold a written licence for.
	SourceLicensed StorySource = "licensed"
)

func (s StorySource) Valid() bool {
	switch s {
	case SourcePublicDomain, SourceParentAuthored, SourceLicensed:
		return true
	}
	return false
}

// Segment is one synthesis unit, a few sentences long.
type Segment struct {
	Index int
	Text  string
	// Role lets a story mark character lines so the pipeline can pass a style
	// hint. It never changes who is speaking — always the parent's voice.
	Role string
}

var ErrEmptyStory = errors.New("story has no segments")

func (s Story) Validate() error {
	if len(s.Segments) == 0 {
		return ErrEmptyStory
	}
	if !s.Source.Valid() {
		return fmt.Errorf("story %s: invalid source %q", s.ID, s.Source)
	}
	for i, seg := range s.Segments {
		if seg.Index != i {
			return fmt.Errorf("story %s: segment %d has index %d", s.ID, i, seg.Index)
		}
		if seg.Text == "" {
			return fmt.Errorf("story %s: segment %d is empty", s.ID, i)
		}
	}
	return nil
}

// ---------- delivery ----------

// Schedule is the standing arrangement: this child, this local time, these
// days. Stored per child because siblings go to bed at different times.
type Schedule struct {
	ChildID FamilyChild
	// Hour and Minute are wall-clock time in the child's own timezone.
	Hour, Minute int
	// Weekdays restricts delivery. Empty means every day.
	Weekdays []time.Weekday
	// Enabled false pauses delivery without losing the configuration.
	Enabled bool
}

// FamilyChild pairs the two identifiers needed to resolve a schedule without
// a second lookup.
type FamilyChild struct {
	FamilyID FamilyID
	ChildID  ChildID
}

// Opening is a parent's short voice note played before the story. Its scarcity
// is the incentive mechanism — the story itself is never withheld (D-006).
type Opening struct {
	SampleID SampleID
	MemberID MemberID
	URI      string
	// Duration is used to size the pre-roll buffer on the child's device.
	Duration time.Duration
}
