package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.MountainousField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.CraterlandsField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.FlatlandsField
import co.voik.agesandtheart.worldgen.InverseCavesField
import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.OverworldField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.ShatteredField
import co.voik.agesandtheart.worldgen.LandSize
import co.voik.agesandtheart.worldgen.LandVariety
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.agesandtheart.worldgen.SolidField
import co.voik.agesandtheart.worldgen.SkylandsField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.biome.Elevation
import co.voik.agesandtheart.worldgen.biome.Grounding
import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.Landmarks
import co.voik.agesandtheart.worldgen.field.Lift
import co.voik.agesandtheart.worldgen.field.MountainRange
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.Wear
import co.voik.agesandtheart.worldgen.field.TerrainFill
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainTable
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Vanilla's, which is where every terrain that has not said otherwise puts its sea.
 *
 * Out here rather than in the companion because an entry's arguments are evaluated before the companion
 * object exists, and four of the entries below set their waterline from it.
 */
private const val ORDINARY_SEA_LEVEL = 63

/**
 * The islands' ponds: vanilla's surface lake, the shape `lakes` mints, filled with water, at eight times
 * vanilla's rate for its lava, since only the islands of a sky give it anywhere to lie.
 */
private val ISLAND_PONDS: String by lazy {
    Claim("minecraft:lake_lava_surface", density = ISLAND_POND_RATE, madeOf = "minecraft:water").spelled()
}

private const val ISLAND_POND_RATE = 8.0

/** [Terrain.SIZE] as the power [SizeScale] raises its base to, read by [LandSize] unless the Age predates it. */
private fun sizeOf(options: Options, salt: Long): Double? {
    val said = options.steer(Terrain.SIZE, salt)
    return if (options.isTrue(Terrain.FORMER_SIZES)) said else LandSize.powerFor(said)
}

/** [sizeOf] as [SizeScale]'s factor, which is what every landform's builder is handed. */
private fun scaleOf(options: Options, salt: Long): Double = SizeScale.factorAt(sizeOf(options, salt))

/** How much a landform changes as it is crossed: 0 for none, 1 unsaid, 2 at the most — see [LandVariety]. */
private fun varietyOf(options: Options, salt: Long): Double = (options.steer(Terrain.VARIETY, salt) ?: UNSAID_VARIETY) + 1.0

private const val UNSAID_VARIETY = 0.0

/**
 * How worn the land is on average, read off [Terrain.WEAR]: none at the bottom, [ordinarily] unsaid — a touch, so an
 * Age nobody called worn stands close to pristine — and [MOST_WEAR] at the top; none at all on an Age written before
 * the weather wore the land.
 */
private fun erosionOf(options: Options, salt: Long, ordinarily: Double = ORDINARY_WEAR): Double {
    if (isFormer(options)) return 0.0
    val steer = options.steer(Terrain.WEAR, salt) ?: UNSAID_WEAR
    return if (steer < 0.0) ordinarily * (1.0 + steer) else ordinarily + steer * (MOST_WEAR - ordinarily)
}

/** Whether this is an Age written before generator 20, whose land is laid as it was then. */
private fun isFormer(options: Options): Boolean = options.isTrue(Terrain.FORMER_LAND)

private const val UNSAID_WEAR = 0.0
private const val ORDINARY_WEAR = 0.25
private const val MOST_WEAR = 2.0

/** Where a landform's landmarks stand, at [landScale]; none on an Age that does not vary. */
private fun landmarksOf(options: Options, salt: Long, landScale: Double): Landmarks =
    LandVariety.landmarksFor(varietyOf(options, salt), landScale, salt)

/** [Terrain.SIZE] as written, for the landforms hanging in the void, which [LandSize] leaves alone. */
private fun scaleAsWritten(options: Options, salt: Long): Double = SizeScale.factorAt(options.steer(Terrain.SIZE, salt))

/** The wear the crater field's craters follow, which is the weather's own; none on an Age that does not vary. */
private fun craterWear(options: Options, salt: Long, table: TerrainTable?): Wear? {
    val strength = varietyOf(options, salt)
    return if (strength <= 0.0) null else LandVariety.wearOf(strength, erosionOf(options, salt), scaleOf(options, salt), salt, table)
}

/**
 * The wear a landform's own features follow, which is the weather's own, even everywhere on an Age that does not vary;
 * none on an Age written before generator 21, whose features were all alike.
 */
private fun featureWear(options: Options, salt: Long, table: TerrainTable?): Wear? {
    if (options.isTrue(Terrain.FORMER_WEAR)) return null
    return LandVariety.wearOf(varietyOf(options, salt), erosionOf(options, salt), scaleOf(options, salt), salt, table)
}

