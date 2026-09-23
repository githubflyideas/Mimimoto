package mimimoto

import mimimoto.audioqc.*
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- test signal synthesis ----
//
// Speech-like signals rather than fixtures, so the tests stay self-contained
// and the band-limiting is exact: a signal whose highest harmonic is 8 kHz is
// precisely what a 16 kHz recording upsampled to 48 kHz looks like, which is
// the case this gate exists to catch.

internal class SigOpts(
    val rate: Int = 48_000,
    val seconds: Double = 30.0,
    val f0: Double = 120.0,
    val maxHarmHz: Double = 20_000.0,
    val amp: Double = 0.5,
    val noise: Double = 1e-4,
    val pauseEvery: Double = 1.2,
    val pauseLen: Double = 0.4,
    val dc: Double = 0.0,
    val clip: Boolean = false,
)

internal fun synth(o: SigOpts): Audio {
    val n = (o.rate * o.seconds).toInt()
    val out = DoubleArray(n)
    val rng = Random(42)

    val nyquist = o.rate / 2.0
    val top = min(o.maxHarmHz, nyquist * 0.98)
    val nHarm = maxOf((top / o.f0).toInt(), 1)
    val cycle = o.pauseEvery + o.pauseLen

    // Every harmonic is a multiple of f0, so the carrier repeats every
    // rate/f0 samples. Where that divides exactly, one period is computed and
    // tiled — the signal is identical and the sine calls drop by two orders of
    // magnitude, which is the difference between a suite that runs in a second
    // and one nobody waits for. Where it does not divide (the 8 kHz case), the
    // splice would inject broadband energy at every seam and corrupt the very
    // spectrum under test, so that one is built the slow way.
    val exactPeriod = if (o.rate % o.f0.toInt() == 0) o.rate / o.f0.toInt() else 0
    val carrier: DoubleArray? = if (exactPeriod > 0) DoubleArray(exactPeriod).also { c ->
        for (h in 1..nHarm) {
            val f = o.f0 * h
            var a = 1.0 / h
            if (f > 500 && f < 1000) a *= 2 // crude formant bump
            val w = 2 * PI * f / o.rate
            for (i in 0 until exactPeriod) c[i] += a * sin(w * i + h)
        }
    } else null

    for (i in 0 until n) {
        val t = i.toDouble() / o.rate
        val speaking = cycle <= 0 || (t % cycle) <= o.pauseEvery
        // Syllable-rate modulation keeps frame energies varied as real speech does.
        val env = 0.6 + 0.4 * sin(2 * PI * 4 * t)

        var v = 0.0
        if (speaking) {
            v = carrier?.get(i % exactPeriod) ?: run {
                var s = 0.0
                for (h in 1..nHarm) {
                    val f = o.f0 * h
                    var a = 1.0 / h
                    if (f > 500 && f < 1000) a *= 2
                    s += a * sin(2 * PI * f * t + h)
                }
                s
            }
            v *= o.amp * env / 3
        }
        v += rng.nextGaussian() * o.noise
        v += o.dc
        if (o.clip) v *= 6 // mic gain far too high, or speaker on the capsule
        out[i] = v.coerceIn(-1.0, 1.0)
    }
    return Audio(out, o.rate, 1, 16)
}

/** 16-bit PCM mono WAV, so the decoder is exercised too. */
internal fun encodeWav(a: Audio): ByteArray {
    val dataLen = a.samples.size * 2
    val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray())
    buf.putInt(36 + dataLen)
    buf.put("WAVEfmt ".toByteArray())
    buf.putInt(16)
    buf.putShort(1)
    buf.putShort(1)
    buf.putInt(a.sampleRate)
    buf.putInt(a.sampleRate * 2)
    buf.putShort(2)
    buf.putShort(16)
    buf.put("data".toByteArray())
    buf.putInt(dataLen)
    for (s in a.samples) buf.putShort((s.coerceIn(-1.0, 1.0) * 32767).roundToInt().toShort())
    return buf.array()
}

