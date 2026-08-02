package co.voik.agesandtheart.preview

import co.voik.agesandtheart.sky.SpireSky
import co.voik.agesandtheart.worldgen.AlpsField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.ShatteredField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.Raised
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Ridge
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws an Age's terrain *shape* to PNGs, offline, in about a second. Possible because field evaluation
 * and [Weathering] reach only for noise and plain maths — so it can show **nothing material**: palettes,
 * surface rules and water all live behind the registries. A geometry instrument, and only that.
 *
 * It renders from the **same** [TerrainField] and [Weathering] objects that generate the world, so a
 * preview cannot quietly disagree with the game.
 *
 * Three orthogonal views named for the axis you look down: `view-y.png` from above shaded by height, and
 * `view-z.png`/`view-x.png` as slices. **The slices are the point** — a surface height cannot tell a
 * sheer wall from a gentle slope, and the pair reveals anisotropy in a wind-stretched world.
 */
fun main(arguments: Array<String>) {
    val name = arguments.firstOrNull() ?: "spire"
    val subject = subjects[name] ?: error("Unknown preset '$name'. Try one of: ${subjects.keys.joinToString()}")

    val output = File("build/preview").apply { mkdirs() }
    val solid = solidity(subject)

    ImageIO.write(fromAbove(solid, subject), "png", File(output, "$name-view-y.png"))
    ImageIO.write(sliceAlongZ(solid, subject), "png", File(output, "$name-view-z.png"))
    ImageIO.write(sliceAlongX(solid, subject), "png", File(output, "$name-view-x.png"))

    report(name, solid, subject, output)
}

/** What to draw: a shape, the weathering working on it, and the window to look through. */
private class Subject(
    val field: TerrainField,
    val weathering: Weathering,
    val lowestY: Int,
    val highestY: Int,
    val radius: Int = 128,
    /**
     * How far up the world this subject floats — see `Terrain.ALTITUDE`. Carried here rather than baked
     * into [field] because **a lift has to reach the weathering too**: erosion's keel and band are absolute
     * heights, so a shape raised without its wind sails over the band and comes out unweathered.
     */
    val lift: Int = 0,
    /**
     * How many blocks one sample covers, on all three axes.
     *
     * **The window is a volume of booleans, so its cost is cubic** — a landform a few hundred blocks across
     * renders block for block, and one several thousand across does not fit in an array, let alone in
     * memory. A step is what lets a subject choose its scale: the picture stops showing individual blocks
     * and starts showing the landform, which for a mountain range is the thing being judged anyway.
     *
     * Everything printed and drawn stays in **world** coordinates, so a stepped render is read exactly like
     * an unstepped one — what changes is only how finely it was asked.
     */
    val step: Int = 1,
    /**
     * Where to cut the two slices, in world coordinates, or null to cut wherever the most rock is.
     *
     * **The fullest line is the wrong default for anything with a grain to it.** It finds the line carrying
     * the most rock, which on a mountain range is always a crest — so the cut runs *along* a ridge and draws
     * a plateau, hiding the very cross-section the picture was asked for. Where a landform has a direction,
     * say where to cut it.
     */
    val sliceAtZ: Int? = null,
    val sliceAtX: Int? = null,
    /**
     * Where the window is centred.
     *
     * The origin is the right place to look at a landform that is the same everywhere, and the wrong one
     * for a landform that is a *network*: whether the origin lands on a range or in the middle of a basin
     * is a coin toss, and a close-up that lands in the basin says nothing about the mountains.
     */
    val centreX: Int = 0,
    val centreZ: Int = 0,
) {
    /** How many samples across the window is, and how many up it. */
    val samplesWide: Int get() = (radius * 2) / step
    val samplesHigh: Int get() = (highestY - lowestY) / step + 1

    /** Which sample a world position falls on — the inverse of [worldAlongX], for a named cut. */
    fun sampleAt(world: Int, centre: Int): Int =
        ((world - centre) / step + samplesWide / 2).coerceIn(0, samplesWide - 1)

    /** The world position a sample stands at, on each horizontal axis and on the vertical. */
    fun worldAlongX(sample: Int): Int = (sample - samplesWide / 2) * step + centreX
    fun worldAlongZ(sample: Int): Int = (sample - samplesWide / 2) * step + centreZ
    fun worldYAt(level: Int): Int = lowestY + level * step

    /**
     * The field as it will actually generate. Built through `Weathered.spire`, **the same factory the
     * generator uses**, so there is no second set of numbers to drift.
     */
    fun weathered(): TerrainField {
        val raised = if (lift == 0) field else Raised(field, lift)
        return if (weathering === Weathering.NONE) raised else Weathered.spire(raised, lift)
    }
}

