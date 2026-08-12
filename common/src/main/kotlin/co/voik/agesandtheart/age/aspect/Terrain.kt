package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.AlpsField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.CraterlandsField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.GreatHalls
import co.voik.agesandtheart.worldgen.InverseCavesField
import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.OverworldField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.ShatteredField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.biome.Elevation
import co.voik.agesandtheart.worldgen.biome.Grounding
import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * The shape of an Age's rock: a Tier-B field preset plus its [waterline], the one fact a composer needs
 * to place anything else against it. The [Sea] chooses only the substance.
 *
 * Every shape standing *on* the ground puts its sea at vanilla's 63 — a convention, not a rule, but a
 * shared waterline is what keeps a seam between two terrains from drowning half the world. The exceptions
 * are the shapes the ground itself is somewhere else for: a gorge floor ([CANYON]) and an archipelago
 * hanging in open air ([SPIRE_ISLANDS]) both put the water where their own world bottoms out.
 */
enum class Terrain(
    override val key: String,
    val waterline: Int?,
    private val build: (Options, Long) -> TerrainField,
) : AspectPreset {
    /**
     * Floating islands over open air: lobed masses, talons and roots, weathered to ribs. Its waterline is
     * the floor of the world they hang over rather than a sea anything stands on, which is why it is far
     * below the 63 the grounded shapes share.
     */
    SPIRE_ISLANDS("spire_islands", waterline = SpireField.SEA_LEVEL, build = { _, salt -> SpireField.world(salt) }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { _, salt -> NoiseField.hills(salt) }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { _, salt -> CavernField.world(salt) }),

    /** Billowy noise weathered into mesa-like relief, hanging clear above the water. */
    ERODED("eroded", waterline = 63, build = { _, salt -> ErodedField.world(salt) }),

    /** Colossal rectangular monoliths on a jittered grid, standing a hundred blocks out of the sea. */
    PILLARS("pillars", waterline = 63, build = { _, salt -> PillarField.world(salt) }),

    /** Instanced pyramids on a plain, in the [ARRANGEMENT] asked for. */
    PYRAMIDS(
        "pyramids",
        waterline = null,
        build = { options, salt -> PyramidField.world(options.of(ARRANGEMENT), salt) },
    ),

    /**
     * Rock to the height limit with one canyon cut through it, on the [BEARING] asked for — the one
     * terrain where the world is what was taken away. Its waterline is the river at the bottom of the
     * gorge, not a sea: there is no open ground for a sea to stand on.
     */
    CANYON(
        "canyon",
        waterline = CanyonField.RIVER_LEVEL,
        build = { options, salt -> CanyonField.world(options.steer(BEARING, salt), salt) },
    ),

    /**
     * A world cut in two on the [BEARING] asked for: ocean one way, plateau the other, one cliff between.
     */
    CLIFFS(
        "cliffs",
        waterline = CliffField.SEA_LEVEL,
        build = { options, salt -> CliffField.world(options.steer(BEARING, salt), salt) },
    ),

    /** Mesa country: a tableland under open sky, cut to pieces by canyons running three ways at once. */
    CANYONLANDS(
        "canyonlands",
        waterline = CanyonlandsField.RIVER_LEVEL,
        build = { _, salt -> CanyonlandsField.world(salt) },
    ),

    /**
     * The same table cracked into cells, a gorge down every join — no trunk, no tributary, no downhill.
     * The one landform here that could not have been made by water, and kept for exactly that.
     */
    SHATTERED(
        "shattered",
        waterline = ShatteredField.RIVER_LEVEL,
        build = { _, salt -> ShatteredField.world(salt) },
    ),

    /**
     * Rolling upland carved by a river system — headwaters branching down into trunks, with the trunks
     * running wet and the headwaters dry. The one landform here with a drainage *hierarchy*.
     */
    RIVERLANDS(
        "riverlands",
        waterline = RiverlandsField.WATERLINE,
        build = { _, salt -> RiverlandsField.world(salt) },
    ),

    /**
     * Islands in an endless sea, at the [EXTENT] asked for — one where the writer arrives and the rest a
     * voyage away. Deliberately never a continent; see `Isle` for what makes that a property rather than
     * a tuning.
     */
    ISLANDS(
        "islands",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt -> IslandsField.world(options.steer(EXTENT, salt), salt) },
    ),

    /**
     * An alpine range at about one to sixteen: a foreland plain, foothills, and a glaciated crest. The one
     * landform here whose surface is built **up from its own drainage** rather than cut into a given one —
     * every ridge is where two hillslopes met.
     */
    ALPS(
        "alps",
        waterline = AlpsField.WATERLINE,
        build = { _, salt -> AlpsField.world(salt) },
    ),

    /**
     * One colossal impact structure with a plain around it: a central peak in a round sea, a ring wall,
     * and ordinary cratered country beyond its ejecta. The one landform here with a **centre** — walking
     * away from the origin is the whole of what happens in it.
     */
    CRATERLANDS(
        "craterlands",
        waterline = CraterlandsField.WATERLINE,
        build = { options, salt ->
            CraterlandsField.world(
                CraterlandsField.Steer(
                    wear = options.steer(WEAR, salt),
                    relief = options.steer(RELIEF, salt),
                    spacing = options.steer(SPACING, salt),
                ),
                salt,
            )
        },
    ),

    /**
     * Minecraft's noise caves with the rock and the air exchanged — the cast of a cave system hanging in
     * open air. No waterline, because a sea would fill every pocket under it and most of this world is
     * under anything.
     */
    INVERSE_CAVES(
        "inverse_caves",
        waterline = null,
        build = { _, salt -> InverseCavesField.world(salt) },
    ),

    /**
     * Continents, seas and hills — ordinary ground, with the overhangs a volumetric field can say and a
     * heightmap cannot. Minecraft's own overworld approximated rather than reproduced, and it sits high
     * so that there is room for an [UNDERGROUND] beneath it.
     */
    OVERWORLD(
        "overworld",
        waterline = OverworldField.WATERLINE,
        build = { _, salt -> OverworldField.world(salt) },
    ),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { _, salt -> ShapesField.world(salt) }),
    ;

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter>
        get() = listOfNotNull(
            ARRANGEMENT.takeIf { this == PYRAMIDS },
            BEARING.takeIf { this == CANYON || this == CLIFFS },
            EXTENT.takeIf { this == ISLANDS },
            SPACING.takeIf { this == CRATERLANDS },
            WEAR.takeIf { this == CRATERLANDS },
            RELIEF.takeIf { this == CRATERLANDS },
            UNDERGROUND.takeIf { undergroundCeiling() != null },
            STONE,
            MINGLING,
            SEAM,
        )

    /**
     * How high an underground of this terrain's may reach, or null where there is no room for one at all —
     * **a landform's own declaration**, like [hasSandyShores], because only the landform knows where its
     * lowest ground is and nothing general can be derived from what it does know.
     *
     * The height is what matters: an underground has to stop under the deepest thing the surface cuts, or
     * it opens into it. That is a different question per landform and each answers from its own datum —
     * [CANYONLANDS] from the floor its gorges reach, [CLIFFS] from its seabed, [OVERWORLD] from the level
     * it stops shaping at. There is no rule behind them and there was never going to be; a waterline stood
     * in for one here for a while and was wrong for [CANYON] in exactly the way a stand-in is.
     *
     * What is excluded is only what could not carry an underground or would make no sense of one:
     *
     * - **[ALPS]** spends its whole vertical budget on the landform and leaves about sixteen blocks under a
     *   valley floor, so caves there would be holes in the bedrock rather than a country under the ground.
     * - **[CAVERNS]** is the opposite case: its caves already *are* its shape, and **[INVERSE_CAVES]** is
     *   that taken to its limit — cutting caves into the cast of a cave system would only erase it.
     * - **[SPIRE_ISLANDS]** hangs in open air and is thin enough to be worked through by the weather alone.
     * - **[SHAPES]** is a reference for the vocabulary, not a world.
     *
     * **[CANYON] has the most room of anything here**, which is easy to get backwards: the gorge reaches
     * the world's floor, but everything either side of it is solid to the ceiling. Its underground is cut
     * off square by the gorge wall, which is a way in rather than a fault.
     */
    fun undergroundCeiling(): Int? = when (this) {
        OVERWORLD -> OverworldField.SOLID_TOP - ROOM_FOR_A_ROOF
        // Solid either side of the gorge all the way up, so this is bounded by taste rather than by rock.
        CANYON -> CanyonField.WORLD_CEILING / 2
        CANYONLANDS -> CanyonlandsField.FLOOR_Y - ROOM_FOR_A_ROOF
        SHATTERED -> ShatteredField.FLOOR_Y - ROOM_FOR_A_ROOF
        CLIFFS -> CliffField.SEABED_Y - ROOM_FOR_A_ROOF
        RIVERLANDS -> RiverlandsField.WATERLINE - DEEP_ENOUGH_TO_MISS_A_RIVERBED
        ISLANDS -> IslandsField.SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // The basin is already the deepest thing here, and it is dug from a plain standing well above
        // the waterline — so this datums on the crater floor rather than on the sea in it.
        CRATERLANDS -> CraterlandsField.BOWL_FLOOR_Y - ROOM_FOR_A_ROOF
        HILLS, ERODED, PILLARS -> ORDINARY_SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // A plain with no sea, so the only thing overhead is the plain itself.
        PYRAMIDS -> ORDINARY_SEA_LEVEL - ROOM_FOR_A_ROOF
        SPIRE_ISLANDS, CAVERNS, ALPS, SHAPES, INVERSE_CAVES -> null
    }

    override fun getSerializedName(): String = key

    /**
     * The rock this terrain lays down, steered by whichever [options] it understands.
     *
     * Takes the Age's [window] because an underground is cut between its floor and ceiling, and a [salt]
     * because two territories of the *same* preset must not build the same rock.
     */
    fun field(options: Options, window: VerticalWindow, salt: Long): TerrainField =
        ground(options, window, salt).shape

    /**
     * The rock this terrain lays down **and** the rock it was cut from, which a generator needs both of.
     *
     * They share their nodes rather than being built twice: the cut holds the uncut field as its own child,
     * so asking for both costs one landform and answers from one cache. Building a second copy would pay
     * for the whole thing again, which for a [MountainRange] or a [Caved] is most of the generator's time.
     */
    fun ground(options: Options, window: VerticalWindow, salt: Long): Ground {
        val uncut = build(options, salt)
        return when (options.of(UNDERGROUND)) {
            NOISE_CAVES -> Ground(
                Caved.of(uncut, CAVE_SEED xor salt, window.minY + BEDROCK_MARGIN, window.topY),
                // A carved cave meets the water table on its way out of the rock, so it answers to one.
                hollows = uncut,
            )
            GREAT_HALLS -> {
                val halls = hallsIn(window, salt)
                Ground(Subtract(uncut, halls), dry = halls)
            }
            else -> Ground(uncut)
        }
    }

    /**
     * The storeys [GREAT_HALLS] takes out of this terrain, between the bedrock and [undergroundCeiling].
     *
     * A ceiling has to be named here, unlike [NOISE_CAVES] where the band is the whole world — `Caved`
     * only ever walks rock the base actually has and its own entrance rule keeps the cut away from the
     * surface, so naming a ceiling there would be a second, worse copy of a decision the node already
     * makes better. A slab of halls has no such rule and would happily open onto a hillside.
     */
    private fun hallsIn(window: VerticalWindow, salt: Long): TerrainField =
        GreatHalls.voidBetween(window.minY + BEDROCK_MARGIN, undergroundCeiling() ?: 0, HALL_SEED xor salt)

    /**
     * The band of world this terrain's underground is **indoors** in, or null where it has none — see
     * [co.voik.agesandtheart.worldgen.biome.Roofed].
     *
     * Only [GREAT_HALLS] claims one. Noise caves are not indoors in this sense: they are open to the
     * surface by design, they belong to the country they were cut into, and vanilla's own cave biomes
     * describe them exactly.
     */
    fun undergroundBand(options: Options, window: VerticalWindow): IntRange? =
        if (options.of(UNDERGROUND) != GREAT_HALLS) null
        else undergroundCeiling()?.let { ceiling -> window.minY + BEDROCK_MARGIN..ceiling }

    /**
     * A terrain's rock, and what the water is to make of the space taken out of it. **The two are
     * alternatives, never both**: an underground either answers to a water table or is kept dry outright.
     *
     * [hollows] is the rock as it stood **before** the cut, handed to the aquifer so that the space inside
     * answers to a water table rather than to the waterline — a flat level fills any emptiness beneath it,
     * so without this a shape-cut cave comes out flooded to its roof. It is what a carved cave already
     * meets on its way out of the rock.
     *
     * [dry] is the opposite answer, and what [GREAT_HALLS] takes: the space is simply never wet. A water
     * table is a good description of rock that water seeps through and a bad one of a room — its wet and
     * dry patches have no walls between them, so a flooded bay ends mid-air against a dry one and reads as
     * a wall of water standing up by itself.
     */
    data class Ground(
        val shape: TerrainField,
        val hollows: TerrainField? = null,
        val dry: TerrainField? = null,
    )

    /**
     * Water this terrain carries **itself**, or null where a waterline is all it needs.
     *
     * A river system's water follows its own beds, which run downhill everywhere, so no single level can
     * pour it — see `SeaFill.wet`. Most terrains carry none, and a new one should not have to say so.
     */
    fun standingWater(salt: Long): TerrainField? = when (this) {
        RIVERLANDS -> RiverlandsField.water(salt)
        ALPS -> AlpsField.water(salt)
        else -> null
    }

    /**
     * **Everything this landform tells the biome layer about itself** — one channel, read only by a
     * grounded Age, and the only place a terrain gets to speak to `Grounding`.
     *
     * One declaration rather than a method per fact. Each of these was its own `when (this)` over every
     * terrain answering false for all but one of them, threaded to the same constructor as a separate
     * argument, and a fourth would have been a fourth of each. What a landform knows about its own shape
     * is one subject and belongs in one table; see [Grounding.Declared] for what the facts mean.
     */
    fun grounding(): Grounding.Declared = when (this) {
        ISLANDS -> Grounding.Declared(hasSandyShores = true)
        CANYON -> Grounding.Declared(waterlineIsRiver = true)
        // Measured from the basin's *shoulder* rather than its floor: the floor is the bottom of a hollow
        // in the middle of a cell, so datuming there chills the whole country by the depth of its lowest
        // hole and the basins come out snowy. The shoulder is where the plains actually sit.
        ALPS -> Grounding.Declared(elevation = Elevation(fromY = AlpsField.PLAIN_Y, toY = ALPINE_CREST_Y))
        // Datumed on the plain the basin was struck into, which is where the ordinary country is, and
        // topped at the rim crest — a hundred blocks of climb that would otherwise pass through no
        // country at all, the same argument `alps` makes.
        CRATERLANDS -> Grounding.Declared(
            elevation = Elevation(fromY = CraterlandsField.PLAIN_Y, toY = CraterlandsField.RIM_CREST_Y),
        )
        else -> Grounding.Declared()
    }

    /** How widely this terrain's materials speckle — see [MINGLING]. */
    fun mingling(options: Options, salt: Long): Double {
        val fineness = options.steer(MINGLING, salt)?.let(Span.NATURAL::fractionOf) ?: PATCHY
        return TerrainFill.PATCHY_MINGLING + fineness * (TerrainFill.FINE_MINGLING - TerrainFill.PATCHY_MINGLING)
    }

    /**
     * Which form the faults along this Age's seams take — see [SEAM]. [drawn] is the Age's own character,
     * and the answer unless a recipe pinned one over it. A pin naming nothing this version understands
     * falls back to the draw, so a dropped token cannot silently flatten an Age's geology.
     */
    fun seamIn(options: Options, drawn: Seam): Seam {
        val pinned = options.of(SEAM)
        if (pinned == SEAM_AS_DRAWN) return drawn
        return Seam.named(pinned) ?: drawn
    }

    /**
     * What this terrain is made of — the blocks the fill lays over its own territory, or empty for plain
     * stone. The fill's business rather than a surface rule's, so it sits *under* vanilla's tree and
     * vanilla paints grass on top of whatever we laid (see [TerrainFill]).
     *
     * Several mingle rather than divide (§3.2), and a material never says whether anything lives on it —
     * a copper spire keeps its grass.
     */
    fun fillBlocks(options: Options): List<BlockState> = options.materialsOf(STONE)

    companion object {
        /**
         * **The three axes a word may bend a landform along**, and the reason they are ranged rather than
         * named steps: a word carries the *band* it means, so `sparse` and `scattered` can sit on
         * different stretches of one axis without either needing a step minted for it. See
         * [Holds.RANGE]; the numbers live on the words and a writer never types one.
         *
         * **Shared on purpose.** Each names a quality many landforms have rather than a knob one of them
         * owns, so a word that bends `spacing` bends a crater field, a pillar grid and an archipelago —
         * each in its own units, none of them told what a block is. A landform declares the ones it can
         * honour and stays silent about the rest, and a word that reaches only silent ones goes unbacked
         * ([Resolver]'s `wentUnheeded`) rather than doing nothing quietly.
         *
         * What a landform may reach *through* one of these is its own business, including features
         * nothing else has: `craterlands` reads [RELIEF] as its rim height, its bowl depth **and** whether
         * a peak ring is drawn at all. The axis is the shared vocabulary; the reading is private.
         */
        val SPACING = Parameter.ranged("spacing")
        val WEAR = Parameter.ranged("wear")
        val RELIEF = Parameter.ranged("relief")

        /**
         * Where on [parameter]'s axis this Age sits, or **null where no word bounded it** — which means
         * the landform's own tuning rather than a draw across everything.
         *
         * That distinction is the whole of why this is not just `Span.read`. An unbounded axis is
         * `Span.NATURAL`, and drawing uniformly from it would make every unsteered Age a lottery and
         * retune all of them at once; leaving it null keeps "an Age told nothing gets what it always got".
         * A *bounded* axis still draws, so two Ages written with the same word differ within the band it
         * asked for — the word says where, the seed says exactly where.
         */
        val ARRANGEMENT = Parameter("arrangement", "grid", "rings", "varied")

        /**
         * Which way a canyon runs, as a fraction of a half-turn — a line has no direction, so half a turn
         * is the whole of it. A word says "north to south"; the angle it lands on is the machine's, which
         * is the split §3.2 draws.
         */
        val BEARING = Parameter.ranged("bearing")

        /**
         * How big an island is. Words rather than a distance, §3.2 keeping numbers away from a writer —
         * and the largest is deliberately short of anywhere you could lose a coastline on.
         */
        val EXTENT = Parameter.ranged("extent")

        /**
         * What lies under a terrain's surface — nothing, Minecraft's own noise caves, or storey upon
         * storey of pillared hall.
         *
         * Offered only by the terrains with room for one ([undergroundCeiling]), which is the shape the
         * whole idea wants: an underground is a *layer* a landform either has or does not, rather than a
         * property of every world — and it is why [GREAT_HALLS] is a value here rather than a landform of
         * its own, since what is over the halls should be able to be any world at all. No word reaches it
         * yet; it exists to be pinned by a recipe.
         *
         * Caves are the **default** where they are offered at all, since a world with room under it and
         * nothing in that room is the odder of the two answers.
         */
        val UNDERGROUND = Parameter("underground", NOISE_CAVES, GREAT_HALLS, UNDERGROUND_NONE)

        const val UNDERGROUND_NONE = "none"
        const val NOISE_CAVES = "noise_caves"
        const val GREAT_HALLS = "great_halls"

        /** Left whole, so the bedrock a cave might otherwise open through stays bedrock. */
        private const val BEDROCK_MARGIN = 5

        /** Rock over the top storey, where a landform's own datum is the ground it has to stay under. */
        private const val ROOM_FOR_A_ROOF = 12

        // A seabed and a riverbed are cut *into* the ground rather than standing on it, so a ceiling set
        // against the water they hold has to clear the bed as well as the level.
        private const val DEEP_ENOUGH_TO_MISS_A_SEABED = 44
        private const val DEEP_ENOUGH_TO_MISS_A_RIVERBED = 36

        /** Vanilla's, which is where every terrain that has not said otherwise puts its sea. */
        private const val ORDINARY_SEA_LEVEL = 63

        // So an Age's caves are its own, and decorrelated from the rock they are cut into.
        private const val CAVE_SEED = 0xCA_7E5L

        // And its halls likewise, decorrelated from both.
        private const val HALL_SEED = 0x4A_115L

        /** The one material parameter — the whole of what a writer means by "the land is andesite". */
        val STONE = Parameter.material("stone")

        /**
         * How finely several materials speckle together. High on the axis brings a patch down to a block or
         * two, for a mixture reading as one mottled rock rather than blotches of two.
         */
        val MINGLING = Parameter.ranged("mingling")

        /** Where mingling sits when nothing said: blotches, which is what an unremarked mixture looks like. */
        private const val PATCHY = 0.0

        /**
         * Which form the faults along this Age's seams take, overriding what its character drew (§3.4).
         * Exists so all four forms are walkable — an Age's seam is otherwise invisible until chance
         * produces one. `sheared` asks for no fault at all.
         *
         * Age-wide despite living on a per-territory aspect, and read from the first territory like
         * `Sea.DEPTH`: a seam belongs to the boundary rather than to either side, so "riven here and whole
         * there" is not something it could mean.
         */
        val SEAM = Parameter("seam", SEAM_AS_DRAWN, "sheared", "scarp", "rift", "fuzzed")

        /** What [SEAM] reads as when nobody overrode the draw: whatever the Age's character carries. */
        const val SEAM_AS_DRAWN = "drawn"

        /**
         * The height a column in [ALPS] reads as fully a summit at — around the crest rather than above the
         * tallest massif, so the peak biomes reach the whole crest line and not only its exceptions.
         */
        private const val ALPINE_CREST_Y = 262
    }
}
