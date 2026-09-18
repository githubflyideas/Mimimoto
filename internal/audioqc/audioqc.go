// Package audioqc decides whether a recording is good enough to clone a voice
// from.
//
// This gate is load-bearing. Cloning quality is dominated by reference quality,
// and a bad reference does not fail loudly — it produces a voice that is
// recognisably "almost" the parent, which is worse than no product at all: a
// child notices the wrong prosody long before an adult does. So the rule is
// that a sample either passes here or it never reaches synthesis, and the
// parent is asked to record again while they still care.
//
// Everything is measured from the signal itself. We deliberately do not trust
// what the container claims: an Android handset reporting 48 kHz while feeding
// us 16 kHz content upsampled in the HAL is a routine occurrence, and the only
// way to catch it is to look at where the spectrum actually stops
// (Report.CutoffHz).
package audioqc

import (
	"fmt"
	"io"
	"math"
)

// Profile selects thresholds by how the recording was obtained. A 10-second
// daily voice note cannot be held to the same duration bar as a guided
// enrolment, but it must meet the same fidelity bar.
type Profile string

const (
	// ProfileEnrolment is the guided liveness recording. Long, and we can ask
	// for a retake without costing anything.
	ProfileEnrolment Profile = "enrolment"
	// ProfileDaily is a parent's short voice note, recorded on the move. Short
	// minimum duration; same spectral and noise requirements.
	ProfileDaily Profile = "daily"
)

// Policy holds the thresholds. Exposed so that field data can move them
// without a code change — expect to tune these once real recordings arrive.
type Policy struct {
	MinSampleRate int

	MinSpeechSeconds float64
	MinSpeechRatio   float64

	// FailSNRdB and WarnSNRdB bracket the noise requirement.
	FailSNRdB float64
	WarnSNRdB float64

	// FailCutoffHz catches telephony-band and heavily processed audio.
	// WarnCutoffHz catches 16 kHz content dressed up as 48 kHz.
	FailCutoffHz float64
	WarnCutoffHz float64

	MaxClippingRatio float64
	MaxDCOffset      float64
}

// PolicyFor returns the default thresholds for a profile.
func PolicyFor(p Profile) Policy {
	base := Policy{
		MinSampleRate:    16000,
		MinSpeechRatio:   0.25,
		FailSNRdB:        18,
		WarnSNRdB:        25,
		FailCutoffHz:     7000,
		WarnCutoffHz:     10000,
		MaxClippingRatio: 0.005,
		MaxDCOffset:      0.02,
	}
	switch p {
	case ProfileEnrolment:
		base.MinSpeechSeconds = 20
	default: // ProfileDaily
		base.MinSpeechSeconds = 2.5
		// A voice note dictated while walking will not be pristine. We keep the
		// spectral bar (it is what distinguishes a usable reference from a
		// telephone-sounding one) and relax only the noise bar a little.
		base.FailSNRdB = 15
	}
	return base
}

// Code identifies a finding so the client can localise it. The parent sees a
// translated sentence and a retake button, never this string.
type Code string

const (
	CodeTooShort      Code = "too_short"
	CodeLowSampleRate Code = "low_sample_rate"
	CodeNoisy         Code = "noisy"
	CodeBandLimited   Code = "band_limited"
	CodeClipping      Code = "clipping"
	CodeDCOffset      Code = "dc_offset"
	CodeMostlySilence Code = "mostly_silence"
	CodeNoSpeech      Code = "no_speech"
)

// Finding is one reason the recording is not ideal.
type Finding struct {
	Code      Code    `json:"code"`
	Message   string  `json:"message"` // English, for logs and triage
	Measured  float64 `json:"measured"`
	Threshold float64 `json:"threshold"`
}

func (f Finding) String() string {
	return fmt.Sprintf("%s: %s (measured %.2f, threshold %.2f)", f.Code, f.Message, f.Measured, f.Threshold)
}

// Report is the full verdict. Persisted in trimmed form as
// domain.QualityStamp.
type Report struct {
	Duration   float64 `json:"duration_s"`
	SampleRate int     `json:"sample_rate"`
	Channels   int     `json:"channels"`
	BitDepth   int     `json:"bit_depth"`

	// SNRdB is the spread between speech-level and noise-floor frame energies.
	// Note the limitation: a recording with no pauses at all under-reports,
	// because the noise-floor percentile lands on quiet speech. In practice
	// voice notes always contain pauses.
	SNRdB           float64 `json:"snr_db"`
	NoiseFloorDBFS  float64 `json:"noise_floor_dbfs"`
	SpeechLevelDBFS float64 `json:"speech_level_dbfs"`

	SpeechSeconds float64 `json:"speech_s"`
	SpeechRatio   float64 `json:"speech_ratio"`

	// CutoffHz is the highest frequency carrying real content. This is the
	// single most diagnostic number in the report.
	CutoffHz float64 `json:"cutoff_hz"`

	ClippingRatio float64 `json:"clipping_ratio"`
	DCOffset      float64 `json:"dc_offset"`

	Passed   bool      `json:"passed"`
	Failures []Finding `json:"failures,omitempty"`
	Warnings []Finding `json:"warnings,omitempty"`
}