/**
 * The two shapes the divided subjects share, so a fault and a rift read against the same `regions`.
 * Declared **above** [subjects]: top-level properties initialise in file order, and one read from above
 * its declaration is null.
 */
private val dividedTerrains = listOf(NoiseField.hills(), PillarField.world())

/**
 * And one landscape either side, for the three seam-form subjects.
 *
 * The same terrain twice on purpose: it makes the boundary invisible until a form is applied, so each picture
 * shows the form and nothing else. Two *different* shapes already meet at a cliff of their own.
 */
private val twoOfOneTerrain = listOf(NoiseField.hills(), NoiseField.hills())

/**
 * The one territory map they read, since a seam has to be the same seam in every picture.
 *
 * A knife edge, which is what every form but [Seam.FUZZED] draws — see [fuzzedTerritories] for the third.
 */
private val territories = RegionMap(
    members = 2,
    scale = 400.0,
    blend = Seam.SCARP.blendBlocks(400),
    originX = 0,
    originZ = 0,
    seed = 0x4E6109L,
)

/**
 * The same territories fuzzed — the rare form, and the only one that is a *width* rather than a
 * displacement, so the only one visible in the map instead of a node over the shape.
 *
 * **16 blocks wide**, which is worth knowing before reading the render: a band this narrow is a detail of
 * a boundary rather than something you can see from above. See [Seam.WIDEST_FUZZ_BLOCKS].
 */
private val fuzzedTerritories = territories.copy(blend = Seam.FUZZED.blendBlocks(400))

/** The band an "inverse caves" world would stand in — see the `inverse-caves` subject. */
private const val INVERSE_FLOOR = -64
private const val INVERSE_CEILING = 320

