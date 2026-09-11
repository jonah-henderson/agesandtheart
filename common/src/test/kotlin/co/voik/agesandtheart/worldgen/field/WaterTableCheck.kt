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
    // `by lazy`, because Kotest builds a spec to discover its tests: read eagerly this touches the block
    // registry during discovery, before `NEEDS_REGISTRIES` has bought the bootstrap — which is fine under
    // `test`, where another spec has already paid it, and fatal under `serverTest`, where the tag filter
    // means nothing here ever does.
    val water by lazy { Blocks.WATER.defaultBlockState() }

    fun tableOver(shape: TerrainField, standing: TerrainField? = null): WaterTable =
        WaterTable.matching(SeaFill.of(water, seaLevel).copy(wet = standing), seaLevel)

    /** Whether the aquifer floods a block the carver just opened at this position. */
    fun floodsAt(table: WaterTable, shape: TerrainField, worldX: Int, worldY: Int, worldZ: Int): Boolean {
        val aquifer = table.aquiferFor(shape)
        val opened = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, worldY, worldZ), -1.0)
        return opened != null && !opened.fluidState.isEmpty
    }

    /** How often a carve at [under] blocks below this shape's surface comes out wet, over a spread of columns. */
    fun floodedShare(table: WaterTable, shape: TerrainField, surfaceY: Int, under: Int): Double {
        val columns = (0..2000 step 53).flatMap { worldX -> (0..2000 step 71).map { worldX to it } }
        return columns.count { (worldX, worldZ) ->
            floodsAt(table, shape, worldX, surfaceY - under, worldZ)
        }.toDouble() / columns.size
    }

    /**
     * **A carver cutting the seabed finds water.** This was the defect: the shallow threshold was a real
     * threshold, so two blocks in five came out air and the ocean floor filled with pockets.
     */
    /**
     * **A pool in a cave never stands against that cave's ceiling** — the water is bounded by the room it
     * is in, not by the hill above it (Jonah, walked 2026-09-11).
     *
     * `perchedLevel` capped its answer at `columnSurface`, which is the highest rock *anywhere* in the
     * column: under a mountain that is the summit. So a cave beneath a tall hill was filled to a level
     * hundreds of blocks above its own roof — which fills the cave to the brim and then pours out of it,
     * for as far as the hill is tall. What a walk saw was water coming out of the ceiling.
     *
     * Checked at the topmost open block of the cave, over many columns: whatever the noise decides about
     * how wet this rock is, that block must be air, because a level equal to the ceiling is one the room
     * cannot hold.
     */
    test("a perched pool never reaches the ceiling of the cave it stands in") {
        val summit = 200
        val roof = 100
        val floor = 40
        // A tall hill with a wide, deep room under it — the shape the failure needed. The room's ceiling is
        // a hundred blocks below the summit, which is the gap the old cap fell through.
        val hollow = Box(minX = -400, minY = floor, minZ = -400, maxX = 400, maxY = roof, maxZ = 400)
        val shape = Subtract(Box(minX = -400, minY = -64, minZ = -400, maxX = 400, maxY = summit, maxZ = 400), hollow)
        val table = tableOver(shape)

        val wetCeilings = (-380..380 step 37).flatMap { worldX ->
            (-380..380 step 41).map { worldX to it }
        }.count { (worldX, worldZ) -> floodsAt(table, shape, worldX, roof, worldZ) }

        check(wetCeilings == 0) {
            "water stands against the cave roof in $wetCeilings columns, so it is being levelled by the " +
                "hill above rather than by the room it is in"
        }
    }

    test("rock just under the sea always floods when it is opened") {
        val surfaceY = seaLevel - 20
        val seabed = Slab(lowY = -64, highY = surfaceY)
        val table = tableOver(seabed)
        for (under in 0..3) {
            val wet = floodedShare(table, seabed, surfaceY, under)
            check(wet == 1.0) { "$under under the seabed, only ${"%.0f%%".format(wet * 100)} of columns flooded" }
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
        val knowing = floodedShare(tableOver(bank, standing = river), bank, surfaceY, under = 1)

        check(knowing == 1.0) { "only ${"%.0f%%".format(knowing * 100)} under a river flooded, so it drains" }
        check(unaware < MOSTLY_DRY) { "the same ground with nothing over it flooded anyway, so this proves nothing" }
    }
}) {
    private companion object {
        /** Perched pockets are meant to happen under land, so this is a rate rather than a certainty. */
        const val MOSTLY_DRY = 0.5
    }
}
