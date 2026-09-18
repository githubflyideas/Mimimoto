package mimimoto

import mimimoto.domain.*
import mimimoto.schedule.*
import java.time.*

private val CHILD = FamilyChild(FamilyId("f1"), ChildId("c1"))
private fun bedtime(h: Int, m: Int) = Spec(CHILD, LocalTime.of(h, m))
private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC)

private fun goodSample(id: String, kind: SampleKind, snr: Double) = VoiceSample(
    id = SampleId(id),
    voiceId = VoiceId("v1"),
    kind = kind,
    language = "zh",
    transcript = "我是爸爸",
    uri = "s3://samples/$id",
    quality = QualityStamp(passed = true, snrDb = snr),
)

private fun baseAvailability() = Availability(
    now = utc(2026, 9, 19, 10, 0),
    voice = VoicePrint(
        id = VoiceId("v1"),
        memberId = MemberId("m1"),
        familyId = FamilyId("f1"),
        samples = listOf(goodSample("s-enrol", SampleKind.ENROLMENT, 40.0)),
    ),
    defaultStory = StoryId("story-default"),
    cachedStory = StoryId("story-cached"),
)

private fun testOccurrence(): Occurrence {
    val at = utc(2026, 9, 19, 11, 30)
    return Occurrence(at, at.atZone(ZoneOffset.UTC), WallKind.NORMAL)
}

