package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.MATERIAL_PARAMETERS
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.math.mix64
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.random.Random

/**
 * **What a block may be as an Age's rock or ground**, so a vague word can ask for one by what it is like —
 * `"stone": "#frozen"` — rather than by name (`notes/the-tag-layer.md` §2: the blocks a material may be
 * are a catalogue, and tags are what let a vague word choose among one).
 *
 * The sea keeps its own table. A sea is the handful of substances a writer might mean by one, where this is
 * every block a world can be built of.
 *
 * [withheld] is the fence: blocks a tag may never draw, however well they answer it. A world of blue ice
 * or of diamond is reached by learning its word, never by a broad one (Jonah, 2026-09-29).
 */
data class MaterialTable(
    private val tags: PresetTags,
    private val withheld: Set<String>,
) {
    /** Every tag some block here carries — what a material query may meaningfully ask for. */
    val carried: Set<String> get() = tags.described.flatMap { tags.byKey(it).tags.keys }.toSet()

    /** Whether a query may land on [block] at all. */
    fun isAvailableToBroadWords(block: String): Boolean =
        block !in withheld && tags.byKey(block).availableToBroadWords

    /**
     * Every block a query for [tag] may draw, with the weight it is drawn at: how strongly it carries the
     * tag, times how readily the Art reaches for it. Sorted, so a draw made by position cannot move when a
     * file is reordered.
     */
    fun carriersOf(tag: String): List<Pair<String, Double>> =
        tags.described.sorted()
            .filter(::isAvailableToBroadWords)
            .mapNotNull { block ->
                val profile = tags.byKey(block)
                val weight = (profile.tags[tag] ?: 0.0) * (profile.readiness ?: PresetProfile.ORDINARY_READINESS)
                if (weight > 0.0) block to weight else null
            }

    /**
     * The block [query] draws for this Age, or null where nothing may answer it. Seeded by the Age, the
     * word and the parameter, so the same book always builds the same rock and two material parameters
     * asking the same tag need not land on the same block.
     */
    fun drawFor(query: String, seed: Long): String? {
        val carriers = carriersOf(query.removePrefix(Materials.QUERY_MARK))
        val total = carriers.sumOf { it.second }
        if (total <= 0.0) return null
        var remaining = Random(mix64(seed)).nextDouble() * total
        for ((block, weight) in carriers) {
            remaining -= weight
            if (remaining < 0.0) return block
        }
        return carriers.last().first
    }

    companion object {
        /** Whether [parameter] with [value], spelled as a word spells them, is a material asked for by tag. */
        fun asksByTag(parameter: String, value: String): Boolean =
            parameterIn(parameter) in MATERIAL_PARAMETERS && Materials.isQuery(value)
    }
}

/**
 * `art/derivation/materials.json`: the ordinary [rules], and the block tags whose members are
 * [withheldFromBroadWords] — `#minecraft:beacon_base_blocks`, the ores. A fence rather than a tag, since
 * whether a block is a prize is a fact about the game's economy and not about the rock
 * (`notes/the-tag-layer.md` §1).
 */
data class MaterialDerivation(
    val rules: Derivation,
    val withheldFromBroadWords: List<String>,
) {
    fun over(later: MaterialDerivation): MaterialDerivation = MaterialDerivation(
        Derivation(rules.byTag + later.rules.byTag, rules.byKind + later.rules.byKind),
        (withheldFromBroadWords + later.withheldFromBroadWords).distinct(),
    )

    companion object {
        val EMPTY = MaterialDerivation(Derivation(), emptyList())

        val CODEC: Codec<MaterialDerivation> = RecordCodecBuilder.create { instance ->
            val weights = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
            instance.group(
                weights.optionalFieldOf("by_tag", emptyMap()).forGetter { it.rules.byTag },
                weights.optionalFieldOf("by_kind", emptyMap()).forGetter { it.rules.byKind },
                Codec.STRING.listOf().optionalFieldOf("withheld_from_broad_words", emptyList())
                    .forGetter(MaterialDerivation::withheldFromBroadWords),
            ).apply(instance) { byTag, byKind, withheld -> MaterialDerivation(Derivation(byTag, byKind), withheld) }
        }
    }
}