private val subjects: Map<String, Subject> = mapOf(
    // Wide enough to hold more than one island, because size and lift variation is a thing you can only
    // see by comparing copies; and tall enough to reach the world ceiling, so a spire that runs into it
    // reads as a clipped flat top rather than as the window's edge.
    //
    // **The floor followed the deck down on 2026-07-29 and must keep following it.** It was 100, which was
    // comfortably below an island when the deck sat at y=190; once the deck dropped to 148 to buy the 2:1
    // split, the hanging spires reached past it and the readout started reporting the *window's* edge as the
    // rock's. A measurement that silently clips is worse than none — 56 sits just under the sea at 63, which
    // is as low as an island is ever allowed to hang.
    // **The window follows the Age's own vertical band**, which for the Spire is `VerticalWindow.LIFTED` —
    // y 0..383 rather than -64..319 — because the recipe pins `altitude=high`. Keeping 320 here would have
    // reported the window's edge as the rock's, the same silent clip the note above warns about.
    "spire" to Subject(
        SpireField.world(), Weathering.SPIRE, lowestY = 56, highestY = 383, radius = 300,
        lift = Terrain.HIGH_ALTITUDE_LIFT,
    ),
    // The same islands with weathering switched off — the pair shows what erosion is actually contributing.
    "spire-nowind" to Subject(
        SpireField.world(), Weathering.NONE, lowestY = 56, highestY = 383, radius = 300,
        lift = Terrain.HIGH_ALTITUDE_LIFT,
    ),
    "hills" to Subject(NoiseField.hills(), Weathering.NONE, lowestY = 20, highestY = 120),
    "pillars" to Subject(PillarField.world(), Weathering.NONE, lowestY = 30, highestY = 185),
    "shapes" to Subject(ShapesField.world(), Weathering.NONE, lowestY = 55, highestY = 130, radius = 200),
    "caverns" to Subject(CavernField.world(), Weathering.NONE, lowestY = -64, highestY = 110),
    // The tunnels on their own. A cave system reads far better as a solid lattice hanging in space than
    // as absence inside a hill, and the slices are where the network's connectedness actually shows.
    "caverns-voids" to Subject(CavernField.caves(), Weathering.NONE, lowestY = -64, highestY = 70),
    "eroded" to Subject(ErodedField.world(), Weathering.NONE, lowestY = 30, highestY = 195, radius = 200),

    // **Read the slice across the bearing, not the plan.** From above a solid world is one flat shade with
    // a ribbon missing from it, which says where the canyon goes and nothing about its shape; the benches,
    // the inner gorge and how steep the whole thing reads are only in the cross-section. The canyon runs
    // north-south here, so `view-x.png` is the one looking along it and `view-z.png` cuts across.
    //
    // The window is the whole of the Age's own band rather than the rock's extent, because the picture's
    // subject is a *depth* — narrowing it to where rock stands would crop the thing being measured.
    "canyon" to Subject(
        CanyonField.world(bearing = "north_south"),
        // Already inside the field, unlike the Spire's — see [CanyonField.world]. Passing it again here
        // would weather the canyon twice with two different winds.
        Weathering.NONE,
        lowestY = -64,
        highestY = CanyonField.WORLD_CEILING,
        radius = 400,
    ),

    // **Read the plan view here, not the slice.** The cliff's profile is one step and says nothing; what
    // the preset lives or dies on is whether the coast has bays and headlands or comes out a ruled line,
    // and that is only visible from above. The window opens to the world's floor because the seabed is
    // most of what the picture contains.
    "cliffs" to Subject(
        CliffField.world(bearing = "north_south"),
        Weathering.NONE,
        lowestY = -64,
        highestY = CliffField.PLATEAU_Y + 32,
        radius = 500,
    ),

    // The same cliff unweathered. The pair matters more here than anywhere: a step is a ruled face until
    // something breaks it, so this is the picture that says whether the weather is doing its job.
    "cliffs-nowind" to Subject(
        CliffField.bareWorld(bearing = "north_south"),
        Weathering.NONE,
        lowestY = -64,
        highestY = CliffField.PLATEAU_Y + 32,
        radius = 500,
    ),

    // **Read the plan view.** What canyonlands is for is what is *left standing* — the mesas between the
    // three families — and the cross-section can only ever show one arbitrary transect of that.
    // The window is wide enough to hold several of the spacing, or a family reads as a single canyon.
    "canyonlands" to Subject(
        CanyonlandsField.world(),
        Weathering.NONE,
        lowestY = -64,
        highestY = CanyonlandsField.PLATEAU_Y + 16,
        radius = 900,
    ),

    // **The plan view is the whole picture.** A cracked plate has no interesting cross-section: every
    // transect is one canyon or none. What is worth reading is whether the cells look like cells.
    "shattered" to Subject(
        ShatteredField.world(),
        Weathering.NONE,
        lowestY = -64,
        highestY = ShatteredField.PLATEAU_Y + 16,
        radius = 900,
    ),

    // **Read the slices, and only the slices.** Caves are absence inside rock: from above a hollowed
    // riverlands is a riverlands, and every cave in it is invisible. The pair with `riverlands` above is
    // the whole point — same shape, one of them hollow.
    "riverlands-caved" to Subject(
        Caved.of(RiverlandsField.world(), 0xCA_7E5L, -59, 320),
        Weathering.NONE,
        lowestY = -64,
        highestY = RiverlandsField.LAND_Y + 60,
        radius = 360,
    ),

    // The caves alone, hanging in space — a cave system reads far better as a solid lattice than as
    // absence inside a hill, which is the lesson `caverns-voids` already paid for.
    // The cave volume on its own, standing in open air rather than cut out of anything — what an
    // "inverse caves" Age would be. `Subtract(slab, Caved(slab))` is the whole shape: no new node.
    "inverse-caves" to Subject(
        Subtract(
            Slab(lowY = INVERSE_FLOOR, highY = INVERSE_CEILING),
            Caved.of(Slab(lowY = INVERSE_FLOOR, highY = INVERSE_CEILING), 0xCA_7E5L, INVERSE_FLOOR, INVERSE_CEILING),
        ),
        Weathering.NONE,
        lowestY = INVERSE_FLOOR,
        highestY = INVERSE_CEILING,
        radius = 200,
    ),
    "riverlands-caves" to Subject(
        Subtract(RiverlandsField.world(), Caved.of(RiverlandsField.world(), 0xCA_7E5L, -59, 320)),
        Weathering.NONE,
        lowestY = -64,
        highestY = RiverlandsField.LAND_Y + 60,
        radius = 360,
    ),

    // **The plan view is the picture.** What a drainage network is for is the branching, and a slice
    // shows one arbitrary valley. Wide, because a catchment is several reaches across and the thing worth
    // seeing is streams joining into trunks.
    "riverlands" to Subject(
        RiverlandsField.world(),
        Weathering.NONE,
        lowestY = 0,
        highestY = RiverlandsField.LAND_Y + 60,
        radius = 700,
    ),

    // **Read the slice across the range, and read it first.** A mountain range's whole claim is its
    // cross-section — foreland, foothills, crest — and the plan view can only show where the valleys went.
    // The range runs north–south, so `view-z.png` is the transect that matters.
    //
    // Wide enough to hold the axis and one whole flank out to the foreland, which is what the wedge is.
    "alps" to Subject(
        AlpsField.world(),
        // Already inside the field, as a canyon's is — passing it again would weather the range twice.
        Weathering.NONE,
        lowestY = -64,
        highestY = 300,
        // Wide enough to hold a couple of the network's cells, which is what the picture is for — one cell
        // says nothing about whether the ranges close round it.
        radius = 5200,
        // The window is a volume of booleans, so this is the first subject that cannot be drawn block for
        // block: at a step of one it would be seventy billion of them. Eight still resolves a range.
        step = 8,
        // Cut straight across the range. The fullest row would run along the crest and show a plateau.
        sliceAtZ = 0,
    ),

    // The same range before the frost reaches it. The pair says whether the weathering is doing anything at
    // this scale, and on a landform whose shape is already made of planes that is a real question.
    "alps-nowind" to Subject(
        AlpsField.bareWorld(),
        Weathering.NONE,
        lowestY = -64,
        highestY = 300,
        radius = 2600,
        step = 4,
        sliceAtZ = 0,
    ),

    // **A valley or two, close enough to read** — and centred on a range rather than on the origin, which
    // since the ranges became a network lands in a basin as often as not. The wide subject shows where the
    // country's mountains are; this shows what one is made of: trough cross-sections, cirques at the heads,
    // and whether the hillslopes really do meet in a crest rather than a dome.
    "alps-core" to Subject(
        AlpsField.world(),
        Weathering.NONE,
        lowestY = -64,
        highestY = 300,
        radius = 1150,
        step = 3,
        sliceAtZ = 0,
        sliceAtX = 1040,
        // On one of the ranges the wide view shows, rather than in the basin the origin happens to sit in.
        centreX = 1040,
    ),

    // **One island, not the archipelago.** The islands lie thousands of blocks apart, and this renders a
    // whole volume — a window wide enough to hold two of them is billions of booleans. So what this shows
    // is the thing a picture can show: that an island is a bounded object with a coast and a sea round it.
    // That they never touch is arithmetic, and `IslandsCheck` asserts it instead of drawing it.
    "islands" to Subject(
        IslandsField.world(extent = IslandsField.Extent.BROAD.key),
        Weathering.NONE,
        lowestY = 20,
        highestY = IslandsField.SEA_LEVEL + 120,
        radius = 1100,
    ),

    // The same islands **composed from the toolkit** rather than written as a node — read it against
    // `islands` above. Same window, so the two pictures are directly comparable.
    "islands-clustered" to Subject(
        IslandsField.clustered(extent = IslandsField.Extent.BROAD.key),
        Weathering.NONE,
        lowestY = 20,
        highestY = IslandsField.SEA_LEVEL + 120,
        radius = 1100,
    ),

    "canyonlands-nowind" to Subject(
        CanyonlandsField.bareWorld(),
        Weathering.NONE,
        lowestY = -64,
        highestY = CanyonlandsField.PLATEAU_Y + 16,
        radius = 900,
    ),

    // The same canyon before the weather reaches it — the pair is what shows what erosion contributes,
    // exactly as `spire-nowind` does. Here it is the difference between benches and ruled contours.
    "canyon-nowind" to Subject(
        CanyonField.bareWorld(bearing = "north_south"),
        Weathering.NONE,
        lowestY = -64,
        highestY = CanyonField.WORLD_CEILING,
        radius = 400,
    ),

    // Two terrains sharing a world. The top-down view is the one to read: it shows the territories and
    // what the seam does to whatever it cuts through. Region size here is the default one, so this is
    // what an Age written in a default world looks like.
    "regions" to Subject(
        Regions(members = dividedTerrains, map = territories),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),

    // **`fault`, `rift` and `fuzz` are the three forms a seam can take, and they are a set to read together.**
    // Each is `Seam.SCARP`, `Seam.RIFT` or `Seam.FUZZED` applied to the SAME two territories, so the pictures
    // differ by nothing but the form. Whether a scarp reads as drama or as breakage is the entire acceptance
    // test for Phase 4.5 step 9, and these three plus `hills` are what it is read from.

    // A scarp: `Seam.SCARP`. **Read against `hills` above**, and note that both territories are the same
    // terrain — the point of the picture rather than laziness. The first version divided hills from pillars,
    // as `regions` does, and showed nothing: those two already stand about ninety blocks apart, so a throw of
    // thirty-two disappeared into a step that was there anyway (design §3.4's "terrain-vs-terrain faults
    // already happen", met from the wrong end). With one landscape either side the seam is invisible without
    // a throw and a clean sixty-four-block cliff with one, so the picture shows the node and nothing else.
    //
    // The window opens by the throw at both ends, so a thrown territory cannot be clipped by the *picture* and
    // read as clipped by the world — the silent-clip trap `spire`'s floor note warns about.
    "fault" to Subject(
        Fault(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            // The same helper the generator uses, so the picture cannot disagree about which side rises.
            throws = Fault.alternatingThrows(members = 2, throwBlocks = Terrain.SCARP_THROW, seed = 1L),
        ),
        Weathering.NONE,
        lowestY = 20 - Terrain.SCARP_THROW,
        highestY = 120 + Terrain.SCARP_THROW,
        radius = 420,
    ),

    // A rift: `Seam.RIFT`. The top-down view shows how wide the band comes out and how far it runs; the slices
    // show it as a chasm rather than as a stripe of missing map. Two shapes here rather than one, since a
    // chasm cutting through both is what a written Age will usually look like.
    //
    // **`FaultCheck` prints the band's width in blocks**, which is the number to tune
    // `Rift.DEFAULT_HALF_WIDTH` against — the seam distance it is measured in is proportionate rather than
    // surveyed, so what the constant means on the ground is something to read off, not to reason about.
    "wall" to Subject(
        Ridge.raised(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            footingY = Terrain.WALL_FOOTING,
            crestY = Terrain.WALL_CREST,
        ),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        // The same window as `rift`, so the two forms can be read against each other.
        radius = 420,
    ),

    "rift" to Subject(
        Rift.opened(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            floorY = Terrain.RIFT_FLOOR,
            rimY = Terrain.RIFT_RIM,
        ),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),

    // The fuzz: `Seam.FUZZED`, the rare form and the only one that is a *width*. There is no node — the whole
    // of it is in the map, so this is a plain `Regions` over a blended one, and what to look at is the band
    // where the two shapes dissolve into each other instead of meeting. Read it against `regions`, which is
    // the same pair knife-edged.
    //
    // **Two DIFFERENT shapes here, where `fault` needs one shape twice, and the asymmetry is the finding.**
    // Fuzzing one terrain against itself is invisible by construction: interlocking two identical shapes
    // column by column reproduces that shape exactly, and the first version of this subject came out as plain
    // hills. So the three forms are not quite peers — a scarp and a rift *make* geology and show up between
    // any two territories, while the fuzz only softens a boundary that was already there and does nothing
    // wherever the two sides happen to be similar. Worth knowing before reading "5% of Ages get a fuzzed
    // border" as though it always delivered as much as the other two.
    //
    // **Worth reading against `fault` in particular**, because the pair is the argument for the whole reshape:
    // these two used to be able to happen at once, and the combination threw the interlocking columns
    // alternately up and down into a picket fence of one-block spikes. A seam is one form or the other now.
    "fuzz" to Subject(
        Regions(members = dividedTerrains, map = fuzzedTerritories),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),
)

