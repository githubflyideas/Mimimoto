package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// FileBlobs stores audio under a local directory.
//
// Local disk for the pilot. Whatever replaces it has to answer the storage
// question properly — per-family keys, short retention on raw audio — and that
// answer should come from a decision, not from whichever object store was
// convenient on the day.
type FileBlobs struct{ root string }

func NewFileBlobs(root string) (*FileBlobs, error) {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, err
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	return &FileBlobs{root: abs}, nil
}

func (f *FileBlobs) Put(_ context.Context, key string, data []byte) (string, error) {
	// Keys are built from identifiers we generate, but a path-traversal check
	// costs nothing and removes the question entirely.
	clean := filepath.Clean("/" + key)
	if strings.Contains(clean, "..") {
		return "", fmt.Errorf("blobs: unsafe key %q", key)
	}
	full := filepath.Join(f.root, clean)

	if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
		return "", err
	}
	// Write to a temp file and rename, so a crash mid-write never leaves a
	// truncated sample that would later be cloned from.
	tmp, err := os.CreateTemp(filepath.Dir(full), ".tmp-*")
	if err != nil {
		return "", err
	}
	defer os.Remove(tmp.Name())

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	if err := os.Chmod(tmp.Name(), 0o600); err != nil {
		return "", err
	}
	if err := os.Rename(tmp.Name(), full); err != nil {
		return "", err
	}
	return "file://" + full, nil
}
