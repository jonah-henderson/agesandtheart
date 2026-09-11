package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome

/**
 * A band of an Age that is **somewhere** — one biome for everything between [floorY] and [roofY], whatever
 * the climate would otherwise have said there.
 *
 * **Not a climate question, which is why it is answered before the table is consulted.** Vanilla's
 * parameter space has axes for how hot, how wet and how far from an ocean a place is, and none at all for
 * having a ceiling over you or eighty blocks of sea. Left to the table, a hall a hundred blocks under a
 * forest comes out as lush caves or dripstone — biomes that describe how water got in and what grew there,
 * which is a description of somewhere nobody built.
 *
 * **It earns far more than the look.** In 26.1 a biome carries its own fog, water colour, water fog, ambient
 * sound, mood, music, mob spawns *and its feature list* — so "the abyss is a place" and "the abyss is black,
 * silent, and nothing grows in it" are one statement rather than four mechanisms.
 *
 * Named for the shape rather than for its first user: this was `Roofed`, which the great halls wanted, and
 * the abyss is the second thing to want exactly the same mechanism (Jonah, 2026-09-10).
 */
data class BiomeBand(
    val biome: Holder<Biome>,
    val floorY: Int,
    val roofY: Int,
    /**
     * Rock this band must stay *above*, or null for one that fills its whole height.
     *
     * **What tells a deep sea from a lush cave at the same Y — CORRECTED 2026-09-10 (Jonah, walked).** The
     * abyss was a bare height band, so every cave under the sea floor came out as abyss as well and the
     * ordinary cave biomes were clobbered wholesale. Vanilla never uses height for this: cave biomes are
     * picked by the *depth* climate parameter and oceans by continentalness, so a lush cave and a trench at
     * the same Y are told apart by what is over them rather than by where they are.
     *
     * Ours can be exact instead of climatic, because our worlds know their own rock. **Above the sea bed is
     * sea, below it is inside the ground** — one reading of the terrain field per lookup.
     *
     * The great halls want none of this, being indoors by definition, so theirs stays null.
     */
    val above: TerrainField? = null,
) {

    /** The biome at this position, or null where the band does not reach — so the climate table answers. */
    fun biomeAt(blockX: Int, blockY: Int, blockZ: Int): Holder<Biome>? {
        if (blockY !in floorY..roofY) return null
        val bed = above?.columnSpans(blockX, blockZ)?.highestSolidY
        if (bed != null && blockY <= bed) return null
        return biome
    }

    companion object {
        /**
         * **[above] is deliberately not serialised.** A field tree is rebuilt from the recipe on every open
         * and the band is built beside it, so writing one down would be a second description of the same
         * rock — and the two could disagree.
         */
        val CODEC: Codec<BiomeBand> = RecordCodecBuilder.create { instance ->
            instance.group(
                Biome.CODEC.fieldOf("biome").forGetter(BiomeBand::biome),
                Codec.INT.fieldOf("floor_y").forGetter(BiomeBand::floorY),
                Codec.INT.fieldOf("roof_y").forGetter(BiomeBand::roofY),
            ).apply(instance) { biome, floor, roof -> BiomeBand(biome, floor, roof) }
        }
    }
}