/**
 * Solidity for the whole window, resolved once. **Asks the very field generation asks** — applying the
 * weathering rule itself was a faithful mirror only while the two stayed identical, and once erosion
 * learned to spare a column by how thick its rock stands the preview showed a world nobody would generate.
 */
private fun solidity(subject: Subject): BooleanArray {
    val width = subject.samplesWide
    val solid = BooleanArray(width * width * subject.samplesHigh)
    val shape = subject.weathered()

    for (imageX in 0..<width) {
        val worldX = subject.worldAlongX(imageX)
        for (imageZ in 0..<width) {
            val worldZ = subject.worldAlongZ(imageZ)
            val spans = shape.columnSpans(worldX, worldZ)
            if (spans.ranges.isEmpty()) continue
            for (level in 0..<subject.samplesHigh) {
                if (!spans.contains(subject.worldYAt(level))) continue
                solid[index(subject, imageX, level, imageZ)] = true
            }
        }
    }
    return solid
}

private fun index(subject: Subject, imageX: Int, level: Int, imageZ: Int): Int {
    val width = subject.samplesWide
    return level * width * width + imageZ * width + imageX
}

/**
 * Looking down: the topmost solid block per column, shaded across **the range the rock actually
 * occupies** rather than the whole window — ranging over the window makes every surface the same
 * brightness and hides the height variation this is drawn to show.
 */
