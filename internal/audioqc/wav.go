package audioqc

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

// Audio is a decoded, mono, float64 signal normalised to [-1, 1].
type Audio struct {
	Samples    []float64
	SampleRate int
	// Channels is the channel count of the source, before downmixing.
	Channels int
	// BitDepth of the source. 16 on a file claiming 48 kHz is not itself a
	// problem; a *spectral* cutoff at 8 kHz is (see Report.CutoffHz).
	BitDepth int
}

// Duration of the decoded signal.
func (a Audio) Duration() float64 {
	if a.SampleRate == 0 {
		return 0
	}
	return float64(len(a.Samples)) / float64(a.SampleRate)
}

var (
	ErrNotRIFF        = errors.New("audioqc: not a RIFF/WAVE file")
	ErrNoFormat       = errors.New("audioqc: missing fmt chunk")
	ErrNoData         = errors.New("audioqc: missing data chunk")
	ErrUnsupportedFmt = errors.New("audioqc: unsupported sample format")
)

const (
	fmtPCM        = 1
	fmtIEEEFloat  = 3
	fmtExtensible = 0xFFFE
)

// DecodeWAV reads a RIFF/WAVE stream and returns a mono float64 signal.
//
// Supports PCM 8/16/24/32-bit and IEEE float 32/64-bit, including
// WAVE_FORMAT_EXTENSIBLE. Multi-channel input is downmixed by averaging,
// which is what we want for quality assessment: a dead channel should drag
// the verdict down rather than be silently discarded.
func DecodeWAV(r io.Reader) (*Audio, error) {
	buf, err := io.ReadAll(r)
	if err != nil {
		return nil, fmt.Errorf("audioqc: read: %w", err)
	}
	if len(buf) < 12 || string(buf[0:4]) != "RIFF" || string(buf[8:12]) != "WAVE" {
		return nil, ErrNotRIFF
	}

	var (
		format     uint16
		channels   int
		sampleRate int
		bitDepth   int
		data       []byte
		haveFmt    bool
	)

	// Walk the chunk list. Unknown chunks (LIST, fact, bext …) are skipped;
	// phone recorders in particular like to prepend metadata.
	for off := 12; off+8 <= len(buf); {
		id := string(buf[off : off+4])
		size := int(binary.LittleEndian.Uint32(buf[off+4 : off+8]))
		body := off + 8
		if size < 0 || body+size > len(buf) {
			// Truncated final chunk: take whatever is actually present rather
			// than failing. Recordings cut short by a crash are still worth
			// judging.
			size = len(buf) - body
			if size <= 0 {
				break
			}
		}
		switch id {
		case "fmt ":
			if size < 16 {
				return nil, ErrNoFormat
			}
			f := buf[body : body+size]
			format = binary.LittleEndian.Uint16(f[0:2])
			channels = int(binary.LittleEndian.Uint16(f[2:4]))
			sampleRate = int(binary.LittleEndian.Uint32(f[4:8]))
			bitDepth = int(binary.LittleEndian.Uint16(f[14:16]))
			if format == fmtExtensible && size >= 40 {
				// The real format lives in the first two bytes of the GUID.
				format = binary.LittleEndian.Uint16(f[24:26])
			}
			haveFmt = true
		case "data":
			data = buf[body : body+size]
		}
		// Chunks are word-aligned.
		off = body + size
		if size%2 == 1 {
			off++
		}
	}

	if !haveFmt {
		return nil, ErrNoFormat
	}
	if data == nil {
		return nil, ErrNoData
	}
	if channels <= 0 || sampleRate <= 0 {
		return nil, fmt.Errorf("%w: channels=%d rate=%d", ErrUnsupportedFmt, channels, sampleRate)
	}

	frames, err := decodeSamples(data, format, bitDepth, channels)
	if err != nil {
		return nil, err
	}

	return &Audio{
		Samples:    frames,
		SampleRate: sampleRate,
		Channels:   channels,
		BitDepth:   bitDepth,
	}, nil
}

// decodeSamples converts raw bytes to mono float64, averaging channels.
func decodeSamples(data []byte, format uint16, bitDepth, channels int) ([]float64, error) {
	bytesPerSample := bitDepth / 8
	if bytesPerSample == 0 {
		return nil, fmt.Errorf("%w: bit depth %d", ErrUnsupportedFmt, bitDepth)
	}
	stride := bytesPerSample * channels
	n := len(data) / stride
	out := make([]float64, n)

	read := func(b []byte) (float64, error) {
		switch {
		case format == fmtPCM && bitDepth == 8:
			// 8-bit WAV is unsigned, offset by 128.
			return (float64(b[0]) - 128) / 128, nil
		case format == fmtPCM && bitDepth == 16:
			return float64(int16(binary.LittleEndian.Uint16(b))) / 32768, nil
		case format == fmtPCM && bitDepth == 24:
			v := int32(b[0]) | int32(b[1])<<8 | int32(b[2])<<16
			if v&0x800000 != 0 { // sign extend
				v |= ^0xFFFFFF
			}
			return float64(v) / 8388608, nil
		case format == fmtPCM && bitDepth == 32:
			return float64(int32(binary.LittleEndian.Uint32(b))) / 2147483648, nil
		case format == fmtIEEEFloat && bitDepth == 32:
			return float64(math.Float32frombits(binary.LittleEndian.Uint32(b))), nil
		case format == fmtIEEEFloat && bitDepth == 64:
			return math.Float64frombits(binary.LittleEndian.Uint64(b)), nil
		}
		return 0, fmt.Errorf("%w: format=%d depth=%d", ErrUnsupportedFmt, format, bitDepth)
	}

	for i := range n {
		base := i * stride
		sum := 0.0
		for c := range channels {
			v, err := read(data[base+c*bytesPerSample:])
			if err != nil {
				return nil, err
			}
			sum += v
		}
		out[i] = sum / float64(channels)
	}
	return out, nil
}
