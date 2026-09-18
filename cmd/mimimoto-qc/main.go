// Command mimimoto-qc judges recordings against the voice-cloning quality gate.
//
// It exists to be useful before the product does. The first real step for this
// project is a manual pilot — generate stories for a handful of families by
// hand and watch what happens — and the fastest way to waste that pilot is to
// clone from a bad reference and conclude the model is not good enough.
//
//	mimimoto-qc -profile enrolment dad-enrolment.wav
//	mimimoto-qc -json *.wav | jq -r 'select(.passed|not) | .file'
//
// Exit status is non-zero if any file fails, so it drops straight into a
// pre-flight script.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/githubflyideas/mimimoto/internal/audioqc"
)

type output struct {
	File string `json:"file"`
	*audioqc.Report
	Error string `json:"error,omitempty"`
}

func main() {
	var (
		profile = flag.String("profile", "daily", "quality profile: enrolment or daily")
		asJSON  = flag.Bool("json", false, "emit one JSON object per file")
		quiet   = flag.Bool("quiet", false, "print only failures")
	)
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s [flags] file.wav...\n\nflags:\n", filepath.Base(os.Args[0]))
		flag.PrintDefaults()
	}
	flag.Parse()

	files := flag.Args()
	if len(files) == 0 {
		flag.Usage()
		os.Exit(2)
	}

	var prof audioqc.Profile
	switch *profile {
	case "enrolment", "enrollment":
		prof = audioqc.ProfileEnrolment
	case "daily":
		prof = audioqc.ProfileDaily
	default:
		fmt.Fprintf(os.Stderr, "unknown profile %q\n", *profile)
		os.Exit(2)
	}

	enc := json.NewEncoder(os.Stdout)
	failed := 0

	for _, name := range files {
		rep, err := analyse(name, prof)
		if err != nil {
			failed++
			if *asJSON {
				_ = enc.Encode(output{File: name, Error: err.Error()})
			} else {
				fmt.Printf("%-40s ERROR  %v\n", name, err)
			}
			continue
		}
		if !rep.Passed {
			failed++
		}
		if *asJSON {
			_ = enc.Encode(output{File: name, Report: rep})
			continue
		}
		if rep.Passed && *quiet {
			continue
		}
		printHuman(name, rep)
	}

	if failed > 0 {
		fmt.Fprintf(os.Stderr, "\n%d of %d file(s) not usable as a voice reference\n", failed, len(files))
		os.Exit(1)
	}
}

func analyse(name string, p audioqc.Profile) (*audioqc.Report, error) {
	f, err := os.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return audioqc.Analyse(f, p)
}

func printHuman(name string, r *audioqc.Report) {
	verdict := "PASS"
	if !r.Passed {
		verdict = "FAIL"
	}
	fmt.Printf("%-40s %s\n", name, verdict)
	fmt.Printf("  %d Hz / %d-bit / %d ch, %.1fs (%.1fs speech, %.0f%%)\n",
		r.SampleRate, r.BitDepth, r.Channels, r.Duration, r.SpeechSeconds, r.SpeechRatio*100)
	fmt.Printf("  SNR %.1f dB   cutoff %.0f Hz   clipping %.2f%%\n",
		r.SNRdB, r.CutoffHz, r.ClippingRatio*100)
	for _, f := range r.Failures {
		fmt.Printf("  ✗ %s\n", f)
	}
	for _, w := range r.Warnings {
		fmt.Printf("  ! %s\n", w)
	}
	fmt.Println()
}