private fun fromAbove(solid: BooleanArray, subject: Subject): BufferedImage {
    val width = subject.samplesWide
    val tops = Array(width) { imageX ->
        IntArray(width) { imageZ ->
            (subject.samplesHigh - 1 downTo 0)
                .firstOrNull { solid[index(subject, imageX, it, imageZ)] }
                ?.let { subject.worldYAt(it) } ?: Int.MIN_VALUE
        }
    }
    val standing = tops.flatMap { row -> row.filter { it != Int.MIN_VALUE } }
    val lowest = standing.minOrNull() ?: 0
    val highest = standing.maxOrNull() ?: 1
    println("  rock stands between y=$lowest and y=$highest")

    return draw(width, width) { imageX, imageZ ->
        val top = tops[imageX][imageZ]
        if (top == Int.MIN_VALUE) EMPTY else heightShade(top, lowest, highest)
    }
}

/**
 * Looking along Z: a vertical cut, the clearest read on wall versus slope.
 *
 * Cut through the *fullest* row rather than the origin. A fixed cut is a trap — the first version sliced
 * at z=0, landed in a gap between two masses, and drew a blank image that looked like a bug.
 */
private fun sliceAlongZ(solid: BooleanArray, subject: Subject): BufferedImage {
    val row = subject.sliceAtZ?.let { subject.sampleAt(it, subject.centreZ) }
        ?: fullest(subject) { imageX, level, imageZ -> solid[index(subject, imageX, level, imageZ)] }
    println("  slice along Z cuts world z=${subject.worldAlongZ(row)}")
    return draw(subject.samplesWide, subject.samplesHigh) { imageX, pixelRow ->
        val level = subject.samplesHigh - 1 - pixelRow
        shadeSlice(solid[index(subject, imageX, level, row)], subject.worldYAt(level))
    }
}

