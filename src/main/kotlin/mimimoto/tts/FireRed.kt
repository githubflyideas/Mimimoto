package mimimoto.tts

import mimimoto.json.Json
import mimimoto.json.asDouble
import mimimoto.json.asInt
import mimimoto.json.asString
import mimimoto.json.get
import mimimoto.json.jsonOf
import mimimoto.json.render
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Talks to the Python worker in worker/, which wraps FireRedTTS3.
 *
 * The wire format is deliberately dull: one JSON request, one JSON response
 * with base64 audio. Segments are short, so simplicity is worth more than
 * streaming would be — the streaming that matters happens a level up, between
 * segments (D-009).
 */
class FireRed(
    private val baseUrl: String,
    /** Reported in results when the worker does not supply its own. */
    private val version: String = "",
    private val http: HttpClient = defaultClient(),
    /**
     * A segment is seconds of audio; a minute of wall clock means the worker is
     * wedged, and waiting longer only delays the fallback.
     */
    private val timeout: Duration = Duration.ofSeconds(60),
) : Synthesizer {

    override val name = "firered-tts3"

    override fun synthesize(request: Request): Result {
        request.validate()
        val language = primaryLanguage(request.language)

        val body = jsonOf(
            "text" to request.text,
            "language" to language,
            "prompt_audio_uri" to request.ref.audioUri,
            "prompt_text" to request.ref.transcript,
            "prev_tail" to request.prevTail,
            "style" to request.style,
            "speed" to request.speed,
        ).render()

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/synthesize"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            throw TransientSynthesisException("calling synthesis worker: ${e.message}", e)
        }

        if (response.statusCode() == 400) {
            // The worker rejects malformed requests with 400. Retrying an
            // identical request cannot help, so this is terminal.
            throw IllegalArgumentException("worker rejected request: ${truncate(response.body())}")
        }
        if (response.statusCode() != 200) {
            throw TransientSynthesisException(
                "worker returned ${response.statusCode()}: ${truncate(response.body())}"
            )
        }

        val parsed = try {
            Json.parse(response.body())
        } catch (e: Exception) {
            throw TransientSynthesisException("decoding worker response: ${e.message}", e)
        }

        parsed["error"].asString()?.let { throw TransientSynthesisException("worker error: $it") }

        val encoded = parsed["audio_b64"].asString()
            ?: throw TransientSynthesisException("worker response has no audio")
        val audio = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw TransientSynthesisException("worker returned undecodable audio", e)
        }
        if (audio.isEmpty()) throw TransientSynthesisException("worker returned empty audio")

        val seconds = parsed["duration_s"].asDouble() ?: 0.0
        return Result(
            audio = audio,
            sampleRate = parsed["sample_rate"].asInt() ?: 0,
            duration = Duration.ofMillis((seconds * 1000).toLong()),
            engine = name,
            engineVersion = parsed["model_version"].asString()?.ifBlank { version } ?: version,
        )
    }

    private fun truncate(s: String, n: Int = 256) = if (s.length <= n) s else s.take(n) + "…"

    companion object {
        private fun defaultClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
