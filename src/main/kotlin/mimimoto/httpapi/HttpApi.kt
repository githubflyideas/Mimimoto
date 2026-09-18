/**
 * Exposes the flows that actually exist today: taking a recording in, judging
 * it, and working out what a given child will hear tonight.
 *
 * Two things are deliberately absent. There is no endpoint that accepts audio
 * of anyone other than the authenticated member — cloning a voice from an
 * uploaded file is the shape of a fraud tool, not of this product (D-005). And
 * there is no endpoint that accepts free-form text to speak in a parent's
 * voice; text arrives as a story id, and stories carry provenance (D-002).
 */
package mimimoto.httpapi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mimimoto.audioqc.Profile
import mimimoto.audioqc.Report
import mimimoto.audioqc.analyse
import mimimoto.domain.*
import mimimoto.json.*
import mimimoto.schedule.*
import mimimoto.store.NotFoundException
import mimimoto.store.Store
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.logging.Logger

/** Where audio is stored. */
interface BlobStore {
    fun put(key: String, data: ByteArray): String
}

/**
 * How long each kind of sample is kept.
 *
 * Daily notes expire quickly: they are an instruction and a fresh reference,
 * not an archive, and the less voice data sits around the smaller the problem
 * when the storage question finally gets answered properly.
 */
data class Retention(
    val daily: Duration = Duration.ofDays(14),
    val inventory: Duration = Duration.ofDays(180),
)

