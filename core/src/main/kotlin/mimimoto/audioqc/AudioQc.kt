/**
 * Decides whether a recording is good enough to clone a voice from.
 *
 * This gate is load-bearing. Cloning quality is dominated by reference quality,
 * and a bad reference does not fail loudly — it produces a voice that is
 * recognisably "almost" the parent, which is worse than no product at all: a
 * child notices the wrong prosody long before an adult does. So a sample either
 * passes here or it never reaches synthesis, and the parent is asked to record
 * again while they still care.
 *
 * Everything is measured from the signal itself. What the container claims is
 * not trusted: an Android handset reporting 48 kHz while feeding us 16 kHz
 * content upsampled in the HAL is routine, and the only way to catch it is to
 * look at where the spectrum actually stops ([Report.cutoffHz]).
 */
package mimimoto.audioqc

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selects thresholds by how the recording was obtained. A ten-second daily note
 * cannot meet the same duration bar as a guided enrolment, but it must meet the
 * same fidelity bar.
 */
enum class Profile { ENROLMENT, DAILY }

/**
 * The thresholds. Exposed so field data can move them without a code change —
 * expect to tune these once real recordings arrive.
 */
data class Policy(
    val minSampleRate: Int = 16_000,
    val minSpeechSeconds: Double,
    val minSpeechRatio: Double = 0.25,
    val failSnrDb: Double,
    val warnSnrDb: Double = 25.0,
    /** Catches telephony-band and heavily processed audio. */
    val failCutoffHz: Double = 7_000.0,
    /** Catches 16 kHz content dressed up as 48 kHz. */
    val warnCutoffHz: Double = 10_000.0,
    val maxClippingRatio: Double = 0.005,
    val maxDcOffset: Double = 0.02,
) {
    companion object {
        fun forProfile(p: Profile): Policy = when (p) {
            Profile.ENROLMENT -> Policy(minSpeechSeconds = 20.0, failSnrDb = 18.0)
            // A note dictated while walking will not be pristine. Keep the
            // spectral bar — it is what separates a usable reference from a
            // telephone-sounding one — and relax the noise bar a little.
            Profile.DAILY -> Policy(minSpeechSeconds = 2.5, failSnrDb = 15.0)
        }
    }
}

/**
 * Identifies a finding so the client can localise it. The parent sees a
 * translated sentence and a retake button, never this string.
 */
enum class Code {
    TOO_SHORT, LOW_SAMPLE_RATE, NOISY, BAND_LIMITED,
    CLIPPING, DC_OFFSET, MOSTLY_SILENCE, NO_SPEECH;

    val wire: String get() = name.lowercase()
}

/** One reason the recording is not ideal. */
data class Finding(
    val code: Code,
    /** English, for logs and triage. */
    val message: String,
    val measured: Double,
    val threshold: Double,
) {
    override fun toString() =
        "${code.wire}: $message (measured %.2f, threshold %.2f)".format(measured, threshold)
}

/** The full verdict. */
data class Report(
    val durationSeconds: Double,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    /**
     * The spread between speech-level and noise-floor frame energies. Note the
     * limitation: a recording with no pauses at all under-reports, because the
     * noise-floor percentile lands on quiet speech. In practice voice notes
     * always contain pauses.
     */
    val snrDb: Double,
    val noiseFloorDbfs: Double,
    val speechLevelDbfs: Double,
    val speechSeconds: Double,
    val speechRatio: Double,
    /**
     * The highest frequency carrying real content. The single most diagnostic
     * number in the report.
     */
    val cutoffHz: Double,
    val clippingRatio: Double,
    val dcOffset: Double,
    val passed: Boolean,
    val failures: List<Finding> = emptyList(),
    val warnings: List<Finding> = emptyList(),
) {
    /** A short summary for storage alongside the sample. */
    fun reason(): String = when {
        !passed -> failures.firstOrNull()?.toString() ?: "rejected"
        warnings.isEmpty() -> "ok"
        else -> "ok with warnings: ${warnings.first()}"
    }
}

private const val FRAME_MS = 25
private const val HOP_MS = 10

/** Clamps digital silence, which would otherwise be -Inf and poison percentiles. */
private const val FLOOR_DB = -120.0

/** Decodes a WAV stream and judges it against the profile's policy. */
fun analyse(input: InputStream, profile: Profile): Report =
    judge(decodeWav(input), Policy.forProfile(profile))

