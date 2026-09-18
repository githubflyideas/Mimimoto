// Package httpapi exposes the flows that actually exist today: taking a
// recording in, judging it, and working out what a given child will hear
// tonight.
//
// Two things are deliberately absent. There is no endpoint that accepts audio
// of anyone other than the authenticated member — cloning a voice from an
// uploaded file is the shape of a fraud tool, not of this product (D-005). And
// there is no endpoint that accepts free-form text to speak in a parent's
// voice; text arrives as a story id, and stories carry provenance (D-002).
package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/githubflyideas/mimimoto/internal/audioqc"
	"github.com/githubflyideas/mimimoto/internal/domain"
	"github.com/githubflyideas/mimimoto/internal/schedule"
	"github.com/githubflyideas/mimimoto/internal/store"
)

// Retention governs how long each kind of sample is kept.
//
// Daily notes expire quickly: they are an instruction and a fresh reference,
// not an archive, and the less voice data sits around the smaller the problem
// when the storage question finally gets answered properly.
type Retention struct {
	Daily     time.Duration
	Inventory time.Duration
}

var DefaultRetention = Retention{
	Daily:     14 * 24 * time.Hour,
	Inventory: 180 * 24 * time.Hour,
}

// Blob stores audio and returns a URI for it.
type Blob interface {
	Put(ctx context.Context, key string, data []byte) (string, error)
}

// Server wires the pieces together.
type Server struct {
	Store     store.Store
	Blobs     Blob
	Retention Retention
	Log       *slog.Logger
	Now       func() time.Time
	// MaxUpload caps request bodies. A minute of 48 kHz 16-bit mono is about
	// 5.8 MB; 32 MB leaves room for an enrolment without inviting abuse.
	MaxUpload int64
}

func (s *Server) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now().UTC()
}

func (s *Server) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

// Routes returns the mux.
func (s *Server) Routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/voices/{voiceID}/samples", s.postSample)
	mux.HandleFunc("DELETE /v1/voices/{voiceID}", s.deleteVoice)
	mux.HandleFunc("POST /v1/plan", s.postPlan)
	return mux
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

type sampleResponse struct {
	Accepted bool            `json:"accepted"`
	SampleID domain.SampleID `json:"sample_id,omitempty"`
	Report   *audioqc.Report `json:"quality"`
	Retry    *retryGuidance  `json:"retry,omitempty"`
}

// retryGuidance is what the app turns into a sentence and a button. Codes, not
// prose: the parent sees their own language.
type retryGuidance struct {
	Codes []audioqc.Code `json:"codes"`
}

// postSample takes one recording, judges it, and stores it only if it passed.
//
// A rejected sample is answered with 200 and accepted=false rather than an
// error status: from the app's point of view a retake is a normal outcome of a
// successful request, and the report is the payload it needs.
func (s *Server) postSample(w http.ResponseWriter, r *http.Request) {
	voiceID := domain.VoiceID(r.PathValue("voiceID"))

	max := s.MaxUpload
	if max <= 0 {
		max = 32 << 20
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, max))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "read body: %v", err)
		return
	}

	kind := domain.SampleKind(r.URL.Query().Get("kind"))
	transcript := r.URL.Query().Get("transcript")
	language := r.URL.Query().Get("language")

	profile := audioqc.ProfileDaily
	if kind == domain.SampleEnrolment {
		profile = audioqc.ProfileEnrolment
	}
	// Zero-shot cloning conditions on the reference transcript as well as the
	// audio, so a missing one is a client bug worth failing loudly on rather
	// than a quality problem to discover later.
	if transcript == "" {
		writeErr(w, http.StatusBadRequest, "transcript is required")
		return
	}

	voice, err := s.Store.Voice(r.Context(), voiceID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "unknown voice")
		return
	}
	if voice.Revoked() {
		writeErr(w, http.StatusForbidden, "voice is revoked")
		return
	}

	rep, err := audioqc.Analyse(bytes.NewReader(body), profile)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "decode audio: %v", err)
		return
	}

	if !rep.Passed {
		codes := make([]audioqc.Code, 0, len(rep.Failures))
		for _, f := range rep.Failures {
			codes = append(codes, f.Code)
		}
		s.log().Info("sample rejected",
			"voice", voiceID, "kind", kind,
			"snr_db", rep.SNRdB, "cutoff_hz", rep.CutoffHz, "codes", codes)
		writeJSON(w, http.StatusOK, sampleResponse{
			Accepted: false,
			Report:   rep,
			Retry:    &retryGuidance{Codes: codes},
		})
		return
	}

	now := s.now()
	id := domain.SampleID(fmt.Sprintf("%s-%d", kind, now.UnixNano()))
	uri, err := s.Blobs.Put(r.Context(), string(voiceID)+"/"+string(id)+".wav", body)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "store audio: %v", err)
		return
	}

	sample := domain.VoiceSample{
		ID:         id,
		VoiceID:    voiceID,
		Kind:       kind,
		Language:   language,
		Transcript: transcript,
		URI:        uri,
		CreatedAt:  now,
		ExpiresAt:  s.expiry(kind, now),
		Quality: domain.QualityStamp{
			Passed:        true,
			SNRdB:         rep.SNRdB,
			SpeechSeconds: rep.SpeechSeconds,
			CutoffHz:      rep.CutoffHz,
			Reason:        rep.Reason(),
		},
	}
	if err := s.Store.AppendSample(r.Context(), voiceID, sample); err != nil {
		writeErr(w, http.StatusInternalServerError, "save sample: %v", err)
		return
	}

	s.log().Info("sample accepted",
		"voice", voiceID, "sample", id, "kind", kind,
		"snr_db", rep.SNRdB, "cutoff_hz", rep.CutoffHz, "speech_s", rep.SpeechSeconds)

	writeJSON(w, http.StatusOK, sampleResponse{Accepted: true, SampleID: id, Report: rep})
}

