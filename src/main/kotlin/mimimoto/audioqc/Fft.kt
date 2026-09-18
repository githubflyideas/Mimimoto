package mimimoto.audioqc

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-place forward DFT. [re] and [im] must have the same power-of-two length.
 * Iterative Cooley-Tukey.
 */
internal fun fftRadix2(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    if (n <= 1) return

    // Bit-reversal permutation.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            re[i] = re[j].also { re[j] = re[i] }
            im[i] = im[j].also { im[j] = im[i] }
        }
    }

    var length = 2
    while (length <= n) {
        val angle = -2 * PI / length
        val wRe = cos(angle)
        val wIm = sin(angle)
        var i = 0
        while (i < n) {
            var curRe = 1.0
            var curIm = 0.0
            val half = length / 2
            for (k in 0 until half) {
                val uRe = re[i + k]
                val uIm = im[i + k]
                val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                re[i + k] = uRe + vRe
                im[i + k] = uIm + vIm
                re[i + k + half] = uRe - vRe
                im[i + k + half] = uIm - vIm
                val nextRe = curRe * wRe - curIm * wIm
                curIm = curRe * wIm + curIm * wRe
                curRe = nextRe
            }
            i += length
        }
        length = length shl 1
    }
}

/** A periodic Hann window of length [n]. */
internal fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

internal fun nextPow2(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}

/** Median of a slice, without modifying the input. */
internal fun medianOf(xs: DoubleArray, from: Int, to: Int): Double {
    val c = xs.copyOfRange(from, to)
    if (c.isEmpty()) return 0.0
    c.sort()
    val m = c.size / 2
    return if (c.size % 2 == 1) c[m] else (c[m - 1] + c[m]) / 2
}

/** Nearest-rank percentile (0..100), without modifying the input. */
internal fun percentile(xs: DoubleArray, p: Double): Double {
    if (xs.isEmpty()) return 0.0
    val c = xs.copyOf()
    c.sort()
    val idx = (p / 100 * (c.size - 1)).roundToInt().coerceIn(0, c.size - 1)
    return c[idx]
}

internal fun DoubleArray.maxAbs(): Double {
    var m = 0.0
    for (v in this) if (abs(v) > m) m = abs(v)
    return m
}