/** Measures an already-decoded signal. */
fun judge(audio: Audio, policy: Policy): Report {
    val failures = mutableListOf<Finding>()
    val warnings = mutableListOf<Finding>()

    val duration = audio.durationSeconds
    val frameLen = audio.sampleRate * FRAME_MS / 1000
    val hopLen = max(audio.sampleRate * HOP_MS / 1000, 1)

    if (audio.sampleRate < policy.minSampleRate) {
        failures += Finding(
            Code.LOW_SAMPLE_RATE,
            "sample rate is below the minimum usable for voice cloning",
            audio.sampleRate.toDouble(), policy.minSampleRate.toDouble(),
        )
    }

    if (frameLen == 0 || audio.samples.size < frameLen) {
        failures += Finding(
            Code.TOO_SHORT, "recording is shorter than one analysis frame",
            duration, policy.minSpeechSeconds,
        )
        return Report(
            durationSeconds = duration, sampleRate = audio.sampleRate,
            channels = audio.channels, bitDepth = audio.bitDepth,
            snrDb = 0.0, noiseFloorDbfs = FLOOR_DB, speechLevelDbfs = FLOOR_DB,
            speechSeconds = 0.0, speechRatio = 0.0, cutoffHz = 0.0,
            clippingRatio = 0.0, dcOffset = 0.0,
            passed = false, failures = failures,
        )
    }

    // ---- amplitude domain ----
    val (clippingRatio, dcOffset) = amplitudeStats(audio.samples)
    if (clippingRatio > policy.maxClippingRatio) {
        failures += Finding(
            Code.CLIPPING,
            "input is clipped; the microphone gain is too high or the speaker is too close",
            clippingRatio, policy.maxClippingRatio,
        )
    }
    if (abs(dcOffset) > policy.maxDcOffset) {
        // Not fatal for cloning, but a strong hint the capture path is broken.
        warnings += Finding(
            Code.DC_OFFSET, "signal has a DC offset; check the capture path",
            abs(dcOffset), policy.maxDcOffset,
        )
    }

    // ---- frame energies, noise floor, VAD ----
    val frameDb = frameEnergiesDb(audio.samples, frameLen, hopLen)
    val noiseFloor = percentile(frameDb, 10.0)
    val speechLevel = percentile(frameDb, 95.0)
    val snr = speechLevel - noiseFloor

    // A frame counts as speech when it stands 6 dB above the floor and is not
    // near-silent in absolute terms. The absolute guard stops a recording of a
    // quiet room reading as 100% speech.
    val vadThreshold = max(noiseFloor + 6, -55.0)
    val voiced = frameDb.indices.filter { frameDb[it] >= vadThreshold }

    val speechSeconds = voiced.size.toDouble() * HOP_MS / 1000
    val speechRatio = if (duration > 0) speechSeconds / duration else 0.0

    when {
        voiced.isEmpty() ->
            failures += Finding(Code.NO_SPEECH, "no speech detected", 0.0, policy.minSpeechSeconds)
        speechSeconds < policy.minSpeechSeconds ->
            failures += Finding(
                Code.TOO_SHORT, "not enough speech in the recording",
                speechSeconds, policy.minSpeechSeconds,
            )
    }
    if (voiced.isNotEmpty() && speechRatio < policy.minSpeechRatio) {
        warnings += Finding(
            Code.MOSTLY_SILENCE, "recording is mostly silence",
            speechRatio, policy.minSpeechRatio,
        )
    }

    when {
        snr < policy.failSnrDb -> failures += Finding(
            Code.NOISY, "background noise is too high for a usable reference",
            snr, policy.failSnrDb,
        )
        snr < policy.warnSnrDb -> warnings += Finding(
            Code.NOISY, "background noise is higher than ideal", snr, policy.warnSnrDb,
        )
    }

    // ---- spectral cutoff ----
    var cutoff = 0.0
    if (voiced.isNotEmpty()) {
        cutoff = spectralCutoff(audio, voiced, frameLen, hopLen)
        when {
            cutoff < policy.failCutoffHz -> failures += Finding(
                Code.BAND_LIMITED,
                "audio is band-limited; it has been through a call or voice-chat " +
                    "pipeline and is not usable as a reference",
                cutoff, policy.failCutoffHz,
            )
            cutoff < policy.warnCutoffHz -> warnings += Finding(
                Code.BAND_LIMITED,
                "audio carries no high-frequency content; the device is likely upsampling",
                cutoff, policy.warnCutoffHz,
            )
        }
    }

    return Report(
        durationSeconds = duration,
        sampleRate = audio.sampleRate,
        channels = audio.channels,
        bitDepth = audio.bitDepth,
        snrDb = snr,
        noiseFloorDbfs = noiseFloor,
        speechLevelDbfs = speechLevel,
        speechSeconds = speechSeconds,
        speechRatio = speechRatio,
        cutoffHz = cutoff,
        clippingRatio = clippingRatio,
        dcOffset = dcOffset,
        passed = failures.isEmpty(),
        failures = failures,
        warnings = warnings,
    )
}

