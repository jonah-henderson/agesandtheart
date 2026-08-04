package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.worldgen.field.Palette
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
     * The surface rule this Age wears — vanilla's own tree, or nothing at all.
     *
     * Pinning a featureless biome is not enough: vanilla's tree is only *partly* biome-keyed, and its
     * grass-over-dirt-above-water default is not gated on biome at all, so an unknown biome still gets a
     * skin. On Biomes because in vanilla the surface rule genuinely is selected by biome.
     *
     * Takes the [terrain] because the tree has to know which blocks are the top of the ground and which are
     * the floor of a cave, and only the shape can say — see [Palette.vanillaOverworldOn].
     */
    fun paletteIn(options: Options, terrain: TerrainField): SurfaceRules.RuleSource =
        if (options.of(SKIN) == BARE_SKIN) Palette.NOTHING else Palette.vanillaOverworldOn(terrain)

    /**
     * The biomes this Age was told to grow, as signed preferences — positive to introduce or strengthen,
     * zero to strike out. Removal shares the weight field rather than carrying a flag of its own.
     */
    fun preferencesIn(options: Options): List<BiomePreference> {
        val asked = Population.of(options.claimsOn(GROWN))
        fun named(values: List<String>) =
            values.filter { it != Parameter.UNCHANGED }.mapNotNull(Identifier::tryParse)
        return named(asked.wanted.map { it.value }).map { BiomePreference(it, BiomePreference.WEIGHT_OF_A_MENTION) } +
            named(asked.struck).map { biome -> BiomePreference(biome, BiomePreference.STRUCK_OUT) }
    }

    /** Whether the sentence singled biomes out, so everything it did not name is struck from the table. */
    fun keepsOnlyNamed(options: Options): Boolean = Population.of(options.claimsOn(GROWN)).exclusive

    /** Whether this Age's biomes are chosen to suit its shape — see [FOOTING]. */
    fun groundsBiomes(options: Options): Boolean = options.of(FOOTING) == GROUNDED_FOOTING

        /**
         * The biomes grown here — populative, so naming one adds it and naming two adds both, with
         * `only`/`except` to narrow (§3.2). Named `grown` rather than `biomes` to avoid `biomes.biomes`.
         */
        val GROWN = Parameter.population("grown")

        /**
         * Whether the ground wears a skin at all — see [paletteIn]. `bare` means the fill is the surface:
         * whatever [co.voik.agesandtheart.worldgen.field.Substance] laid is what you stand on. No word
         * reaches it yet; it exists to be pinned by a bespoke recipe.
         */
        val SKIN = Parameter("skin", "vanilla", BARE_SKIN)

        /**
         * Whether this Age's biomes agree with its shape — see
         * [co.voik.agesandtheart.worldgen.biome.Grounding].
         *
         * **`free` is the default, and it is a lever rather than a bug.** An ocean biome on a hilltop and a
         * pool in a desert are things an Age is allowed to be, and reading one as an oasis is the recorded
         * call (`notes/terrain-architecture.md`). `grounded` buys the other kind of Age — the one that means
         * to look like somewhere — and a preset that wants it pins it.
         */
        val FOOTING = Parameter("footing", FREE_FOOTING, GROUNDED_FOOTING)

        const val FREE_FOOTING = "free"
        const val GROUNDED_FOOTING = "grounded"

        private const val BARE_SKIN = "bare"
    }
