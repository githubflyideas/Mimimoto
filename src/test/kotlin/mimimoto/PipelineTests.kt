package mimimoto

import mimimoto.domain.*
import mimimoto.pipeline.*
import mimimoto.schedule.*
import mimimoto.tts.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val CHILD2 = FamilyChild(FamilyId("f1"), ChildId("c1"))

private fun story(n: Int, source: StorySource = StorySource.PUBLIC_DOMAIN) = Story(
    id = StoryId("story-1"),
    title = "三只小猪",
    language = "zh",
    ageBand = AgeBand.PRESCHOOL,
    source = source,
    segments = (0 until n).map { Segment(it, "小豆子走进了森林。树很高，风很轻。") },
)

private fun plan(): Plan {
    val at = Instant.parse("2026-09-19T11:30:00Z")
    return Plan(
        child = CHILD2,
        deliverAt = at,
        localWall = at.atZone(ZoneOffset.UTC),
        wallKind = WallKind.NORMAL,
        tier = Tier.FRESH,
        storyId = StoryId("story-1"),
        voiceId = VoiceId("v1"),
        referenceSample = SampleId("s1"),
        startGenerationAt = at.minusSeconds(135),
    )
}

private fun job(n: Int, source: StorySource = StorySource.PUBLIC_DOMAIN) = Job(
    plan = plan(),
    story = story(n, source),
    ref = Reference("file:///ref.wav", "我是爸爸", "zh"),
    language = "zh",
)

/** A clock the test drives, so latency assertions are exact rather than flaky. */
private class FakeClock(var t: Instant = Instant.parse("2026-09-19T11:25:00Z")) {
    fun now() = t
    fun advance(d: Duration) { t = t.plus(d) }
}

/** Charges simulated GPU time to the fake clock instead of sleeping. */
private class ClockAdvancing(
    private val inner: Synthesizer,
    private val clock: FakeClock,
    private val perCall: Duration,
) : Synthesizer {
    override val name get() = inner.name
    override fun synthesize(request: Request): Result {
        clock.advance(perCall)
        return inner.synthesize(request)
    }
}

private fun renderer(synth: Synthesizer, clock: FakeClock, perCall: Duration) = Renderer(
    synth = ClockAdvancing(synth, clock, perCall),
    now = clock::now,
    sleep = { clock.advance(it) },
)

