package mimimoto.audioqc

import java.io.InputStream

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
 * Hand-written rather than delegating to `javax.sound.sampled`, because that
 * package does not exist on Android and this gate has to give the SAME verdict
 * on the handset as on the server. A parent who is told "record again" by the
 * app and then has the upload accepted by the server — or the reverse — has
 * caught us contradicting ourselves. One decoder, one answer.
 *
 * Handles PCM 8/16/24/32-bit and IEEE float 32/64, mono or multi-channel,
 * including WAVE_FORMAT_EXTENSIBLE. Channels are averaged rather than one being
 * picked, so a dead channel drags the verdict down instead of being silently
 * discarded. Unknown chunks are skipped: phone recorders like to prepend
 * metadata, and a truncated final chunk is read as far as it goes rather than
 * failing, because a recording cut short by a crash is still worth judging.
 */
fun decodeWav(input: InputStream): Audio = decodeWav(input.readBytes())

fun decodeWav(bytes: ByteArray): Audio {
    if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") {
        throw AudioDecodeException("not a RIFF/WAVE stream")
    }

    var format = 0
    var channels = 0
    var sampleRate = 0
    var bits = 0
    var dataOffset = -1
    var dataLength = 0

    var off = 12
    while (off + 8 <= bytes.size) {
        val id = tag(bytes, off)
        var size = u32(bytes, off + 4)
        val body = off + 8
        if (size < 0 || body + size > bytes.size) size = bytes.size - body
        if (size <= 0) break

        when (id) {
            "fmt " -> {
                if (size < 16) throw AudioDecodeException("fmt chunk is too short")
                format = u16(bytes, body)
                channels = u16(bytes, body + 2)
                sampleRate = u32(bytes, body + 4)
                bits = u16(bytes, body + 14)
                // The real format of an extensible file lives in its GUID.
                if (format == 0xFFFE && size >= 40) format = u16(bytes, body + 24)
            }
            "data" -> {
                dataOffset = body
                dataLength = size
            }
        }
        off = body + size + (size and 1) // chunks are word-aligned
    }

    if (channels <= 0 || sampleRate <= 0 || bits <= 0) {
        throw AudioDecodeException("missing or unusable fmt chunk")
    }
    if (dataOffset < 0) throw AudioDecodeException("missing data chunk")

    val bytesPerSample = (bits + 7) / 8
    val stride = bytesPerSample * channels
    if (stride == 0) throw AudioDecodeException("unusable frame size")

    val frames = dataLength / stride
    val out = DoubleArray(frames)
    val scale = fullScale(bits)
    val isFloat = format == FORMAT_IEEE_FLOAT

    for (f in 0 until frames) {
        var sum = 0.0
        for (c in 0 until channels) {
            val at = dataOffset + f * stride + c * bytesPerSample
            sum += when {
                isFloat && bits == 32 -> Float.fromBits(i32(bytes, at)).toDouble()
                isFloat && bits == 64 -> Double.fromBits(i64(bytes, at))
                // 8-bit WAV is unsigned, offset by 128.
                bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / scale
                bits == 16 -> i16(bytes, at) / scale
                bits == 24 -> i24(bytes, at) / scale
                bits == 32 -> i32(bytes, at) / scale
                else -> throw AudioDecodeException("unsupported sample format: $format/$bits-bit")
            }
        }
        out[f] = sum / channels
    }

    return Audio(out, sampleRate, channels, bits)
}

/**
 * Packs mono samples as 16-bit PCM WAV — the format the recorder uploads.
 *
 * Deliberately lossless. A perceptual codec on the upload path would band-limit
 * the audio itself, and the gate would then be measuring the encoder's low-pass
 * rather than the microphone's: clean 48 kHz recordings would come back as
 * "this device is upsampling". See docs/DECISIONS.md D-014.
 */
fun encodeWav(samples: DoubleArray, sampleRate: Int): ByteArray {
    val out = ByteArray(44 + samples.size * 2)
    putTag(out, 0, "RIFF"); putU32(out, 4, 36 + samples.size * 2)
    putTag(out, 8, "WAVE"); putTag(out, 12, "fmt "); putU32(out, 16, 16)
    putU16(out, 20, 1)                       // PCM
    putU16(out, 22, 1)                       // mono
    putU32(out, 24, sampleRate)
    putU32(out, 28, sampleRate * 2)          // byte rate
    putU16(out, 32, 2)                       // block align
    putU16(out, 34, 16)                      // bits
    putTag(out, 36, "data"); putU32(out, 40, samples.size * 2)

    for (i in samples.indices) {
        val v = samples[i].coerceIn(-1.0, 1.0)
        val s = Math.round(v * 32767.0).toInt()
        out[44 + i * 2] = (s and 0xFF).toByte()
        out[45 + i * 2] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}

private const val FORMAT_IEEE_FLOAT = 3

private fun fullScale(bits: Int): Double = when (bits) {
    8 -> 128.0
    16 -> 32768.0
    24 -> 8388608.0
    32 -> 2147483648.0
    else -> Math.pow(2.0, (bits - 1).toDouble())
}

private fun tag(b: ByteArray, off: Int): String =
    String(b, off, 4, Charsets.ISO_8859_1)

private fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun u32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

private fun i16(b: ByteArray, off: Int): Double = u16(b, off).toShort().toDouble()

private fun i24(b: ByteArray, off: Int): Double {
    var v = (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16)
    if (v and 0x800000 != 0) v = v or 0xFF000000.toInt() // sign extend
    return v.toDouble()
}

private fun i32(b: ByteArray, off: Int): Int = u32(b, off)

private fun i64(b: ByteArray, off: Int): Long =
    (u32(b, off).toLong() and 0xFFFFFFFFL) or (u32(b, off + 4).toLong() shl 32)

private fun putTag(b: ByteArray, off: Int, s: String) {
    for (i in s.indices) b[off + i] = s[i].code.toByte()
}

private fun putU16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
}

private fun putU32(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
    b[off + 2] = ((v shr 16) and 0xFF).toByte()
    b[off + 3] = ((v shr 24) and 0xFF).toByte()
}
