/**
 * Turns a family's standing arrangement ("every night at 20:30, the child's
 * time") into concrete, generatable jobs.
 *
 * Two rules drive everything here, and both come from product decisions rather
 * than engineering taste:
 *
 *  - Delivery is anchored to the CHILD's timezone, never the parent's and never
 *    the server's (D-007). Once a family builds a ritual around 20:30, a missed
 *    or mistimed delivery is a high-severity incident.
 *
 *  - The child's 20:30 is never empty (D-008), and a parent who did not record
 *    today is never punished through their child (D-006). Missing material
 *    degrades the evening one step at a time; it never cancels it.
 *
 * Both are expressed as data — [Tier] and [Plan.fallbacks] — so a caller cannot
 * accidentally implement a harsher policy than the one agreed.
 */
package mimimoto.schedule

import mimimoto.domain.FamilyChild
import mimimoto.domain.Opening
import mimimoto.domain.SampleId
import mimimoto.domain.SampleKind
import mimimoto.domain.StoryId
import mimimoto.domain.VoiceId
import mimimoto.domain.VoicePrint
import mimimoto.domain.VoiceSample
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The degradation ladder. Lower is better; every tier still plays a story. */
enum class Tier {
    /** A voice note arrived today: opening plus the story they asked for. */
    FRESH,

    /** No note today, but a pre-recorded opening is queued. */
    INVENTORY,

    /**
     * No opening available. The story is still told in the parent's voice, and
     * the child is NOT told that the parent did not come.
     */
    VOICE_ONLY,

    /** Synthesis unavailable or failed: replay something already on the device. */
    CACHED;

    val hasOpening: Boolean get() = this == FRESH || this == INVENTORY
    val wire: String get() = name.lowercase()
}

class ScheduleException(message: String) : Exception(message)