/** Looking along X: the perpendicular cut. Against [sliceAlongZ] it exposes wind anisotropy. */
private fun sliceAlongX(solid: BooleanArray, subject: Subject): BufferedImage {
    val column = subject.sliceAtX?.let { subject.sampleAt(it, subject.centreX) }
        ?: fullest(subject) { imageZ, level, imageX -> solid[index(subject, imageX, level, imageZ)] }
    println("  slice along X cuts world x=${subject.worldAlongX(column)}")
    return draw(subject.samplesWide, subject.samplesHigh) { imageZ, pixelRow ->
        val level = subject.samplesHigh - 1 - pixelRow
        shadeSlice(solid[index(subject, column, level, imageZ)], subject.worldYAt(level))
    }
}

/** The line through the window carrying the most rock, so a cut always has something to show. */
private fun fullest(subject: Subject, isSolid: (Int, Int, Int) -> Boolean): Int {
    val width = subject.samplesWide
    var bestLine = width / 2
    var bestCount = -1
    for (line in 0..<width) {
        var count = 0
        for (across in 0..<width) {
            for (level in 0..<subject.samplesHigh) {
                if (isSolid(across, level, line)) count++
            }
        }
        if (count > bestCount) {
            bestCount = count
            bestLine = line
        }
    }
    return bestLine
}