// Reason returns a short summary for storage alongside the sample.
func (r Report) Reason() string {
	if r.Passed {
		if len(r.Warnings) == 0 {
			return "ok"
		}
		return "ok with warnings: " + r.Warnings[0].String()
	}
	if len(r.Failures) == 0 {
		return "rejected"
	}
	return r.Failures[0].String()
}

// Analyse decodes a WAV stream and judges it against the profile's policy.
func Analyse(r io.Reader, p Profile) (*Report, error) {
	a, err := DecodeWAV(r)
	if err != nil {
		return nil, err
	}
	return Judge(a, PolicyFor(p)), nil
}

const (
	frameMS = 25
	hopMS   = 10
	// floorDB clamps digital silence, which would otherwise be -Inf dB and
	// poison every percentile downstream.
	floorDB float64 = -120
)

// Judge measures an already-decoded signal.
func Judge(a *Audio, pol Policy) *Report {
	rep := &Report{
		Duration:   a.Duration(),
		SampleRate: a.SampleRate,
		Channels:   a.Channels,
		BitDepth:   a.BitDepth,
	}

	frameLen := a.SampleRate * frameMS / 1000
	hopLen := a.SampleRate * hopMS / 1000

	if a.SampleRate < pol.MinSampleRate {
		rep.fail(CodeLowSampleRate,
			"sample rate is below the minimum usable for voice cloning",
			float64(a.SampleRate), float64(pol.MinSampleRate))
	}
	if frameLen == 0 || len(a.Samples) < frameLen {
		rep.fail(CodeTooShort, "recording is shorter than one analysis frame",
			rep.Duration, pol.MinSpeechSeconds)
		rep.Passed = false
		return rep
	}

	// ---- amplitude-domain measures ----
	rep.ClippingRatio, rep.DCOffset = amplitudeStats(a.Samples)
	if rep.ClippingRatio > pol.MaxClippingRatio {
		rep.fail(CodeClipping,
			"input is clipped; the microphone gain is too high or the speaker is too close",
			rep.ClippingRatio, pol.MaxClippingRatio)
	}
	if math.Abs(rep.DCOffset) > pol.MaxDCOffset {
		// Not fatal for cloning, but a strong hint the capture path is broken.
		rep.warn(CodeDCOffset, "signal has a DC offset; check the capture path",
			math.Abs(rep.DCOffset), pol.MaxDCOffset)
	}

	// ---- frame energies, noise floor, VAD ----
	frameDB := frameEnergiesDB(a.Samples, frameLen, hopLen)
	rep.NoiseFloorDBFS = percentile(frameDB, 10)
	rep.SpeechLevelDBFS = percentile(frameDB, 95)
	rep.SNRdB = rep.SpeechLevelDBFS - rep.NoiseFloorDBFS

	// A frame counts as speech when it stands 6 dB above the floor and is not
	// itself near-silent in absolute terms. The absolute guard stops a
	// recording of a quiet room from reading as 100% speech.
	vadThresh := math.Max(rep.NoiseFloorDBFS+6, -55)
	voiced := make([]int, 0, len(frameDB))
	for i, db := range frameDB {
		if db >= vadThresh {
			voiced = append(voiced, i)
		}
	}
	rep.SpeechSeconds = float64(len(voiced)) * float64(hopMS) / 1000
	if rep.Duration > 0 {
		rep.SpeechRatio = rep.SpeechSeconds / rep.Duration
	}

	switch {
	case len(voiced) == 0:
		rep.fail(CodeNoSpeech, "no speech detected", 0, pol.MinSpeechSeconds)
	case rep.SpeechSeconds < pol.MinSpeechSeconds:
		rep.fail(CodeTooShort, "not enough speech in the recording",
			rep.SpeechSeconds, pol.MinSpeechSeconds)
	}
	if len(voiced) > 0 && rep.SpeechRatio < pol.MinSpeechRatio {
		rep.warn(CodeMostlySilence, "recording is mostly silence",
			rep.SpeechRatio, pol.MinSpeechRatio)
	}

	switch {
	case rep.SNRdB < pol.FailSNRdB:
		rep.fail(CodeNoisy, "background noise is too high for a usable reference",
			rep.SNRdB, pol.FailSNRdB)
	case rep.SNRdB < pol.WarnSNRdB:
		rep.warn(CodeNoisy, "background noise is higher than ideal",
			rep.SNRdB, pol.WarnSNRdB)
	}

	// ---- spectral cutoff ----
	if len(voiced) > 0 {
		rep.CutoffHz = spectralCutoff(a, voiced, frameLen, hopLen)
		switch {
		case rep.CutoffHz < pol.FailCutoffHz:
			rep.fail(CodeBandLimited,
				"audio is band-limited; it has been through a call or voice-chat pipeline and is not usable as a reference",
				rep.CutoffHz, pol.FailCutoffHz)
		case rep.CutoffHz < pol.WarnCutoffHz:
			rep.warn(CodeBandLimited,
				"audio carries no high-frequency content; the device is likely upsampling",
				rep.CutoffHz, pol.WarnCutoffHz)
		}
	}

	rep.Passed = len(rep.Failures) == 0
	return rep
}

