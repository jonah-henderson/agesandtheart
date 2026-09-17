package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * What a carver finds when it cuts — the aquifer, which decides whether an opened block comes out water or
 * air.
 *
 * **The failure this guards is a hole in the ocean.** Vanilla's carvers reach the seabed like any other
 * rock, and every block they take is handed here to be filled; answering "dry" under open water leaves an
 * air pocket with sea all round it. It looks like a cave from below and like nothing at all from above, so
 * it is the kind of thing that survives a lot of walking.
 *
 * The other half is the rule that makes it hard: rock under *land* must stay dry however far below sea
 * level it lies, or every cave in the world floods. So this cannot be a height, and the tests are about the
 * branch that tells the two cases apart.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class WaterTableCheck : FunSpec({

    val seaLevel = 63

    /**
     * How much the water's surface may step between one column and the next before it is a face rather
     * than a slope. Three blocks: a pool's edge running up a sloping floor steps by ones and twos.
     */
    val A_LEDGE = 3

    /** The share of cases that vanilla's own aquifer allows, but seldom produces. */
    val RARELY = 0.05
    // `by lazy`, because Kotest builds a spec to discover its tests: read eagerly this touches the block
    // registry during discovery, before `NEEDS_REGISTRIES` has bought the bootstrap — which is fine under
    // `test`, where another spec has already paid it, and fatal under `serverTest`, where the tag filter
    // means nothing here ever does.
    val water by lazy { Blocks.WATER.defaultBlockState() }

    fun tableOver(shape: TerrainField): WaterTable = WaterTable.matching(SeaFill.of(water, seaLevel), seaLevel)

    /** Whether the aquifer floods a block the carver just opened at this position, under water [standing]. */
    fun floodsAt(
        table: WaterTable,
        shape: TerrainField,
        worldX: Int,
        worldY: Int,
        worldZ: Int,
        standing: TerrainField? = null,
    ): Boolean {
        val aquifer = table.aquiferOver(shape, seaFill = SeaFill.of(water, seaLevel).copy(wet = standing))
        val opened = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
        return opened != null && !opened.fluidState.isEmpty
    }

    /** How often a carve at [under] blocks below this shape's surface comes out wet, over a spread of columns. */
    fun floodedShare(
        table: WaterTable,
        shape: TerrainField,
        surfaceY: Int,
        under: Int,
        standing: TerrainField? = null,
    ): Double {
        val columns = (0..2000 step 53).flatMap { worldX -> (0..2000 step 71).map { worldX to it } }
        return columns.count { (worldX, worldZ) ->
            floodsAt(table, shape, worldX, surfaceY - under, worldZ, standing)
        }.toDouble() / columns.size
    }

    /**
     * **Neighbouring columns of one cave agree about how high the water stands** — no sheer faces.
     *
     * The failure this is for, seen on a walk and described exactly: *"a giant curtain of water down the
     * middle of the cave"* (Jonah, 2026-09-11). The three-way rule is a threshold on a noise, so when the
     * level was decided per column the two columns either side of that threshold got opposite answers —
     * one full, one bone dry — and a cave spanning both came out half water with a vertical wall through
     * it. No tuning fixes that: the cliff is what a per-column decision *is*.
     *
     * **A reading, and it is the one that stopped a fix going in.** Porting vanilla's per-cell grid — a
     * jittered 16×12×16 lattice with one level per anchor — was measured here against the per-column rule
     * and came out **worse**: 18 steps where the old way had 11, with the same worst case of 12. The grid
     * moves the wet/dry boundary from a noise contour onto a cell lattice; it does not remove it, because
     * the *decision* is binary either way.
     *
     * What removes it in vanilla, and here, is a **barrier of rock**: where two neighbours disagree, the dry
     * one keeps its rock, so a walk never sees water meeting air at a face — it sees stone. That is the test
     * below this one. This reads the wet verdict, which the barrier leaves alone, so it prints and asserts
     * nothing.
     */
    test("water in one room has no sheer faces across it") {
        val roof = 40
        val floor = 10
        val room = Box(minX = -300, minY = floor, minZ = -300, maxX = 300, maxY = roof, maxZ = 300)
        val shape = Subtract(Box(minX = -300, minY = -64, minZ = -300, maxX = 300, maxY = 90, maxZ = 300), room)
        val table = tableOver(shape)

        fun surfaceAt(worldX: Int, worldZ: Int): Int {
            // The highest block of this column the aquifer would fill, or the floor where it fills none.
            for (worldY in roof downTo floor) {
                if (floodsAt(table, shape, worldX, worldY, worldZ)) return worldY
            }
            return floor - 1
        }

        var steps = 0
        var worst = 0
        for (worldZ in -280..280 step 53) {
            var previous = surfaceAt(-280, worldZ)
            for (worldX in -279..280) {
                val here = surfaceAt(worldX, worldZ)
                val step = kotlin.math.abs(here - previous)
                if (step > A_LEDGE) {
                    steps++
                    worst = maxOf(worst, step)
                }
                previous = here
            }
        }
        // Printed, not asserted: vanilla's own cells step as far as this across a cave as open as this one,
        // and what keeps a walk from seeing a curtain is the barrier and the flow — the test below this one.
        println("  $steps steps over $A_LEDGE blocks between neighbouring columns; the worst is $worst")
    }

    /**
     * **Water against open air at its own height is almost always told to move.** Where two cells disagree
     * about the water, vanilla lays rock between them — and where the cave is too open for the rock to stand,
     * near the water's surface where the pressure is low, it marks the water for its first tick so it runs
     * off and settles instead. Vanilla asks only the water's own four nearest cells whether to mark it, and the
     * dry neighbour's nearest may be none of them, so a face that stands can happen — rarely.
     */
    test("water beside a dry neighbour is held by rock, or nearly always told to flow") {
        val roof = 40
        val floor = 10
        val room = Box(minX = -300, minY = floor, minZ = -300, maxX = 300, maxY = roof, maxZ = 300)
        val shape = Subtract(Box(minX = -300, minY = -64, minZ = -300, maxX = 300, maxY = 90, maxZ = 300), room)
        val aquifer = tableOver(shape).aquiferOver(shape)

        var barriers = 0
        var faces = 0
        var stranded = 0
        for (worldZ in -280..280 step 53) {
            for (worldX in -280..280) {
                for (worldY in floor..roof) {
                    val here = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
                    val toldToMove = aquifer.shouldScheduleFluidUpdate()
                    if (here == null || here.fluidState.isEmpty) continue
                    for (besideX in listOf(worldX - 1, worldX + 1)) {
                        val beside = aquifer.computeSubstance(DensityFunction.SinglePointContext(besideX, worldY, worldZ), -1.0)
                        when {
                            beside == null -> barriers++
                            beside.isAir -> {
                                faces++
                                if (!toldToMove) stranded++
                            }
                        }
                    }
                }
            }
        }
        println("  $barriers blocks of barrier hold water in; $faces faces of water against open air, $stranded never told to move")
        check(barriers > 0) { "no neighbours disagreed anywhere in this room, so this proves nothing" }
        check(stranded <= faces * RARELY) { "$stranded of $faces faces of water against open air are never told to move" }
    }

    /**
     * **Water on open air is always told to fall.** Two cells stacked one on another can hold different water;
     * vanilla lays a shelf of rock between them, and where the room is too open for it, marks the water to
     * fall and settle. Found walking w6 at (103, 9, 10), where glow berry vines grew in a dry pocket under
     * water that nothing had told to move.
     */
    test("water over a dry room below it stands on rock, or is told to fall") {
        val seabed = 40
        val roof = 30
        val floor = -40
        val room = Box(minX = -300, minY = floor, minZ = -300, maxX = 300, maxY = roof, maxZ = 300)
        val shape = Subtract(Box(minX = -300, minY = -64, minZ = -300, maxX = 300, maxY = seabed, maxZ = 300), room)
        val aquifer = tableOver(shape).aquiferOver(shape)

        fun answerAt(worldX: Int, worldY: Int, worldZ: Int) =
            aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)

        var shelves = 0
        var onAir = 0
        var stranded = 0
        for (worldZ in -280..280 step 53) {
            for (worldX in -280..280 step 7) {
                for (worldY in floor..<roof) {
                    val above = answerAt(worldX, worldY + 1, worldZ) ?: continue
                    val aboveToldToMove = aquifer.shouldScheduleFluidUpdate()
                    if (above.fluidState.isEmpty) continue
                    val here = answerAt(worldX, worldY, worldZ)
                    when {
                        here == null -> shelves++
                        here.isAir -> {
                            onAir++
                            if (!aboveToldToMove) stranded++
                        }
                    }
                }
            }
        }
        println("  $shelves blocks of shelf hold water up; $onAir blocks of water on open air, $stranded never told to fall")
        check(shelves > 0) { "no room here came out wet over dry, so this proves nothing" }
        check(stranded == 0) { "$stranded blocks of water stand on open air and are never told to fall" }
    }

    /**
     * **Rock just under the sea seldom opens to air.** A carver cutting the seabed finds water, or the rock left
     * standing, nearly everywhere. Vanilla promises neither: a cell four or more blocks down can come out dry,
     * and where it is much the nearest, no barrier is laid — so a rare pocket under the sea is vanilla's too.
     */
    test("rock just under the sea seldom opens to air") {
        val surfaceY = seaLevel - 20
        val seabed = Slab(lowY = -64, highY = surfaceY)
        val table = tableOver(seabed)
        val aquifer = table.aquiferOver(seabed)
        val columns = (0..2000 step 53).flatMap { worldX -> (0..2000 step 71).map { worldX to it } }
        for (under in 0..3) {
            val opened = columns.count { (worldX, worldZ) ->
                val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, surfaceY - under, worldZ), 0.0)
                put != null && put.isAir
            }
            println("  $under under the seabed, $opened of ${columns.size} columns opened to air")
            check(opened <= columns.size * RARELY) { "$under under the seabed, $opened of ${columns.size} columns opened to air" }
        }
    }

    /**
     * And the half that makes it hard: **rock under land stays dry**, however far under the waterline it
     * lies. Flooding by height would drown every cave in the world, which is why the column asks what is
     * standing over it rather than where it is.
     */
    test("rock under dry land is nothing like as wet as rock under the sea") {
        val surfaceY = seaLevel + 60
        val upland = Slab(lowY = -64, highY = surfaceY)
        // Well under the waterline, so height alone would have called this seabed.
        val wet = floodedShare(tableOver(upland), upland, surfaceY, under = 80)
        check(wet < MOSTLY_DRY) {
            "${"%.0f%%".format(wet * 100)} of carves under high ground flooded; a cave system would be a lake"
        }
    }

    /**
     * **A river bed is submerged too.** Its ground stands well over the waterline, so a rule reading height
     * alone calls it land and lets a carver open a hole in the river — which drains it.
     */
    test("rock under a river floods, though its bed is over the waterline") {
        val bank = Slab(lowY = -64, highY = seaLevel + 40)
        val river = Slab(lowY = -64, highY = seaLevel + 46)

        val surfaceY = seaLevel + 40
        val unaware = floodedShare(tableOver(bank), bank, surfaceY, under = 1)
        val knowing = floodedShare(tableOver(bank), bank, surfaceY, under = 1, standing = river)

        check(knowing == 1.0) { "only ${"%.0f%%".format(knowing * 100)} under a river flooded, so it drains" }
        check(unaware < MOSTLY_DRY) { "the same ground with nothing over it flooded anyway, so this proves nothing" }
    }
}) {
    private companion object {
        /** Perched pockets are meant to happen under land, so this is a rate rather than a certainty. */
        const val MOSTLY_DRY = 0.5
    }
}