/** Solid rock light, air dark, with a faint rule every [GRID_SPACING] blocks so heights can be read off. */
private fun shadeSlice(isSolid: Boolean, worldY: Int): Int = when {
    isSolid -> ROCK
    worldY % GRID_SPACING == 0 -> GRID
    else -> EMPTY
}

private fun heightShade(top: Int, lowest: Int, highest: Int): Int {
    val fraction = (top - lowest).toDouble() / (highest - lowest).coerceAtLeast(1)
    val level = (LOWEST_SHADE + (HIGHEST_SHADE - LOWEST_SHADE) * fraction).toInt().coerceIn(0, 255)
    return (level shl 16) or (level shl 8) or level
}

/** Every pixel is [MAGNIFY] blocks square, so a 1:1 render is not too small to read. */
private fun draw(blocksWide: Int, blocksHigh: Int, shade: (Int, Int) -> Int): BufferedImage {
    val image = BufferedImage(blocksWide * MAGNIFY, blocksHigh * MAGNIFY, BufferedImage.TYPE_INT_RGB)
    for (blockX in 0..<blocksWide) {
        for (blockY in 0..<blocksHigh) {
            val colour = shade(blockX, blockY)
            for (offsetX in 0..<MAGNIFY) {
                for (offsetY in 0..<MAGNIFY) {
                    image.setRGB(blockX * MAGNIFY + offsetX, blockY * MAGNIFY + offsetY, colour)
                }
            }
        }
    }
    return image
}

