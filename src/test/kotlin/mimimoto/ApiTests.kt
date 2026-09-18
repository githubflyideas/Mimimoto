package mimimoto

import mimimoto.domain.*
import mimimoto.httpapi.Api
import mimimoto.httpapi.BlobStore
import mimimoto.httpapi.buildServer
import mimimoto.json.*
import mimimoto.store.MemoryStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId

/**
 * Exercises the whole chain over a real socket: HTTP in, audioqc, store, plan
 * out. Cheaper than it looks and it catches the things unit tests cannot —
 * routing, status codes, and whether a rejected recording really does come back
 * as a normal 200 the app can act on.
 */
/** Records what was written so a test can assert audio actually landed. */
private class RecordingBlobStore : BlobStore {
    val written = mutableMapOf<String, Int>()
    override fun put(key: String, data: ByteArray): String {
        written[key] = data.size
        return "mem:///$key"
    }
}

private class Harness {
    val store = MemoryStore()
    val blobs = RecordingBlobStore()
    val now: Instant = Instant.parse("2026-09-19T09:00:00Z")
    private val server = buildServer(Api(store, blobs, now = { now }), 0, threads = 2)
    private val client: HttpClient = HttpClient.newHttpClient()

    val port: Int get() = server.address.port

    fun start() = server.start()
    fun stop() = server.stop(0)

    fun get(path: String): Pair<Int, Json> = send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
    )

    fun post(path: String, body: ByteArray): Pair<Int, Json> = send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
    )

    fun postJson(path: String, body: String): Pair<Int, Json> = send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    )

    fun delete(path: String): Pair<Int, Json> = send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).DELETE().build()
    )

    private fun send(request: HttpRequest): Pair<Int, Json> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to Json.parse(response.body())
    }

    fun seed() {
        store.putFamily(
            Family(
                id = FamilyId("f1"),
                ownerId = MemberId("m1"),
                createdAt = now,
                members = listOf(
                    Member(MemberId("m1"), FamilyId("f1"), Role.OWNER, "爸爸", "zh", VoiceId("v1"))
                ),
                children = listOf(
                    Child(ChildId("c1"), FamilyId("f1"), "小豆子", AgeBand.PRESCHOOL, "zh", ZoneId.of("Asia/Tokyo"))
                ),
            )
        )
        store.putVoice(VoicePrint(VoiceId("v1"), MemberId("m1"), FamilyId("f1"), language = "zh"))
        store.putStory(
            Story(
                StoryId("story-pigs"), "三只小猪", "zh", AgeBand.PRESCHOOL, StorySource.PUBLIC_DOMAIN,
                listOf(Segment(0, "从前有三只小猪。"), Segment(1, "他们住在森林边上。")),
            )
        )
    }
}

fun apiTests() = Suite.group("api") {
    val h = Harness()
    h.seed()
    h.start()
    try {
        Suite.test("health") {
            val (code, body) = h.get("/healthz")
            assertEquals(200, code)
            assertEquals("ok", body.str("status"))
        }

        Suite.test("a good daily note is accepted and stored") {
            val wav = wideband48k(seconds = 6.0)
            val (code, body) = h.post(
                "/v1/voices/v1/samples?kind=DAILY&language=zh&transcript=%E6%88%91%E6%98%AF%E7%88%B8%E7%88%B8", wav
            )
            assertEquals(200, code)
            assertEquals(true, body.bool("accepted"), "rejected: ${body.render()}")

            val stored = h.store.voice(VoiceId("v1"))!!.samples
            assertEquals(1, stored.size)
            assertEquals("我是爸爸", stored[0].transcript, "transcript lost in transit")
            assertTrue(stored[0].quality.passed)
            // Daily notes are short-lived by design.
            assertNotNull(stored[0].expiresAt, "a daily note must carry an expiry")
            assertEquals(1, h.blobs.written.size, "audio should have been written once")
        }

        // The failure the gate exists for, end to end: a call-quality recording
        // comes back as a normal 200 the app can turn into a retake prompt,
        // and nothing is stored.
        Suite.test("a telephony-band recording is refused without being stored") {
            val before = h.store.voice(VoiceId("v1"))!!.samples.size
            val (code, body) = h.post(
                "/v1/voices/v1/samples?kind=DAILY&transcript=hello", telephonyBand(seconds = 6.0)
            )
            assertEquals(200, code, "a retake is a normal outcome, not an error status")
            assertEquals(false, body.bool("accepted"))
            assertEquals(
                listOf("band_limited"),
                body["retry"]!!["codes"].asList()!!.map { it.asString() },
            )
            assertEquals(before, h.store.voice(VoiceId("v1"))!!.samples.size, "a failed sample was stored")
        }

        Suite.test("a sample without a transcript is a client error") {
            val (code, _) = h.post("/v1/voices/v1/samples?kind=DAILY", wideband48k(seconds = 6.0))
            assertEquals(400, code)
        }

        Suite.test("plan reports the tier and the reason") {
            val (code, body) = h.postJson(
                "/v1/plan",
                """{"family_id":"f1","child_id":"c1","member_id":"m1","at":"2026-09-19T09:00:00Z"}""",
            )
            assertEquals(200, code, body.render())
            // A daily note was accepted above, so tonight is the full experience.
            assertEquals("fresh", body.str("tier"))
            assertEquals("story-pigs", body.str("story_id"))
            assertEquals(true, body.bool("has_opening"))
            // 20:30 Tokyo on the 19th is 11:30Z.
            assertEquals("2026-09-19T11:30:00Z", body.str("deliver_at"))
            assertEquals("normal", body.str("wall_kind"))
            assertTrue(body.str("notes")!!.isNotBlank(), "support needs the reason")
        }

        Suite.test("unknown family and child are 404") {
            assertEquals(404, h.postJson("/v1/plan", """{"family_id":"nope","child_id":"c1"}""").first)
            assertEquals(404, h.postJson("/v1/plan", """{"family_id":"f1","child_id":"nope"}""").first)
        }

        Suite.test("deleting a voice removes it and its samples") {
            val (code, _) = h.delete("/v1/voices/v1")
            assertEquals(200, code)
            assertNull(h.store.voice(VoiceId("v1")))
            // And a later upload has nowhere to land.
            assertEquals(404, h.post("/v1/voices/v1/samples?transcript=x", wideband48k(2.0)).first)
        }

        Suite.test("wrong method is rejected") {
            assertEquals(405, h.postJson("/healthz", "{}").first)
        }
    } finally {
        h.stop()
    }
}
