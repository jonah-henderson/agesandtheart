package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.Raised
import co.voik.agesandtheart.worldgen.field.Substance
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.block.state.BlockState

/**
 * The shape of an Age's rock — the aspect that carries the most meaning, and the one whose family of
 * siblings is the main content workload of the whole design (§3.1).
 *
 * Each entry bundles a pile of tuned constants behind one name, which is exactly what the Tier-B preset
 * objects in `worldgen/` already did. This promotes that existing layer rather than inventing one: a
 * terrain *is* `SpireField.world()` plus the one fact a composer needs to place anything else against
 * it — its [waterline].
 *
 * A shape knows where its own sea belongs, and the [Sea] chooses only the *substance* — which is what
 * lets "Spire islands over water rather than plasma" be an ordinary sentence instead of a special case.
 *
 * Every shape that wants a sea now puts it at vanilla's **63**. That is a convention rather than a rule:
 * each terrain was retuned so its own relief sits correctly around that height, precisely so that a
 * future shape wanting its sea somewhere odd can simply say so. Standardising matters because two
 * terrains can now share one Age, and a shared waterline is what keeps a seam between them from
 * drowning half the world.
 */
enum class Terrain(
    override val key: String,
    val waterline: Int?,
    private val build: (String) -> TerrainField,
) : AspectPreset {
    /** Floating islands over open air: lobed masses, talons and roots, weathered to ribs. */
    SPIRE_ISLANDS("spire_islands", waterline = 63, build = { SpireField.world() }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { NoiseField.hills() }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { CavernField.world() }),

    /** Billowy noise weathered into mesa-like relief, hanging clear above the water. */
    ERODED("eroded", waterline = 63, build = { ErodedField.world() }),

    /** Colossal rectangular monoliths on a jittered grid, standing a hundred blocks out of the sea. */
    PILLARS("pillars", waterline = 63, build = { PillarField.world() }),

    /**
     * Instanced pyramids on a plain. The one terrain with a real parameter: the same shapes arranged
     * three ways, which used to be three separate presets and reads far better as one preset asked a
     * question.
     */
    PYRAMIDS("pyramids", waterline = null, build = { arrangement -> PyramidField.world(arrangement) }),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { ShapesField.world() }),
    ;

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter>
        get() = listOfNotNull(
            ARRANGEMENT.takeIf { this == PYRAMIDS },
            ALTITUDE.takeIf { this == SPIRE_ISLANDS },
            STONE,
            MINGLING,
            SEAM,
        )

    override fun getSerializedName(): String = key

    /**
     * The rock this terrain lays down, steered by whichever [options] it understands.
     *
     * Takes the Age's [window] because altitude is only offerable where there is room for it — see [lift].
     */
    fun field(options: Options, window: VerticalWindow): TerrainField {
        val shape = build(options.of(ARRANGEMENT))
        val lift = lift(options, window)
        return if (lift == 0) shape else Raised(shape, lift)
    }

    /**
     * How far up the world this terrain sits — see [ALTITUDE].
     *
     * **Conditioned on the [window], which is what makes the pairing safe rather than a rule to remember.**
     * A lifted archipelago's spires reach about 374, so asking for altitude in a band that stops at 319 would
     * have flat-topped every one of them. Rather than police that with a check, a terrain in a band with no room
     * simply sits where it always did: the worst outcome of a mismatch is the old altitude, never a clipped world.
     */
    fun lift(options: Options, window: VerticalWindow): Int {
        val wantsHeight = options.of(ALTITUDE) == "high"
        val hasRoom = window == VerticalWindow.LIFTED
        return if (wantsHeight && hasRoom) HIGH_ALTITUDE_LIFT else 0
    }

    /** How widely this terrain's materials speckle — see [MINGLING]. */
    fun mingling(options: Options): Double =
        if (options.of(MINGLING) == "fine") Substance.FINE_MINGLING else Substance.PATCHY_MINGLING

    /**
     * Which form the faults along this Age's seams take — see [SEAM].
     *
     * [drawn] is what the Age's own character drew, and is the answer unless a recipe pinned one over the
     * top of it. A pin that names nothing this version understands falls back to the draw rather than to
     * nothing, for the reason every unrecognised option is kept rather than rejected: a recipe is the only
     * record of an Age, and a token some later version dropped must not silently flatten its geology.
     */
    fun seamIn(options: Options, drawn: Seam): Seam {
        val pinned = options.of(SEAM)
        if (pinned == SEAM_AS_DRAWN) return drawn
        return Seam.named(pinned) ?: drawn
    }

    /**
     * What this terrain is *made of* — the blocks the fill lays over its own territory, or empty where the
     * sentence never said and the rock is plain stone.
     *
     * **This used to be a surface rule and is now the fill's business** (Phase 4.5 step 4). A material had to
     * paint itself into a rule tree, which for `dressing=overworld` meant substituting into vanilla's own —
     * sixty biome branches each naming their stone, with no seam to reach. That is what `Dressing.ignoresMaterial`
     * existed to report, and why "vanilla's biomes on black rock" was unsayable. As the default block it sits
     * *under* the whole tree instead, and vanilla paints grass on top of whatever we laid. See [Substance].
     *
     * Several mingle rather than divide (design §3.2), and a material still says what the substance is and never
     * whether anything lives on it — a copper spire keeps its grass.
     */
    fun substance(options: Options): List<BlockState> = Palette.materialsNamed(options.allOf(STONE))

    companion object {
        val ARRANGEMENT = Parameter("arrangement", "grid", "rings", "varied")

        /**
         * The one material parameter, and **the whole of what a writer means by "the land is andesite"**.
         *
         * The dressing carried a second `stone` of its own until step 4, spelled identically so that one word
         * would reach both — a writer does not distinguish the rock a shape is made of from the rock a dressing
         * paints, and should not have to (§4.3.1). Now that a material is the *fill* rather than a surface rule
         * there is only one thing to reach, so the duplicate is gone and the spelling was already right.
         */
        val STONE = Parameter.material("stone")

        /**
         * How finely several materials speckle together — the *"mingling quantifiers"* the Phase 4 backlog has
         * been carrying, arriving as one rung pair rather than the full set.
         *
         * `patches` is first and so the default, which is what every Age had before this existed. `fine` brings a
         * patch down to a block or two, for a mixture meant to read as a single mottled rock rather than as
         * blotches of two.
         *
         * **No word reaches it yet**, deliberately: it exists to be *pinned* by a recipe — the Spire is the first
         * — and the vocabulary for it (`finely mingled` against `in patches`) is a Phase 4 decision, not this
         * change's. Same shape as `Biomes.SKIN`.
         */
        val MINGLING = Parameter("mingling", "patches", "fine")

        /**
         * How high up the world an archipelago floats — offered by [SPIRE_ISLANDS] alone, since it is the one
         * terrain that hangs in open air rather than rising out of a sea.
         *
         * Jonah, 2026-07-29: *"the islands are pretty big. I think I like them, but let's shift them up even
         * further, roughly so the highest points of the ellipsoids are just below the upper cloud layer. Then the
         * central spires can poke way up over the clouds."* Measured, the island tops sat at a median of y=157
         * against an upper cloud deck at 265 — so the deck the renderer's own doc says exists to *"hide the
         * peaks"* was covering one column in a hundred.
         *
         * `low` is first and so the default, which is where every Age had its islands before this existed.
         * **No word reaches it**, deliberately: like [MINGLING] it exists to be *pinned* by a recipe, and the
         * vocabulary for altitude is a Phase 4 decision rather than this change's.
         */
        val ALTITUDE = Parameter("altitude", "low", "high")

        /**
         * Which form the faults along this Age's seams take, **overriding what its character drew**
         * (design §3.4, "Faults: the region seam made visible").
         *
         * The form is normally a property of the Age rather than of the sentence — [Seam] is drawn per Age and
         * frozen: a chasm or a cliff four times in five, a plain cut about one time in seven, a dissolve
         * rarely. This parameter exists so a form can be *asked for* anyway, which is what makes all four
         * walkable: an Age's seam is otherwise invisible, so seeing a fuzzed one meant writing Ages until
         * chance produced it.
         *
         * [SEAM_AS_DRAWN] is first and so the default, so an Age that says nothing keeps its own character
         * and this parameter changes nothing about how Ages normally come out. `sheared` is how to ask for
         * *no* fault, a boundary where two shapes simply meet.
         *
         * **Age-wide despite living on a per-territory aspect**, and read from the first territory the way
         * `Sea.DEPTH` and [MINGLING] are: a seam is a property of the *boundary* rather than of either side
         * of it, so "riven here and whole there" is not a thing it could mean. That is also why it is not
         * the per-territory `throw` this replaced — which side of a scarp rises is not a decision worth
         * exposing, and word order deciding it would have broken the resolver's own promise that it does not.
         *
         * **No word reaches it, and here that is more than the usual deferral.** Design §5 has a fault's
         * **magnitude** reading off the instability index, so that an Age at odds with itself is the one
         * whose ground comes apart; Phase 6 step 0 builds that wire. The *form* stays the character's.
         */
        val SEAM = Parameter("seam", SEAM_AS_DRAWN, "sheared", "scarp", "rift", "fuzzed")

        /** What [SEAM] reads as when nobody overrode the draw: whatever the Age's character carries. */
        const val SEAM_AS_DRAWN = "drawn"

        /**
         * How far a scarp throws each side of a seam, in blocks — so a cliff of twice it where two
         * territories are thrown opposite ways.
         *
         * Two chunk sections either way, so a **64-block cliff** where two territories are thrown opposite
         * ways: unmistakable without being a wall, given the terrains it divides stand between about y=63
         * and y=185.
         *
         * **Not reduced when the form became drawn rather than pinned, and that is worth saying**, because
         * the instinct is to soften anything that starts happening by itself. A scarp needs *terrain* to be
         * divided, and terrain is the reluctant aspect (`Aspect.appetiteForCompany` 0.12 — `resolvercheck`
         * measures two terrains at 4 seeds in 60), so a scarp lands on a few percent of Ages. It is rare, and
         * rare things are supposed to be worth the walk.
         *
         * **A guess, and the first number a walk should correct** — the same standing that
         * [HIGH_ALTITUDE_LIFT] had before it was measured, and that one turned out to be wrong by about a
         * hundred blocks. `./gradlew :common:preview --args=fault` draws it without booting a server. It is
         * also the magnitude instability is meant to take over, so a fixed number here is a placeholder by
         * design rather than a decision.
         */
        const val SCARP_THROW = 32

        /**
         * The floor a rift cuts down to.
         *
         * Below the 63 every composed terrain puts its sea at, and by enough to matter: a rift cut to here
         * stands about twenty blocks deep in water, which is design §7.8's star fissure arriving for
         * nothing — a flooded chasm sited exactly where the Age is most at odds with itself.
         *
         * **Not the world's floor, deliberately, though that is more dramatic.** A chasm to bedrock along
         * every seam would sever the territories outright: 7% of the world as bottomless water, and no way
         * to walk from one terrain to the next. At this depth a rift is swimmable, so it divides an Age
         * without partitioning it. Cutting deeper is exactly what instability should buy.
         */
        const val RIFT_FLOOR = 40

        /**
         * How far `altitude=high` lifts an archipelago.
         *
         * Set from the measured spread rather than by eye: the island tops' ninetieth percentile was 175, so 72
         * puts it at 247 — a comfortable eighteen blocks under the upper cloud deck at 265, with the central
         * spires carrying on up to about 374. That last number is why it is not larger: [VerticalWindow.LIFTED]
         * tops out at 383, and clipping the spires would cost exactly the feature this is for.
         */
        const val HIGH_ALTITUDE_LIFT = 72
    }
}