/**
 * Clipped-sample ratio and DC offset.
 *
 * A sample counts as clipped only inside a run of at least three consecutive
 * near-full-scale samples: isolated peaks at full scale are normal, flat tops
 * are not.
 */
private fun amplitudeStats(s: DoubleArray): Pair<Double, Double> {
    if (s.isEmpty()) return 0.0 to 0.0
    val ceiling = 0.995
    val minRun = 3

    var sum = 0.0
    var clipped = 0
    var run = 0
    for (v in s) {
        sum += v
        if (abs(v) >= ceiling) {
            run++
        } else {
            if (run >= minRun) clipped += run
            run = 0
        }
    }
    if (run >= minRun) clipped += run
    return clipped.toDouble() / s.size to sum / s.size
}

/** Per-frame RMS in dBFS. */
private fun frameEnergiesDb(s: DoubleArray, frameLen: Int, hopLen: Int): DoubleArray {
    val count = if (s.size >= frameLen) (s.size - frameLen) / hopLen + 1 else 0
    val out = DoubleArray(count)
    var i = 0
    var f = 0
    while (i + frameLen <= s.size) {
        var sum = 0.0
        for (k in i until i + frameLen) sum += s[k] * s[k]
        val rms = sqrt(sum / frameLen)
        out[f] = if (rms > 0) max(20 * log10(rms), FLOOR_DB) else FLOOR_DB
        i += hopLen
        f++
    }
    return out
}

/**
 * Estimates the highest frequency carrying real content.
 *
 * Average the magnitude spectrum over voiced frames only (silence would drag
 * the average into the numerical floor), smooth with a short median filter to
 * reject single-bin spikes, take a reference level from the speech band, then
 * walk down from Nyquist for the first bin standing less than [CUTOFF_FLOOR_DB]
 * below that reference.
 *
 * A 48 kHz recording from a real microphone keeps content well past 15 kHz.
 * 16 kHz content upsampled to 48 kHz stops dead at 8 kHz. Telephony and
 * voice-chat pipelines stop around 3.4–4 kHz. The three cases are far enough
 * apart that this is a robust discriminator despite the crude method.
 */
private fun spectralCutoff(audio: Audio, voiced: List<Int>, frameLen: Int, hopLen: Int): Double {
    val nfft = max(nextPow2(frameLen), 512)
    val window = hann(frameLen)
    val half = nfft / 2

    val acc = DoubleArray(half)
    val re = DoubleArray(nfft)
    val im = DoubleArray(nfft)

    // The estimate converges quickly; a five-minute enrolment should not cost a
    // full-file FFT sweep.
    val maxFrames = 400
    val step = max(voiced.size / maxFrames, 1)

    var used = 0
    var k = 0
    while (k < voiced.size) {
        val start = voiced[k] * hopLen
        if (start + frameLen <= audio.samples.size) {
            re.fill(0.0)
            im.fill(0.0)
            for (i in 0 until frameLen) re[i] = audio.samples[start + i] * window[i]
            fftRadix2(re, im)
            for (i in 0 until half) acc[i] += hypot(re[i], im[i])
            used++
        }
        k += step
    }
    if (used == 0) return 0.0

    val spec = DoubleArray(half) {
        val v = acc[it] / used
        if (v <= 0) FLOOR_DB else 20 * log10(v)
    }

    val smooth = DoubleArray(half) {
        medianOf(spec, max(it - 2, 0), min(it + 3, half))
    }

    val binHz = audio.sampleRate.toDouble() / nfft
    fun bin(hz: Double) = (hz / binHz).toInt().coerceIn(0, half - 1)

    // Reference: the strongest bin in the core speech band, measured on the
    // same smoothed spectrum the walk-down below uses. Mixing the raw and
    // smoothed spectra here would bias the threshold by however much the
    // median filter shaved off the peak.
    var ref = FLOOR_DB
    for (i in bin(200.0)..bin(4000.0)) if (smooth[i] > ref) ref = smooth[i]
    val threshold = ref - CUTOFF_FLOOR_DB

    for (i in half - 1 downTo 0) if (smooth[i] > threshold) return i * binHz
    return 0.0
}

/** How far below the speech-band peak still counts as content. */
private const val CUTOFF_FLOOR_DB = 60.0
