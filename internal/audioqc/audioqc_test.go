package audioqc

import (
	"bytes"
	"encoding/binary"
	"math"
	"math/rand"
	"testing"
)

// ---- test signal synthesis ----
//
// We build speech-like signals rather than using fixtures so the tests stay
// self-contained and the band-limiting is exact: a signal whose highest
// harmonic is 8 kHz is precisely what a 16 kHz recording upsampled to 48 kHz
// looks like, which is the case the gate exists to catch.

type sigOpts struct {
	rate       int
	seconds    float64
	f0         float64
	maxHarmHz  float64 // highest harmonic present
	amp        float64
	noise      float64
	pauseEvery float64 // seconds of speech between pauses
	pauseLen   float64
	dc         float64
	clip       bool
}

func synth(o sigOpts) *Audio {
	n := int(float64(o.rate) * o.seconds)
	out := make([]float64, n)
	rng := rand.New(rand.NewSource(42))

	nyquist := float64(o.rate) / 2
	top := math.Min(o.maxHarmHz, nyquist*0.98)
	nHarm := int(top / o.f0)
	if nHarm < 1 {
		nHarm = 1
	}

	period := o.pauseEvery + o.pauseLen
	for i := range n {
		t := float64(i) / float64(o.rate)

		// Syllable gate: speech for pauseEvery, silence for pauseLen.
		gate := 1.0
		if period > 0 && math.Mod(t, period) > o.pauseEvery {
			gate = 0
		}
		// Syllable-rate amplitude modulation keeps frame energies varied the
		// way real speech does.
		env := 0.6 + 0.4*math.Sin(2*math.Pi*4*t)

		v := 0.0
		if gate > 0 {
			for h := 1; h <= nHarm; h++ {
				f := o.f0 * float64(h)
				// 1/h rolloff, plus a crude formant bump around 700 Hz.
				a := 1 / float64(h)
				if f > 500 && f < 1000 {
					a *= 2
				}
				v += a * math.Sin(2*math.Pi*f*t+float64(h))
			}
			v *= o.amp * env / 3
		}
		v += rng.NormFloat64() * o.noise
		v += o.dc
		if o.clip {
			v *= 6 // mic gain far too high, or the speaker is right on the capsule
		}
		out[i] = math.Max(-1, math.Min(1, v))
	}

	return &Audio{Samples: out, SampleRate: o.rate, Channels: 1, BitDepth: 16}
}

func cleanWideband(seconds float64) sigOpts {
	return sigOpts{
		rate: 48000, seconds: seconds, f0: 120, maxHarmHz: 20000,
		amp: 0.5, noise: 1e-4, pauseEvery: 1.2, pauseLen: 0.4,
	}
}

// encodeWAV writes a 16-bit PCM mono WAV so the decoder is exercised too.
func encodeWAV(a *Audio) []byte {
	var b bytes.Buffer
	dataLen := len(a.Samples) * 2
	b.WriteString("RIFF")
	binary.Write(&b, binary.LittleEndian, uint32(36+dataLen))
	b.WriteString("WAVEfmt ")
	binary.Write(&b, binary.LittleEndian, uint32(16))
	binary.Write(&b, binary.LittleEndian, uint16(1))
	binary.Write(&b, binary.LittleEndian, uint16(1))
	binary.Write(&b, binary.LittleEndian, uint32(a.SampleRate))
	binary.Write(&b, binary.LittleEndian, uint32(a.SampleRate*2))
	binary.Write(&b, binary.LittleEndian, uint16(2))
	binary.Write(&b, binary.LittleEndian, uint16(16))
	b.WriteString("data")
	binary.Write(&b, binary.LittleEndian, uint32(dataLen))
	for _, s := range a.Samples {
		binary.Write(&b, binary.LittleEndian, int16(math.Round(math.Max(-1, math.Min(1, s))*32767)))
	}
	return b.Bytes()
}

func hasCode(fs []Finding, c Code) bool {
	for _, f := range fs {
		if f.Code == c {
			return true
		}
	}
	return false
}

// ---- decoder ----

