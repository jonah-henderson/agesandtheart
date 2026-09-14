package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.VerticalWindow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.Mth
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
    fun aquifer(): Aquifer = table.aquiferFor(ground.shape, uncut)

    /** As it was asked before stamp 43, judging the sea from the carved rock alone. */
    fun carvedOnlyAquifer(): Aquifer = table.aquiferFor(ground.shape)

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
                    put == null -> '#'
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
            for (worldZ in -600..600 step 97) {
                for (worldX in -600..600 step 3) {
                    var running = 0
                    for (y in SCAN_BOTTOM..SCAN_TOP) {
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
        println("  $label")
        for (y in top downTo bottom) {
            val row = StringBuilder()
            for ((worldX, worldZ) in columns) row.append(shownAt(after, worldX, worldZ, y))
            println("  ${y.toString().padStart(4)} $row")
        }
    }

    // The noise the thresholds are read against, rebuilt as `WaterTable.matching` builds it, so the inputs
    // of each room's level can be printed rather than inferred.
    val floodedness by lazy { fieldNoise(SEED, -3, listOf(1.0, 1.0)) }

    /**
     * **Every room in a column, and the level the perched branch gives it** — `perchedLevel` recomputed from
     * its own inputs, beside what the aquifer actually answered at the room's floor.
     */
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
            val band = Math.floorDiv(roomFloor, 40)
            val nudge = floodedness.getValue(
                Math.floorDiv(worldX, 16).toDouble(),
                band.toDouble(),
                Math.floorDiv(worldZ, 16).toDouble(),
            ) * 10.0
            val level = minOf(surface, (ceiling ?: surface + 1) - 1, band * 40 + 20 + Mth.quantize(nudge, 3))
            val wetness = floodedness.getValue(worldX / 96.0, roomFloor / 64.0, worldZ / 96.0).coerceIn(-1.0, 1.0)
            println(
                "      room ${roomFloor}..${(ceiling ?: surface + 1) - 1}  band $band  perched level $level" +
                    "  wetness at floor ${"%+.3f".format(wetness)}  (sea > 0.8, perched > 0.4 under land)",
            )
        }
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
        println("  W  water    .  open air    #  rock, or a barrier that holds water back")
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
    }
}
