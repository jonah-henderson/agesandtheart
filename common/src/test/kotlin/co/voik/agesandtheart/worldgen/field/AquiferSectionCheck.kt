package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.VerticalWindow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * **What the water actually is, in a real hills Age, drawn as a cross-section.**
 *
 * Written because three fixes to the aquifer in a row failed to change what a walk saw. Every one of them
 * was aimed by reading the code, and every one was aimed at the wrong branch; what settled it in a single
 * run was drawing the aquifer's **verdict** — what a hole opened anywhere would hold — and then printing
 * the inputs of whichever branch the picture implicated.
 *
 * So this prints rather than asserts. It builds the landform a book actually gets and reads the same
 * aquifer the fill and the carvers ask, which is the point: the caves a walk falls down are cut *after*
 * the fill by vanilla's carvers, so drawing the water alone shows none of them, where the verdict is what
 * both passes consult and is defined everywhere.
 *
 * Read the verdict map first, then the inputs. A region boundary that is dead straight is a discrete
 * level switching on a predicate; the tables say which predicate, and they are how the forty-block band in
 * `perchedLevel` was finally caught.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class AquiferSectionCheck : FunSpec({

    /**
     * **The column-by-column reading at a place a walk found water standing against air** — Jonah gave the
     * block: `-192, 60, 182` on seed 4242.
     *
     * The suspicion it was written to settle — that `columnSubmerged` is a hard per-column binary and two
     * neighbours either side of it answer oppositely all the way down — turned out to be wrong: the
     * columns either side of a wall agree about being submerged. Kept because that is worth being able to
     * see, and because it is where a reading of this kind starts.
     */
    test("what the aquifer says either side of the block Jonah found, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), SEA_LEVEL)
        val aquifer = WaterTable.matching(sea, SEA_LEVEL, SALT).aquiferFor(ground.shape)

        println("  seed $SALT, z=$FOUND_Z, y=$FOUND_Y — the block Jonah found is x=$FOUND_X")
        println("     x  surface  submerged  aquifer says")
        for (worldX in (FOUND_X - 12)..(FOUND_X + 12)) {
            val surface = ground.shape.columnSpans(worldX, FOUND_Z).highestSolidY
            val submerged = (surface ?: SEA_LEVEL) < SEA_LEVEL
            val put = aquifer.computeSubstance(
                DensityFunction.SinglePointContext(worldX, FOUND_Y, FOUND_Z),
                -1.0,
            )
            val says = when {
                put == null -> "(solid)"
                put.fluidState.isEmpty -> "dry"
                else -> "WATER"
            }
            val mark = if (worldX == FOUND_X) " <-- here" else ""
            println("  ${worldX.toString().padStart(5)}  ${(surface ?: -999).toString().padStart(7)}" +
                "  ${submerged.toString().padStart(9)}  $says$mark")
        }
    }

    test("a slice through a hills Age's caves, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)

        val water = Blocks.WATER.defaultBlockState()
        val sea = SeaFill.of(water, SEA_LEVEL)
        val table = WaterTable.matching(sea, SEA_LEVEL, SALT)
        val aquifer = table.aquiferFor(ground.shape)

        val uncut = ground.hollows ?: error("noise caves should leave the uncut rock as its hollows")

        println("  a hills Age at seed $SALT, sea level $SEA_LEVEL — z=$ACROSS_Z, x from $FROM_X:")
        var seaBranch = 0
        var perchedBranch = 0
        for (y in HIGHEST downTo LOWEST) {
            val row = StringBuilder()
            for (step in 0..<WIDE) {
                val worldX = FROM_X + step * STRIDE
                val rock = ground.shape.columnSpans(worldX, ACROSS_Z)
                when {
                    rock.contains(y) -> row.append('#')
                    // Inside the rock the caving removed: the aquifer decides, as the fill asks it to.
                    uncut.columnSpans(worldX, ACROSS_Z).contains(y) -> {
                        val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, y, ACROSS_Z), -1.0)
                        if (put == null || put.fluidState.isEmpty) {
                            row.append('.')
                        } else {
                            // Which branch: at the sea's own level, or a pool perched above it.
                            if (y < SEA_LEVEL) seaBranch++ else perchedBranch++
                            row.append(if (y < SEA_LEVEL) 'S' else 'P')
                        }
                    }
                    y < SEA_LEVEL -> row.append('~')
                    else -> row.append(' ')
                }
            }
            if (row.any { it != ' ' }) println("  ${y.toString().padStart(4)} $row")
        }
        println("  wet blocks in cave: $seaBranch below the waterline, $perchedBranch above it")
    }

    /**
     * **What makes two neighbouring columns disagree by forty blocks**, read off the column state that
     * decides it rather than off the water it produces.
     *
     * This is the reading that cleared the geometry: either side of the wall the carved top, the roof over
     * the query, the hollowness and the submerged verdict are all **identical**, and only the water
     * differs. That is what ruled out every suspect above the noise and sent the search into
     * `perchedLevel`, whose own inputs are printed below.
     */
    test("the column state either side of the wall Jonah found, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)
        val uncut = ground.hollows ?: error("noise caves should leave the uncut rock as its hollows")
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), SEA_LEVEL)
        val aquifer = WaterTable.matching(sea, SEA_LEVEL, SALT).aquiferFor(ground.shape)

        println("  seed $SALT, hills, z=$WALL_Z — the wall Jonah walked is around x=$WALL_X, y=$WALL_Y")
        println("     x  carved top  submerged  roof over y${WALL_Y}  hollow here  water stands to")
        for (worldX in (WALL_X - 6)..(WALL_X + 6)) {
            val carved = ground.shape.columnSpans(worldX, WALL_Z)
            val top = carved.highestSolidY
            val submerged = (top ?: Int.MIN_VALUE) < SEA_LEVEL
            val roof = carved.roofOver(WALL_Y)
            val hollow = uncut.columnSpans(worldX, WALL_Z).contains(WALL_Y)
            // How high the aquifer actually fills this column, walked down from the roof of the world.
            val standsTo = (window.topY - 1 downTo window.minY).firstOrNull { y ->
                val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, y, WALL_Z), -1.0)
                put != null && !put.fluidState.isEmpty
            }
            println(
                "  ${worldX.toString().padStart(5)}  ${(top ?: -999).toString().padStart(10)}" +
                    "  ${submerged.toString().padStart(9)}  ${(roof ?: -999).toString().padStart(13)}" +
                    "  ${hollow.toString().padStart(11)}  ${(standsTo ?: -999).toString().padStart(15)}",
            )
        }
    }

    /**
     * **How tall the faces are, over ground wide enough to be representative** — the measurement the
     * barrier has to earn its place against.
     *
     * A face is water standing beside something that is not water and not rock, which is the only
     * arrangement a walk can actually see. Height is what makes one offensive: a pool's edge steps by ones
     * and twos up a sloping floor, where the reported failure was a sheer drop of forty.
     */
    test("how tall the water's exposed faces get, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), SEA_LEVEL)
        val aquifer = WaterTable.matching(sea, SEA_LEVEL, SALT).aquiferFor(ground.shape)

        // What one column would show if it were opened: water, bank, or nothing to see.
        fun shownAt(worldX: Int, worldZ: Int, y: Int): Char {
            if (ground.shape.columnSpans(worldX, worldZ).contains(y)) return '#'
            val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, y, worldZ), -1.0)
            return when {
                put == null -> '#'
                !put.fluidState.isEmpty -> 'W'
                else -> '.'
            }
        }

        val faces = mutableListOf<Int>()
        val tall = mutableListOf<Triple<Int, Int, Int>>()
        for (worldZ in -600..600 step 97) {
            for (worldX in -600..600 step 3) {
                var running = 0
                for (y in SCAN_BOTTOM..SCAN_TOP) {
                    // Water on one side, open air on the other: the face a walk stands and looks at.
                    val exposed = shownAt(worldX, worldZ, y) == 'W' && shownAt(worldX + 1, worldZ, y) == '.'
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
        val tallest = faces.maxOrNull() ?: 0
        val overALedge = faces.count { it > 3 }
        println("  ${faces.size} exposed water faces; $overALedge over 3 blocks tall; tallest $tallest")
        println("  heights: ${faces.groupingBy { it }.eachCount().toSortedMap().entries.take(12).joinToString()}")
        // Where the tall ones are, so the next reading can go and look at one rather than hunt for it.
        tall.forEach { (worldX, topY, worldZ) ->
            val rock = ground.shape.columnSpans(worldX, worldZ)
            println("    a tall face tops out at $worldX, $topY, $worldZ — floor under it ${rock.floorUnder(topY)}")
        }
    }

    /**
     * **The wall itself, block for block**, drawn the way the fill would lay it.
     *
     * The legend is the fill's own `when`, in its own order — so a column of this picture *is* what the
     * generator writes, and the boundary between two letters is the seam a walk complains about.
     */
    test("a block-for-block section through the wall, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)
        val uncut = ground.hollows ?: error("noise caves should leave the uncut rock as its hollows")
        val water = Blocks.WATER.defaultBlockState()
        val sea = SeaFill.of(water, SEA_LEVEL)
        val aquifer = WaterTable.matching(sea, SEA_LEVEL, SALT).aquiferFor(ground.shape)

        // **The verdict, not the water.** The caves a walk falls down here are cut by vanilla's carvers,
        // which run after the fill and are not in this instrument — but they ask this same aquifer for
        // every block they take. So what a hole opened anywhere would hold is the thing to draw, and the
        // rock is drawn over it only as context.
        println("  W  would hold water    .  would be dry    B  the bank that seals the seam    |  surface")
        println("  seed $SALT, hills, z=$WALL_Z, x from ${WALL_X - 20} to ${WALL_X + 20}:")
        var banked = 0
        for (y in WALL_TOP downTo WALL_BOTTOM) {
            val row = StringBuilder()
            for (worldX in (WALL_X - 20)..(WALL_X + 20)) {
                val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, y, WALL_Z), -1.0)
                val carved = ground.shape.columnSpans(worldX, WALL_Z)
                row.append(
                    when {
                        // Rock the shape kept, which is what a walk would have to cut through to see any
                        // of this — a face between two letters only shows where the rock is not.
                        carved.contains(y) -> '#'
                        // Null on this path is the barrier — the fill and the carvers both leave it rock.
                        put == null -> 'B'.also { banked++ }
                        !put.fluidState.isEmpty -> 'W'
                        else -> '.'
                    },
                )
            }
            println("  ${y.toString().padStart(4)} $row")
        }
        println("  $banked of ${(WALL_TOP - WALL_BOTTOM + 1) * 41} blocks in this section are bank")

        // The noise the thresholds are read against, rebuilt exactly as `matching` builds it — so the
        // number a seam turns on is visible rather than inferred. A bank can only ever be as wide as the
        // noise is slow, and this is what says how wide that is.
        val floodedness = fieldNoise(SALT, -3, listOf(1.0, 1.0))
        fun wetnessAt(worldX: Int, worldY: Int) =
            floodedness.getValue(worldX / 96.0, worldY / 64.0, WALL_Z / 96.0).coerceIn(-1.0, 1.0)
        println("  floodedness along y=$WALL_Y (the sea threshold under land is 0.8):")
        for (worldX in (WALL_X - 8)..(WALL_X + 2)) {
            println("  ${worldX.toString().padStart(5)}  ${"%+.4f".format(wetnessAt(worldX, WALL_Y))}")
        }
        val across = (WALL_X - 40..WALL_X + 40).map { wetnessAt(it, WALL_Y) }
        val steepest = across.zipWithNext().maxOf { (before, after) -> kotlin.math.abs(after - before) }
        println("  steepest step per block across x: ${"%.5f".format(steepest)}")

        // 0.44 clears the perched threshold and not the sea's, so the level every one of these columns
        // stands at is `perchedLevel`'s — recomputed here from its own inputs, which is what says which of
        // them moves. The band is the suspect: it is read off the room's floor, and a column with no rock
        // below the query falls back to the query's own height.
        println("  the perched level's inputs at y=$WALL_Y:")
        println("     x  floorUnder  ceilingAbove  surface  band  level")
        for (worldX in (WALL_X - 8)..(WALL_X + 2)) {
            val rock = ground.shape.columnSpans(worldX, WALL_Z)
            val floorUnder = rock.floorUnder(WALL_Y)
            val roomFloor = floorUnder?.plus(1) ?: WALL_Y
            val band = Math.floorDiv(roomFloor, 40)
            val middle = band * 40 + 20
            val nudge = floodedness.getValue(
                Math.floorDiv(worldX, 16).toDouble(),
                band.toDouble(),
                Math.floorDiv(WALL_Z, 16).toDouble(),
            ) * 10.0
            val surface = rock.highestSolidY ?: SEA_LEVEL
            val roomFor = rock.ceilingAbove(WALL_Y)?.minus(1) ?: surface
            val level = minOf(surface, roomFor, middle + Math.round(nudge).toInt())
            println(
                "  ${worldX.toString().padStart(5)}  ${(floorUnder ?: -999).toString().padStart(10)}" +
                    "  ${(rock.ceilingAbove(WALL_Y) ?: -999).toString().padStart(12)}" +
                    "  ${surface.toString().padStart(7)}  ${band.toString().padStart(4)}" +
                    "  ${level.toString().padStart(5)}",
            )
        }

        // How fast the floodedness noise moves per block, which is what decides how thick a barrier has
        // to be to hide a seam — and whether a seam can be found by widening the threshold at all.
        val steps = (WALL_X - 60..WALL_X + 60).map { worldX ->
            aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, WALL_Y, WALL_Z), -1.0)
        }
        val flips = steps.zipWithNext().count { (before, after) ->
            (before != null && !before.fluidState.isEmpty) != (after != null && !after.fluidState.isEmpty)
        }
        println("  over 120 columns at y=$WALL_Y the wet/dry verdict flips $flips times")
    }
}) {
    private companion object {
        private const val SALT = 4242L
        private const val SEA_LEVEL = 63

        /** A slice wide enough to cross a coast, which is where the reported curtain stands. */
        private const val FROM_X = -256
        private const val WIDE = 128
        private const val STRIDE = 4
        private const val ACROSS_Z = 0

        /** The block a walk found water standing against air on, seed 4242. */
        private const val FOUND_X = -192
        private const val FOUND_Y = 60
        private const val FOUND_Z = 182

        /** And the wall a later walk found, on a world written `age hills landmass` at the same seed. */
        private const val WALL_X = -20
        private const val WALL_Y = 60
        private const val WALL_Z = -20

        private const val SCAN_BOTTOM = -60
        private const val SCAN_TOP = 120

        private const val WALL_TOP = 70
        private const val WALL_BOTTOM = 0

        private const val LOWEST = -60
        private const val HIGHEST = 150
    }
}
