/**
 * Tests for `core`'s own internals.
 *
 * These live here rather than in `:server` because `fftRadix2` is `internal`,
 * and Kotlin grants friend access only within a module. That is not a
 * technicality to work around: the FFT is not part of what `core` offers its
 * callers, and the moment it became public to satisfy a test in another module
 * it would be something we owe compatibility on.
 *
 * Worth the sharpness. Every cutoff number the product depends on — 19969 /
 * 8016 / 3492 Hz, and the PASS/WARN/FAIL boundary between a real microphone and
 * a call recording — is computed through this function. A subtle error here
 * would not crash anything; it would shift those numbers, and the pinned-value
 * test in `:server` would then be pinning a wrong answer very precisely.
 * Checking the transform against a naive DFT is the cheapest insurance there
 * is against that.
 */
package mimimoto

import mimimoto.audioqc.fftRadix2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

fun fftTests() = Suite.group("fft") {

    Suite.test("FFT matches a naive DFT") {
        val n = 64
        val rng = Random(7)
        val src = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
        val wantRe = DoubleArray(n)
        val wantIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val ang = -2 * PI * k * t / n
            wantRe[k] += src[t] * kotlin.math.cos(ang)
            wantIm[k] += src[t] * sin(ang)
        }
        val re = src.copyOf()
        val im = DoubleArray(n)
        fftRadix2(re, im)
        for (k in 0 until n) {
            assertTrue(abs(re[k] - wantRe[k]) < 1e-9, "bin $k real: ${re[k]} vs ${wantRe[k]}")
            assertTrue(abs(im[k] - wantIm[k]) < 1e-9, "bin $k imag: ${im[k]} vs ${wantIm[k]}")
        }
    }

    Suite.test("FFT locates a tone") {
        val n = 1024
        val rate = 48_000.0
        val tone = 3_000.0
        val re = DoubleArray(n) { sin(2 * PI * tone * it / rate) }
        val im = DoubleArray(n)
        fftRadix2(re, im)
        var peak = 0
        var peakMag = 0.0
        for (i in 1 until n / 2) {
            val m = hypot(re[i], im[i])
            if (m > peakMag) { peak = i; peakMag = m }
        }
        assertNear(tone, peak * rate / n, rate / n, "tone location")
    }
}