private fun craterSteer(options: Options, salt: Long) = CraterlandsField.Steer(
    wear = options.steer(Terrain.WEAR, salt),
    relief = options.steer(Terrain.RELIEF, salt),
    spacing = options.steer(Terrain.SPACING, salt),
    size = sizeOf(options, salt),
)

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
    private val build: (Options, Long, TerrainTable?) -> TerrainField,
    /**
     * Where the sea stands if the book names one, for a landform that has none of its own: islands in the
     * void are sealess unless asked, and then hang over a sea far below (Jonah, 2026-10-06).
     */
    private val waterlineWhenASeaIsNamed: Int? = null,
    // Deferred like [build], because a Parameter is a companion value and an entry is built before the
    // companion is.
    private val axes: () -> List<Parameter> = { emptyList() },
    // The page that means this landform, which is its key: an adjective, since a book writes
    // `<landform> landmass`.
    private val page: String? = key,
) : AuthoredPreset {
    /**
     * Floating islands over open air: lobed masses, talons and roots, weathered to ribs. Its waterline is
     * the floor of the world they hang over rather than a sea anything stands on, which is why it is far
     * below the 63 the grounded shapes share.
     */
    SPIRE_ISLANDS(
        "spire_islands",
        waterline = SpireField.SEA_LEVEL,
        build = { options, salt, _ -> SpireField.world(salt, scaleAsWritten(options, salt)) },
        axes = { listOf(SIZE) },
        // The Spire's own rock, reached by its preset and never by a writer.
        page = null,
    ),

    /**
     * Islands floating in nothing at about one height, the End's arrangement: broad tops over tapering
     * undersides, near enough to cross between. Nothing below them but the void, unless a book names a sea.
     */
    SKYLANDS(
        "skyborne",
        waterline = null,
        build = { options, salt, _ -> SkylandsField.world(salt, scaleAsWritten(options, salt)) },
        waterlineWhenASeaIsNamed = SkylandsField.NAMED_SEA_LEVEL,
        axes = { listOf(SIZE) },
    ),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS(
        "gentle",
        waterline = ORDINARY_SEA_LEVEL,
        build = { options, salt, _ -> NoiseField.hills(salt, scaleOf(options, salt)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * A level plain to the horizon and no relief anywhere in it — Minecraft's own superflat. Its waterline
     * is null for the same reason [PYRAMIDS]' is: ground this even has nowhere to hold a sea, and a
     * phantom level under it would file the whole world as coast (see [Grounding.hasSea]).
     */
    FLATLANDS("flat", waterline = null, build = { _, _, _ -> FlatlandsField.world() }),

    /** Weathered rock country rising out of the sea: sheer-walled buttes, arches and overhangs. */
    ERODED(
        "eroded",
        waterline = ORDINARY_SEA_LEVEL,
        build = { options, salt, _ -> ErodedField.world(salt, scaleOf(options, salt)) },
        axes = { listOf(SIZE) },
    ),

    /** Rectangular monoliths on a jittered grid — at `colossal`, standing a hundred blocks out of the sea. */
    PILLARS(
        "pillared",
        waterline = ORDINARY_SEA_LEVEL,
        build = { options, salt, table -> PillarField.world(salt, scaleOf(options, salt), featureWear(options, salt, table)) },
        axes = { listOf(SIZE) },
    ),

    /** Instanced pyramids on a plain, in the [ARRANGEMENT] asked for. */
    PYRAMIDS(
        "pyramidal",
        waterline = null,
        build = { options, salt, _ ->
            val scale = scaleOf(options, salt)
            PyramidField.world(options.of(ARRANGEMENT), salt, scale, landmarksOf(options, salt, scale))
        },
        axes = { listOf(ARRANGEMENT, SIZE) },
    ),

    /**
     * Rock to the height limit with one canyon cut through it, on the [BEARING] asked for — the one
     * terrain where the world is what was taken away. Its waterline is the river at the bottom of the
     * gorge, not a sea: there is no open ground for a sea to stand on.
     */
    CANYON(
        "cleft",
        waterline = CanyonField.RIVER_LEVEL,
        build = { options, salt, _ ->
            val scale = scaleOf(options, salt)
            CanyonField.world(bearingAt(options.steer(BEARING, salt)), salt, scale, landmarksOf(options, salt, scale))
        },
        axes = { listOf(BEARING, SIZE) },
    ),

    /**
     * A world cut in two on the [BEARING] asked for: ocean one way, plateau the other, one cliff between.
     */
    CLIFFS(
        "sheer",
        waterline = CliffField.SEA_LEVEL,
        build = { options, salt, _ ->
            CliffField.world(bearingAt(options.steer(BEARING, salt)), salt, scaleOf(options, salt))
        },
        axes = { listOf(BEARING, SIZE) },
    ),

    /** Mesa country: a tableland under open sky, cut to pieces by canyons running three ways at once. */
    CANYONLANDS(
        "canyoned",
        waterline = CanyonlandsField.RIVER_LEVEL,
        build = { options, salt, _ -> CanyonlandsField.world(salt, scaleOf(options, salt), varietyOf(options, salt)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * The same table cracked into cells, a gorge down every join — no trunk, no tributary, no downhill.
     * The one landform here that could not have been made by water, and kept for exactly that.
     */
    SHATTERED(
        "shattered",
        waterline = ShatteredField.RIVER_LEVEL,
        build = { options, salt, _ -> ShatteredField.world(salt, scaleOf(options, salt)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * Rolling upland carved by a river system — headwaters branching down into trunks, with the trunks
     * running wet and the headwaters dry. The one landform here with a drainage *hierarchy*.
     */
    RIVERLANDS(
        "riverine",
        waterline = RiverlandsField.WATERLINE,
        build = { options, salt, _ -> RiverlandsField.world(salt, scaleOf(options, salt), isFormer(options)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * Islands in an endless sea, at the [SIZE] asked for — one where the writer arrives and the rest a
     * voyage away. Deliberately never a continent; see `Isle` for what makes that a property rather than
     * a tuning.
     */
    ISLANDS(
        "archipelagic",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt, table -> IslandsField.world(sizeOf(options, salt), salt, featureWear(options, salt, table)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * **One** island at the [SIZE] asked for, with open ocean however far you sail from it — where
     * [ISLANDS] promises another a voyage away, this promises there is no other.
     *
     * Its ladder reaches further at both ends than the archipelago's for exactly that reason: nothing has
     * to fit around it, so it may be a continent or a rock, neither of which an archipelago can draw.
     */
    ISLE(
        "insular",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt, table -> IslandsField.lone(sizeOf(options, salt), salt, featureWear(options, salt, table)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * An alpine range at about one to sixteen: a foreland plain, foothills, and a glaciated crest. The one
     * landform here whose surface is built **up from its own drainage** rather than cut into a given one —
     * every ridge is where two hillslopes met.
     */
    MOUNTAINOUS(
        "mountainous",
        waterline = MountainousField.WATERLINE,
        build = { options, salt, _ -> MountainousField.world(salt, scaleOf(options, salt)) },
        axes = { listOf(SIZE) },
    ),

    /**
     * One colossal impact structure with a plain around it: a central peak in a round sea, a ring wall,
     * and ordinary cratered country beyond its ejecta. The one landform here with a **centre** — walking
     * away from the origin is the whole of what happens in it.
     */
    CRATERLANDS(
        "cratered",
        waterline = CraterlandsField.WATERLINE,
        build = { options, salt, table ->
            CraterlandsField.world(
                craterSteer(options, salt),
                salt,
                craterWear(options, salt, table),
                centralPeaks = !options.isTrue(FORMER_WEAR),
            )
        },
        axes = { listOf(SPACING, WEAR, RELIEF, SIZE) },
    ),

    /**
     * Minecraft's noise caves with the rock and the air exchanged — the cast of a cave system hanging in
     * open air. No waterline, because a sea would fill every pocket under it and most of this world is
     * under anything.
     */
    INVERSE_CAVES(
        "tangled",
        waterline = null,
        build = { options, salt, _ ->
            val scale = scaleAsWritten(options, salt)
            InverseCavesField.world(salt, scale, landmarksOf(options, salt, scale))
        },
        waterlineWhenASeaIsNamed = InverseCavesField.NAMED_SEA_LEVEL,
        axes = { listOf(SIZE) },
    ),

    /**
     * Continents, seas and hills — ordinary ground, with the overhangs a volumetric field can say and a
     * heightmap cannot. Minecraft's own overworld approximated rather than reproduced, and it sits high
     * so that there is room for an [Underground] beneath it, which vanilla's own [OVERWORLD] has not.
     */
    CONTINENTS(
        "continental",
        waterline = OverworldField.WATERLINE,
        build = { options, salt, _ -> OverworldField.world(salt, scaleOf(options, salt)) },
        axes = { listOf(SIZE) },
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
        "subterranean",
        waterline = SolidField.WATERLINE,
        build = { _, _, _ -> SolidField.world() },
    ),

    /**
     * **Vanilla's own rock, one per world** — its overworld, its nether and its end — rather than a shape of
     * ours, and each under any template: nether rock under an overworld sky, or the end's islands under a
     * blue one. Which noise settings each wears is [AgeTemplate.ofRock]'s to say.
     *
     * A template's own world wears its own ([AgeTemplate.world]), which is what an Age that named no
     * landform gets. They are either/or with a landform of ours, vanilla's router answering for the rock,
     * the aquifers and the preliminary surface together where the field tree answers for all three — so a
     * book naming one beside a shape of ours keeps the vanilla rock and is charged for the other.
     *
     * **They build no field, and asking one for it is unreachable rather than merely wrong.**
     * `AgeGeneration.ourGround` is the only caller of [ground], and it is entered only where none of these
     * is in the composition. The throw records the invariant rather than guarding a live path.
     */
    OVERWORLD("overworld", waterline = null, build = { _, _, _ -> error(NO_FIELD_OF_OURS) }),
    NETHER("nether", waterline = null, build = { _, _, _ -> error(NO_FIELD_OF_OURS) }),
    END("end", waterline = null, build = { _, _, _ -> error(NO_FIELD_OF_OURS) }),
    ;

    /** Whether this is vanilla's own rock rather than a shape of ours — see [OVERWORLD]. */
    val isVanillas: Boolean get() = this == OVERWORLD || this == NETHER || this == END

    /**
     * Vanilla's rocks are kept from broad words: named, they are reached by their page, but an evocative
     * word drawing one into a world divided with a shape of ours would draw a world that cannot be built.
     * [SPIRE_ISLANDS] is the Spire's, an Age recreated rather than written, so no word reaches it at all.
     */
    override val availableToBroadWords: Boolean get() = !isVanillas && this != SPIRE_ISLANDS

    override val takesTheWholeAspect: Boolean get() = isVanillas

    /**
     * Whether there is rock under this landform's land all the way down — false for the ones hanging in
     * the void, where anything vanilla builds at a fixed depth (`Structures.BUILT_UNDERGROUND`) would hang
     * there too.
     */
    val hasGroundBeneath: Boolean get() = this !in HANGING_IN_THE_VOID

    /**
     * Where this landform's clouds sit when the book says nothing about them, or null for the sky's own
     * height. Only islands in the void ask: their clouds belong under them.
     */
    val cloudsAtY: Int? get() = if (this == SKYLANDS) SkylandsField.CLOUDS_Y else null

    /**
     * Whether this landform brings the Spire's own sky ([SpireSky]) — its two cloud decks are measured off
     * these islands, so the sky rides with them. Only [SPIRE_ISLANDS] does.
     */
    val bringsTheSpiresSky: Boolean get() = this == SPIRE_ISLANDS

    /**
     * Every landform a writer can reach for has a page that means it, and the page is minted from here —
     * a landform is a thing with a name, where a carve pattern is a quality of the rock and is reached by
     * `unbroken`, `riddled` and `flooded` instead.
     */
    override val writtenWordFor: String? get() = page

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter> get() = (axes() + weatheringAxes).distinct() + STONE + MINGLING

    /** A worn landform honours [WEAR], which sets how deeply the weather wears it. */
    private val weatheringAxes: List<Parameter> get() = if (this in WORN) listOf(WEAR) else emptyList()

    /**
     * Where this terrain's sea stands at the size asked for: [waterline], for every landform but the one
     * whose water is a river on a floor that size moves.
     */
    fun waterlineAt(options: Options, salt: Long, sea: SeaSetting? = null): Int? =
        if (sea != null && movesToTheSea(options, salt, sea)) sea.level else ownWaterlineAt(options, salt)

    private fun ownWaterlineAt(options: Options, salt: Long): Int? = when (this) {
        CANYON -> CanyonField.riverLevel(scaleOf(options, salt))
        RIVERLANDS -> RiverlandsField.waterlineOf(isFormer(options))
        else -> waterline ?: waterlineWhenASeaIsNamed
    }

    /**
     * The height of this landform that belongs at an Age's sea, and the highest it builds where that could pass the top
     * of the world once moved; null for what hangs in the void or fills the world, which keeps its own heights, and
     * for vanilla's rock. Its sea where it has one; a landform with none says where its land meets a coast.
     */
    private fun footing(options: Options, salt: Long): Footing? = when (this) {
        HILLS, ERODED, PILLARS, CLIFFS, CANYONLANDS, SHATTERED, RIVERLANDS, ISLANDS, ISLE, CRATERLANDS, CONTINENTS, SOLID ->
            ownWaterlineAt(options, salt)?.let(::Footing)
        // A plain standing just over vanilla's sea.
        FLATLANDS, PYRAMIDS -> Footing(ORDINARY_SEA_LEVEL)
        CANYON -> scaleOf(options, salt).let { scale -> Footing(CanyonField.riverLevel(scale), topY = CanyonField.plateauY(scale)) }
        // The basin floors meet the coast, and the peaks reach the top of the world already.
        MOUNTAINOUS -> Footing(MountainousField.BASIN_FLOOR, topY = VerticalWindow.HIGHEST_BLOCK_Y)
        SKYLANDS, SPIRE_ISLANDS, INVERSE_CAVES, OVERWORLD, NETHER, END -> null
    }

    /**
     * Whether this landform is moved to [sea]: where it stands on ground and has a sea of its own to set there, and
     * where the sea is shared, so a landform with none meets the one its neighbours pour.
     */
    private fun movesToTheSea(options: Options, salt: Long, sea: SeaSetting): Boolean {
        if (footing(options, salt) == null) return false
        val own = ownWaterlineAt(options, salt)
        val hasASeaOfItsOwn = own != null && own > VerticalWindow.MIN_Y
        return hasASeaOfItsOwn || sea.isShared
    }

    /** How this landform is moved to [sea], or null where it keeps its own heights. */
    private fun liftTo(options: Options, salt: Long, sea: SeaSetting?): Lift? {
        if (sea == null || !movesToTheSea(options, salt, sea)) return null
        val footing = footing(options, salt) ?: return null
        val squeeze = footing.topY?.let { topY -> Lift.squeezeToFit(footing.datumY, sea.level, topY) } ?: 1.0
        return Lift(footing.datumY, sea.level, squeeze).takeUnless { it.isNone }
    }

    /** The height of this landform's that belongs at an Age's sea, and the highest it builds where that is near the top. */
    private data class Footing(val datumY: Int, val topY: Int? = null)

    /**
     * The one sea an Age laid out by the table sets its landforms at: each standing on ground with a sea of its own is
     * moved so that sea stands at [level], and where the sea [isShared] — more than one territory, one with a sea of its
     * own — so is every other, so every territory pours the same sea and no seam has two to wall apart.
     */
    data class SeaSetting(val level: Int, val isShared: Boolean)

    /**
     * Whether this landform takes a sea only when a book names one. Its unspoken sea is none rather than the
     * world's water, which is what keeps it sealess by default (see `AgeComposition.laidOver`).
     */
    val takesASeaOnlyWhenNamed: Boolean get() = waterline == null && waterlineWhenASeaIsNamed != null

    /**
     * How high an underground of this terrain's may reach, or null where there is no room for one at all —
     * **a landform's own declaration**, like [Grounding.Declared.hasSandyShores], because only the landform knows
     * where its lowest ground is and nothing general can be derived from what it does know.
     *
     * The height is what matters: an underground has to stop under the deepest thing the surface cuts, or
     * it opens into it. That is a different question per landform and each answers from its own datum —
     * [CANYONLANDS] from the floor its gorges reach, [CLIFFS] from its seabed, [CONTINENTS] from the level
     * it stops shaping at. There is no rule behind them and there was never going to be; a waterline stood
     * in for one here for a while and was wrong for [CANYON] in exactly the way a stand-in is.
     *
     * What is excluded is only what could not carry an underground or would make no sense of one:
     *
     * - **[MOUNTAINOUS]** spends its whole vertical budget on the landform and leaves about sixteen blocks under a
     *   valley floor, so caves there would be holes in the bedrock rather than a country under the ground.
     * - **[INVERSE_CAVES]** is the opposite case: it is the cast of a cave system already, and cutting caves
     *   into it would only erase it.
     * - **[SPIRE_ISLANDS]** hangs in open air and is thin enough to be worked through by the weather alone.
         *
     * **[CANYON] has the most room of anything here**, which is easy to get backwards: the gorge reaches
     * the world's floor, but everything either side of it is solid to the ceiling. Its underground is cut
     * off square by the gorge wall, which is a way in rather than a fault.
     *
     * **Size moves only the landforms whose datum moves with it**: a canyon's plateau comes down onto its
     * underground, and a colossal crater's basin reaches below the tuned one. Everywhere else the datum is a
     * floor or a sea that every size shares.
     */
    fun undergroundCeiling(options: Options, salt: Long, sea: SeaSetting? = null): Int? {
        val own = ownUndergroundCeiling(options, salt) ?: return null
        return liftTo(options, salt, sea)?.heightOf(own) ?: own
    }

    private fun ownUndergroundCeiling(options: Options, salt: Long): Int? = when (this) {
        CONTINENTS -> OverworldField.SOLID_TOP - ROOM_FOR_A_ROOF
        // Solid either side of the gorge all the way up, so this is bounded by taste rather than by rock —
        // and by the plateau, once the canyon is smaller than the world.
        CANYON -> minOf(VerticalWindow.HIGHEST_BLOCK_Y / 2, CanyonField.plateauY(scaleOf(options, salt)) - ROOM_FOR_A_ROOF)
        CANYONLANDS -> CanyonlandsField.FLOOR_Y - ROOM_FOR_A_ROOF
        SHATTERED -> ShatteredField.FLOOR_Y - ROOM_FOR_A_ROOF
        CLIFFS -> CliffField.SEABED_Y - ROOM_FOR_A_ROOF
        RIVERLANDS -> RiverlandsField.waterlineOf(isFormer(options)) - DEEP_ENOUGH_TO_MISS_A_RIVERBED
        ISLANDS, ISLE -> IslandsField.SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // A plain with no sea, and the only thing over the top storey is the plain itself.
        FLATLANDS -> FlatlandsField.SURFACE_Y - ROOM_FOR_A_ROOF
        // The basin is already the deepest thing here, and it is dug from a plain standing well above
        // the waterline — so this datums on the crater floor rather than on the sea in it.
        CRATERLANDS -> CraterlandsField.bowlFloorY(craterSteer(options, salt)) - ROOM_FOR_A_ROOF
        HILLS, PILLARS -> ORDINARY_SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        // Its seabed is the band's foot, which a larger country carries deeper.
        ERODED -> ErodedField.seabedY(scaleOf(options, salt)) - ROOM_FOR_A_ROOF
        // A plain with no sea, so the only thing overhead is the plain itself.
        PYRAMIDS -> ORDINARY_SEA_LEVEL - ROOM_FOR_A_ROOF
        // **The most room of anything here, and for once nothing is being cleared.** There is no surface
        // for an underground to open into, so this is bounded by the bedrock roof alone.
        SOLID -> SolidField.UNDERGROUND_CEILING
        // Islands in the void have no ground under them to hollow.
        SPIRE_ISLANDS, SKYLANDS, MOUNTAINOUS, INVERSE_CAVES -> null
        // Vanilla's overworld has its underground cut after its own fill, under an ordinary landform's ceiling
        // ([undergroundCutFromVanilla]); the nether is open caverns already and the End has no ground to cut.
        OVERWORLD -> ORDINARY_SEA_LEVEL - DEEP_ENOUGH_TO_MISS_A_SEABED
        NETHER, END -> null
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
        /** The Age's terrain table, shared by every landform in it; none on an Age written before there was one. */
        table: TerrainTable? = null,
        /** The sea this landform is set at, or null where it keeps its own heights. */
        sea: SeaSetting? = null,
    ): Ground {
        val built = build(options, salt, table).let { field -> liftTo(options, salt, sea)?.over(field) ?: field }
        val uncut = varied(built, options, salt, isRock = true, table, sea)
        // **A landform with no room under it carries nothing**, whatever was asked for — the same shape as
        // a preset ignoring a material it cannot be made of, and the reason the ceiling is declared here.
        val ceiling = undergroundCeiling(options, salt, sea) ?: return Ground(uncut)
        return underground.carve(uncut, window.minY + BEDROCK_MARGIN, ceiling, window, undergroundOptions, salt)
    }

    /**
     * What [underground] takes out of rock that is not ours, and the lakes it stands — what an Age wearing vanilla's
     * rock has cut into it after vanilla's own fill, between this terrain's floor and ceiling. Null where the
     * terrain has no room under it, or the underground takes nothing.
     */
    fun undergroundCutFromVanilla(
        underground: Underground,
        undergroundOptions: Options,
        options: Options,
        window: VerticalWindow,
        salt: Long,
    ): UndergroundCut? {
        if (underground == Underground.NONE || underground == Underground.NOISE_CAVES) return null
        val ceiling = undergroundCeiling(options, salt) ?: return null
        val whole = Slab(lowY = window.minY, highY = window.topY)
        val carved = underground.carve(whole, window.minY + BEDROCK_MARGIN, ceiling, window, undergroundOptions, salt)
        return UndergroundCut(void = Subtract(whole, carved.shape), lakes = carved.wet)
    }

    /** What an underground takes out of rock that is not ours, and the lakes standing in it. */
    data class UndergroundCut(val void: TerrainField, val lakes: TerrainField?)

    /**
     * The band of world this terrain's underground is **indoors** in, or null where it has none.
     *
     * Which undergrounds count as indoors is [Underground.indoorBand]'s question; this answers only whether
     * there is room under the landform at all.
     */
    fun undergroundBand(
        underground: Underground,
        window: VerticalWindow,
        options: Options,
        salt: Long,
        sea: SeaSetting? = null,
    ): IntRange? =
        undergroundCeiling(options, salt, sea)?.let { ceiling -> underground.indoorBand(window.minY + BEDROCK_MARGIN, ceiling) }

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
     * overhead. [NETHER] is here because vanilla's nether rock lays a bedrock ceiling of its own, so no
     * book can open it to the sky. [CANYON] is the one that looks like it belongs here and does not: its plateau reaches the
     * ceiling, but the gorge is open to the sky and that is the whole landform.
     */
    val roofsTheWorld: Boolean get() = this == SOLID || this == NETHER

    /**
     * Water this terrain carries **itself**, or null where a waterline is all it needs.
     *
     * A river system's water follows its own beds, which run downhill everywhere, so no single level can
     * pour it — see `SeaFill.wet`. Most terrains carry none, and a new one should not have to say so.
     */
    /**
     * Features this landform carries of its own, as claims added to whatever the book asked for: the
     * islands' ponds (Jonah, 2026-10-06), vanilla's surface lake filled with water, which lies on the ground
     * where it falls and will not place where it would spill.
     */
    val carriedFeatures: List<String> get() = if (this == SKYLANDS) listOf(ISLAND_PONDS) else emptyList()

    /**
     * Rivers this terrain runs **of water whatever its sea**, or null for none — the islands' rivers, there
     * whether the Age has a sea or not. See `SkylandsField.rivers`.
     */
    fun rivers(options: Options, salt: Long, table: TerrainTable? = null, sea: SeaSetting? = null): TerrainField? =
        ownRivers(options, salt)?.let { carried(it, options, salt, table, sea) }

    fun standingWater(options: Options, salt: Long, table: TerrainTable? = null, sea: SeaSetting? = null): TerrainField? =
        ownStandingWater(options, salt)?.let { carried(it, options, salt, table, sea) }

    private fun ownRivers(options: Options, salt: Long): TerrainField? =
        if (this == SKYLANDS) SkylandsField.rivers(salt, scaleAsWritten(options, salt)) else null

    private fun ownStandingWater(options: Options, salt: Long): TerrainField? = when (this) {
        RIVERLANDS -> RiverlandsField.water(salt, scaleOf(options, salt), isFormer(options))
        MOUNTAINOUS -> MountainousField.water(salt, scaleOf(options, salt))
        else -> null
    }

    /** Water this landform carries, moved with its rock and varied as it is. */
    private fun carried(water: TerrainField, options: Options, salt: Long, table: TerrainTable?, sea: SeaSetting?): TerrainField =
        varied(lifted(water, options, salt, sea), options, salt, isRock = false, table, sea)

    private fun lifted(water: TerrainField, options: Options, salt: Long, sea: SeaSetting?): TerrainField =
        liftTo(options, salt, sea)?.over(water, keepsFloor = false) ?: water

    /**
     * The water this landform carries, lifted but not yet varied, whose banks its wear leaves alone; null where it
     * carries none, or where the Age was written while the wear still cut through them ([FORMER_WEAR]).
     */
    private fun bankedWater(options: Options, salt: Long, sea: SeaSetting?): TerrainField? {
        if (options.isTrue(FORMER_WEAR)) return null
        val own = ownStandingWater(options, salt) ?: ownRivers(options, salt) ?: return null
        return lifted(own, options, salt, sea)
    }

    /**
     * Whether this landform's ground can be eased into another's where two meet ([Seam.MERGED]): a surface standing
     * on rock below it. Not what hangs in the void or fills the world to its roof, and not the made shapes, which
     * would lean.
     */
    val reconciles: Boolean get() = this in RECONCILES

    /**
     * Where this landform lives in an Age's [TerrainTable] — how uplifted, how old and how wet the country it stands
     * in is — so that territories go to the landform the land suits and neighbours are plausible. Null for what was
     * made or is strange, which keeps to a part of the table drawn for it instead.
     */
    val home: TerrainTable.Home? get() = when (this) {
        MOUNTAINOUS -> TerrainTable.Home(uplift = 0.8, age = -0.5, wetness = 0.2)
        SHATTERED -> TerrainTable.Home(uplift = 0.6, age = -0.5, wetness = -0.6)
        CANYON -> TerrainTable.Home(uplift = 0.6, age = 0.0, wetness = -0.3)
        CANYONLANDS -> TerrainTable.Home(uplift = 0.4, age = 0.2, wetness = -0.6)
        CLIFFS -> TerrainTable.Home(uplift = 0.3, age = -0.4, wetness = 0.4)
        ERODED -> TerrainTable.Home(uplift = 0.2, age = 0.6, wetness = -0.4)
        HILLS -> TerrainTable.Home(uplift = 0.0, age = 0.4, wetness = 0.3)
        RIVERLANDS -> TerrainTable.Home(uplift = -0.1, age = 0.3, wetness = 0.7)
        CRATERLANDS -> TerrainTable.Home(uplift = -0.2, age = 0.7, wetness = -0.5)
        CONTINENTS -> TerrainTable.Home(uplift = -0.3, age = 0.3, wetness = 0.1)
        FLATLANDS -> TerrainTable.Home(uplift = -0.4, age = 0.8, wetness = -0.1)
        PILLARS -> TerrainTable.Home(uplift = -0.5, age = -0.2, wetness = 0.5)
        ISLE -> TerrainTable.Home(uplift = -0.6, age = -0.3, wetness = 0.4)
        ISLANDS -> TerrainTable.Home(uplift = -0.7, age = 0.0, wetness = 0.6)
        PYRAMIDS, SKYLANDS, INVERSE_CAVES, SPIRE_ISLANDS, SOLID, OVERWORLD, NETHER, END -> null
    }

    /** The height this landform's drift stretches from: its waterline, or for the skylands the plane they float at. */
    private fun driftsAbove(options: Options, salt: Long, sea: SeaSetting?): Int? =
        if (this == SKYLANDS) SkylandsField.PLANE_Y else waterlineAt(options, salt, sea)

    /**
     * [field] as this landform changes across the world ([LandVariety]) — the rock, and the water it carries, alike,
     * so a river still lies in its bed; only the rock is worn. Which landforms drift in height, which are worn and
     * which bend is [DRIFTS_IN_HEIGHT], [WORN] and [BENDS].
     */
    private fun varied(
        field: TerrainField,
        options: Options,
        salt: Long,
        isRock: Boolean,
        table: TerrainTable?,
        sea: SeaSetting?,
    ): TerrainField =
        LandVariety.over(
            field,
            stretchesAbove = if (this in DRIFTS_IN_HEIGHT) driftsAbove(options, salt, sea) else null,
            wornAbove = if (isRock && this in WORN) waterlineAt(options, salt, sea) ?: VerticalWindow.MIN_Y else null,
            terraces = this !in UNTERRACED,
            bends = this in BENDS,
            strength = varietyOf(options, salt),
            erosion = erosionOf(options, salt, ordinarily = if (this in MADE) 0.0 else ORDINARY_WEAR),
            landScale = if (hasGroundBeneath) scaleOf(options, salt) else scaleAsWritten(options, salt),
            salt = salt,
            table = table,
            water = if (isRock) bankedWater(options, salt, sea) else null,
        )

    /**
     * **Everything this landform tells the biome layer about itself** — one channel, read only by a
     * grounded Age, and the only place a terrain gets to speak to `Grounding`.
     *
     * One declaration rather than a method per fact. Each of these was its own `when (this)` over every
     * terrain answering false for all but one of them, threaded to the same constructor as a separate
     * argument, and a fourth would have been a fourth of each. What a landform knows about its own shape
     * is one subject and belongs in one table; see [Grounding.Declared] for what the facts mean.
     */
    fun grounding(options: Options, salt: Long, sea: SeaSetting? = null): Grounding.Declared {
        val own = ownGrounding(options, salt)
        val lift = liftTo(options, salt, sea) ?: return own
        return own.copy(
            elevation = own.elevation?.let { it.copy(fromY = lift.heightOf(it.fromY), toY = lift.heightOf(it.toY)) },
        )
    }

    private fun ownGrounding(options: Options, salt: Long): Grounding.Declared = when (this) {
        ISLANDS, ISLE -> Grounding.Declared(hasSandyShores = true)
        CANYON -> Grounding.Declared(waterlineIsRiver = true)
        // Rock to the ceiling has no coast in it anywhere, and vanilla's continentalness curve has no
        // anchor that far over the water — so without this a sealed world files as mid inland and grows
        // plains in its own rock. See [Grounding.Declared.isDeepInland].
        SOLID -> Grounding.Declared(isDeepInland = true)
        // Measured from the basin's *shoulder* rather than its floor: the floor is the bottom of a hollow
        // in the middle of a cell, so datuming there chills the whole country by the depth of its lowest
        // hole and the basins come out snowy. The shoulder is where the plains actually sit.
        MOUNTAINOUS -> Grounding.Declared(elevation = Elevation(fromY = MountainousField.PLAIN_Y, toY = ALPINE_CREST_Y))
        // Datumed on the plain the basin was struck into, which is where the ordinary country is, and
        // topped at the rim crest — a hundred blocks of climb that would otherwise pass through no
        // country at all, the same argument `mountainous` makes.
        CRATERLANDS -> Grounding.Declared(
            elevation = Elevation(fromY = CraterlandsField.PLAIN_Y, toY = CraterlandsField.rimCrestY(craterSteer(options, salt))),
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
        /** The landforms with nothing beneath their land — see [hasGroundBeneath]. */
        private val HANGING_IN_THE_VOID by lazy { setOf(SKYLANDS, SPIRE_ISLANDS, INVERSE_CAVES, END) }

        /**
         * The landforms whose height above their waterline drifts, and rises far higher at a landmark; the skylands'
         * above the plane they float at. Not a canyon, whose plateau is the roof over its underground; nor the
         * mountains, whose waterline is the floor of the world and whose peaks reach its top already; nor the flat or
         * the solid rock, which have no height to drift; nor the tangle, which fills the world. The cleft, the
         * pyramids and the tangle have landmarks of their own instead, which their builders place.
         */
        private val DRIFTS_IN_HEIGHT by lazy {
            setOf(HILLS, ERODED, PILLARS, CLIFFS, CANYONLANDS, SHATTERED, RIVERLANDS, ISLANDS, ISLE, CRATERLANDS, CONTINENTS, SKYLANDS)
        }

        private val RECONCILES by lazy {
            setOf(HILLS, FLATLANDS, ERODED, CLIFFS, CANYONLANDS, SHATTERED, RIVERLANDS, ISLANDS, ISLE, MOUNTAINOUS, CRATERLANDS, CONTINENTS)
        }

        /**
         * The landforms the weather wears, terracing their slopes in patches, gullying them, and undercutting their faces
         * and piling scree under them — every one with a top to wear. Not the flat, which has no slope; nor the solid
         * rock, the tangle or the Spire, whose surfaces are inside them or are the Spire's own.
         */
        private val WORN by lazy {
            setOf(
                HILLS, ERODED, PILLARS, PYRAMIDS, CANYON, CLIFFS, CANYONLANDS, SHATTERED, RIVERLANDS, ISLANDS, ISLE, MOUNTAINOUS,
                CRATERLANDS, CONTINENTS, SKYLANDS,
            )
        }

        /**
         * The worn landforms left unterraced: the mountains, whose slopes are too steep for steps to read as strata;
         * the cleft, whose walls are benched already; and the pyramids, which are built in courses.
         */
        private val UNTERRACED by lazy { setOf(MOUNTAINOUS, CANYON, PYRAMIDS) }

        /** The landforms that were made rather than weathered, which stay unworn unless a word wears them. */
        private val MADE by lazy { setOf(PYRAMIDS) }

        /** The landforms whose outlines bend. Not the made shapes — pillars and pyramids — nor the Spire's islands. */
        private val BENDS by lazy {
            setOf(
                HILLS, ERODED, CANYON, CLIFFS, CANYONLANDS, SHATTERED, RIVERLANDS, ISLANDS, ISLE, MOUNTAINOUS, CRATERLANDS,
                CONTINENTS, SKYLANDS, INVERSE_CAVES,
            )
        }

        private const val NO_FIELD_OF_OURS =
            "vanilla's own rock has no field of ours; AgeGeneration.ourGround is not reached for it"

        /**
         * Whether the seams between territories are torn as wide as instability tears them — `torn landmass`.
         * The aspect's own, so it holds whatever landform is seated.
         */
        val TORN = Parameter.flag("torn", help = "Whether the seams between territories are torn wide open.")

        /**
         * Whether patches of the ground are another country's, as far as instability shifts them —
         * `incongruous landmass` (`Dislocation`).
         */
        val INCONGRUOUS = Parameter.flag(
            "incongruous",
            help = "Whether patches of the ground are the Age's own terrain read from far away.",
        )

        /**
         * How much a landform changes as it is crossed ([LandVariety]): none at the bottom, which is how every Age
         * written before it reads, and twice the unsaid amount at the top.
         */
        val VARIETY = Parameter.ranged(
            "variety",
            help = "How much the land changes as you cross it.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "the same everywhere"),
                Parameter.Landmark(0.0, "as an ordinary world", isVanilla = true),
                Parameter.Landmark(1.0, "changing a great deal"),
            ),
        )

        /**
         * Whether the land is laid as it was before generator 20: unworn whatever [WEAR] says, and with no
         * [TerrainTable], so its territories fall where chance put them and its biomes take vanilla's temperature and
         * humidity. Set on every Age written before then, so the land it has already generated still meets what it
         * generates next. No word sets it.
         */
        val FORMER_LAND = Parameter.flag(
            "former_land",
            help = "Whether the land is laid as it was before it had a table to follow and weather to wear it.",
        )

        /**
         * Whether the wear works as it did before generator 21: through the banks of the water a landform carries, and
         * with no say in the shape of its own features — set on every Age written before then, so the land it has
         * already generated still meets what it generates next. No word sets it.
         */
        val FORMER_WEAR = Parameter.flag(
            "former_wear",
            help = "Whether the weather wears the land as it did before it spared the rivers and shaped each landform's features.",
        )

        /**
         * Whether the landforms lie where chance puts them rather than where the land's table suits them — the
         * patchwork every Age had before there was a table, and the way to the strange mixtures: islands beside
         * mountains, canyons in a sea of hills. The dials and the biomes still follow the table.
         */
        val PATCHWORK = Parameter.flag(
            "patchwork",
            help = "Whether the landforms lie where chance puts them rather than where the land suits them.",
        )

        /**
         * Whether [SIZE] is read as it was before [LandSize] — set on every Age written before generator 15, so the
         * land it has already generated still meets what it generates next. No word sets it.
         */
        val FORMER_SIZES = Parameter.flag(
            "former_sizes",
            help = "Whether the landform's size is read as it was before ordinary land grew to vanilla's scale.",
        )

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
         * (`wentUnheeded` in `age/resolution/Steering.kt`) rather than doing nothing quietly.
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
         * How big the landform is, from a quarter of what was tuned to four times it, unsaid being twice it
         * ([LandSize]). **What grows is each landform's own business**: an island's footprint more than its
         * height, a cave system's noise, a canyon's depth and width over a floor that stays put, a range's
         * spacing with the ice still capping its peaks. The builders say which.
         *
         * **`size`, the same name a sun and a feature use**, because it is the size of the whole thing and
         * not one dimension of it: `colossal archipelagic landmass` is the word a writer would reach for and it
         * costs no word of its own. [MINGLING] keeps its own name for the opposite reason — how finely two
         * rocks speckle together is not how big anything is.
         */
        val SIZE = Parameter.ranged(
            "size",
            help = "How big the landform is, each in its own way: an island's shore, a canyon's depth, a cave's span.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "minuscule"),
                Parameter.Landmark(-0.5, "small"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(0.5, "large"),
                Parameter.Landmark(1.0, "colossal"),
            ),
        )

        /** Where every sea stands in an Age laid out by the table, where its landforms have the room: vanilla's. */
        const val ONE_SEA_LEVEL = ORDINARY_SEA_LEVEL

        /**
         * The sea an Age laid out by the table sets [members] at, each with the options and salt [optionsOf] and
         * [saltOf] give it: shared where more than one landform divides the Age and one of them has a sea of its own.
         */
        fun seaSettingFor(members: List<Terrain>, optionsOf: (Int) -> Options, saltOf: (Int) -> Long): SeaSetting {
            fun hasASeaOfItsOwn(member: Int): Boolean {
                val terrain = members[member]
                val standsOnGround = terrain.footing(optionsOf(member), saltOf(member)) != null
                val own = terrain.ownWaterlineAt(optionsOf(member), saltOf(member))
                return standsOnGround && own != null && own > VerticalWindow.MIN_Y
            }
            val isDivided = members.size > 1
            return SeaSetting(ONE_SEA_LEVEL, isShared = isDivided && members.indices.any(::hasASeaOfItsOwn))
        }

        /** Left whole, so the bedrock a cave might otherwise open through stays bedrock. */
        private const val BEDROCK_MARGIN = 5

        /** Rock over the top storey, where a landform's own datum is the ground it has to stay under. */
        private const val ROOM_FOR_A_ROOF = 12

        // A seabed and a riverbed are cut *into* the ground rather than standing on it, so a ceiling set
        // against the water they hold has to clear the bed as well as the level.
        private const val DEEP_ENOUGH_TO_MISS_A_SEABED = 44
        private const val DEEP_ENOUGH_TO_MISS_A_RIVERBED = 36

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
         * The height a column in [MOUNTAINOUS] reads as fully a summit at — around the crest rather than above the
         * tallest massif, so the peak biomes reach the whole crest line and not only its exceptions.
         */
        private const val ALPINE_CREST_Y = 262
    }
}