private fun List<Finding>.has(code: Code) = any { it.code == code }

fun audioQcTests() = Suite.group("audioqc") {

    // ---- decoder ----

    Suite.test("WAV round trip") {
        val src = synth(SigOpts(seconds = 1.0))
        val got = decodeWav(ByteArrayInputStream(encodeWav(src)))
        assertEquals(48_000, got.sampleRate)
        assertEquals(1, got.channels)
        assertEquals(16, got.bitDepth)
        assertEquals(src.samples.size, got.samples.size)
        for (i in got.samples.indices) {
            assertTrue(
                abs(got.samples[i] - src.samples[i]) <= 1.0 / 32767 + 1e-9,
                "sample $i: got ${got.samples[i]}, want ${src.samples[i]}",
            )
        }
    }

    Suite.test("garbage is rejected") {
        assertThrows<AudioDecodeException> {
            decodeWav(ByteArrayInputStream("not a wav at all".toByteArray()))
        }
    }

    Suite.test("unknown chunks are skipped") {
        // Splice a LIST chunk between fmt and data, as phone recorders do.
        val base = encodeWav(synth(SigOpts(seconds = 0.5)))
        val idx = String(base, Charsets.ISO_8859_1).indexOf("data")
        assertTrue(idx > 0, "no data chunk in fixture")

        val extra = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("LIST".toByteArray()); putInt(4); put("INFO".toByteArray())
        }.array()

        val out = ByteArrayOutputStream()
        out.write(base, 0, idx)
        out.write(extra)
        out.write(base, idx, base.size - idx)
        val spliced = out.toByteArray()
        ByteBuffer.wrap(spliced).order(ByteOrder.LITTLE_ENDIAN).putInt(4, spliced.size - 8)

        val got = decodeWav(ByteArrayInputStream(spliced))
        assertTrue(got.samples.isNotEmpty(), "no samples decoded")
    }

    // ---- the gate ----

    Suite.test("clean wideband passes") {
        val r = analyse(ByteArrayInputStream(encodeWav(synth(SigOpts()))), Profile.ENROLMENT)
        assertTrue(r.passed, "clean wideband should pass, got ${r.failures}")
        assertTrue(r.cutoffHz >= 15_000, "cutoff should be high, got ${r.cutoffHz}")
        assertTrue(r.snrDb >= 30, "SNR should be high, got ${r.snrDb}")
    }

    // The case the gate exists for: a device reporting 48 kHz while the content
    // stops at 8 kHz.
    Suite.test("upsampled content is detected") {
        val r = analyse(
            ByteArrayInputStream(encodeWav(synth(SigOpts(maxHarmHz = 8_000.0)))),
            Profile.ENROLMENT,
        )
        assertTrue(r.cutoffHz <= 9_000, "cutoff should land near 8 kHz, got ${r.cutoffHz}")
        assertTrue(
            r.warnings.has(Code.BAND_LIMITED),
            "expected band_limited warning, got ${r.warnings} / ${r.failures} at ${r.cutoffHz} Hz",
        )
    }

    // Telephony band: the "sounds like a phone call, not like dad" failure.
    Suite.test("telephony band fails") {
        val r = analyse(
            ByteArrayInputStream(encodeWav(synth(SigOpts(maxHarmHz = 3_400.0)))),
            Profile.ENROLMENT,
        )
        assertFalse(r.passed, "telephony-band input must not pass (cutoff ${r.cutoffHz})")
        assertTrue(r.failures.has(Code.BAND_LIMITED), "expected band_limited, got ${r.failures}")
    }

    Suite.test("noisy recording fails") {
        val r = analyse(
            ByteArrayInputStream(encodeWav(synth(SigOpts(noise = 0.05)))),
            Profile.ENROLMENT,
        )
        assertFalse(r.passed, "noisy input must not pass (SNR ${r.snrDb})")
        assertTrue(r.failures.has(Code.NOISY), "expected noisy, got ${r.failures} at ${r.snrDb} dB")
    }

    Suite.test("clipping is detected") {
        val r = analyse(
            ByteArrayInputStream(encodeWav(synth(SigOpts(clip = true)))),
            Profile.ENROLMENT,
        )
        assertTrue(
            r.failures.has(Code.CLIPPING),
            "expected clipping, got ${r.failures} at ratio ${r.clippingRatio}",
        )
    }

    Suite.test("short daily note passes daily but not enrolment") {
        val wav = encodeWav(synth(SigOpts(seconds = 4.0, pauseEvery = 1.5, pauseLen = 0.3)))

        val daily = analyse(ByteArrayInputStream(wav), Profile.DAILY)
        assertTrue(daily.passed, "short note should pass daily, got ${daily.failures}")

        val enrol = analyse(ByteArrayInputStream(wav), Profile.ENROLMENT)
        assertFalse(enrol.passed, "a 4-second note must not satisfy enrolment")
        assertTrue(enrol.failures.has(Code.TOO_SHORT), "expected too_short, got ${enrol.failures}")
    }

    Suite.test("digital silence is rejected") {
        val silent = Audio(DoubleArray(48_000 * 5), 48_000, 1, 16)
        assertFalse(judge(silent, Policy.forProfile(Profile.DAILY)).passed)
    }

    Suite.test("low sample rate fails") {
        val r = judge(
            synth(SigOpts(rate = 8_000, maxHarmHz = 3_400.0)),
            Policy.forProfile(Profile.DAILY),
        )
        assertFalse(r.passed, "8 kHz input must not pass")
        assertTrue(r.failures.has(Code.LOW_SAMPLE_RATE), "expected low_sample_rate, got ${r.failures}")
    }

    Suite.test("DC offset warns") {
        val r = judge(synth(SigOpts(dc = 0.1)), Policy.forProfile(Profile.ENROLMENT))
        assertTrue(
            r.warnings.has(Code.DC_OFFSET),
            "expected dc_offset warning, got ${r.warnings} at offset ${r.dcOffset}",
        )
    }

    // The discriminator must stay where the Go implementation measured it, so a
    // future change to the smoothing or the threshold cannot quietly blur the
    // three classes together.
    Suite.test("cutoff discriminates the three classes at the measured values") {
        val wideband = judge(synth(SigOpts()), Policy.forProfile(Profile.ENROLMENT))
        val upsampled = judge(synth(SigOpts(maxHarmHz = 8_000.0)), Policy.forProfile(Profile.ENROLMENT))
        val phone = judge(synth(SigOpts(maxHarmHz = 3_400.0)), Policy.forProfile(Profile.ENROLMENT))

        assertNear(19_969.0, wideband.cutoffHz, 600.0, "wideband cutoff")
        assertNear(8_016.0, upsampled.cutoffHz, 400.0, "upsampled cutoff")
        assertNear(3_492.0, phone.cutoffHz, 300.0, "telephony cutoff")
    }

    // ---- FFT ----

    Suite.test("FFT matches a naive DFT") {
        val n = 64
        val rng = Random(7)
        val src = DoubleArray(n) { rng.nextGaussian() }
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
            val m = kotlin.math.hypot(re[i], im[i])
            if (m > peakMag) { peak = i; peakMag = m }
        }
        assertNear(tone, peak * rate / n, rate / n, "tone location")
    }
}

// Shared fixtures for the integration tests: the two cases that decide whether
// a recording is usable at all.

/** A clean full-band recording, the kind a decent phone mic produces. */
internal fun wideband48k(seconds: Double): ByteArray =
    encodeWav(synth(SigOpts(seconds = seconds, pauseEvery = 1.2, pauseLen = 0.3)))

/** What a call or voice-chat pipeline hands back. Must never be usable. */
internal fun telephonyBand(seconds: Double): ByteArray =
    encodeWav(synth(SigOpts(seconds = seconds, maxHarmHz = 3_400.0, pauseEvery = 1.2, pauseLen = 0.3)))
