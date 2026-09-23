/**
 * The synthesis boundary.
 *
 * The engine behind this interface is expected to change. FireRedTTS3 is the
 * current choice on quality grounds (docs/DECISIONS.md D-010), but its
 * licensing is unresolved — the repository is Apache-2.0 while the model card
 * restricts voice cloning to academic research. Keeping synthesis behind a
 * narrow interface is what makes that a procurement problem rather than a
 * rewrite.
 */
package mimimoto.tts

import java.time.Duration

/**
 * Conditioning material for zero-shot cloning: a short recording of the parent
 * plus its transcript.
 *
 * Both matter. Zero-shot models condition on reference text as well as
 * reference audio, and a missing or wrong transcript degrades output in a way
 * that is easy to mistake for a model problem.
 */
data class Reference(
    val audioUri: String,
    val transcript: String,
    /**
     * Language of the reference recording. Cloning is noticeably better when
     * this matches the synthesis language, so callers should prefer a
     * same-language reference where one exists.
     */
    val language: String = "",
)

/** One segment of synthesis. */
data class Request(
    val text: String,
    /** BCP-47; the adapter maps it to the engine's own tag. */
    val language: String,
    val ref: Reference,
    /**
     * The last sentence of the preceding segment. Passing it lets the engine
     * continue the prosodic contour rather than restarting it, which is what
     * stops segment-wise generation sounding like a sequence of separate takes
     * (D-009).
     */
    val prevTail: String = "",
    /**
     * An optional hint drawn from the story's own markup (a character line, a
     * whisper). It never changes whose voice is used.
     */
    val style: String = "",
    /** Multiplies the default rate. Bedtime stories want slightly slow. */
    val speed: Double = 0.0,
)

/** Synthesised audio. */
data class Result(
    /**
     * WAV bytes. Segments are short enough to hold one in memory; whole stories
     * are never assembled in memory.
     */
    val audio: ByteArray,
    val sampleRate: Int,
    val duration: Duration,
    /** Recorded so a quality regression can be traced to a model change. */
    val engine: String,
    val engineVersion: String,
) {
    // Generated equals/hashCode would compare the array by identity, which is
    // never what a caller means.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Raised for a request the engine will never accept. Retrying will not help. */
class UnsupportedLanguageException(tag: String) : Exception("unsupported language: $tag")

/** Raised for a failure that may succeed on another attempt. */
class TransientSynthesisException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Renders one segment.
 *
 * Implementations must honour cancellation: a job whose delivery window has
 * passed is abandoned, and continuing to occupy a GPU for it delays someone
 * else's bedtime.
 */
interface Synthesizer {
    fun synthesize(request: Request): Result

    /** Identifies the backend in logs and metrics. */
    val name: String

    /**
     * The primary subtags this engine can speak.
     *
     * Exposed on the interface so the rest of the system asks the engine rather
     * than consulting a list that outlives it — the set shrank by two thirds
     * when the engine changed (docs/DECISIONS.md D-015), and nothing should
     * have to be remembered for that to be true.
     */
    val languages: Set<String>
}

/** True when retrying [e] cannot possibly help. */
fun isTerminal(e: Throwable): Boolean =
    e is UnsupportedLanguageException ||
        e is IllegalArgumentException ||
        e is InterruptedException

/**
 * Resolves a BCP-47 tag to a primary subtag the engine accepts, matching on the
 * primary subtag so "pt-BR" and "zh-Hans" resolve correctly.
 *
 * The set belongs to the engine, not to this file: which languages exist is a
 * property of whichever model is loaded, and swapping engines changes it. A
 * global list would go stale silently, and shipping a language the engine
 * cannot speak is a support incident rather than a setting.
 *
 * @throws UnsupportedLanguageException if the engine cannot speak it.
 */
fun primaryLanguage(tag: String, supported: Set<String>): String {
    val primary = tag.substringBefore('-').substringBefore('_').lowercase()
    if (primary !in supported) throw UnsupportedLanguageException(tag)
    return primary
}

internal fun Request.validate() {
    require(text.isNotBlank()) { "empty text" }
    require(language.isNotBlank()) { "empty language" }
    require(ref.audioUri.isNotBlank()) { "reference has no audio" }
    require(ref.transcript.isNotBlank()) { "reference has no transcript" }
}