/** Numbers worth having next to the pictures: how much rock stands, and how tall it gets. */
private fun report(name: String, solid: BooleanArray, subject: Subject, output: File) {
    val width = subject.samplesWide
    val columns = width * width
    val tops = ArrayList<Int>()

    for (imageX in 0..<width) {
        for (imageZ in 0..<width) {
            val top = (subject.samplesHigh - 1 downTo 0)
                .firstOrNull { solid[index(subject, imageX, it, imageZ)] }
            if (top != null) tops += subject.worldYAt(top)
        }
    }
    val standing = solid.count { it }
    val area = if (tops.isEmpty()) "no rock in window" else "${tops.size * 100 / columns}% of columns hold rock"
    val peak = tops.maxOrNull()?.toString() ?: "—"
    println("$name: $area, $standing solid samples, tallest y=$peak")
    reportTops(tops)
    reportResistance(subject)
    val sampled = if (subject.step == 1) "" else ", sampled every ${subject.step} blocks"
    println("  window ±${subject.radius} blocks, y ${subject.lowestY}..${subject.highestY}$sampled")
    println("  wrote ${output.absolutePath}/$name-view-{y,z,x}.png")
}

/**
 * Where the rock's *surfaces* actually sit — a different question from how tall the tallest column is.
 * **Reading altitude off the single peak is misleading by a wide margin**: `PEAK_CEILING` said 296 while
 * most island tops were nowhere near it. A percentile spread plus a count of what breaks each cloud deck
 * is the readout that answers the brief.
 */
private fun reportTops(tops: List<Int>) {
    if (tops.isEmpty()) return
    val sorted = tops.sorted()
    fun at(fraction: Double) = sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1)]
    val overUpper = tops.count { it > UPPER_CLOUD_DECK }
    val overLower = tops.count { it > LOWER_CLOUD_DECK }
    println("  column tops: p10 ${at(0.10)}, median ${at(0.50)}, p90 ${at(0.90)}, p99 ${at(0.99)}")
    println(
        "  above the lower cloud deck ($LOWER_CLOUD_DECK): ${overLower * 100 / tops.size}%" +
            ", above the upper ($UPPER_CLOUD_DECK): ${overUpper * 100 / tops.size}%"
    )
}

// The Spire's own deck heights, read rather than copied: they used to be mirrored here because the only
// place they existed was a client-side renderer, and now they are data in `common` like the rest of its
// sky. Only ever read for the printed comparison above — nothing here generates against them.
private val UPPER_CLOUD_DECK = SpireSky.UPPER_DECK_HEIGHT
private val LOWER_CLOUD_DECK = SpireSky.LOWER_DECK_HEIGHT

/**
 * The spread of the resistance noise, which every threshold in [Weathering] is judged against. Worth
 * printing because guessing it is how thresholds end up an order of magnitude out: values that look
 * decisive against an assumed ±0.1 do nothing against a real ±0.4.
 */
private fun reportResistance(subject: Subject) {
    if (subject.weathering === Weathering.NONE) return
    val samples = ArrayList<Double>()
    for (worldX in -subject.radius..<subject.radius step 4) {
        for (worldZ in -subject.radius..<subject.radius step 4) {
            samples += subject.weathering.resistanceAt(worldX, subject.weathering.keelY, worldZ)
        }
    }
    samples.sort()
    fun at(fraction: Double) = "%+.3f".format(samples[(samples.size * fraction).toInt().coerceAtMost(samples.size - 1)])
    println("  resistance: min ${at(0.0)}, p10 ${at(0.10)}, median ${at(0.50)}, p90 ${at(0.90)}, max ${at(0.999)}")
    println("  a threshold must sit inside that range to select anything")
}

private const val MAGNIFY = 2
private const val GRID_SPACING = 16
private const val ROCK = 0xF0F0F0
private const val EMPTY = 0x101418
private const val GRID = 0x1E2630
private const val LOWEST_SHADE = 70.0
private const val HIGHEST_SHADE = 255.0