func TestDecodeWAVRoundTrip(t *testing.T) {
	src := synth(cleanWideband(1))
	got, err := DecodeWAV(bytes.NewReader(encodeWAV(src)))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.SampleRate != 48000 || got.Channels != 1 || got.BitDepth != 16 {
		t.Fatalf("header mismatch: %+v", *got)
	}
	if len(got.Samples) != len(src.Samples) {
		t.Fatalf("sample count: got %d want %d", len(got.Samples), len(src.Samples))
	}
	for i := range got.Samples {
		if math.Abs(got.Samples[i]-src.Samples[i]) > 1.0/32767+1e-9 {
			t.Fatalf("sample %d: got %v want %v", i, got.Samples[i], src.Samples[i])
		}
	}
}

func TestDecodeWAVRejectsGarbage(t *testing.T) {
	if _, err := DecodeWAV(bytes.NewReader([]byte("not a wav at all"))); err == nil {
		t.Fatal("expected error for non-RIFF input")
	}
}

func TestDecodeWAVSkipsUnknownChunks(t *testing.T) {
	// Splice a LIST chunk between fmt and data, as phone recorders do.
	base := encodeWAV(synth(cleanWideband(0.5)))
	idx := bytes.Index(base, []byte("data"))
	if idx < 0 {
		t.Fatal("no data chunk in fixture")
	}
	var extra bytes.Buffer
	extra.WriteString("LIST")
	binary.Write(&extra, binary.LittleEndian, uint32(4))
	extra.WriteString("INFO")

	spliced := append([]byte{}, base[:idx]...)
	spliced = append(spliced, extra.Bytes()...)
	spliced = append(spliced, base[idx:]...)
	binary.LittleEndian.PutUint32(spliced[4:8], uint32(len(spliced)-8))

	got, err := DecodeWAV(bytes.NewReader(spliced))
	if err != nil {
		t.Fatalf("decode with LIST chunk: %v", err)
	}
	if len(got.Samples) == 0 {
		t.Fatal("no samples decoded")
	}
}

// ---- the gate ----

