package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.VerticalWindow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * **What the water actually is, in a real hills Age, drawn as a cross-section.**
 *
 * So this prints rather than asserts. It builds the landform `age hills landmass` gets at seed 4242 — the
 * terrain salted as [AgeGeneration.saltFor] salts it, which is what makes this the world a walk stands in
 * rather than a neighbour of it — and reads the same aquifer the fill and the carvers ask. Vanilla's carvers
 * cut after the fill, so drawing the water alone shows none of their caves, where the verdict is what both
 * passes consult and is defined everywhere.
 *
 * Read the verdict map first, then the inputs. A region boundary that is dead straight is a discrete
 * level switching on a predicate; the tables say which predicate.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class AquiferSectionCheck : FunSpec({

    val window = VerticalWindow.DEFAULT
    val ground by lazy {
        MinecraftRegistries.ensureStoodUp()
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, TERRAIN_SALT)
    }
    val uncut by lazy { ground.hollows ?: error("noise caves should leave the uncut rock as its hollows") }
    val table by lazy { WaterTable.matching(SeaFill.of(Blocks.WATER.defaultBlockState(), SEA_LEVEL), SEA_LEVEL, SEED) }

    /** As the generator asks it: the carved rock, and the rock before the caves were cut. */
    fun aquifer(): Aquifer = table.aquiferOver(ground.shape, uncut)

    /** As it was asked before stamp 43, judging the sea from the carved rock alone. */
    fun carvedOnlyAquifer(): Aquifer = table.aquiferOver(ground.shape)

    fun holdsWater(aquifer: Aquifer, worldX: Int, worldY: Int, worldZ: Int): Boolean {
        val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
        return put != null && !put.fluidState.isEmpty
    }

    fun isHollow(worldX: Int, worldY: Int, worldZ: Int): Boolean =
        uncut.columnSpans(worldX, worldZ).contains(worldY) && !ground.shape.columnSpans(worldX, worldZ).contains(worldY)

    /** The water in one column's hollows, as runs of y — the same reading `/age probe` gives of the blocks. */
    fun wetRuns(aquifer: Aquifer, worldX: Int, worldZ: Int): String {
        val wet = (window.minY..<window.topY).filter { y -> isHollow(worldX, y, worldZ) && holdsWater(aquifer, worldX, y, worldZ) }
        if (wet.isEmpty()) return "none"
        val runs = mutableListOf<IntRange>()
        for (y in wet) {
            val last = runs.lastOrNull()
            if (last != null && last.last == y - 1) runs[runs.lastIndex] = last.first..y else runs += y..y
        }
        return runs.joinToString { "${it.first}..${it.last}" }
    }

    /**
     * What a walk would see at a block, following the fill's own `when`: rock, then the aquifer inside a
     * hollow — rock where it answers null, and walled off where it came out dry against the sea, as
     * `heldBackFrom` does — then the sea below the waterline, then air.
     */
    fun shownAt(aquifer: Aquifer, worldX: Int, worldZ: Int, worldY: Int): Char {
        fun isRock(x: Int, z: Int, y: Int) = ground.shape.columnSpans(x, z).contains(y)
        fun isSea(x: Int, z: Int, y: Int) = !isRock(x, z, y) && !isHollow(x, y, z) && y < SEA_LEVEL
        fun seaTouching() = isSea(worldX, worldZ, worldY + 1) ||
            isSea(worldX - 1, worldZ, worldY) || isSea(worldX + 1, worldZ, worldY) ||
            isSea(worldX, worldZ - 1, worldY) || isSea(worldX, worldZ + 1, worldY)
        return when {
            isRock(worldX, worldZ, worldY) -> '#'
            isHollow(worldX, worldY, worldZ) -> {
                val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
                when {
                    put == null -> 'B'
                    !put.fluidState.isEmpty -> 'W'
                    seaTouching() -> '#'
                    else -> '.'
                }
            }
            worldY < SEA_LEVEL -> 'W'
            else -> '.'
        }
    }

    /**
     * **The column-by-column reading at the wall walk W6 found** after the `perchedLevel` fix: a cave from
     * y 10 to 81 that breaks through to the sky on one side and keeps a single block of roof on the other.
     * `/age probe` on the real w6b read the carved top as 9 and 82 either side of x = -21 | -20, and the
     * water as y 10..62 against y 10..23.
     */
    test("the column state either side of the W6 wall, before and after the uncut surface") {
        println("  seed $SEED, hills, z=$WALL_Z — the wall is between x=${WALL_X - 1} and x=$WALL_X")
        println("     x  carved top  uncut top  water, carved only          water, as generated")
        val before = carvedOnlyAquifer()
        val after = aquifer()
        for (worldX in (WALL_X - 4)..(WALL_X + 4)) {
            val carvedTop = ground.shape.columnSpans(worldX, WALL_Z).highestSolidY
            val uncutTop = uncut.columnSpans(worldX, WALL_Z).highestSolidY
            println(
                "  ${worldX.toString().padStart(5)}  ${(carvedTop ?: -999).toString().padStart(10)}" +
                    "  ${(uncutTop ?: -999).toString().padStart(9)}" +
                    "  ${wetRuns(before, worldX, WALL_Z).padEnd(26)}  ${wetRuns(after, worldX, WALL_Z)}",
            )
        }
    }

    /**
     * **How tall the water's exposed faces get, over ground wide enough to be representative** — the
     * measurement a fix has to earn its place against.
     *
     * A face is water standing beside open air at the same height, which is the only arrangement a walk can
     * actually see. Height is what makes one offensive: a pool's edge steps by ones and twos up a sloping
     * floor, where the reported failures were sheer drops of forty.
     */
    test("how tall the water's exposed faces get, for reading") {
        fun census(label: String, aquifer: Aquifer) {
            val faces = mutableListOf<Int>()
            val tall = mutableListOf<Triple<Int, Int, Int>>()
            var onAir = 0
            for (worldZ in -600..600 step 97) {
                for (worldX in -600..600 step 3) {
                    var running = 0
                    for (y in SCAN_BOTTOM..SCAN_TOP) {
                        // Water with open air straight under it, which falls the moment the chunk ticks.
                        val standsOnAir = shownAt(aquifer, worldX, worldZ, y + 1) == 'W' && shownAt(aquifer, worldX, worldZ, y) == '.'
                        if (standsOnAir) onAir++
                        val exposed = shownAt(aquifer, worldX, worldZ, y) == 'W' && shownAt(aquifer, worldX + 1, worldZ, y) == '.'
                        if (exposed) {
                            running++
                        } else {
                            if (running > 3) tall += Triple(worldX, y - 1, worldZ)
                            if (running > 0) faces += running
                            running = 0
                        }
                    }
                    if (running > 0) faces += running
                }
            }
            println("  $label: ${faces.size} exposed water faces; ${faces.count { it > 3 }} over 3 blocks tall; tallest ${faces.maxOrNull() ?: 0}")
            println("    $onAir blocks of water stand directly on open air")
            println("    heights: ${faces.groupingBy { it }.eachCount().toSortedMap().entries.take(12).joinToString()}")
            tall.take(TALL_LISTED).forEach { (worldX, topY, worldZ) ->
                println("    a tall face tops out at $worldX, $topY, $worldZ")
            }
        }
        census("as generated", aquifer())
    }

    /** A section through any line of columns, drawn the way the fill would lay it — see [shownAt]. */
    fun printSection(label: String, columns: List<Pair<Int, Int>>, top: Int, bottom: Int) {
        val after = aquifer()
        println("  $label    W water  . open air  # rock  B barrier")
        for (y in top downTo bottom) {
            val row = StringBuilder()
            for ((worldX, worldZ) in columns) row.append(shownAt(after, worldX, worldZ, y))
            println("  ${y.toString().padStart(4)} $row")
        }
    }

    /** **Every room in a column**, with the forty-block band its floor sits in. */
    fun printRooms(worldX: Int, worldZ: Int) {
        val carved = ground.shape.columnSpans(worldX, worldZ)
        val uncutTop = uncut.columnSpans(worldX, worldZ).highestSolidY
        val surface = maxOf(carved.highestSolidY ?: SEA_LEVEL, uncutTop ?: Int.MIN_VALUE)
        val submerged = surface < SEA_LEVEL
        val after = aquifer()
        println(
            "  ($worldX, $worldZ)  rock ${carved.ranges.joinToString { "${it.first}..${it.last}" }}" +
                "  uncut top $uncutTop  submerged $submerged  water ${wetRuns(after, worldX, worldZ)}",
        )
        val ranges = carved.ranges
        for (index in ranges.indices) {
            val roomFloor = ranges[index].last + 1
            val ceiling = ranges.getOrNull(index + 1)?.first
            if (!isHollow(worldX, roomFloor, worldZ)) continue
            println("      room ${roomFloor}..${(ceiling ?: surface + 1) - 1}  band ${Math.floorDiv(roomFloor, 40)}")
        }
    }

    /**
     * **Where the barriers stand, seen from above, and whether they follow the chunk grid** — which is what a
     * walk at stamp 44 found: every level boundary on a chunk line, so every dam traced a square.
     *
     * One character a column: `#` where a barrier stands anywhere in its hollows, `W` where water does and no
     * barrier, `.` elsewhere. Then each pair of neighbouring columns where water meets a barrier at one height
     * is counted, with how many of those pairs straddle a chunk line — one in sixteen if the edges fall
     * anywhere, nearly all of them if they follow the grid.
     */
    test("where the barriers stand, seen from above, for reading") {
        val after = aquifer()
        val heights = SCAN_BOTTOM..SCAN_TOP

        fun statesOf(worldX: Int, worldZ: Int): ByteArray {
            val carved = ground.shape.columnSpans(worldX, worldZ)
            val before = uncut.columnSpans(worldX, worldZ)
            return ByteArray(heights.count()) { up ->
                val worldY = heights.first + up
                val hollow = before.contains(worldY) && !carved.contains(worldY)
                val put = if (hollow) {
                    after.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
                } else {
                    Blocks.AIR.defaultBlockState()
                }
                when {
                    put == null -> BARRIER
                    !put.fluidState.isEmpty -> WATER
                    else -> NEITHER
                }
            }
        }

        val wide = PLAN_EAST - PLAN_WEST + 2
        val deep = PLAN_SOUTH - PLAN_NORTH + 2
        val states = Array(wide) { east -> Array(deep) { south -> statesOf(PLAN_WEST + east, PLAN_NORTH + south) } }

        fun meet(one: ByteArray, other: ByteArray): Boolean = one.indices.any { up ->
            val waterAgainstBarrier = one[up] == WATER && other[up] == BARRIER
            val barrierAgainstWater = one[up] == BARRIER && other[up] == WATER
            waterAgainstBarrier || barrierAgainstWater
        }

        var pairs = 0
        var onChunkLines = 0
        for (east in 0..<wide - 1) {
            for (south in 0..<deep - 1) {
                val here = states[east][south]
                if (meet(here, states[east + 1][south])) {
                    pairs++
                    if (Math.floorMod(PLAN_WEST + east, CHUNK_WIDTH) == CHUNK_WIDTH - 1) onChunkLines++
                }
                if (meet(here, states[east][south + 1])) {
                    pairs++
                    if (Math.floorMod(PLAN_NORTH + south, CHUNK_WIDTH) == CHUNK_WIDTH - 1) onChunkLines++
                }
            }
        }

        println("  seed $SEED, hills, ($PLAN_WEST, $PLAN_NORTH) to ($PLAN_EAST, $PLAN_SOUTH):  # barrier  W water  . neither")
        for (south in 0..<deep - 1) {
            val row = StringBuilder()
            for (east in 0..<wide - 1) {
                val column = states[east][south]
                row.append(
                    when {
                        BARRIER in column -> '#'
                        WATER in column -> 'W'
                        else -> '.'
                    },
                )
            }
            println("  ${(PLAN_NORTH + south).toString().padStart(5)} $row")
        }
        println(
            "  $pairs pairs of neighbouring columns where water meets a barrier; $onChunkLines straddle a chunk " +
                "line, where edges falling anywhere would put about ${pairs / CHUNK_WIDTH} there",
        )

        // How much of the caves the barrier takes: a wall has to be there, but it should be a wall and not
        // a cave filled in.
        // The last strip and row are the margin the pairs above read into, not part of the plan.
        val every = states.dropLast(1).flatMap { strip -> strip.dropLast(1) }
        val barriers = every.sumOf { column -> column.count { it == BARRIER } }
        val water = every.sumOf { column -> column.count { it == WATER } }
        fun tallestRun(column: ByteArray): Int {
            var tallest = 0
            var running = 0
            for (state in column) {
                running = if (state == BARRIER) running + 1 else 0
                tallest = maxOf(tallest, running)
            }
            return tallest
        }
        val runs = every.map(::tallestRun)
        println(
            "  $barriers blocks of barrier against $water of water; the tallest wall is ${runs.max()} blocks, and " +
                "${runs.count { it > TALL_WALL }} columns carry one over $TALL_WALL",
        )
        // And both sections through the column carrying the tallest wall, which is the one to look at.
        val tallestAt = runs.indices.maxBy { runs[it] }
        val tallestX = PLAN_WEST + tallestAt / (deep - 1)
        val tallestZ = PLAN_NORTH + tallestAt % (deep - 1)
        val across = (tallestX - SECTION_HALF_WIDTH)..(tallestX + SECTION_HALF_WIDTH)
        val along = (tallestZ - SECTION_HALF_WIDTH)..(tallestZ + SECTION_HALF_WIDTH)
        printSection("the tallest wall is at ($tallestX, $tallestZ); along z=$tallestZ, x from ${across.first}:", across.map { it to tallestZ }, 90, -10)
        printSection("and along x=$tallestX, z from ${along.first}:", along.map { tallestX to it }, 90, -10)
    }

    /**
     * **Where a cave opens through the seabed** — the columns under the sea whose cut rock stands lower than
     * the rock before its caves were cut, around the frozen oceans `scripts/checks/frozen-caves.txt` found.
     *
     * Those are the columns vanilla's surface system reads a lower preliminary surface from than vanilla
     * would, and the frozen-ocean icebergs reach down to that surface — so they are where to look for packed
     * ice in a cave. Listed deepest opening first.
     */
    test("where caves open through the seabed near the frozen oceans, for reading") {
        data class Opening(val worldX: Int, val worldZ: Int, val uncutTop: Int, val carvedTop: Int)
        val openings = mutableListOf<Opening>()
        for (worldX in OPENINGS_WEST..OPENINGS_EAST) {
            for (worldZ in OPENINGS_NORTH..OPENINGS_SOUTH) {
                val uncutTop = uncut.columnSpans(worldX, worldZ).highestSolidY ?: continue
                val carvedTop = ground.shape.columnSpans(worldX, worldZ).highestSolidY ?: continue
                val underTheSea = uncutTop < SEA_LEVEL
                val openedDeep = uncutTop - carvedTop > OPENING_DEPTH
                if (underTheSea && openedDeep) openings += Opening(worldX, worldZ, uncutTop, carvedTop)
            }
        }
        println("  ${openings.size} columns under the sea opened more than $OPENING_DEPTH blocks below their seabed")
        openings.sortedByDescending { it.uncutTop - it.carvedTop }.take(OPENINGS_LISTED).forEach {
            println("    (${it.worldX}, ${it.worldZ})  seabed ${it.uncutTop}, cave floor ${it.carvedTop}")
        }
    }

    /**
     * **Glow berry vines hanging dry in a flooded cave**, walked 2026-09-14 in w6 at (103, 9, 10).
     *
     * Vanilla grows cave vines down into air only, so a vine standing in a flood means that air was there
     * when the features ran and the water beside it was never given a tick. What this can say offline is
     * whether the spot is a cave of the shape's — answered by the fill — or solid rock a carver cut later,
     * and what the aquifer answers there either way, which is what a carver would have been handed.
     */
    test("a section through the vines at (103, 9, 10), for reading") {
        printSection("seed $SEED, hills, z=$VINES_Z, x from ${VINES_X - 20} to ${VINES_X + 20}:", (VINES_X - 20..VINES_X + 20).map { it to VINES_Z }, 40, -10)
        printSection("and x=$VINES_X, z from ${VINES_Z - 20} to ${VINES_Z + 20}:", (VINES_Z - 20..VINES_Z + 20).map { VINES_X to it }, 40, -10)
        println("  what the aquifer answers in the rock too, as a carver cutting there would be told (W water, . air, B barrier):")
        val after = aquifer()
        for (y in 40 downTo -10) {
            val row = StringBuilder()
            for (worldX in VINES_X - 20..VINES_X + 20) {
                val put = after.computeSubstance(DensityFunction.SinglePointContext(worldX, y, VINES_Z), 0.0)
                row.append(
                    when {
                        put == null -> 'B'
                        !put.fluidState.isEmpty -> 'W'
                        else -> '.'
                    },
                )
            }
            println("  ${y.toString().padStart(4)} $row")
        }
        printRooms(VINES_X, VINES_Z)
    }

    /**
     * **Rings of stone with air under them**, walked 2026-09-14 in w6 near (-99, 1, -65) at stamp 47:
     * concentric rings dividing what is otherwise under water, with water cascading down between them.
     */
    test("a section through the rings near (-99, 1, -65), for reading") {
        printSection("seed $SEED, hills, z=$RINGS_Z, x from ${RINGS_X - 30} to ${RINGS_X + 30}:", (RINGS_X - 30..RINGS_X + 30).map { it to RINGS_Z }, 70, -40)
        printSection("and x=$RINGS_X, z from ${RINGS_Z - 30} to ${RINGS_Z + 30}:", (RINGS_Z - 30..RINGS_Z + 30).map { RINGS_X to it }, 70, -40)
        for (worldX in RINGS_X - 12..RINGS_X + 12 step 6) printRooms(worldX, RINGS_Z)
    }

    /**
     * **The pool walked around (1, -47)** at stamp 43: water at one level, mounds of water along its edge,
     * and a long drop on the far side.
     */
    test("a section through the pool at (1, -47), for reading") {
        printSection("seed $SEED, hills, z=-47, x from -30 to 30:", (-30..30).map { it to -47 }, 90, -10)
        printSection("seed $SEED, hills, x=1, z from -77 to -17:", (-77..-17).map { 1 to it }, 90, -10)
        for (worldX in -8..10 step 2) printRooms(worldX, -47)
        for (worldZ in -55..-39 step 2) printRooms(1, worldZ)
    }

    /**
     * **The wall itself, block for block**, drawn the way the fill would lay it — see [shownAt]. The rock is
     * `#`, so a face between two letters shows only where both sides are open.
     */
    test("a block-for-block section through the W6 wall, for reading") {
        val after = aquifer()
        println("  W  water    .  open air    #  rock    B  barrier")
        println("  seed $SEED, hills, z=$WALL_Z, x from ${WALL_X - 20} to ${WALL_X + 20}:")
        for (y in WALL_TOP downTo WALL_BOTTOM) {
            val row = StringBuilder()
            for (worldX in (WALL_X - 20)..(WALL_X + 20)) row.append(shownAt(after, worldX, WALL_Z, y))
            println("  ${y.toString().padStart(4)} $row")
        }
    }
}) {
    private companion object {
        private const val SEED = 4242L
        private val TERRAIN_SALT = AgeGeneration.saltFor(SEED, 0)
        private const val SEA_LEVEL = 63

        /** The wall walk W6 found in `age hills landmass` at seed 4242, after stamp 41. */
        private const val WALL_X = -20
        private const val WALL_Z = -20

        private const val SCAN_BOTTOM = -60
        private const val SCAN_TOP = 120
        private const val TALL_LISTED = 12

        private const val WALL_TOP = 90
        private const val WALL_BOTTOM = 0

        /** The plan view: 128 columns square, around the pool walked at (1, -47). */
        private const val PLAN_WEST = -64
        private const val PLAN_EAST = 63
        private const val PLAN_NORTH = -111
        private const val PLAN_SOUTH = 16
        private const val CHUNK_WIDTH = 16
        private const val TALL_WALL = 20

        /** Where the walk of 2026-09-14 found rings of stone over air in a flooded area, in w6. */
        private const val RINGS_X = -99
        private const val RINGS_Z = -65

        /** Where the walk of 2026-09-14 found cave vines hanging dry in a flood, in w6. */
        private const val VINES_X = 103
        private const val VINES_Z = 10

        /** Around both frozen oceans in the cold hills: (-32, -32) and (64, 32). */
        private const val OPENINGS_WEST = -128
        private const val OPENINGS_EAST = 128
        private const val OPENINGS_NORTH = -128
        private const val OPENINGS_SOUTH = 128
        private const val OPENING_DEPTH = 5
        private const val OPENINGS_LISTED = 25
        private const val SECTION_HALF_WIDTH = 30

        private const val NEITHER: Byte = 0
        private const val WATER: Byte = 1
        private const val BARRIER: Byte = 2
    }
}
