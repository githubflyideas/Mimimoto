// Command mimimotod runs the API and the sample-expiry loop.
//
// It is scoped to the stage this project is actually at: enough to run a pilot
// against real families with real recordings, and no more. State is in memory
// and audio is on local disk, which is the honest choice while the storage
// question is still open (see docs/DECISIONS.md, 未决).
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "time/tzdata" // embed the zone database rather than trusting the image

	"github.com/githubflyideas/mimimoto/internal/httpapi"
	"github.com/githubflyideas/mimimoto/internal/store"
)

func main() {
	var (
		addr    = flag.String("addr", ":8080", "listen address")
		blobDir = flag.String("blobs", "./data/blobs", "directory for audio files")
		verbose = flag.Bool("v", false, "debug logging")
	)
	flag.Parse()

	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	blobs, err := NewFileBlobs(*blobDir)
	if err != nil {
		log.Error("open blob directory", "err", err)
		os.Exit(1)
	}

	st := store.NewMemory()
	srv := &httpapi.Server{
		Store:     st,
		Blobs:     blobs,
		Retention: httpapi.DefaultRetention,
		Log:       log,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go expiryLoop(ctx, st, log)

	h := &http.Server{
		Addr:              *addr,
		Handler:           srv.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		// Generous: an enrolment upload over a poor mobile link is the whole
		// point of the product's target market.
		ReadTimeout:  2 * time.Minute,
		WriteTimeout: 2 * time.Minute,
	}

	go func() {
		log.Info("listening", "addr", *addr, "blobs", *blobDir)
		if err := h.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("serve", "err", err)
			stop()
		}
	}()

	<-ctx.Done()
	log.Info("shutting down")

	shutCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := h.Shutdown(shutCtx); err != nil {
		log.Error("shutdown", "err", err)
	}
}

// expiryLoop deletes samples past their retention date.
//
// Daily notes are short-lived by design, and retention that only happens when
// someone remembers to run it is not retention.
func expiryLoop(ctx context.Context, st *store.Memory, log *slog.Logger) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			n, err := st.ExpireSamples(ctx, time.Now().UTC())
			if err != nil {
				log.Error("expire samples", "err", err)
				continue
			}
			if n > 0 {
				log.Info("expired samples", "count", n)
			}
		}
	}
}
