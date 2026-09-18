/**
 * Persistence for families, voiceprints and stories.
 *
 * The interface is the point; the implementation here is not. Memory is
 * adequate for the pilot this project is actually at — a handful of families,
 * observed by hand — and building a schema now would mean building it around
 * guesses. What the interface does encode is the one requirement that will not
 * change: deleting a voice removes its samples with it, in one call, so a
 * deletion request has a single place it can be honoured and a single place it
 * can be audited.
 */
package mimimoto.store

import mimimoto.domain.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class NotFoundException(what: String) : Exception("not found: $what")

interface Store {
    fun putFamily(family: Family)
    fun family(id: FamilyId): Family?

    fun putVoice(voice: VoicePrint)
    fun voice(id: VoiceId): VoicePrint?
    fun voiceForMember(id: MemberId): VoicePrint?
    fun appendSample(id: VoiceId, sample: VoiceSample)

    /** Stops all synthesis with this voice, immediately. */
    fun revokeVoice(id: VoiceId, at: Instant)

    /**
     * Removes the voiceprint and every sample belonging to it. This is what a
     * deletion request resolves to; partial deletion is not on offer.
     */
    fun deleteVoice(id: VoiceId)

    fun putStory(story: Story)
    fun story(id: StoryId): Story?

    /** The rotation for a language and age band. */
    fun storiesFor(language: String, band: AgeBand): List<Story>

    /**
     * Deletes samples past their retention date, returning how many went.
     * Daily notes are short-lived by design (D-001), and expiry must happen
     * whether or not anyone remembers to ask.
     */
    fun expireSamples(now: Instant): Int
}

/** An in-process [Store]. */
class MemoryStore : Store {
    private val families = ConcurrentHashMap<FamilyId, Family>()
    private val voices = ConcurrentHashMap<VoiceId, VoicePrint>()
    private val stories = ConcurrentHashMap<StoryId, Story>()

    override fun putFamily(family: Family) { families[family.id] = family }
    override fun family(id: FamilyId) = families[id]

    override fun putVoice(voice: VoicePrint) { voices[voice.id] = voice }
    override fun voice(id: VoiceId) = voices[id]
    override fun voiceForMember(id: MemberId) = voices.values.firstOrNull { it.memberId == id }

    override fun appendSample(id: VoiceId, sample: VoiceSample) {
        voices.compute(id) { _, existing ->
            val v = existing ?: throw NotFoundException("voice $id")
            // Newest first, matching what callers expect when they scan for a
            // same-language reference.
            v.copy(samples = listOf(sample) + v.samples)
        }
    }

    override fun revokeVoice(id: VoiceId, at: Instant) {
        voices.compute(id) { _, existing ->
            (existing ?: throw NotFoundException("voice $id")).copy(revokedAt = at)
        }
    }

    override fun deleteVoice(id: VoiceId) {
        // Samples live inside the voiceprint precisely so this cannot leave
        // orphans behind.
        voices.remove(id) ?: throw NotFoundException("voice $id")
    }

    override fun putStory(story: Story) {
        story.validate()?.let { throw IllegalArgumentException(it) }
        stories[story.id] = story
    }

    override fun story(id: StoryId) = stories[id]

    override fun storiesFor(language: String, band: AgeBand): List<Story> =
        stories.values
            .filter { it.language == language && it.ageBand == band }
            .sortedBy { it.id.value }

    override fun expireSamples(now: Instant): Int {
        var removed = 0
        for (id in voices.keys) {
            voices.compute(id) { _, existing ->
                if (existing == null) return@compute null
                val kept = existing.samples.filter { it.expiresAt == null || !now.isAfter(it.expiresAt) }
                removed += existing.samples.size - kept.size
                if (kept.size == existing.samples.size) existing else existing.copy(samples = kept)
            }
        }
        return removed
    }
}
