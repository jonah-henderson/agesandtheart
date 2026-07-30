package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.RegionMap
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * An Age's climate, which may be **more than one** — a hot half and a cold half, with a seam between them.
 *
 * This is the piece the climate aspect could not be built without. Every other divided aspect divides on a map
 * the generator already holds: the terrain's, the dressing's, the carving's. Climate needs its **own** map,
 * independent of all of them, because *which* climate a column has is a different question from which dressing
 * paints it — and a writer who asked for "hot and cold" was talking about the climate, not about the ground.
 *
 * A single bias needs no map and gets [RegionMap.whole], so the common case — every Age that named at most one
 * climate word — costs one array lookup returning member zero, and no Age that existed before climate became an
 * aspect changes by a block.
 *
 * **Why the fracture happens at all** is [co.voik.agesandtheart.age.aspect.Parameter.Kind.RANGED]: two words
 * bounding one axis to stretches that do not overlap cannot both be honoured in one climate, so each takes
 * ground of its own. The seam between them is an ordinary region seam, which is what makes the jump in climate
 * read as *impossible geography* rather than as a glitch — the same reading a terrain seam already has.
 */
data class RegionalClimate(
    /** One per region of [where], in the order [where] numbers them. Never empty. */
    val biases: List<ClimateBias> = listOf(ClimateBias.NONE),
    /** Which ground each bias governs — the whole world, where there is only one. */
    val where: RegionMap = RegionMap.whole(),
) {
    /** The climate at a position, quartered as biome coordinates arrive. */
    fun at(quartX: Int, quartZ: Int): ClimateBias {
        biases.singleOrNull()?.let { return it }
        val member = where.memberAt(quartX * QUARTS_TO_BLOCKS, quartZ * QUARTS_TO_BLOCKS)
        return biases.getOrElse(member) { biases.first() }
    }

    /** Whether every bias leaves the climate exactly as vanilla made it, so a consumer may skip the lookup. */
    val isIdle: Boolean get() = biases.all { it.isIdle }

    companion object {
        /** Biome coordinates are one per four blocks, and [RegionMap] speaks in blocks. */
        private const val QUARTS_TO_BLOCKS = 4

        val NONE = RegionalClimate()

        val CODEC: Codec<RegionalClimate> = RecordCodecBuilder.create { instance ->
            instance.group(
                ClimateBias.CODEC.listOf().optionalFieldOf("biases", listOf(ClimateBias.NONE))
                    .forGetter(RegionalClimate::biases),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("where", RegionMap.whole())
                    .forGetter(RegionalClimate::where),
            ).apply(instance, ::RegionalClimate)
        }
    }
}
