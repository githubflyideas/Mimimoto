package mimimoto.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * What a requested local time turned out to be on a given date. Twice a year in
 * most of our markets it is not simply "a time".
 */
enum class WallKind {
    /** Occurs exactly once. */
    NORMAL,

    /** The clock jumped over it (spring forward). */
    SKIPPED,

    /** It occurs twice (autumn back). */
    AMBIGUOUS,
}

data class Resolved(val instant: Instant, val kind: WallKind)

/**
 * Maps a local wall-clock date and time in [zone] to a unique instant, and says
 * which of the three cases applied.
 *
 * The two DST cases get an explicit policy, because "an hour late" means the
 * child is already asleep:
 *
 *  - skipped: deliver at the instant the clock passes the requested time, which
 *    is the transition itself rather than an hour later.
 *  - ambiguous: the earlier of the two occurrences.
 *
 * `ZoneRules.getValidOffsets` returns zero, one or two offsets, which is
 * exactly this distinction, so the policy is a `when` over the list size rather
 * than something to derive. Recording [WallKind] alongside the instant is what
 * makes the DST days assertable in tests instead of discovered from support
 * tickets.
 */
fun resolveWallClock(zone: ZoneId, date: LocalDate, time: LocalTime): Resolved {
    val wall = LocalDateTime.of(date, time)
    val rules = zone.rules
    val offsets = rules.getValidOffsets(wall)
    return when (offsets.size) {
        1 -> Resolved(wall.toInstant(offsets[0]), WallKind.NORMAL)
        0 -> Resolved(rules.getTransition(wall).instant, WallKind.SKIPPED)
        // Two offsets: the smaller instant is the first occurrence.
        else -> Resolved(offsets.minOf { wall.toInstant(it) }, WallKind.AMBIGUOUS)
    }
}
