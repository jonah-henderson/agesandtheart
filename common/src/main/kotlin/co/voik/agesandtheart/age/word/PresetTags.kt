package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Taggable
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional

/**
 * What one preset is *like*: the tags it carries, and how readily the Art reaches for it.
 *
 * Tag membership is **unipolar and weighted**, so a thing may be neither `lush` nor `barren`, or oddly
 * both (§3.3) — which is how the world absorbs a contradiction. A bipolar axis could not: on one scalar
 * "lush, barren" averages to temperate nothing, where independent tags give cherry groves beside desert.
 *
 * Tags belong to presets and content, never to primitives — an `Ellipsoid` is not lush.
 */
data class PresetProfile(
    val tags: Map<String, Double>,
    /**
     * How willingly the Art reaches for this preset when **nothing in the sentence asked**, relative to
     * its siblings. One is ordinary; a quarter is something it would rather not do unbidden.
     *
     * Without a prior an unconstrained aspect draws uniformly, so a sentence saying nothing about the sea
     * got lava one time in three — §8.2's "vagueness draws only from the curated pool", broken. Precision
     * still reaches anything, since readiness only weights an *unasked* draw.
     */
    val readiness: Double?,
    /**
     * Tags this entry takes back off whatever was **derived** for the same member — the way to say that a
     * rule reached something it should not have without giving up the rest of what it got right.
     */
    val dropped: Set<String> = emptySet(),
    /**
     * Whether this entry stands **instead of** the derivation rather than over it, for the member the
     * rules simply cannot read.
     */
    val replaces: Boolean = false,
    /**
     * Whether this member is in the world **anyway**, so that naming it asks for *more* of it rather than
     * for it at all — the flag the mention bump turns on (`claimForMember` in `age/resolution/Populations.kt`, design §3.3).
     *
     * **True by default, which is what keeps every entry written before this correct.** A biome, a creature,
     * a vanilla feature a biome already grows: all present unless something strikes them, so naming one at
     * the ordinary share would be a page read, charged for, and worth nothing. The bump is what makes the
     * word mean something.
     *
     * **False is for a member that only exists because a word asked** — a volcano, a magma chamber. It goes
     * in at the ordinary share and is moved from there by the other words in the sentence, which is what a
     * quantifier is for (Jonah, 2026-09-10: *"if it otherwise would not be in the pool, and the word adds
     * it, it goes in at the default weight, which other words may then modify"*).
     *
     * **Why this is not `alreadyInThePool`, which is the subtlety the design turned on.** That predicate
     * means *listed in `art/preset_tags/<aspect>.json`*. For biomes it happens to coincide with being
     * present anyway; for **features** it does not, because `Features.placedIn` starts from what each biome
     * already carries — so a feature is present-anyway when a biome grows it, which the tag table knows
     * nothing about. Tagging the volcanic features `molten` so `volcanic` could query them would have put
     * them "in the pool" and handed the bump straight back.
     *
     * Per member rather than per aspect, so a vanilla feature that really does grow anyway keeps its bump
     * while ours do not. **Revisit if flagging each one becomes a chore** as the pack gains custom features
     * (Jonah).
     */
    val presentAnyway: Boolean = true,
    /**
     * Whether a vague word, or the draw for an aspect nobody spoke to, may land on this member. False keeps
     * it for words that name it outright — how a rare thing stays out of cheap writing without losing its
     * word or its tags.
     */
    val availableToBroadWords: Boolean = true,
) {
    /** This profile with [later] laid over it — a higher-priority pack retuning some of it. */
    fun mergedWith(later: PresetProfile): PresetProfile =
        PresetProfile(
            tags + later.tags,
            later.readiness ?: readiness,
            dropped + later.dropped,
            replaces || later.replaces,
            // A later pack saying nothing about this leaves the earlier answer standing, as `readiness` does.
            later.presentAnyway && presentAnyway,
            later.availableToBroadWords && availableToBroadWords,
        )

    /**
     * This profile laid over what was **derived** for the same member (`notes/the-tag-layer.md` §5).
     *
     * Three levers, and the format carries all three from the start so slotting one in is never a
     * migration: an authored weight wins outright, [dropped] takes a derived tag back off, and [replaces]
     * ignores the derivation entirely.
     */
    fun over(derived: Map<String, Double>): PresetProfile {
        if (replaces) return this
        return copy(tags = (derived - dropped) + tags)
    }

    companion object {
        /** What a preset that says nothing about its readiness gets. */
        const val ORDINARY_READINESS = 1.0

        val CODEC: Codec<PresetProfile> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("tags", emptyMap())
                    .forGetter(PresetProfile::tags),
                Codec.DOUBLE.optionalFieldOf("readiness").forGetter { Optional.ofNullable(it.readiness) },
                Codec.STRING.listOf().optionalFieldOf("drop", emptyList())
                    .forGetter { it.dropped.toList() },
                Codec.BOOL.optionalFieldOf("replace", false).forGetter(PresetProfile::replaces),
                Codec.BOOL.optionalFieldOf("present_anyway", true).forGetter(PresetProfile::presentAnyway),
                Codec.BOOL.optionalFieldOf("available_to_broad_words", true)
                    .forGetter(PresetProfile::availableToBroadWords),
            ).apply(instance) { tags, readiness, dropped, replaces, presentAnyway, availableToBroadWords ->
                PresetProfile(
                    tags, readiness.orElse(null), dropped.toSet(), replaces, presentAnyway, availableToBroadWords,
                )
            }
        }
    }
}

/** One aspect's worth of profiles, which is one `art/preset_tags/<aspect>.json`. */
data class PresetTags(private val byPreset: Map<String, PresetProfile>) {
    fun of(preset: Taggable): PresetProfile = byKey(preset.key)

    /** The same, where only the preset's name is known — which is all a file being loaded has. */
    fun byKey(key: String): PresetProfile = byPreset[key] ?: EMPTY_PROFILE

    /** Every preset key this table has something to say about — for validation, not for resolution. */
    val described: Set<String> get() = byPreset.keys

    /** Every tag anything here carries, which bounds what any word can meaningfully ask for. */
    val carried: Set<String> get() = byPreset.values.flatMap { it.tags.keys }.toSet()

    /** This table with a higher-priority pack's [later] laid on it, preset by preset. */
    fun stackedWith(later: PresetTags): PresetTags = PresetTags(
        (described + later.described).associateWith { key ->
            val mine = byPreset[key]
            val theirs = later.byPreset[key]
            when {
                mine == null -> later.byKey(key)
                theirs == null -> mine
                else -> mine.mergedWith(theirs)
            }
        },
    )

    /**
     * This table laid over what was **derived** — every member some rule spoke to, plus every member the
     * pack authored, with the authored entry winning where both have something to say.
     */
    fun over(derived: Map<String, Map<String, Double>>): PresetTags {
        if (derived.isEmpty()) return this
        val members = derived.keys + byPreset.keys
        return PresetTags(
            members.associateWith { member ->
                byKey(member).over(derived[member].orEmpty())
            },
        )
    }

    companion object {
        val EMPTY_PROFILE = PresetProfile(emptyMap(), readiness = null)

        val CODEC: Codec<PresetTags> = Codec.unboundedMap(Codec.STRING, PresetProfile.CODEC)
            .xmap(::PresetTags, PresetTags::byPreset)
    }
}