func (s *Server) expiry(kind domain.SampleKind, now time.Time) time.Time {
	ret := s.Retention
	if ret.Daily == 0 && ret.Inventory == 0 {
		ret = DefaultRetention
	}
	switch kind {
	case domain.SampleDaily:
		return now.Add(ret.Daily)
	case domain.SampleInventory:
		return now.Add(ret.Inventory)
	default:
		// Enrolment samples live until the member deletes the voice.
		return time.Time{}
	}
}

// deleteVoice honours a deletion request. The store removes the voiceprint and
// every sample together; there is no partial form of this operation.
func (s *Server) deleteVoice(w http.ResponseWriter, r *http.Request) {
	id := domain.VoiceID(r.PathValue("voiceID"))
	if err := s.Store.DeleteVoice(r.Context(), id); err != nil {
		writeErr(w, http.StatusNotFound, "unknown voice")
		return
	}
	s.log().Info("voice deleted", "voice", id)
	w.WriteHeader(http.StatusNoContent)
}

type planRequest struct {
	FamilyID domain.FamilyID `json:"family_id"`
	ChildID  domain.ChildID  `json:"child_id"`
	MemberID domain.MemberID `json:"member_id"`
	// At previews a specific moment; empty means now.
	At string `json:"at,omitempty"`
}

type planResponse struct {
	Tier      string    `json:"tier"`
	Story     string    `json:"story_id"`
	HasOpen   bool      `json:"has_opening"`
	DeliverAt time.Time `json:"deliver_at"`
	LocalWall string    `json:"local_wall"`
	WallKind  string    `json:"wall_kind"`
	StartGen  time.Time `json:"start_generation_at"`
	Notes     string    `json:"notes"`
}

// postPlan answers "what will this child hear tonight, and why". It is the
// endpoint support will live in: every degradation decision is visible, with
// the reason attached.
func (s *Server) postPlan(w http.ResponseWriter, r *http.Request) {
	var req planRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "decode: %v", err)
		return
	}

	now := s.now()
	if req.At != "" {
		t, err := time.Parse(time.RFC3339, req.At)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "parse at: %v", err)
			return
		}
		now = t
	}

	fam, err := s.Store.Family(r.Context(), req.FamilyID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "unknown family")
		return
	}

	var child domain.Child
	for _, c := range fam.Children {
		if c.ID == req.ChildID {
			child = c
		}
	}
	if child.ID == "" {
		writeErr(w, http.StatusNotFound, "unknown child")
		return
	}
	loc, err := child.Location()
	if err != nil {
		// A bad timezone is a configuration error that would silently deliver
		// at the wrong hour, so it is a hard failure, not a default to UTC.
		writeErr(w, http.StatusUnprocessableEntity, "%v", err)
		return
	}

	spec := schedule.Spec{
		Child:   domain.FamilyChild{FamilyID: fam.ID, ChildID: child.ID},
		Hour:    20,
		Minute:  30,
		Enabled: true,
	}
	occ, err := schedule.NextOccurrence(spec, loc, now)
	if err != nil {
		writeErr(w, http.StatusUnprocessableEntity, "schedule: %v", err)
		return
	}

	av := schedule.Availability{Now: now}
	if v, err := s.Store.VoiceForMember(r.Context(), req.MemberID); err == nil {
		av.Voice = &v
		for i := range v.Samples {
			if v.Samples[i].Kind == domain.SampleDaily && v.Samples[i].Usable(now) {
				av.DailySample = &v.Samples[i]
				break
			}
		}
	}
	if stories, err := s.Store.StoriesFor(r.Context(), child.Language, child.AgeBand); err == nil && len(stories) > 0 {
		av.DefaultStory = stories[0].ID
	}

	plan, err := schedule.Decide(occ, spec.Child, av, schedule.DefaultLead)
	if err != nil {
		writeErr(w, http.StatusUnprocessableEntity, "plan: %v", err)
		return
	}

	writeJSON(w, http.StatusOK, planResponse{
		Tier:      plan.Tier.String(),
		Story:     string(plan.StoryID),
		HasOpen:   plan.Opening != nil,
		DeliverAt: plan.DeliverAt,
		LocalWall: plan.LocalWall.Format("2006-01-02 15:04 MST"),
		WallKind:  string(plan.WallKind),
		StartGen:  plan.StartGenerationAt,
		Notes:     plan.Notes,
	})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, format string, args ...any) {
	writeJSON(w, code, map[string]string{"error": fmt.Sprintf(format, args...)})
}