fun scheduleTests() = Suite.group("schedule") {

    // ---------- plain timezones ----------

    Suite.test("Tokyo bedtime resolves exactly") {
        val zone = ZoneId.of("Asia/Tokyo")
        val occ = nextOccurrence(bedtime(20, 30), zone, utc(2026, 9, 19))
        // 20:30 JST == 11:30 UTC, no DST ever.
        assertEquals(utc(2026, 9, 19, 11, 30), occ.at)
        assertEquals(WallKind.NORMAL, occ.kind)
    }

    // Kathmandu is UTC+05:45. Anything assuming whole-hour offsets breaks here.
    Suite.test("fractional offset zone") {
        val zone = ZoneId.of("Asia/Kathmandu")
        val occ = nextOccurrence(bedtime(20, 30), zone, utc(2026, 9, 19))
        assertEquals(utc(2026, 9, 19, 14, 45), occ.at)
        assertEquals(20, occ.localWall.hour)
        assertEquals(30, occ.localWall.minute)
    }

    // ---------- DST: the product guarantee ----------

    // What families actually care about: bedtime stays at 20:30 on their clock
    // through a DST change, even though the UTC instant moves.
    for (zoneName in listOf(
        "America/Los_Angeles", "Europe/Berlin", "Australia/Sydney",
        "Asia/Tokyo", "America/Sao_Paulo", "Pacific/Chatham",
    )) {
        Suite.test("bedtime holds wall clock across DST: $zoneName") {
            val zone = ZoneId.of(zoneName)
            var cursor = utc(2026, 1, 1)
            var previous: Instant? = null
            // A full year covers every transition in both hemispheres.
            repeat(365) {
                val occ = nextOccurrence(bedtime(20, 30), zone, cursor)
                assertEquals(20, occ.localWall.hour, "hour drifted on ${occ.localWall.toLocalDate()}")
                assertEquals(30, occ.localWall.minute, "minute drifted on ${occ.localWall.toLocalDate()}")
                previous?.let { prev ->
                    val gap = Duration.between(prev, occ.at)
                    // 23h on spring forward, 25h on autumn back, 24h otherwise.
                    // Chatham shifts by 45 minutes, so allow a little either way.
                    assertTrue(
                        gap >= Duration.ofMinutes(22 * 60 + 45) && gap <= Duration.ofMinutes(25 * 60 + 15),
                        "gap of $gap before ${occ.localWall} is not a day",
                    )
                }
                previous = occ.at
                cursor = occ.at
            }
        }
    }

    // Spring forward: 02:30 does not exist. Policy is the instant the clock
    // passes it (D-007) — the transition itself, not an hour later.
    Suite.test("skipped wall time resolves to the transition") {
        data class C(val zone: String, val date: LocalDate, val at: LocalTime, val want: Instant)
        val cases = listOf(
            // 02:00 PST jumps to 03:00 PDT == 10:00 UTC.
            C("America/Los_Angeles", LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), utc(2026, 3, 8, 10, 0)),
            // 02:00 CET jumps to 03:00 CEST == 01:00 UTC.
            C("Europe/Berlin", LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), utc(2026, 3, 29, 1, 0)),
            // Sydney springs forward on 4 Oct 2026; 02:00 AEST -> 03:00 AEDT.
            C("Australia/Sydney", LocalDate.of(2026, 10, 4), LocalTime.of(2, 30), utc(2026, 10, 3, 16, 0)),
        )
        for (c in cases) {
            val r = resolveWallClock(ZoneId.of(c.zone), c.date, c.at)
            assertEquals(WallKind.SKIPPED, r.kind, c.zone)
            assertEquals(c.want, r.instant, c.zone)
        }
    }

    // Autumn back: the time happens twice. Policy is the earlier one, so the
    // story never lands after the child is asleep.
    Suite.test("ambiguous wall time takes the earlier occurrence") {
        data class C(val zone: String, val date: LocalDate, val at: LocalTime)
        val cases = listOf(
            C("America/Los_Angeles", LocalDate.of(2026, 11, 1), LocalTime.of(1, 30)),
            C("Europe/Berlin", LocalDate.of(2026, 10, 25), LocalTime.of(2, 30)),
            C("Australia/Sydney", LocalDate.of(2026, 4, 5), LocalTime.of(2, 30)),
        )
        for (c in cases) {
            val zone = ZoneId.of(c.zone)
            val r = resolveWallClock(zone, c.date, c.at)
            assertEquals(WallKind.AMBIGUOUS, r.kind, c.zone)

            // The chosen instant must carry the requested wall clock...
            val local = r.instant.atZone(zone)
            assertEquals(c.at, local.toLocalTime(), c.zone)
            // ...and the same wall clock must recur an hour later, proving we
            // took the first of the two.
            val later = r.instant.plus(Duration.ofHours(1)).atZone(zone)
            assertEquals(c.at, later.toLocalTime(), "${c.zone}: chose the later occurrence")
        }
    }

    // ---------- weekdays ----------

    Suite.test("weekday filter") {
        val zone = ZoneId.of("Asia/Tokyo")
        val spec = bedtime(20, 30).copy(weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val occs = upcoming(spec, zone, utc(2026, 9, 16), 4) // from a Wednesday
        assertEquals(4, occs.size)
        occs.forEachIndexed { i, o ->
            assertTrue(
                o.localWall.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                "occurrence $i falls on ${o.localWall.dayOfWeek}",
            )
            if (i > 0) assertTrue(o.at.isAfter(occs[i - 1].at), "occurrence $i not strictly after previous")
        }
    }

    Suite.test("disabled schedule throws") {
        assertThrows<ScheduleException> {
            nextOccurrence(bedtime(20, 30).copy(enabled = false), ZoneId.of("Asia/Tokyo"), Instant.now())
        }
    }

    // ---------- degradation ladder ----------

    Suite.test("fresh tier uses today's note") {
        val daily = goodSample("s-today", SampleKind.DAILY, 32.0)
        val av = baseAvailability().copy(
            dailySample = daily,
            requestedStory = StoryId("story-three-pigs"),
        )
        val plan = decide(testOccurrence(), CHILD, av)
        assertEquals(Tier.FRESH, plan.tier)
        assertEquals(StoryId("story-three-pigs"), plan.storyId)
        assertEquals(SampleId("s-today"), plan.opening?.sampleId)
        assertEquals(SampleId("s-today"), plan.referenceSample)
    }

    // A sample that failed audioqc must never be used, even when it is the only
    // thing from today. A bad reference is worse than an older good one.
    Suite.test("sample that failed QC is ignored") {
        val bad = goodSample("s-bad", SampleKind.DAILY, 5.0)
            .let { it.copy(quality = it.quality.copy(passed = false, reason = "band_limited")) }
        val plan = decide(testOccurrence(), CHILD, baseAvailability().copy(dailySample = bad))
        assertTrue(plan.tier != Tier.FRESH, "a failed sample must not produce a fresh tier")
        assertEquals(SampleId("s-enrol"), plan.referenceSample)
    }

    Suite.test("inventory tier when no note today") {
        val av = baseAvailability().copy(
            inventoryOpening = Opening(SampleId("s-inv"), MemberId("m1"), "s3://openings/inv"),
        )
        val plan = decide(testOccurrence(), CHILD, av)
        assertEquals(Tier.INVENTORY, plan.tier)
        assertEquals(SampleId("s-inv"), plan.opening?.sampleId)
    }

    // The core of D-006: a parent who sent nothing still gets a story told in
    // their voice, and nothing announces their absence.
    Suite.test("voice-only tier still tells a story") {
        val plan = decide(testOccurrence(), CHILD, baseAvailability())
        assertEquals(Tier.VOICE_ONLY, plan.tier)
        assertEquals(StoryId("story-default"), plan.storyId)
        assertNull(plan.opening, "voice-only must have no opening")
        assertFalse(plan.tier.hasOpening)
    }

    // Never empty (D-008), whatever is missing.
    Suite.test("plan is always playable") {
        val variants: Map<String, (Availability) -> Availability> = mapOf(
            "no voice" to { a -> a.copy(voice = null) },
            "revoked voice" to { a -> a.copy(voice = a.voice!!.copy(revokedAt = a.now.minusSeconds(3600))) },
            "no usable samples" to { a ->
                a.copy(voice = a.voice!!.copy(samples = a.voice!!.samples.map {
                    it.copy(quality = it.quality.copy(passed = false))
                }))
            },
            "no default story" to { a -> a.copy(defaultStory = null) },
            "expired sample" to { a ->
                a.copy(voice = a.voice!!.copy(samples = a.voice!!.samples.map {
                    it.copy(expiresAt = a.now.minusSeconds(3600))
                }))
            },
            "everything missing" to { a -> a.copy(voice = null, defaultStory = null) },
        )
        for ((name, mutate) in variants) {
            val plan = decide(testOccurrence(), CHILD, mutate(baseAvailability()))
            assertTrue(plan.storyId.value.isNotEmpty(), "$name: no story chosen")
        }
    }

    // The only acceptable failure: nothing to play at all. It must throw so it
    // pages someone, rather than becoming a silent empty evening.
    Suite.test("a family with genuinely nothing throws") {
        val av = baseAvailability().copy(voice = null, cachedStory = null, defaultStory = null)
        assertThrows<ScheduleException> { decide(testOccurrence(), CHILD, av) }
    }

    Suite.test("generation lead precedes delivery and stays small") {
        val plan = decide(testOccurrence(), CHILD, baseAvailability())
        val lead = Duration.between(plan.startGenerationAt, plan.deliverAt)
        assertEquals(Lead.DEFAULT.total, lead)
        // Segment-wise generation keeps this small; past a few minutes the
        // "record any time before bedtime" promise is broken.
        assertTrue(lead <= Duration.ofMinutes(5), "lead of $lead reintroduces a recording cutoff")
    }

    Suite.test("queue orders by deadline") {
        val base = utc(2026, 9, 19)
        fun at(h: Long) = decide(
            Occurrence(base.plusSeconds(h * 3600), base.atZone(ZoneOffset.UTC), WallKind.NORMAL),
            CHILD, baseAvailability(),
        )
        val ordered = listOf(at(3), at(1), at(2)).byDeadline()
        for (i in 1 until ordered.size) {
            assertTrue(
                !ordered[i].startGenerationAt.isBefore(ordered[i - 1].startGenerationAt),
                "queue out of order at $i",
            )
        }
    }
}