class Api(
    private val store: Store,
    private val blobs: BlobStore,
    private val retention: Retention = Retention(),
    private val now: () -> Instant = Instant::now,
    /**
     * Caps request bodies. A minute of 48 kHz 16-bit mono is about 5.8 MB;
     * 32 MB leaves room for an enrolment without inviting abuse.
     */
    private val maxUpload: Int = 32 * 1024 * 1024,
    private val log: Logger = Logger.getLogger("mimimoto.api"),
) {

    fun attachTo(server: HttpServer) {
        server.createContext("/healthz") { ex -> handle(ex, "GET") { health() } }
        server.createContext("/v1/voices") { ex -> route(ex) }
        server.createContext("/v1/plan") { ex -> handle(ex, "POST") { postPlan(readBody(ex)) } }
    }

    // ---------- routing ----------

    private fun route(ex: HttpExchange) {
        // /v1/voices/{id}/samples  and  /v1/voices/{id}
        val parts = ex.requestURI.path.trim('/').split('/')
        when {
            parts.size == 4 && parts[3] == "samples" ->
                handle(ex, "POST") { postSample(VoiceId(parts[2]), ex) }
            parts.size == 3 ->
                handle(ex, "DELETE") { deleteVoice(VoiceId(parts[2])) }
            else -> respond(ex, 404, jsonOf("error" to "not found"))
        }
    }

    private fun handle(ex: HttpExchange, method: String, body: () -> Pair<Int, Json>) {
        try {
            if (ex.requestMethod != method) {
                respond(ex, 405, jsonOf("error" to "method not allowed"))
                return
            }
            val (code, payload) = body()
            respond(ex, code, payload)
        } catch (e: NotFoundException) {
            respond(ex, 404, jsonOf("error" to (e.message ?: "not found")))
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, jsonOf("error" to (e.message ?: "bad request")))
        } catch (e: ScheduleException) {
            respond(ex, 422, jsonOf("error" to (e.message ?: "cannot schedule")))
        } catch (e: Exception) {
            log.warning("request failed: ${e.message}")
            respond(ex, 500, jsonOf("error" to "internal error"))
        } finally {
            ex.close()
        }
    }

    private fun respond(ex: HttpExchange, code: Int, payload: Json) {
        val bytes = payload.render().toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun readBody(ex: HttpExchange): Json {
        val raw = ex.requestBody.readNBytes(1 shl 20).toString(StandardCharsets.UTF_8)
        return if (raw.isBlank()) Json.Obj(emptyMap()) else Json.parse(raw)
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&')
            .filter { it.isNotBlank() }
            .associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                URLDecoder.decode(k, StandardCharsets.UTF_8) to
                    URLDecoder.decode(v, StandardCharsets.UTF_8)
            }

    // ---------- handlers ----------

    private fun health(): Pair<Int, Json> = 200 to jsonOf("status" to "ok")

    /**
     * Takes one recording, judges it, and stores it only if it passed.
     *
     * A rejected sample is answered with 200 and accepted=false rather than an
     * error status: from the app's point of view a retake is a normal outcome
     * of a successful request, and the report is the payload it needs.
     */
    private fun postSample(voiceId: VoiceId, ex: HttpExchange): Pair<Int, Json> {
        val q = query(ex)
        val transcript = q["transcript"].orEmpty()
        // Zero-shot cloning conditions on the reference transcript as well as
        // the audio, so a missing one is a client bug worth failing loudly on
        // rather than a quality problem to discover later.
        require(transcript.isNotBlank()) { "transcript is required" }

        val kind = q["kind"]?.uppercase()?.let { name ->
            SampleKind.entries.firstOrNull { it.name == name }
        } ?: SampleKind.DAILY
        val language = q["language"].orEmpty()

        val voice = store.voice(voiceId) ?: throw NotFoundException("voice $voiceId")
        if (voice.revoked) return 403 to jsonOf("error" to "voice is revoked")

        val body = ex.requestBody.readNBytes(maxUpload)
        val profile = if (kind == SampleKind.ENROLMENT) Profile.ENROLMENT else Profile.DAILY
        val report = analyse(ByteArrayInputStream(body), profile)

        if (!report.passed) {
            log.info(
                "sample rejected: voice=$voiceId kind=$kind snr=%.1f cutoff=%.0f codes=%s"
                    .format(report.snrDb, report.cutoffHz, report.failures.map { it.code.wire })
            )
            return 200 to jsonOf(
                "accepted" to false,
                "quality" to reportJson(report),
                // Codes, not prose: the parent sees their own language.
                "retry" to jsonOf("codes" to report.failures.map { it.code.wire }),
            )
        }

        val at = now()
        val id = SampleId("${kind.name.lowercase()}-${at.toEpochMilli()}")
        val uri = blobs.put("${voiceId.value}/${id.value}.wav", body)

        store.appendSample(
            voiceId,
            VoiceSample(
                id = id,
                voiceId = voiceId,
                kind = kind,
                language = language,
                transcript = transcript,
                uri = uri,
                createdAt = at,
                expiresAt = expiry(kind, at),
                quality = QualityStamp(
                    passed = true,
                    snrDb = report.snrDb,
                    speechSeconds = report.speechSeconds,
                    cutoffHz = report.cutoffHz,
                    reason = report.reason(),
                ),
            ),
        )

        log.info(
            "sample accepted: voice=$voiceId sample=$id kind=$kind snr=%.1f cutoff=%.0f speech=%.1fs"
                .format(report.snrDb, report.cutoffHz, report.speechSeconds)
        )
        return 200 to jsonOf(
            "accepted" to true,
            "sample_id" to id.value,
            "quality" to reportJson(report),
        )
    }

    /** Enrolment samples live until the member deletes the voice. */
    private fun expiry(kind: SampleKind, at: Instant): Instant? = when (kind) {
        SampleKind.DAILY -> at.plus(retention.daily)
        SampleKind.INVENTORY -> at.plus(retention.inventory)
        SampleKind.ENROLMENT -> null
    }

    /**
     * Honours a deletion request. The store removes the voiceprint and every
     * sample together; there is no partial form of this operation.
     */
    private fun deleteVoice(id: VoiceId): Pair<Int, Json> {
        store.deleteVoice(id)
        log.info("voice deleted: $id")
        return 200 to jsonOf("deleted" to id.value)
    }

    /**
     * Answers "what will this child hear tonight, and why". This is the
     * endpoint support will live in: every degradation decision is visible,
     * with the reason attached.
     */
    private fun postPlan(body: Json): Pair<Int, Json> {
        val familyId = FamilyId(body.str("family_id") ?: throw IllegalArgumentException("family_id is required"))
        val childId = ChildId(body.str("child_id") ?: throw IllegalArgumentException("child_id is required"))
        val memberId = body.str("member_id")?.let { MemberId(it) }
        val at = body.str("at")?.let { Instant.parse(it) } ?: now()

        val family = store.family(familyId) ?: throw NotFoundException("family $familyId")
        val child = family.child(childId) ?: throw NotFoundException("child $childId")

        val spec = Spec(
            child = FamilyChild(familyId, childId),
            at = LocalTime.of(20, 30),
        )
        val occurrence = nextOccurrence(spec, child.zone, at)

        val voice = memberId?.let { store.voiceForMember(it) }
        val daily = voice?.samples?.firstOrNull { it.kind == SampleKind.DAILY && it.usableAt(at) }
        val rotation = store.storiesFor(child.language, child.ageBand)

        val plan = decide(
            occurrence,
            spec.child,
            Availability(
                now = at,
                voice = voice,
                dailySample = daily,
                defaultStory = rotation.firstOrNull()?.id,
            ),
        )

        return 200 to jsonOf(
            "tier" to plan.tier.wire,
            "story_id" to plan.storyId.value,
            "has_opening" to (plan.opening != null),
            "deliver_at" to plan.deliverAt.toString(),
            "local_wall" to plan.localWall.format(WALL_FORMAT),
            "wall_kind" to plan.wallKind.name.lowercase(),
            "start_generation_at" to plan.startGenerationAt.toString(),
            "notes" to plan.notes,
        )
    }

    private fun reportJson(r: Report): Json = jsonOf(
        "passed" to r.passed,
        "duration_s" to r.durationSeconds,
        "sample_rate" to r.sampleRate,
        "channels" to r.channels,
        "bit_depth" to r.bitDepth,
        "snr_db" to r.snrDb,
        "speech_s" to r.speechSeconds,
        "speech_ratio" to r.speechRatio,
        "cutoff_hz" to r.cutoffHz,
        "clipping_ratio" to r.clippingRatio,
        "failures" to r.failures.map { jsonOf("code" to it.code.wire, "message" to it.message) },
        "warnings" to r.warnings.map { jsonOf("code" to it.code.wire, "message" to it.message) },
    )

    private companion object {
        val WALL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz")
    }
}

/** Builds a configured server. The caller starts and stops it. */
fun buildServer(api: Api, port: Int, threads: Int = 8): HttpServer =
    HttpServer.create(InetSocketAddress(port), 0).also {
        api.attachTo(it)
        it.executor = Executors.newFixedThreadPool(threads)
    }
