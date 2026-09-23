/**
 * The core entities.
 *
 * Note the deliberate asymmetry: [Member] (a parent) carries credentials and a
 * voiceprint; [Child] carries neither. A child is a data object, never an
 * account, and no field here can hold child audio. See docs/DECISIONS.md D-003.
 */
package mimimoto.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// ---------- identifiers ----------
//
// Value classes so a StoryId cannot be passed where a VoiceId is expected. The
// mistake is easy to make with plain strings and expensive here: it would mean
// synthesising with the wrong family's voice.

@JvmInline value class FamilyId(val value: String) { override fun toString() = value }
@JvmInline value class MemberId(val value: String) { override fun toString() = value }
@JvmInline value class ChildId(val value: String) { override fun toString() = value }
@JvmInline value class VoiceId(val value: String) { override fun toString() = value }
@JvmInline value class StoryId(val value: String) { override fun toString() = value }
@JvmInline value class SampleId(val value: String) { override fun toString() = value }

/** Identifies a child within a family without a second lookup. */
data class FamilyChild(val familyId: FamilyId, val childId: ChildId)

// ---------- family ----------

/** The billing and permission boundary. Everything else hangs off it. */
data class Family(
    val id: FamilyId,
    val ownerId: MemberId,
    val createdAt: Instant,
    val members: List<Member> = emptyList(),
    val children: List<Child> = emptyList(),
) {
    fun child(id: ChildId): Child? = children.firstOrNull { it.id == id }
    fun member(id: MemberId): Member? = members.firstOrNull { it.id == id }
}

enum class Role {
    OWNER,
    PARENT,

    /** A grandparent or similar: may record and send, may not change settings. */
    RELATIVE,
}

/** An adult with credentials. Only members are ever recorded. */
data class Member(
    val id: MemberId,
    val familyId: FamilyId,
    val role: Role,
    /** What the child's device shows: "爸爸", "おじいちゃん". */
    val displayName: String,
    /** BCP-47; drives synthesis language selection. */
    val locale: String,
    /** Null until liveness enrolment succeeds (D-005). */
    val voiceId: VoiceId? = null,
    val createdAt: Instant = Instant.EPOCH,
) {
    val enrolled: Boolean get() = voiceId != null
}

enum class AgeBand(val label: String) {
    TODDLER("2-4"),
    PRESCHOOL("4-6"),
    EARLY_SCHOOL("6-9"),
    OLDER("9-12"),
}

/**
 * A listener, not a user. No credentials, no biometric data, no audio — by
 * design, and this must stay true (D-003).
 */
data class Child(
    val id: ChildId,
    val familyId: FamilyId,
    /** Used only to address the child inside story text. */
    val displayName: String,
    /**
     * Selects story difficulty. Deliberately coarse: we do not store a birth
     * date, because a birth date is what turns a listener into a data subject
     * under GDPR-K.
     */
    val ageBand: AgeBand,
    /**
     * The language stories are told in, which is a property of the child rather
     * than of the parent: migrant families routinely want the child kept in the
     * home language while the parent's own locale is where they work.
     */
    val language: String,
    /** IANA zone. Delivery is anchored here, never to the parent's (D-007). */
    val zone: ZoneId,
)

// ---------- voice ----------

/**
 * The reference material used to condition synthesis.
 *
 * Holds no audio bytes. The store decides where audio lives and for how long;
 * see docs/DECISIONS.md "未决" on storage.
 */
data class VoicePrint(
    val id: VoiceId,
    val memberId: MemberId,
    val familyId: FamilyId,
    /**
     * Language of the enrolment prompt. Cloning quality drops when the
     * reference language differs from the synthesis language, so we keep it and
     * prefer a same-language reference where one exists.
     */
    val language: String = "",
    val enrolledAt: Instant = Instant.EPOCH,
    /** Non-null disables all synthesis with this voice, immediately. */
    val revokedAt: Instant? = null,
    /** Newest first. */
    val samples: List<VoiceSample> = emptyList(),
) {
    val revoked: Boolean get() = revokedAt != null
}

enum class SampleKind {
    /** From the liveness challenge. Long-lived. */
    ENROLMENT,

