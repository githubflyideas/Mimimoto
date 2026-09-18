package mimimoto

import mimimoto.domain.*
import mimimoto.json.*
import mimimoto.store.MemoryStore
import mimimoto.store.NotFoundException
import java.time.Instant

fun jsonTests() = Suite.group("json") {

    Suite.test("round trips the shapes the API uses") {
        val original = jsonOf(
            "accepted" to true,
            "sample_id" to "daily-1758000000000",
            "quality" to jsonOf("snr_db" to 31.5, "cutoff_hz" to 19969.0, "passed" to true),
            "retry" to jsonOf("codes" to listOf("band_limited", "noisy")),
            "notes" to null,
        )
        val parsed = Json.parse(original.render())
        assertEquals(true, parsed["accepted"].asBool())
        assertEquals("daily-1758000000000", parsed["sample_id"].asString())
        assertEquals(31.5, parsed["quality"]!!["snr_db"].asDouble())
        assertEquals(2, parsed["retry"]!!["codes"].asList()?.size)
        assertEquals(Json.Null, parsed["notes"])
    }

    Suite.test("whole numbers render without a decimal tail") {
        assertEquals("""{"n":48000}""", jsonOf("n" to 48000).render())
        assertEquals("""{"n":0.5}""", jsonOf("n" to 0.5).render())
    }

    Suite.test("escapes and unicode survive") {
        val text = "爸爸说：\"晚安\"\n\t路径 C:\\tmp"
        val parsed = Json.parse(jsonOf("t" to text).render())
        assertEquals(text, parsed["t"].asString())
    }

    Suite.test("nested structures survive") {
        val nested = jsonOf(
            "families" to listOf(
                jsonOf("id" to "f1", "children" to listOf(jsonOf("id" to "c1", "tz" to "Asia/Tokyo"))),
                jsonOf("id" to "f2", "children" to emptyList<Json>()),
            ),
        )
        val parsed = Json.parse(nested.render())
        val families = parsed["families"].asList()!!
        assertEquals(2, families.size)
        assertEquals("Asia/Tokyo", families[0]["children"].asList()!![0]["tz"].asString())
        assertEquals(0, families[1]["children"].asList()!!.size)
    }

    Suite.test("malformed input is rejected rather than guessed at") {
        for (bad in listOf("{", "{\"a\":}", "[1,]", "\"unterminated", "{\"a\":1}x", "")) {
            assertThrows<Exception>("should have rejected <$bad>") { Json.parse(bad) }
        }
    }

    Suite.test("a missing field reads as absent, not as a crash") {
        val parsed = Json.parse("""{"a":1}""")
        assertNull(parsed["b"])
        assertNull(parsed["b"].asString())
        assertEquals(1, parsed["a"].asInt())
    }
}

fun storeTests() = Suite.group("store") {

    fun sample(id: String, kind: SampleKind, expires: Instant? = null) = VoiceSample(
        id = SampleId(id),
        voiceId = VoiceId("v1"),
        kind = kind,
        language = "zh",
        transcript = "我是爸爸",
        uri = "file:///$id.wav",
        quality = QualityStamp(passed = true, snrDb = 30.0),
        expiresAt = expires,
    )

    fun freshStore() = MemoryStore().apply {
        putVoice(VoicePrint(VoiceId("v1"), MemberId("m1"), FamilyId("f1")))
    }

    Suite.test("samples are stored newest first") {
        val store = freshStore()
        store.appendSample(VoiceId("v1"), sample("s1", SampleKind.ENROLMENT))
        store.appendSample(VoiceId("v1"), sample("s2", SampleKind.DAILY))
        assertEquals(SampleId("s2"), store.voice(VoiceId("v1"))!!.samples.first().id)
    }

    // A deletion request must be honourable in one call, with nothing left
    // behind for a later audit to find.
    Suite.test("deleting a voice takes its samples with it") {
        val store = freshStore()
        store.appendSample(VoiceId("v1"), sample("s1", SampleKind.ENROLMENT))
        store.deleteVoice(VoiceId("v1"))
        assertNull(store.voice(VoiceId("v1")))
        assertNull(store.voiceForMember(MemberId("m1")))
        assertThrows<NotFoundException> { store.deleteVoice(VoiceId("v1")) }
    }

    Suite.test("revocation is visible immediately") {
        val store = freshStore()
        val at = Instant.parse("2026-09-19T00:00:00Z")
        store.revokeVoice(VoiceId("v1"), at)
        assertTrue(store.voice(VoiceId("v1"))!!.revoked)
    }

    Suite.test("expiry removes only what is past its date") {
        val now = Instant.parse("2026-09-19T00:00:00Z")
        val store = freshStore()
        store.appendSample(VoiceId("v1"), sample("keep-enrol", SampleKind.ENROLMENT))
        store.appendSample(VoiceId("v1"), sample("keep-daily", SampleKind.DAILY, now.plusSeconds(3600)))
        store.appendSample(VoiceId("v1"), sample("drop-daily", SampleKind.DAILY, now.minusSeconds(1)))

        assertEquals(1, store.expireSamples(now))
        val left = store.voice(VoiceId("v1"))!!.samples.map { it.id.value }.toSet()
        assertEquals(setOf("keep-enrol", "keep-daily"), left)
        // Idempotent: running it again changes nothing.
        assertEquals(0, store.expireSamples(now))
    }

    Suite.test("story rotation filters by language and age band") {
        val store = MemoryStore()
        fun story(id: String, lang: String, band: AgeBand) = Story(
            StoryId(id), id, lang, band, StorySource.PUBLIC_DOMAIN,
            listOf(Segment(0, "从前……")),
        )
        store.putStory(story("zh-pre-b", "zh", AgeBand.PRESCHOOL))
        store.putStory(story("zh-pre-a", "zh", AgeBand.PRESCHOOL))
        store.putStory(story("zh-old", "zh", AgeBand.OLDER))
        store.putStory(story("ja-pre", "ja", AgeBand.PRESCHOOL))

        val got = store.storiesFor("zh", AgeBand.PRESCHOOL).map { it.id.value }
        // Sorted, so the rotation is deterministic rather than map-order.
        assertEquals(listOf("zh-pre-a", "zh-pre-b"), got)
    }

    Suite.test("a malformed story is refused at the door") {
        val store = MemoryStore()
        assertThrows<IllegalArgumentException> {
            store.putStory(
                Story(StoryId("bad"), "bad", "zh", AgeBand.PRESCHOOL, StorySource.PUBLIC_DOMAIN, emptyList())
            )
        }
        assertThrows<IllegalArgumentException> {
            store.putStory(
                Story(
                    StoryId("bad2"), "bad2", "zh", AgeBand.PRESCHOOL, StorySource.PUBLIC_DOMAIN,
                    listOf(Segment(0, "ok"), Segment(5, "wrong index")),
                )
            )
        }
    }
}
