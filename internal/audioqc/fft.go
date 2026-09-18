package audioqc

import (
	"math"
	"slices"
)

// fftRadix2 computes an in-place forward DFT of re/im, whose length must be a
// power of two. Iterative Cooley-Tukey; no dependencies, no allocation beyond
// the caller's slices.
func fftRadix2(re, im []float64) {
	n := len(re)
	if n <= 1 {
		return
	}

	// Bit-reversal permutation.
	for i, j := 1, 0; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j |= bit
		if i < j {
			re[i], re[j] = re[j], re[i]
			im[i], im[j] = im[j], im[i]
		}
	}

	for length := 2; length <= n; length <<= 1 {
		ang := -2 * math.Pi / float64(length)
		wRe, wIm := math.Cos(ang), math.Sin(ang)
		for i := 0; i < n; i += length {
			curRe, curIm := 1.0, 0.0
			half := length / 2
			for j := range half {
				uRe, uIm := re[i+j], im[i+j]
				vRe := re[i+j+half]*curRe - im[i+j+half]*curIm
				vIm := re[i+j+half]*curIm + im[i+j+half]*curRe
				re[i+j], im[i+j] = uRe+vRe, uIm+vIm
				re[i+j+half], im[i+j+half] = uRe-vRe, uIm-vIm
				curRe, curIm = curRe*wRe-curIm*wIm, curRe*wIm+curIm*wRe
			}
		}
	}
}

// hann returns a periodic Hann window of length n.
func hann(n int) []float64 {
	w := make([]float64, n)
	for i := range w {
		w[i] = 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/float64(n))
	}
	return w
}

// nextPow2 returns the smallest power of two >= n.
func nextPow2(n int) int {
	p := 1
	for p < n {
		p <<= 1
	}
	return p
}

// medianOf returns the median of a copy of xs. xs is not modified.
func medianOf(xs []float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	c := slices.Clone(xs)
	slices.Sort(c)
	m := len(c) / 2
	if len(c)%2 == 1 {
		return c[m]
	}
	return (c[m-1] + c[m]) / 2
}

// percentile returns the p-th percentile (0..100) using nearest-rank on a copy.
func percentile(xs []float64, p float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	c := slices.Clone(xs)
	slices.Sort(c)
	idx := int(math.Round(p / 100 * float64(len(c)-1)))
	idx = min(max(idx, 0), len(c)-1)
	return c[idx]
}
