/**
 * Renders a story segment by segment.
 *
 * Rendering is incremental on purpose (docs/DECISIONS.md D-009). Fifteen
 * minutes of audio takes minutes to synthesise; if delivery waited for the
 * whole file, parents would face a recording cutoff hours before bedtime, and
 * that cutoff is exactly the friction the product exists to remove. Instead the
 * first segment is enough to start playback, and the rest is produced while the
 * child listens.
 *
 * The consequence is a race the renderer has to win: audio must be produced
 * faster than it is consumed. [Outcome.realtimeFactor] reports the margin, and
 * a value at or below 1 means the buffer drains and the child hears a gap — a
 * capacity problem, visible as a number, before it is a complaint.
 */
package mimimoto.pipeline

import mimimoto.domain.Segment
import mimimoto.domain.Story
import mimimoto.schedule.Plan
import mimimoto.tts.Reference
import mimimoto.tts.Request
import mimimoto.tts.Result
import mimimoto.tts.Synthesizer
import mimimoto.tts.isTerminal
import java.time.Duration
import java.time.Instant

/** One story to render in one parent's voice. */
data class Job(
    val plan: Plan,
    val story: Story,
    val ref: Reference,
    /**
     * Synthesis language; may differ from the reference language, though quality
     * is better when they match.
     */
    val language: String,
    val speed: Double = 0.0,
)

/** One rendered segment, delivered in index order. */
data class SegmentResult(
    val index: Int,
    val total: Int,
    val audio: Result,
    /**
     * Measured from the start of the render, so the first result's value is the
     * latency that decides whether playback can begin on time.
     */
    val elapsed: Duration,
    val attempts: Int,
)

/** Summary of a render. */
data class Outcome(
    val segments: Int,
    val completed: Int = 0,
    /**
     * The number that matters for the promise that a parent can record at 20:25
     * for a 20:30 delivery.
     */
    val firstSegmentLatency: Duration = Duration.ZERO,
    val audioDuration: Duration = Duration.ZERO,
    val wall: Duration = Duration.ZERO,
    /**
     * audioDuration / wall. Above 1 means generation outruns playback and the
     * buffer grows. At or below 1 the child hears gaps.
     */
    val realtimeFactor: Double = 0.0,
    val engine: String = "",
    val retries: Int = 0,
) {
    val complete: Boolean get() = segments > 0 && completed == segments

    /** True when generation outran playback with margin to spare. */
    val keepsUp: Boolean get() = realtimeFactor > 1.2
}

class NoVoiceException(message: String) : Exception(message)

/** Raised when the consumer stops the render, carrying its reason. */
class AbortedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Raised when a segment could not be rendered after every attempt. */
class SegmentFailedException(val index: Int, val outcome: Outcome, cause: Throwable) :
    Exception("segment $index: ${cause.message}", cause)

