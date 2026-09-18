package mimimoto.audioqc

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/** A decoded, mono signal normalised to [-1, 1]. */
class Audio(
    val samples: DoubleArray,
    val sampleRate: Int,
    /** Channel count of the source, before downmixing. */
    val channels: Int,
    /** Bit depth of the source. */
    val bitDepth: Int,
) {
    val durationSeconds: Double
        get() = if (sampleRate == 0) 0.0 else samples.size.toDouble() / sampleRate
}

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads a WAV stream and returns a mono signal.
 *
 * Decoding goes through `javax.sound.sampled`, which already handles the chunk
 * walking, WAVE_FORMAT_EXTENSIBLE and the odd metadata chunk that phone
 * recorders like to prepend. Only the conversion to normalised doubles is done
 * here, because that part depends on how we want to treat multi-channel input:
 * channels are averaged rather than one being picked, so a dead channel drags
 * the verdict down instead of being silently discarded.
 */
fun decodeWav(input: InputStream): Audio {
    val stream: AudioInputStream = try {
        // getAudioInputStream needs mark/reset to sniff the header.
        AudioSystem.getAudioInputStream(BufferedInputStream(input))
    } catch (e: UnsupportedAudioFileException) {
        throw AudioDecodeException("not a readable WAV stream", e)
    }

    stream.use {
        val format = it.format
        val channels = format.channels
        val bits = format.sampleSizeInBits
        val rate = format.sampleRate.toInt()

        if (channels <= 0 || rate <= 0 || bits <= 0) {
            throw AudioDecodeException("unusable format: ${format}")
        }

        val raw = it.readAllBytes()
        val samples = toMonoDoubles(raw, format)
        return Audio(samples, rate, channels, bits)
    }
}

private fun toMonoDoubles(raw: ByteArray, format: AudioFormat): DoubleArray {
    val bits = format.sampleSizeInBits
    val channels = format.channels
    val bytesPerSample = (bits + 7) / 8
    val stride = bytesPerSample * channels
    if (stride == 0) return DoubleArray(0)

    val frames = raw.size / stride
    val out = DoubleArray(frames)
    val order = if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    val buf = ByteBuffer.wrap(raw).order(order)

    val encoding = format.encoding
    val isFloat = encoding == AudioFormat.Encoding.PCM_FLOAT
    val isUnsigned = encoding == AudioFormat.Encoding.PCM_UNSIGNED

    for (f in 0 until frames) {
        var sum = 0.0
        for (c in 0 until channels) {
            val at = f * stride + c * bytesPerSample
            sum += when {
                isFloat && bits == 32 -> buf.getFloat(at).toDouble()
                isFloat && bits == 64 -> buf.getDouble(at)
                // 8-bit WAV is unsigned, offset by 128.
                isUnsigned && bits == 8 -> (raw[at].toInt() and 0xFF) - 128.0
                bits == 8 -> raw[at].toDouble()
                bits == 16 -> buf.getShort(at).toDouble()
                bits == 24 -> read24(raw, at, format.isBigEndian).toDouble()
                bits == 32 -> buf.getInt(at).toDouble()
                else -> throw AudioDecodeException("unsupported sample format: $encoding/$bits-bit")
            }
        }
        val mean = sum / channels
        out[f] = if (isFloat) mean else mean / fullScale(bits)
    }
    return out
}

private fun fullScale(bits: Int): Double = when (bits) {
    8 -> 128.0
    16 -> 32768.0
    24 -> 8388608.0
    32 -> 2147483648.0
    else -> (1L shl (bits - 1)).toDouble()
}

private fun read24(raw: ByteArray, at: Int, bigEndian: Boolean): Int {
    val b0 = raw[at].toInt() and 0xFF
    val b1 = raw[at + 1].toInt() and 0xFF
    val b2 = raw[at + 2].toInt() and 0xFF
    var v = if (bigEndian) (b0 shl 16) or (b1 shl 8) or b2 else (b2 shl 16) or (b1 shl 8) or b0
    if (v and 0x800000 != 0) v = v or 0xFF000000.toInt() // sign extend
    return v
}