    /** A parent's daily voice note: instruction and fresh reference (D-001). */
    DAILY,

    /** Recorded ahead of time to cover absence (D-006). */
    INVENTORY,
}

/** One reference recording belonging to a [VoicePrint]. */
data class VoiceSample(
    val id: SampleId,
    val voiceId: VoiceId,
    val kind: SampleKind,
    val language: String,
    /**
     * Required: zero-shot cloning conditions on reference text as well as
     * reference audio.
     */
    val transcript: String,
    /** Opaque pointer to the stored audio. */
    val uri: String,
    /**
     * The audioqc verdict recorded at intake. A sample that did not pass must
     * never reach synthesis — bad references are how the product fails its
     * "sounds like dad" test.
     */
    val quality: QualityStamp,
    val createdAt: Instant = Instant.EPOCH,
    val expiresAt: Instant? = null,
) {
    fun usableAt(now: Instant): Boolean =
        quality.passed && (expiresAt == null || !now.isAfter(expiresAt))
}

/** The subset of the audioqc report that is persisted. */
data class QualityStamp(
    val passed: Boolean,
    val snrDb: Double = 0.0,
    val speechSeconds: Double = 0.0,
    val cutoffHz: Double = 0.0,
    val reason: String = "",
)

// ---------- story ----------

/**
 * Raised when text arrives without provenance we accept. See [StorySource].
 */
class UnapprovedSourceException(message: String) : Exception(message)

/**
 * Provenance. These are the only ways text may enter the system; nothing
 * model-invented may be spoken in a parent's voice (D-002).
 *
 * The red line is carried by this type rather than by a check somewhere
 * downstream: an unapproved source is not a value [Story] can hold, so no code
 * path can forget to test for one. That moves the whole burden to the single
 * place a string becomes a [StorySource] — [fromWire] — which is why that
 * function refuses rather than defaulting, and why it is the thing the tests
 * point at.
 */
enum class StorySource {
    /** Grimm, Andersen, Aesop and the like. */
    PUBLIC_DOMAIN,

    /** The parent wrote or dictated it. */
    PARENT_AUTHORED,

    /** Content we hold a written licence for. */
    LICENSED;

    val wire: String get() = name.lowercase()

    companion object {
        /**
         * Parses a stored or submitted provenance value.
         *
         * Anything unrecognised is refused outright. A default here — "treat
         * unknown as public domain", or a nullable return that a caller might
         * ignore — is precisely how a model-generated story would end up spoken
         * in a parent's voice one schema migration later.
         */
        fun fromWire(value: String): StorySource =
            entries.firstOrNull { it.wire == value.lowercase().trim() }
                ?: throw UnapprovedSourceException(
                    "story source '$value' is not approved for synthesis"
                )
    }
}

/** One synthesis unit, a few sentences long. */
data class Segment(
    val index: Int,
    val text: String,
    /**
     * Lets a story mark character lines so the pipeline can pass a style hint.
     * It never changes who is speaking — always the parent.
     */
    val role: String = "",
)

data class Story(
    val id: StoryId,
    val title: String,
    val language: String,
    val ageBand: AgeBand,
    val source: StorySource,
    /**
     * Split at authoring time rather than generation time, which keeps segment
     * boundaries at sentence ends — that is what makes segment-wise generation
     * sound continuous (D-009).
     */
    val segments: List<Segment>,
) {
    /** Returns the problem with this story, or null if it is well formed. */
    fun validate(): String? {
        if (segments.isEmpty()) return "story ${id}: has no segments"
        segments.forEachIndexed { i, seg ->
            if (seg.index != i) return "story ${id}: segment $i has index ${seg.index}"
            if (seg.text.isBlank()) return "story ${id}: segment $i is empty"
        }
        return null
    }
}

// ---------- delivery ----------

/**
 * A parent's short voice note played before the story.
 *
 * Its scarcity is the incentive mechanism; the story itself is never withheld
 * (D-006).
 */
data class Opening(
    val sampleId: SampleId,
    val memberId: MemberId,
    val uri: String,
    val duration: Duration = Duration.ZERO,
)
