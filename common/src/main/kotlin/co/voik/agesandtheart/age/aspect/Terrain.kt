package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.AlpsField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.Chambers
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.CraterlandsField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.FlatlandsField
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
import co.voik.agesandtheart.worldgen.SolidField
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
) : AuthoredPreset {
    /**
     * Floating islands over open air: lobed masses, talons and roots, weathered to ribs. Its waterline is
     * the floor of the world they hang over rather than a sea anything stands on, which is why it is far
     * below the 63 the grounded shapes share.
     */
    SPIRE_ISLANDS("spire_islands", waterline = SpireField.SEA_LEVEL, build = { _, salt -> SpireField.world(salt) }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { _, salt -> NoiseField.hills(salt) }),

    /**
     * A level plain to the horizon and no relief anywhere in it — Minecraft's own superflat. Its waterline
     * is null for the same reason [PYRAMIDS]' is: ground this even has nowhere to hold a sea, and a
     * phantom level under it would file the whole world as coast (see [Grounding.hasSea]).
     */
    FLATLANDS("flatlands", waterline = null, build = { _, _ -> FlatlandsField.world() }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { _, salt -> CavernField.world(salt) }),

    /** Plain 3D noise weathered into mesa-like relief, hanging clear above the water. */
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
     * Islands in an endless sea, at the [SIZE] asked for — one where the writer arrives and the rest a
     * voyage away. Deliberately never a continent; see `Isle` for what makes that a property rather than
     * a tuning.
     */
    ISLANDS(
        "islands",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt -> IslandsField.world(options.steer(SIZE, salt), salt) },
    ),

    /**
     * **One** island at the [SIZE] asked for, with open ocean however far you sail from it — where
     * [ISLANDS] promises another a voyage away, this promises there is no other.
     *
     * Its ladder reaches further at both ends than the archipelago's for exactly that reason: nothing has
     * to fit around it, so it may be a continent or a rock, neither of which an archipelago can draw.
     */
    ISLE(
        "isle",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt -> IslandsField.lone(options.steer(SIZE, salt), salt) },
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
     * so that there is room for an [Underground] beneath it.
     */
    OVERWORLD(
        "overworld",
        waterline = OverworldField.WATERLINE,
        build = { _, salt -> OverworldField.world(salt) },
    ),

    /**
     * Rock from the floor of the world to its ceiling: **the one landform with no surface**, and the only
     * one that shuts the world overhead by being what it is rather than by wearing a lid ([roofsTheWorld]).
     *
     * What is hollowed out of it is the [Underground]'s to say, which is the whole of why the shape is one
     * node — see [SolidField]. Its waterline is a water table rather than a sea, there being no open
     * ground for a sea to stand on.
     */
    SOLID(
        "solid",
        waterline = SolidField.WATERLINE,
        build = { _, _ -> SolidField.world() },
    ),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { _, salt -> ShapesField.world(salt) }),

    /**
     * **The rock this Age's template brings** — vanilla's own nether, end or overworld — rather than a
     * shape of ours.
     *
     * *Which* vanilla is the recipe's to say ([co.voik.agesandtheart.age.AgeRecipe.template]), so this
     * names the fact and nothing more. Naming any other landform replaces it, which is how a writer leaves
     * a template's rock behind: the two are either/or, vanilla's router answering for the rock, the
     * aquifers and the preliminary surface together where the field tree answers for all three.
     *
     * **It builds no field, and asking it for one is unreachable rather than merely wrong.**
     * `AgeGeneration.ourGround` is the only caller of [ground], and it is entered only where this is absent
     * from the composition; `AgeComposition.parse` refuses it beside a landform of ours. The throw records
     * the invariant rather than guarding a live path.
     */
    VANILLA("vanilla", waterline = null, build = { _, _ ->
        error("the template's own rock has no field of ours; AgeGeneration.ourGround is not reached for it")
    }),
    ;

    /**
     * [VANILLA] is not askable, and being so is the point of it: it is what an Age wears when the writer
     * named no landform at all, never something they can reach for.
     */
    override val askableInASentence: Boolean get() = this != VANILLA

    /**
     * Every landform a writer can reach for has a page that means it, and the page is minted from here —
     * a landform is a thing with a name, where a carve pattern is a quality of the rock and is reached by
     * `unbroken`, `riddled` and `flooded` instead.
     */
    override val writtenWordFor: String? get() = when (this) {
        // Where the key is a noun for the thing and the page is what a writer says of an Age wearing it.
        SPIRE_ISLANDS -> "spires"
        INVERSE_CAVES -> "inverted"
        VANILLA -> null
        HILLS, CAVERNS, ERODED, PILLARS, PYRAMIDS, CANYON, CLIFFS, CANYONLANDS, SHATTERED,
        RIVERLANDS, ISLANDS, ISLE, ALPS, CRATERLANDS, OVERWORLD, SHAPES, FLATLANDS, SOLID,
        -> key
    }

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter>
        get() = listOfNotNull(
            ARRANGEMENT.takeIf { this == PYRAMIDS },
            BEARING.takeIf { this == CANYON || this == CLIFFS },
            SIZE.takeIf { this == ISLANDS || this == ISLE },
            SPACING.takeIf { this == CRATERLANDS },
            WEAR.takeIf { this == CRATERLANDS },
            RELIEF.takeIf { this == CRATERLANDS },
            STONE,
            MINGLING,
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
        ISLANDS, ISLE -> IslandsField.SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // A plain with no sea, and the only thing over the top storey is the plain itself.
        FLATLANDS -> FlatlandsField.SURFACE_Y - ROOM_FOR_A_ROOF
        // The basin is already the deepest thing here, and it is dug from a plain standing well above
        // the waterline — so this datums on the crater floor rather than on the sea in it.
        CRATERLANDS -> CraterlandsField.BOWL_FLOOR_Y - ROOM_FOR_A_ROOF
        HILLS, ERODED, PILLARS -> ORDINARY_SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // A plain with no sea, so the only thing overhead is the plain itself.
        PYRAMIDS -> ORDINARY_SEA_LEVEL - ROOM_FOR_A_ROOF
        // **The most room of anything here, and for once nothing is being cleared.** There is no surface
        // for an underground to open into, so this is bounded by the bedrock roof alone.
        SOLID -> SolidField.UNDERGROUND_CEILING
        // VANILLA has no shape of ours to hollow under, its rock being vanilla's to describe.
        SPIRE_ISLANDS, CAVERNS, ALPS, SHAPES, INVERSE_CAVES, VANILLA -> null
    }

    override fun getSerializedName(): String = key

    /**
     * The rock this terrain lays down **and** the rock it was cut from, which a generator needs both of.
     *
     * They share their nodes rather than being built twice: the cut holds the uncut field as its own child,
     * so asking for both costs one landform and answers from one cache. Building a second copy would pay
     * for the whole thing again, which for a [MountainRange] or a [Caved] is most of the generator's time.
     */
    fun ground(
        underground: Underground,
        undergroundOptions: Options,
        options: Options,
        window: VerticalWindow,
        salt: Long,
    ): Ground {
        val uncut = build(options, salt)
        // **A landform with no room under it carries nothing**, whatever was asked for — the same shape as
        // a preset ignoring a material it cannot be made of, and the reason the ceiling is declared here.
        if (undergroundCeiling() == null) return Ground(uncut)
        return when (underground) {
            Underground.NOISE_CAVES -> Ground(
                Caved.of(uncut, CAVE_SEED xor salt, window.minY + BEDROCK_MARGIN, window.topY),
                // A carved cave meets the water table on its way out of the rock, so it answers to one.
                hollows = uncut,
            )
            Underground.GREAT_HALLS -> {
                val halls = hallsIn(window, salt)
                Ground(Subtract(uncut, halls), dry = halls)
            }
            // **Dry *and* wet**, which is not a contradiction: the vaults are kept out of the Age's own
            // flat fill outright, and the lake standing in each is put back by the field that knows where
            // its own water line is. Handing them to a water table instead would stand a flooded bay
            // against a dry one with nothing between, which is what `GreatHallsWaterCheck` records.
            Underground.CHAMBERED -> {
                val floor = window.minY + BEDROCK_MARGIN
                val roof = undergroundCeiling() ?: 0
                val size = undergroundOptions.steer(SIZE, salt)
                val vaults = Chambers.voidBetween(floor, roof, size, CHAMBER_SEED xor salt)
                Ground(
                    Subtract(uncut, vaults),
                    dry = vaults,
                    wet = Chambers.lakesIn(floor, roof, size, CHAMBER_SEED xor salt),
                )
            }
            Underground.NONE -> Ground(uncut)
        }
    }

    /**
     * The storeys [Underground.GREAT_HALLS] takes out of this terrain, between the bedrock and
     * [undergroundCeiling].
     *
     * A ceiling has to be named here, unlike [Underground.NOISE_CAVES] where the band is the whole world — `Caved`
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
     * Only [Underground.GREAT_HALLS] claims one. Noise caves are not indoors in this sense: they are open to the
     * surface by design, they belong to the country they were cut into, and vanilla's own cave biomes
     * describe them exactly.
     *
     * **[Underground.CHAMBERED] is enclosed and still does not claim one**, which is a decision rather
     * than an omission (Jonah, 2026-09-08). A band here overrides the climate table with one fixed biome,
     * and what a vault wants is the opposite: the cave biomes are what carry the lush growth the algae
     * rides on, and pinning every chamber to a hall's biome would take its features and its mob list with
     * it. A hall is somebody's architecture and reads as one room however far it runs; a chamber is a
     * place, and places are what biomes are for.
     */
    fun undergroundBand(underground: Underground, window: VerticalWindow): IntRange? =
        if (underground != Underground.GREAT_HALLS) null
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
     * [dry] is the opposite answer, and what [Underground.GREAT_HALLS] takes: the space is simply never wet. A water
     * table is a good description of rock that water seeps through and a bad one of a room — its wet and
     * dry patches have no walls between them, so a flooded bay ends mid-air against a dry one and reads as
     * a wall of water standing up by itself.
     */
    data class Ground(
        val shape: TerrainField,
        val hollows: TerrainField? = null,
        val dry: TerrainField? = null,
        /**
         * Water the underground carries **itself**, standing wherever this says regardless of the Age's own
         * waterline — the counterpart of [dry] and the same channel a river's water runs through.
         *
         * It exists because every landform's underground ceiling sits *below* its waterline, so a chamber
         * filled by the Age's sea is a drowned chamber everywhere. A lake that knows its own level is what
         * lets one be written under any shape with room for it.
         */
        val wet: TerrainField? = null,
    )

    /**
     * Whether this shape **shuts the world overhead by being what it is** — solid rock all the way to the
     * ceiling, with no surface anywhere for a sky to reach.
     *
     * A physical fact about the Age, which is what `sky.sealed` already claims to be — so it is read
     * beside it rather than instead of it (`AgeComposition.isRoofed`), and everything that follows from
     * being roofed follows here too: the dimension type, where a visitor arrives, and what is painted
     * overhead. [CANYON] is the one that looks like it belongs here and does not: its plateau reaches the
     * ceiling, but the gorge is open to the sky and that is the whole landform.
     */
    val roofsTheWorld: Boolean get() = this == SOLID

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
        ISLANDS, ISLE -> Grounding.Declared(hasSandyShores = true)
        CANYON -> Grounding.Declared(waterlineIsRiver = true)
        // Rock to the ceiling has no coast in it anywhere, and vanilla's continentalness curve has no
        // anchor that far over the water — so without this a sealed world files as mid inland and grows
        // plains in its own rock. See [Grounding.Declared.isDeepInland].
        SOLID -> Grounding.Declared(isDeepInland = true)
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
        val coarseness = options.steer(MINGLING, salt)?.let(Span.NATURAL::fractionOf) ?: EVENLY_MINGLED
        return TerrainFill.FINE_MINGLING + coarseness * (TerrainFill.PATCHY_MINGLING - TerrainFill.FINE_MINGLING)
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
         * **Shared on purpose.** Each names a quality many landforms have rather than a parameter one of them
         * owns, so a word that bends `spacing` bends a crater field, a pillar grid and an archipelago —
         * each in its own units, none of them told what a block is. A landform declares the ones it can
         * honour and stays silent about the rest, and a word that reaches only silent ones goes unbacked
         * ([Resolver]'s `wentUnheeded`) rather than doing nothing quietly.
         *
         * What a landform may reach *through* one of these is its own business, including features
         * nothing else has: `craterlands` reads [RELIEF] as its rim height, its bowl depth **and** whether
         * a peak ring is drawn at all. The axis is the shared vocabulary; the reading is private.
         */
        val SPACING = Parameter.ranged(
            "spacing",
            help = "How far apart the landform's features stand.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "crowded together"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "far apart"),
            ),
        )
        val WEAR = Parameter.ranged(
            "wear",
            help = "How weathered and broken up the landform is.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "sharp and unbroken"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "worn to rubble"),
            ),
        )
        val RELIEF = Parameter.ranged(
            "relief",
            help = "How much height the landform has.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "nearly flat"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "towering"),
            ),
        )

        val ARRANGEMENT = Parameter(
            "arrangement",
            listOf("grid", "rings", "varied"),
            help = "How the landform's pieces are laid out.",
            optionHelp = mapOf(
                "grid" to "Evenly spaced, in rows and columns.",
                "rings" to "In concentric rings about a centre.",
                "varied" to "Scattered, with no pattern to it.",
            ),
        )

        /**
         * Which way a canyon runs, as a fraction of a half-turn — a line has no direction, so half a turn
         * is the whole of it. A word says "north to south"; the angle it lands on is the machine's, which
         * is the split §3.2 draws.
         */
        val BEARING = Parameter.ranged(
            "bearing",
            help = "Which way a canyon runs.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "north to south"),
                Parameter.Landmark(-0.5, "diagonal"),
                Parameter.Landmark(0.0, "east to west"),
                Parameter.Landmark(0.5, "the other diagonal"),
                Parameter.Landmark(1.0, "north to south again"),
            ),
        )

        /**
         * How big an island is — its shore, its height and how far apart they stand, which move together.
         * Words rather than a distance, §3.2 keeping numbers away from a writer, and the largest is
         * deliberately short of anywhere you could lose a coastline on.
         *
         * **`size`, the same name a sun and a feature use**, because it is the size of the whole thing and
         * not one dimension of it: `colossal islands landmass` is the word a writer would reach for and it
         * costs no word of its own. [MINGLING] keeps its own name for the opposite reason — how finely two
         * rocks speckle together is not how big anything is.
         */
        val SIZE = Parameter.ranged(
            "size",
            help = "How big an island is: shore, height and spacing move together.",
            // Words anybody knows. A skerry is a rock in the sea and the right word for the low end, and
            // a landmark nobody can read is a landmark that says nothing.
            landmarks = listOf(
                Parameter.Landmark(-1.0, "tiny"),
                Parameter.Landmark(0.0, "an island"),
                Parameter.Landmark(1.0, "a continent"),
            ),
        )

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

        // And its chambers, so a world's vaults are not laid where its caves were.
        private const val CHAMBER_SEED = 0x0C_4A_9BEL

        /** The one material parameter — the whole of what a writer means by "the land is andesite". */
        val STONE = Parameter.material(
            "stone",
            holdsYouUp = true,
            help = "The block the land itself is made of.",
        )

        /**
         * **How coarsely several materials lie together** — the bottom of the axis is a speckle at block
         * scale and the top is the widest patch that still reads as one mixed rock.
         *
         * **Low is fine, which is the same way up as every other ranged parameter**: more of the axis is more of
         * what the name says. It ran the other way and meant *fineness*, which made a word for the coarse
         * end read as an argument with its own axis, and made the reader between here and
         * `TerrainFill.mingleStretch` an inversion rather than a scale.
         *
         * The top is bounded by what mingling *is* rather than by taste — see
         * [TerrainFill.PATCHY_MINGLING]: anything much wider stops being a mixture and reads as two
         * territories, which has its own spelling in `and` and a seam.
         */
        val MINGLING = Parameter.ranged(
            "mingling",
            help = "How coarsely two materials lie together: low is a speckle, high is broad patches.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "an even speckle"),
                Parameter.Landmark(0.0, "blotches"),
                Parameter.Landmark(1.0, "broad patches"),
            ),
        )

        /**
         * Where mingling sits when nothing said: **as evenly intermixed as the surface rules can manage**,
         * so two rocks read as one mottled stone rather than as two regions (walked 2026-07-27).
         *
         * The floor of the axis rather than a value in the middle, so an unremarked mixture is the quietest
         * a mixture can be and every word about it asks for more.
         */
        private const val EVENLY_MINGLED = 0.0

        /**
         * The height a column in [ALPS] reads as fully a summit at — around the crest rather than above the
         * tallest massif, so the peak biomes reach the whole crest line and not only its exceptions.
         */
        private const val ALPINE_CREST_Y = 262
    }
}