func TestCleanWidebandPasses(t *testing.T) {
	rep, err := Analyse(bytes.NewReader(encodeWAV(synth(cleanWideband(30)))), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	if !rep.Passed {
		t.Fatalf("clean wideband should pass, got failures %v (report %+v)", rep.Failures, *rep)
	}
	if rep.CutoffHz < 15000 {
		t.Errorf("cutoff should be high for wideband input, got %.0f Hz", rep.CutoffHz)
	}
	if rep.SNRdB < 30 {
		t.Errorf("SNR should be high for clean input, got %.1f dB", rep.SNRdB)
	}
}

// The case the gate exists for: a device reporting 48 kHz while the content
// stops at 8 kHz.
func TestUpsampledContentIsDetected(t *testing.T) {
	o := cleanWideband(30)
	o.maxHarmHz = 8000
	rep, err := Analyse(bytes.NewReader(encodeWAV(synth(o))), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	if rep.CutoffHz > 9000 {
		t.Fatalf("cutoff should land near 8 kHz, got %.0f Hz", rep.CutoffHz)
	}
	if !hasCode(rep.Warnings, CodeBandLimited) {
		t.Errorf("expected band_limited warning, warnings=%v failures=%v cutoff=%.0f",
			rep.Warnings, rep.Failures, rep.CutoffHz)
	}
}

// Telephony / voice-chat band. Must be rejected outright: this is the
// "sounds like a phone call, not like dad" failure.
func TestTelephonyBandFails(t *testing.T) {
	o := cleanWideband(30)
	o.maxHarmHz = 3400
	rep, err := Analyse(bytes.NewReader(encodeWAV(synth(o))), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	if rep.Passed {
		t.Fatalf("telephony-band input must not pass (cutoff %.0f Hz)", rep.CutoffHz)
	}
	if !hasCode(rep.Failures, CodeBandLimited) {
		t.Errorf("expected band_limited failure, got %v (cutoff %.0f Hz)", rep.Failures, rep.CutoffHz)
	}
}

func TestNoisyRecordingFails(t *testing.T) {
	o := cleanWideband(30)
	o.noise = 0.05
	rep, err := Analyse(bytes.NewReader(encodeWAV(synth(o))), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	if rep.Passed {
		t.Fatalf("noisy input must not pass (SNR %.1f dB)", rep.SNRdB)
	}
	if !hasCode(rep.Failures, CodeNoisy) {
		t.Errorf("expected noisy failure, got %v (SNR %.1f dB)", rep.Failures, rep.SNRdB)
	}
}

func TestClippingIsDetected(t *testing.T) {
	o := cleanWideband(30)
	o.clip = true
	rep, err := Analyse(bytes.NewReader(encodeWAV(synth(o))), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	if !hasCode(rep.Failures, CodeClipping) {
		t.Errorf("expected clipping failure, got failures=%v ratio=%.4f",
			rep.Failures, rep.ClippingRatio)
	}
}

func TestShortDailyNotePasses(t *testing.T) {
	// "今天讲三只小猪吧" — about 4 seconds with a pause. Must pass as a daily
	// note and fail as an enrolment.
	o := cleanWideband(4)
	o.pauseEvery = 1.5
	o.pauseLen = 0.3
	wav := encodeWAV(synth(o))

	daily, err := Analyse(bytes.NewReader(wav), ProfileDaily)
	if err != nil {
		t.Fatalf("analyse daily: %v", err)
	}
	if !daily.Passed {
		t.Errorf("short note should pass the daily profile, got %v (speech %.2fs)",
			daily.Failures, daily.SpeechSeconds)
	}

	enrol, err := Analyse(bytes.NewReader(wav), ProfileEnrolment)
	if err != nil {
		t.Fatalf("analyse enrolment: %v", err)
	}
	if enrol.Passed {
		t.Error("a 4-second note must not satisfy the enrolment profile")
	}
	if !hasCode(enrol.Failures, CodeTooShort) {
		t.Errorf("expected too_short, got %v", enrol.Failures)
	}
}

func TestSilenceIsRejected(t *testing.T) {
	a := &Audio{Samples: make([]float64, 48000*5), SampleRate: 48000, Channels: 1, BitDepth: 16}
	rep := Judge(a, PolicyFor(ProfileDaily))
	if rep.Passed {
		t.Fatal("digital silence must not pass")
	}
}

func TestLowSampleRateFails(t *testing.T) {
	o := cleanWideband(30)
	o.rate = 8000
	o.maxHarmHz = 3400
	rep := Judge(synth(o), PolicyFor(ProfileDaily))
	if rep.Passed {
		t.Fatal("8 kHz input must not pass")
	}
	if !hasCode(rep.Failures, CodeLowSampleRate) {
		t.Errorf("expected low_sample_rate, got %v", rep.Failures)
	}
}

func TestDCOffsetWarns(t *testing.T) {
	o := cleanWideband(30)
	o.dc = 0.1
	rep := Judge(synth(o), PolicyFor(ProfileEnrolment))
	if !hasCode(rep.Warnings, CodeDCOffset) {
		t.Errorf("expected dc_offset warning, got warnings=%v offset=%.4f", rep.Warnings, rep.DCOffset)
	}
}

// ---- FFT ----

func TestFFTMatchesNaiveDFT(t *testing.T) {
	const n = 64
	rng := rand.New(rand.NewSource(7))
	re := make([]float64, n)
	im := make([]float64, n)
	for i := range re {
		re[i] = rng.NormFloat64()
	}
	wantRe := make([]float64, n)
	wantIm := make([]float64, n)
	for k := range n {
		for t := range n {
			ang := -2 * math.Pi * float64(k) * float64(t) / n
			wantRe[k] += re[t] * math.Cos(ang)
			wantIm[k] += re[t] * math.Sin(ang)
		}
	}
	fftRadix2(re, im)
	for k := range n {
		if math.Abs(re[k]-wantRe[k]) > 1e-9 || math.Abs(im[k]-wantIm[k]) > 1e-9 {
			t.Fatalf("bin %d: got (%v,%v) want (%v,%v)", k, re[k], im[k], wantRe[k], wantIm[k])
		}
	}
}

func TestFFTLocatesTone(t *testing.T) {
	const n, rate = 1024, 48000
	const tone = 3000.0
	re := make([]float64, n)
	im := make([]float64, n)
	for i := range re {
		re[i] = math.Sin(2 * math.Pi * tone * float64(i) / rate)
	}
	fftRadix2(re, im)
	peak, peakMag := 0, 0.0
	for i := 1; i < n/2; i++ {
		if m := math.Hypot(re[i], im[i]); m > peakMag {
			peak, peakMag = i, m
		}
	}
	gotHz := float64(peak) * rate / n
	if math.Abs(gotHz-tone) > float64(rate)/n {
		t.Fatalf("peak at %.0f Hz, want %.0f Hz", gotHz, tone)
	}
}
