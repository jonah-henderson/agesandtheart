package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.resources.Identifier

/**
 * Which biomes an Age grows — what a place *is*, as opposed to how hot it is or what shape it takes.
 *
 * **No preset, because a biome table is not a thing you pick one of.** An Age begins with vanilla's whole
 * table and a sentence adjusts it: naming a biome strengthens it and removes nothing (§3.2), an evocative
 * word weighs a whole region of tag space up or down, and `only`/`except` are the only things that trim.
 * Exclusion deletes table entries, after which vanilla's nearest-neighbour search closes the gap rather
 * than leaving a hole.
 *
 * What is left here is how the table is *worn* — the skin over it and whether it agrees with the shape.
 */
object Biomes {

    /**
     * The biomes this Age was told to grow, as weights against what it would have grown anyway — above
     * [BiomePreference.ORDINARY] for more of one, below for less, zero to strike it out. Removal shares
     * the weight field rather than carrying a flag of its own.
     *
     * The claim already holds the weight: naming a biome is worth [GROWN]'s mention and a quantifier
     * multiplies it, both of which the resolver applied on the way in. Nothing is re-weighed here, or a
     * word's emphasis would be applied twice and differ from what the recipe says.
     */
    fun preferencesIn(options: Options): List<BiomePreference> {
        val asked = Skew.of(options.claimsOn(GROWN))
        fun named(claims: List<Claim>) = claims.filter { it.value != Parameter.UNCHANGED }
            .mapNotNull { claim -> Identifier.tryParse(claim.value)?.let { it to claim.density } }
        return named(asked.wanted).map { (biome, weight) -> BiomePreference(biome, weight) } +
            asked.struck.filter { it != Parameter.UNCHANGED }.mapNotNull(Identifier::tryParse)
                .map { biome -> BiomePreference(biome, BiomePreference.STRUCK_OUT) }
    }

    /** Whether the sentence singled biomes out, so everything it did not name is struck from the table. */
    fun keepsOnlyNamed(options: Options): Boolean = Skew.of(options.claimsOn(GROWN)).exclusive

    /** Whether this Age's biomes are chosen to suit its shape — see [FOOTING]. */
    fun groundsBiomes(options: Options): Boolean = options.of(FOOTING) != FREE_FOOTING

        /**
         * The biomes grown here — populative, so naming one adds it and naming two adds both, with
         * `only`/`except` to narrow (§3.2). Named `grown` rather than `biomes` to avoid `biomes.biomes`.
         */
        val GROWN = Parameter.population(
            "grown",
            worthOfAMention = BiomePreference.WEIGHT_OF_A_MENTION,
            // No word may empty it: every column has to have *some* biome, so being rare is as far down
            // as an Age can push one. `except` still strikes one out, that being an outright instruction.
            leastKept = BiomePreference.LEAST_KEPT,
        )

        /**
         * Whether this Age's biomes agree with its shape — see
         * [co.voik.agesandtheart.worldgen.biome.Grounding].
         *
         * **`free` is the default, and it is a lever rather than a bug.** An ocean biome on a hilltop and a
         * pool in a desert are things an Age is allowed to be, and reading one as an oasis is the recorded
         * call (`notes/terrain-architecture.md`). `grounded` buys the other kind of Age — the one that means
         * to look like somewhere — and a preset that wants it pins it.
         */
        /**
         * Whether the biomes agree with the shape — **grounded unless a writer says otherwise** (Jonah,
         * 2026-08-05), the first option being the one an unsaid parameter takes.
         *
         * It was the other way round, on the argument that an ocean biome on a hilltop is a thing this
         * mod's worlds are allowed to do. Measured, that reading cost more than it bought: a `craterlands`
         * Age came out **63% ocean biomes** across nine thousand columns of dry land, because an ungrounded
         * Age reads vanilla's continentalness, which describes vanilla's continental shelf and has no
         * relationship to where our field put the rock. Sixty-three per cent is not a licence for the
         * occasional strange Age, it is the normal case — so the lever stays and points the other way.
         */
        val FOOTING = Parameter("footing", GROUNDED_FOOTING, FREE_FOOTING)

        const val FREE_FOOTING = "free"
        const val GROUNDED_FOOTING = "grounded"

    }
