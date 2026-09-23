package mimimoto.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * Captures raw PCM from the microphone.
 *
 * Two decisions here carry the whole product, and both are easy to undo by
 * accident:
 *
 *  1. AudioRecord, not MediaRecorder. MediaRecorder writes an encoded file
 *     (AAC, AMR), and every one of those encoders band-limits the audio. The
 *     quality gate reads where the spectrum stops to decide whether a recording
 *     is usable; hand it encoded audio and it measures the ENCODER's low-pass,
 *     not the microphone's, so a clean 48 kHz take comes back as "this device
 *     is upsampling". Lossless in, always (docs/DECISIONS.md D-014).
 *
 *  2. The audio source is chosen, not defaulted. MIC has been through whatever
 *     AGC, noise suppression and echo cancellation the handset's HAL applies —
 *     a pipeline tuned to make phone calls intelligible, which is precisely the
 *     damage that makes a voice clone sound "almost" like the parent.
 *
 * Which source a handset actually grants is not knowable in advance: plenty
 * report UNPROCESSED as unsupported, and some report it as supported and
 * process the audio anyway. So the source that was obtained is recorded
 * alongside the audio and shown in the UI — across a pilot's worth of devices
 * that list is the map of which hardware this product works on.
 */
class PcmRecorder(private val audioManager: AudioManager?) {

    data class Take(
        val samples: DoubleArray,
        val sampleRate: Int,
        /** The source actually granted, e.g. "UNPROCESSED". */
        val source: String,
        /** What the device claims about unprocessed capture, for comparison. */
        val claimsUnprocessed: Boolean,
    )

    private data class Candidate(val id: Int, val label: String)

    private val candidates = listOf(
        Candidate(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"),
        Candidate(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
        Candidate(MediaRecorder.AudioSource.MIC, "MIC"),
    )

    @Volatile
    private var running = false

    /** What the device says it can do — often at odds with what it delivers. */
    fun claimsUnprocessed(): Boolean =
        audioManager?.getProperty(PROPERTY_UNPROCESSED) == "true"

    fun stop() { running = false }

    /**
     * Records until [stop] is called, then returns the take.
     *
     * Blocks; call it off the main thread. [onLevel] receives a 0..1 amplitude
     * for the meter, roughly every 20 ms.
     */
    @SuppressLint("MissingPermission") // the caller holds RECORD_AUDIO
    fun record(onLevel: (Float) -> Unit): Take {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        require(minBuffer > 0) { "this device cannot capture 48 kHz mono PCM" }
        // Four times the minimum: enough slack that a scheduling hiccup on a
        // cheap tablet drops no samples, which would otherwise show up as
        // clicks and wreck the spectrum.
        val bufferBytes = minBuffer * 4

        var recorder: AudioRecord? = null
        var chosen: Candidate? = null
        for (candidate in candidates) {
            val attempt = try {
                AudioRecord(candidate.id, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
            } catch (_: Exception) {
                null
            }
            if (attempt != null && attempt.state == AudioRecord.STATE_INITIALIZED) {
                recorder = attempt
                chosen = candidate
                break
            }
            attempt?.release()
        }
        val audioRecord = recorder
        val source = chosen
        require(audioRecord != null && source != null) { "no usable audio source on this device" }

        val chunk = ShortArray(bufferBytes / 2)
        val collected = ArrayList<ShortArray>()
        var total = 0

        running = true
        try {
            audioRecord.startRecording()
            while (running) {
                val n = audioRecord.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                collected.add(chunk.copyOf(n))
                total += n

                var peak = 0
                for (i in 0 until n) {
                    val v = abs(chunk[i].toInt())
                    if (v > peak) peak = v
                }
                onLevel(peak / 32768f)

                // A stuck stop() must not let a take grow without bound.
                if (total > SAMPLE_RATE * MAX_SECONDS) break
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) { }
            audioRecord.release()
            running = false
        }

        val samples = DoubleArray(total)
        var at = 0
        for (part in collected) {
            for (s in part) samples[at++] = s / 32768.0
        }

        return Take(
            samples = samples,
            sampleRate = SAMPLE_RATE,
            source = source.label,
            claimsUnprocessed = claimsUnprocessed(),
        )
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_SECONDS = 120
        const val PROPERTY_UNPROCESSED = "android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED"
    }
}