/** A standing delivery arrangement for one child. */
data class Spec(
    val child: FamilyChild,
    /** Wall-clock time in the child's timezone. */
    val at: LocalTime,
    /** Empty means every day. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
) {
    internal fun allows(day: DayOfWeek) = weekdays.isEmpty() || day in weekdays
}

/** A resolved delivery moment. */
data class Occurrence(
    /** The instant to deliver. */
    val at: Instant,
    /** The wall clock the family thinks in, for logs and UI. */
    val localWall: java.time.ZonedDateTime,
    /** Flags the DST cases so they show up in logs rather than in complaints. */
    val kind: WallKind,
)

/**
 * The first delivery strictly after [after].
 *
 * Walks forward day by day in the child's local calendar. Scanning a bounded
 * window rather than computing an offset is what lets the weekday filter and
 * the DST cases compose without either needing a special case.
 */
fun nextOccurrence(spec: Spec, zone: ZoneId, after: Instant): Occurrence {
    if (!spec.enabled) throw ScheduleException("schedule is disabled")

    // Start a day early: `after` may fall before today's delivery in local
    // terms even when UTC suggests otherwise.
    var date = after.atZone(zone).toLocalDate().minusDays(1)

    // Two weeks is more than enough for any weekday set; if nothing matches,
    // the set itself is broken.
    repeat(15) {
        val r = resolveWallClock(zone, date, spec.at)
        val local = r.instant.atZone(zone)
        // Filter on the weekday actually delivered on, which matters in the
        // skipped case where the instant can land past midnight.
        if (spec.allows(local.dayOfWeek) && r.instant.isAfter(after)) {
            return Occurrence(r.instant, local, r.kind)
        }
        date = date.plusDays(1)
    }
    throw ScheduleException("no weekday in ${spec.weekdays} ever matches")
}

/** The next [n] occurrences. Used to warm the queue and to show parents. */
fun upcoming(spec: Spec, zone: ZoneId, after: Instant, n: Int): List<Occurrence> {
    val out = ArrayList<Occurrence>(n)
    var cursor = after
    repeat(n) {
        val occ = nextOccurrence(spec, zone, cursor)
        out += occ
        cursor = occ.at
    }
    return out
}

// ---------- degradation ----------

/** Everything known at planning time about what this family can play tonight. */
data class Availability(
    val now: Instant,
    /** Null, or revoked, means no synthesis may happen at all. */
    val voice: VoicePrint? = null,
    /** Today's voice note, already through audioqc. */
    val dailySample: VoiceSample? = null,
    /** A pre-recorded opening waiting in the queue. */
    val inventoryOpening: Opening? = null,
    /** What the parent asked for in today's note. */
    val requestedStory: StoryId? = null,
    /** Tonight's pick from the age-appropriate rotation. */
    val defaultStory: StoryId? = null,
    /** Already rendered and on the device. The never-empty guarantee rests here. */
    val cachedStory: StoryId? = null,
)

/**
 * How far ahead of delivery generation must start.
 *
 * Because segments are generated and streamed in order (D-009), only the FIRST
 * segment has to exist at delivery time. That is what removes the "record
 * before 18:30" cutoff that would otherwise be forced on parents.
 */
data class Lead(
    val firstSegment: Duration,
    /** Absorbs queue wait, upload and the child device's fetch. */
    val safety: Duration,
) {
    val total: Duration get() = firstSegment.plus(safety)

    companion object {
        /** A starting point; replace with measured p95 per language and GPU class. */
        val DEFAULT = Lead(Duration.ofSeconds(45), Duration.ofSeconds(90))
    }
}

/** Something already playable, used when generation fails. */
data class Fallback(val tier: Tier, val storyId: StoryId, val reason: String)

/** One night's delivery for one child. */
data class Plan(
    val child: FamilyChild,
    val deliverAt: Instant,
    val localWall: java.time.ZonedDateTime,
    val wallKind: WallKind,
    val tier: Tier,
    val storyId: StoryId,
    /** Null means no synthesis is possible and the plan is replay-only. */
    val voiceId: VoiceId? = null,
    val referenceSample: SampleId? = null,
    /**
     * Played before the story. Null at [Tier.VOICE_ONLY] and below — and its
     * absence is silent, never announced to the child (D-006).
     */
    val opening: Opening? = null,
    /**
     * When this job must enter the GPU queue. Sorting pending plans by this
     * field is the whole scheduling policy: across twelve languages bedtime
     * rolls around the clock, so ordering by deadline fills the troughs with no
     * explicit balancing.
     */
    val startGenerationAt: Instant,
    /** Ordered, best first. */
    val fallbacks: List<Fallback> = emptyList(),
    /** Why this tier was chosen, for support and for tests. */
    val notes: String = "",
)

/**
 * Applies the degradation ladder.
 *
 * Deliberately total: for any input it returns a plan that plays something, or
 * throws naming a configuration problem the family must fix. There is no
 * "nothing tonight" outcome, because that outcome is the one that gets the app
 * deleted.
 */
fun decide(
    occurrence: Occurrence,
    child: FamilyChild,
    availability: Availability,
    lead: Lead = Lead.DEFAULT,
): Plan {
    val av = availability
    val startGen = occurrence.at.minus(lead.total)

    // The fallback chain first, so it exists whichever branch is taken below.
    val fallbacks = buildList {
        av.cachedStory?.let {
            add(Fallback(Tier.CACHED, it, "previously delivered, already on device"))
        }
    }

    fun replayOnly(why: String): Plan {
        val story = fallbacks.firstOrNull()?.storyId
            ?: throw ScheduleException(
                "child ${child.childId} has nothing to play: $why, and no cached story"
            )
        return Plan(
            child = child,
            deliverAt = occurrence.at,
            localWall = occurrence.localWall,
            wallKind = occurrence.kind,
            tier = Tier.CACHED,
            storyId = story,
            startGenerationAt = startGen,
            fallbacks = fallbacks,
            notes = why,
        )
    }

    val voice = av.voice
    // No usable voice: the enrolment-pending and the revoked cases. Both are
    // legitimate, and neither may silence the evening.
    if (voice == null || voice.revoked) {
        return replayOnly("no usable voiceprint; replay only")
    }

    // Reference sample: today's note if it passed QC, else the best enrolled
    // sample. A failed sample must never reach synthesis.
    val reference = pickReference(av, voice)
        ?: return replayOnly("voiceprint has no usable reference sample")

    val daily = av.dailySample?.takeIf { it.usableAt(av.now) }
    val tier: Tier
    val opening: Opening?
    val chosen: StoryId?
    val notes: String

    when {
        daily != null -> {
            tier = Tier.FRESH
            chosen = av.requestedStory ?: av.defaultStory
            opening = Opening(daily.id, voice.memberId, daily.uri)
            notes = "today's voice note used as opening and reference"
        }
        av.inventoryOpening != null -> {
            tier = Tier.INVENTORY
            chosen = av.defaultStory
            opening = av.inventoryOpening
            notes = "pre-recorded opening from inventory"
        }
        else -> {
            tier = Tier.VOICE_ONLY
            chosen = av.defaultStory
            opening = null
            notes = "no opening available; story only, absence not surfaced to the child"
        }
    }

    // A voice to tell it with but no story to tell: fall back rather than fail.
    // A rotation that runs dry is our bug, not the family's problem.
    if (chosen == null) {
        return replayOnly("no story available; replaying cached")
    }

    return Plan(
        child = child,
        deliverAt = occurrence.at,
        localWall = occurrence.localWall,
        wallKind = occurrence.kind,
        tier = tier,
        storyId = chosen,
        voiceId = voice.id,
        referenceSample = reference.id,
        opening = opening,
        startGenerationAt = startGen,
        fallbacks = fallbacks,
        notes = notes,
    )
}

/**
 * Chooses the conditioning sample.
 *
 * Today's note is preferred because it carries the parent's current voice, but
 * any usable enrolled sample works — cloning quality barely moves day to day,
 * which is precisely why daily recording must not be a gate (D-006).
 */
private fun pickReference(av: Availability, voice: VoicePrint): VoiceSample? {
    av.dailySample?.takeIf { it.usableAt(av.now) }?.let { return it }
    return voice.samples
        .filter { it.usableAt(av.now) }
        .minWithOrNull(
            compareBy<VoiceSample> { rank(it.kind) }.thenByDescending { it.quality.snrDb }
        )
}

private fun rank(kind: SampleKind): Int = when (kind) {
    SampleKind.ENROLMENT -> 0
    SampleKind.INVENTORY -> 1
    SampleKind.DAILY -> 2
}

/** Orders plans for the GPU queue: earliest generation start first. */
fun List<Plan>.byDeadline(): List<Plan> = sortedBy { it.startGenerationAt }
