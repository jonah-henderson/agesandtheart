package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.worldgen.field.Palette
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.resources.ResourceLocation

/**
 * Which biomes an Age grows — what a place *is*, as opposed to how hot it is or what shape it takes.
 *
 * **What is left of the dressing, and the only one of its four jobs that was ever really its own.** Structures
 * left in step 2, climate in step 3, the material in step 4; this is step 5, and the dressing is deleted with it.
 * The barren presets — `bare_rock`, `verdant`, `plasma` — went with it rather than becoming custom biomes as the
 * plan first had it (Jonah, 2026-07-29: *"the barren ages are now an artifact of earlier design and should be
 * deleted now so as not to mislead further"*). They were placeholders from before biomes were handled properly,
 * and a bare-rock world may be reinstated during the biome pass as a real datapack biome.
 *
 * **One preset, and the writing happens in [GROWN]** — the same shape as [Climate], and for the same reason: a
 * biome table is not a thing you pick one of, it is a population you add to. §3.1's own table says biomes never
 * divide into regions, because *"the table holds many natively"* — one climate table spans the world however
 * many terrains carve it up, which is exactly why `RegionBiomeSource` had no reason to survive this step.
 *
 * Naming a biome **adds or strengthens and removes nothing** (Jonah's call, §3.2): a mention is inclusive, and
 * `only`/`except` are how a writer narrows. Both work — see [Claim] — and exclusion reaches the table by deleting
 * entries, after which vanilla's nearest-neighbour search closes the gap, so a world without swamps gets more
 * marsh-adjacent forest rather than a hole.
 */
enum class Biomes(override val key: String) : AspectPreset {
    /**
     * Vanilla's own climate-to-biome table — every biome the game has, looked up at the coordinates [Climate]
     * decided.
     *
     * The only preset, and it always seats. A datapack could ship another `multi_noise_biome_source_parameter_list`
     * and this is where naming it would go, which is the seam `AgeBiomeSource`'s KDoc has advertised since 3c.
     */
    VANILLA("vanilla"),
    ;

    override val aspect = Aspect.BIOMES

    override val parameters: List<Parameter> get() = listOf(GROWN, SKIN)

    override fun getSerializedName(): String = key

    /**
     * The surface rule this Age wears — vanilla's own tree, or nothing at all.
     *
     * **This exists because pinning a featureless biome is not enough, which cost a debugging round.** An Age
     * pinned to `agesandtheart:plasma` gets no *decoration*, because features come from the biome and that one has
     * none. But it still got grass and dirt, because the **surface rule is a separate mechanism** and
     * `AgeGeneration` handed every composed Age `Palette.VANILLA_OVERWORLD` unconditionally. Vanilla's tree is only
     * *partly* biome-keyed: its grass-over-dirt-above-water default is not gated on biome at all, so an unknown
     * biome still gets a skin. Measured the wrong way round first — two `blackstone` columns at an origin looked
     * like proof that nothing was painting, and were not.
     *
     * Lives on **Biomes** because in vanilla the surface rule genuinely is selected by biome; the aspect that owns
     * what a place *is* is the one that should own what its ground looks like. It is a *parameter* rather than a
     * second preset deliberately: a preset would take this aspect from one candidate to two, and
     * `vocabularycheck` would then rightly demand a word that can ask for `biomes=vanilla` — a real question, but
     * not this change's.
     */
    fun paletteIn(options: Options): SurfaceRules.RuleSource =
        if (options.of(SKIN) == BARE_SKIN) Palette.NOTHING else Palette.VANILLA_OVERWORLD

    /**
     * The biomes this Age was told to grow, as signed preferences — positive to introduce or strengthen, zero to
     * strike out.
     *
     * Removal shares the weight field rather than carrying a flag of its own, which is what let `except` arrive
     * as a wire rather than as a mechanism.
     */
    fun preferencesIn(options: Options): List<BiomePreference> {
        val asked = Population.of(options.claimsOn(GROWN))
        fun named(values: List<String>) =
            values.filter { it != Parameter.UNCHANGED }.mapNotNull(ResourceLocation::tryParse)
        return named(asked.wanted.map { it.value }).map { BiomePreference(it, BiomePreference.WEIGHT_OF_A_MENTION) } +
            named(asked.struck).map { biome -> BiomePreference(biome, BiomePreference.STRUCK_OUT) }
    }

    /** Whether the sentence singled biomes out, so everything it did not name is struck from the table. */
    fun keepsOnlyNamed(options: Options): Boolean = Population.of(options.claimsOn(GROWN)).exclusive

    companion object {
        /**
         * The biomes grown here — **populative**, so naming one adds it and naming two adds both, with
         * `only`/`except` to narrow (design §3.2).
         *
         * Named `grown` rather than `biomes` for the reason `structures.built` is not `structures.structures`:
         * the referent-plural convention that served this well as a parameter *on the dressing* spells it
         * `biomes.biomes` once biomes are an aspect of their own, and the redundancy costs a reader more than
         * the small inconsistency does.
         */
        val GROWN = Parameter.population("grown")

        /**
         * Whether the ground wears a skin at all — see [paletteIn].
         *
         * `vanilla` first, so it is the default and no existing Age changes. `bare` means the *fill* is the
         * surface: whatever [co.voik.agesandtheart.worldgen.field.Substance] laid is what you stand on, with no
         * grass, no dirt and no sand over it.
         *
         * **No word reaches this yet, and that is deliberate for now.** It exists to be *pinned* by a bespoke
         * recipe — the Spire is the first — and giving it vocabulary is a decision for the biome pass, where
         * "an Age of bare rock" gets designed properly rather than reintroduced by the back door.
         */
        val SKIN = Parameter("skin", "vanilla", BARE_SKIN)

        private const val BARE_SKIN = "bare"
    }
}
