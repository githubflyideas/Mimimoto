package mimimoto.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Collections
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A deterministic [Synthesizer] for tests and for running the scheduler end to
 * end without a GPU.
 *
 * It produces real WAV bytes — a quiet tone shaped like a segment — so anything
 * downstream that decodes audio is genuinely exercised.
 */
class MockSynthesizer(
    /** Simulated GPU time per call. */
    var latency: Duration = Duration.ZERO,
    /** Texts that should fail, and the exception to raise for each. */
    val failOn: MutableMap<String, Exception> = mutableMapOf(),
    /** Controls generated duration; the default is a natural CJK reading pace. */
    val secondsPerChar: Double = 0.12,
) : Synthesizer {

    override val name = "mock"

    /**
     * Matches the real engine's coverage rather than accepting everything, so a
     * test that passes here would also pass in production. A mock that is more
     * permissive than the thing it stands in for hides exactly the failures it
     * is there to catch.
     */
    override val languages: Set<String> = setOf("zh", "yue", "en", "ja", "ko")

    private val recorded = Collections.synchronizedList(mutableListOf<Request>())

    /** A snapshot of the requests seen so far. */
    val calls: List<Request> get() = synchronized(recorded) { recorded.toList() }

    fun reset() = synchronized(recorded) { recorded.clear() }

    override fun synthesize(request: Request): Result {
        request.validate()
        primaryLanguage(request.language, languages) // throws for an unsupported tag
        recorded += request

        if (!latency.isZero) Thread.sleep(latency.toMillis())
        failOn[request.text]?.let { throw it }

        val duration = Duration.ofMillis(
            (request.text.codePointCount(0, request.text.length) * secondsPerChar * 1000).toLong()
        )
        val rate = 24_000
        return Result(
            audio = toneWav(rate, duration),
            sampleRate = rate,
            duration = duration,
            engine = name,
            engineVersion = "mock-1",
        )
    }
}

/** A 16-bit mono WAV of the given duration holding a quiet tone. */
internal fun toneWav(rate: Int, duration: Duration): ByteArray {
    val n = maxOf((duration.toMillis() * rate / 1000).toInt(), 1)
    val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray())
    buf.putInt(36 + n * 2)
    buf.put("WAVEfmt ".toByteArray())
    buf.putInt(16)
    buf.putShort(1)
    buf.putShort(1)
    buf.putInt(rate)
    buf.putInt(rate * 2)
    buf.putShort(2)
    buf.putShort(16)
    buf.put("data".toByteArray())
    buf.putInt(n * 2)
    for (i in 0 until n) {
        val v = 0.1 * sin(2 * PI * 220 * i / rate)
        buf.putShort((v * 32767).roundToInt().toShort())
    }
    return buf.array()
}