fun pipelineTests() = Suite.group("pipeline") {

    Suite.test("segments arrive in order") {
        val clock = FakeClock()
        val seen = mutableListOf<Int>()
        val outcome = renderer(MockSynthesizer(), clock, Duration.ofSeconds(2)).render(job(6)) {
            seen += it.index
            assertEquals(6, it.total)
        }
        seen.forEachIndexed { i, idx -> assertEquals(i, idx, "segment out of order") }
        assertTrue(outcome.complete, "outcome should be complete: $outcome")
    }

    // The promise that removes the recording cutoff: playback can start long
    // before the story is finished.
    Suite.test("first segment is ready long before the rest") {
        val clock = FakeClock()
        val outcome = renderer(MockSynthesizer(), clock, Duration.ofSeconds(3)).render(job(20)) {}
        assertEquals(Duration.ofSeconds(3), outcome.firstSegmentLatency)
        assertTrue(outcome.wall > outcome.firstSegmentLatency, "wall should exceed first-segment latency")
        val advantage = outcome.wall.toMillis() / outcome.firstSegmentLatency.toMillis()
        assertTrue(advantage >= 10, "first-segment advantage only ${advantage}x")
    }

    // Generation must outrun playback or the child hears a gap mid-story.
    Suite.test("realtime factor flags falling behind") {
        // Each segment is ~17 characters at 0.12 s each, about 2 s of audio.
        val fast = renderer(MockSynthesizer(), FakeClock(), Duration.ofMillis(300)).render(job(10)) {}
        assertTrue(fast.keepsUp, "realtimeFactor ${fast.realtimeFactor} should be comfortably above 1")

        val slow = renderer(MockSynthesizer(), FakeClock(), Duration.ofSeconds(30)).render(job(10)) {}
        assertFalse(slow.keepsUp, "realtimeFactor ${slow.realtimeFactor} should be flagged as too slow")
    }

    // D-002's teeth live at the parsing boundary, because StorySource cannot
    // hold an unapproved value in the first place.
    Suite.test("unapproved provenance is refused at the boundary") {
        assertThrows<UnapprovedSourceException> { StorySource.fromWire("llm_generated") }
        assertThrows<UnapprovedSourceException> { StorySource.fromWire("") }
        // The approved ones round-trip.
        for (s in StorySource.entries) assertEquals(s, StorySource.fromWire(s.wire))
        assertEquals(StorySource.PUBLIC_DOMAIN, StorySource.fromWire("  Public_Domain "))
    }

    Suite.test("transient failure is retried") {
        val clock = FakeClock()
        val target = "第二段。"
        var calls = 0
        val flaky = object : Synthesizer {
            val inner = MockSynthesizer()
            override val name = "flaky"
            override fun synthesize(request: Request): Result {
                if (request.text == target && ++calls <= 2) {
                    throw TransientSynthesisException("gpu busy")
                }
                return inner.synthesize(request)
            }
        }
        val j = job(1).let { it.copy(story = it.story.copy(segments = listOf(Segment(0, target)))) }
        val outcome = renderer(flaky, clock, Duration.ofSeconds(1)).render(j) {}
        assertEquals(2, outcome.retries)
        assertTrue(outcome.complete, "should be complete after retries")
    }

    // A language the engine cannot speak must fail at once rather than burning
    // three GPU slots on its way to the same answer.
    Suite.test("terminal error is not retried") {
        val clock = FakeClock()
        val e = assertThrows<SegmentFailedException> {
            renderer(MockSynthesizer(), clock, Duration.ofSeconds(1))
                .render(job(2).copy(language = "xx")) {}
        }
        assertTrue(e.cause is UnsupportedLanguageException, "cause was ${e.cause}")
        assertEquals(0, e.outcome.retries, "terminal errors must not be retried")
    }

    Suite.test("consumer can stop the render") {
        val clock = FakeClock()
        val e = assertThrows<AbortedException> {
            renderer(MockSynthesizer(), clock, Duration.ofSeconds(1)).render(job(10)) {
                if (it.index == 2) throw IllegalStateException("delivery window passed")
            }
        }
        assertTrue(e.message!!.contains("delivery window passed"), "reason lost: ${e.message}")
    }

    // Prosody continuity: each call after the first carries the previous
    // segment's tail, and every call carries the same reference.
    Suite.test("previous tail is carried forward") {
        val clock = FakeClock()
        val mock = MockSynthesizer()
        val j = job(1).let {
            it.copy(story = it.story.copy(segments = listOf(
                Segment(0, "从前有三只小猪。他们住在森林边上。"),
                Segment(1, "有一天，狼来了。"),
                Segment(2, "小豆子跑得最快。"),
            )))
        }
        renderer(mock, clock, Duration.ofSeconds(1)).render(j) {}

        val calls = mock.calls
        assertEquals(3, calls.size)
        assertEquals("", calls[0].prevTail, "first call should have no tail")
        assertEquals("他们住在森林边上。", calls[1].prevTail)
        assertEquals("有一天，狼来了。", calls[2].prevTail)
        calls.forEachIndexed { i, c ->
            assertEquals("file:///ref.wav", c.ref.audioUri, "call $i lost the reference")
        }
    }

    Suite.test("tail sentence") {
        val cases = mapOf(
            "从前有三只小猪。他们住在森林边上。" to "他们住在森林边上。",
            "Once upon a time. The wolf came." to "The wolf came.",
            "どうしたの？おやすみ。" to "おやすみ。",
            "no terminator here" to "no terminator here",
            "" to "",
            "   " to "",
            "One." to "One.",
        )
        for ((input, want) in cases) assertEquals(want, tailSentence(input), "input=<$input>")
    }

    Suite.test("empty story is rejected") {
        assertThrows<IllegalArgumentException> {
            renderer(MockSynthesizer(), FakeClock(), Duration.ofSeconds(1)).render(job(0)) {}
        }
    }

    Suite.test("plan without a voice is rejected") {
        assertThrows<NoVoiceException> {
            val j = job(3).let { it.copy(plan = it.plan.copy(voiceId = null)) }
            renderer(MockSynthesizer(), FakeClock(), Duration.ofSeconds(1)).render(j) {}
        }
    }

    // The mock produces real WAV bytes, so anything downstream that decodes
    // audio is genuinely exercised rather than fed a placeholder.
    Suite.test("rendered segments are decodable audio") {
        val clock = FakeClock()
        renderer(MockSynthesizer(), clock, Duration.ofSeconds(1)).render(job(2)) {
            val decoded = mimimoto.audioqc.decodeWav(it.audio.audio.inputStream())
            assertEquals(24_000, decoded.sampleRate)
            assertTrue(decoded.samples.isNotEmpty(), "segment ${it.index} decoded to nothing")
        }
    }
}