func (r *Report) fail(c Code, msg string, measured, threshold float64) {
	r.Failures = append(r.Failures, Finding{Code: c, Message: msg, Measured: measured, Threshold: threshold})
}

func (r *Report) warn(c Code, msg string, measured, threshold float64) {
	r.Warnings = append(r.Warnings, Finding{Code: c, Message: msg, Measured: measured, Threshold: threshold})
}

// amplitudeStats returns the clipped-sample ratio and the DC offset.
//
// A sample counts as clipped only inside a run of at least three consecutive
// near-full-scale samples: isolated peaks at full scale are normal, flat tops
// are not.
func amplitudeStats(s []float64) (clipRatio, dc float64) {
	const ceiling = 0.995
	const minRun = 3

	sum := 0.0
	clipped, run := 0, 0
	for _, v := range s {
		sum += v
		if math.Abs(v) >= ceiling {
			run++
		} else {
			if run >= minRun {
				clipped += run
			}
			run = 0
		}
	}
	if run >= minRun {
		clipped += run
	}
	if len(s) == 0 {
		return 0, 0
	}
	return float64(clipped) / float64(len(s)), sum / float64(len(s))
}

// frameEnergiesDB returns per-frame RMS in dBFS.
func frameEnergiesDB(s []float64, frameLen, hopLen int) []float64 {
	if hopLen <= 0 {
		hopLen = 1
	}
	n := 0
	if len(s) >= frameLen {
		n = (len(s)-frameLen)/hopLen + 1
	}
	out := make([]float64, 0, n)
	for i := 0; i+frameLen <= len(s); i += hopLen {
		sum := 0.0
		for _, v := range s[i : i+frameLen] {
			sum += v * v
		}
		rms := math.Sqrt(sum / float64(frameLen))
		db := floorDB
		if rms > 0 {
			db = 20 * math.Log10(rms)
			if db < floorDB {
				db = floorDB
			}
		}
		out = append(out, db)
	}
	return out
}

// spectralCutoff estimates the highest frequency carrying real content.
//
// Method: average the magnitude spectrum over voiced frames only (silence
// would drag the average into the numerical floor), smooth it with a short
// median filter to reject single-bin spikes, take a reference level from the
// speech band, then walk down from Nyquist for the first bin standing more
// than cutoffFloorDB below that reference.
//
// A 48 kHz recording from a real microphone keeps content well past 15 kHz.
// 16 kHz content upsampled to 48 kHz stops dead at 8 kHz. Telephony and
// voice-chat pipelines stop around 3.4-4 kHz. The three cases are far enough
// apart that this is a robust discriminator despite the crude method.
func spectralCutoff(a *Audio, voiced []int, frameLen, hopLen int) float64 {
	const cutoffFloorDB = 60 // how far below the speech-band peak still counts as content

	nfft := nextPow2(frameLen)
	if nfft < 512 {
		nfft = 512
	}
	win := hann(frameLen)
	half := nfft / 2

	acc := make([]float64, half)
	re := make([]float64, nfft)
	im := make([]float64, nfft)

	// Cap the number of frames analysed: the estimate converges quickly and a
	// five-minute enrolment should not cost a full-file FFT sweep.
	const maxFrames = 400
	step := 1
	if len(voiced) > maxFrames {
		step = len(voiced) / maxFrames
	}

	used := 0
	for k := 0; k < len(voiced); k += step {
		start := voiced[k] * hopLen
		if start+frameLen > len(a.Samples) {
			continue
		}
		clear(re)
		clear(im)
		for i := range frameLen {
			re[i] = a.Samples[start+i] * win[i]
		}
		fftRadix2(re, im)
		for i := range half {
			acc[i] += math.Hypot(re[i], im[i])
		}
		used++
	}
	if used == 0 {
		return 0
	}

	spec := make([]float64, half)
	for i := range acc {
		v := acc[i] / float64(used)
		if v <= 0 {
			spec[i] = floorDB
			continue
		}
		spec[i] = 20 * math.Log10(v)
	}

	// Median-smooth over 5 bins.
	smooth := make([]float64, half)
	for i := range spec {
		lo := max(i-2, 0)
		hi := min(i+3, half)
		smooth[i] = medianOf(spec[lo:hi])
	}

	binHz := float64(a.SampleRate) / float64(nfft)
	refBin := func(hz float64) int {
		return min(max(int(hz/binHz), 0), half-1)
	}

	// Reference: the strongest bin in the core speech band.
	ref := floorDB
	for i := refBin(200); i <= refBin(4000) && i < half; i++ {
		if smooth[i] > ref {
			ref = smooth[i]
		}
	}
	threshold := ref - cutoffFloorDB

	for i := half - 1; i >= 0; i-- {
		if smooth[i] > threshold {
			return float64(i) * binHz
		}
	}
	return 0
}