/** Turns jobs into audio. */
class Renderer(
    private val synth: Synthesizer,
    /**
     * Extra attempts per segment. Transient GPU and network failures are common
     * enough that zero retries would push families to the fallback tier for no
     * good reason.
     */
    private val retries: Int = 2,
    /** Base delay between attempts; it doubles each time. */
    private val backoff: Duration = Duration.ofMillis(500),
    private val now: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {

    /**
     * Synthesises every segment in order, calling [emit] as each one completes.
     * Blocks until the story is done, [emit] throws, or the thread is
     * interrupted.
     *
     * [emit] is called from the calling thread, in index order, exactly once per
     * segment. Throwing from it stops the render — that is how a consumer whose
     * delivery window has passed releases the GPU.
     */
    fun render(job: Job, emit: (SegmentResult) -> Unit): Outcome {
        // There is deliberately no provenance check here.
        //
        // D-002 — nothing reaches synthesis unless a human chose or wrote it —
        // is enforced by StorySource being an enum of approved values only, so
        // a Story carrying anything else cannot be constructed. The guard lives
        // at the single boundary where a string becomes a StorySource
        // (StorySource.fromWire), which refuses outright. Repeating the check
        // here would be dead code, and dead code is how a red line quietly
        // stops being one: someone later reads the unreachable branch, decides
        // it is redundant, and deletes the real guard along with it.
        job.story.validate()?.let { throw IllegalArgumentException(it) }
        if (job.plan.voiceId == null) {
            throw NoVoiceException("plan for ${job.plan.child.childId} has no voice to synthesise with")
        }

        val start = now()
        var completed = 0
        var totalRetries = 0
        var firstLatency = Duration.ZERO
        var audioDuration = Duration.ZERO
        var prevTail = ""

        fun snapshot(): Outcome {
            val wall = Duration.between(start, now())
            val factor = if (!wall.isZero && !wall.isNegative) {
                audioDuration.toMillis().toDouble() / wall.toMillis()
            } else 0.0
            return Outcome(
                segments = job.story.segments.size,
                completed = completed,
                firstSegmentLatency = firstLatency,
                audioDuration = audioDuration,
                wall = wall,
                realtimeFactor = factor,
                engine = synth.name,
                retries = totalRetries,
            )
        }

        for ((index, segment) in job.story.segments.withIndex()) {
            val request = Request(
                text = segment.text,
                language = job.language,
                ref = job.ref,
                prevTail = prevTail,
                style = segment.role,
                speed = job.speed,
            )

            val attempt = try {
                synthesizeWithRetry(request)
            } catch (e: Attempted) {
                totalRetries += e.attempts - 1
                throw SegmentFailedException(index, snapshot(), e.cause!!)
            }
            totalRetries += attempt.attempts - 1

            val elapsed = Duration.between(start, now())
            if (index == 0) firstLatency = elapsed
            completed++
            audioDuration = audioDuration.plus(attempt.result.duration)

            try {
                emit(SegmentResult(index, job.story.segments.size, attempt.result, elapsed, attempt.attempts))
            } catch (e: Exception) {
                throw AbortedException("render aborted by consumer: ${e.message}", e)
            }

            prevTail = tailSentence(segment.text)
        }

        return snapshot()
    }

    private class Attempted(val attempts: Int, cause: Throwable) : Exception(cause)
    private class Succeeded(val result: Result, val attempts: Int)

    private fun synthesizeWithRetry(request: Request): Succeeded {
        var delay = if (backoff.isZero || backoff.isNegative) Duration.ofMillis(500) else backoff
        var last: Throwable? = null

        for (attempt in 1..retries + 1) {
            if (Thread.currentThread().isInterrupted) {
                throw Attempted(attempt, InterruptedException("render interrupted"))
            }
            try {
                return Succeeded(synth.synthesize(request), attempt)
            } catch (e: Throwable) {
                last = e
                // A request the engine will never accept is not worth a second
                // GPU slot; fail now so the fallback tier takes over sooner.
                if (isTerminal(e)) throw Attempted(attempt, e)
                if (attempt <= retries) {
                    sleep(delay)
                    delay = delay.multipliedBy(2)
                }
            }
        }
        throw Attempted(retries + 1, last ?: IllegalStateException("no attempt was made"))
    }
}

/**
 * The last sentence of a segment, used to carry prosody across the boundary.
 *
 * Splitting on terminators from every script we ship matters: a Japanese
 * segment ending in 。 or a Thai one with no terminator at all must still hand
 * something useful to the next call.
 */
internal fun tailSentence(text: String): String {
    val terminators = ".!?。！？…"
    val maxTailChars = 80

    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""

    // Drop any trailing terminator so the search finds the *previous* one.
    val body = trimmed.trimEnd { it in terminators }
    val idx = body.indexOfLast { it in terminators }

    var tail = trimmed
    if (idx >= 0) {
        val candidate = trimmed.substring(idx + 1).trim()
        if (candidate.isNotEmpty()) tail = candidate
    }
    // A whole paragraph is no better as conditioning than its end, and costs
    // context on every call.
    if (tail.length > maxTailChars) tail = tail.takeLast(maxTailChars)
    return tail
}

/** Convenience for building a job's segment list from plain text blocks. */
fun segmentsOf(vararg texts: String): List<Segment> =
    texts.mapIndexed { i, t -> Segment(i, t) }
