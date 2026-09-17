package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks

/**
 * **The halls are kept dry outright, and never by a water table.**
 *
 * A table is a good description of rock that water seeps through and a bad one of a room. Its wet and dry
 * patches have no walls between them, which in open rock reads as damp ground and in a hall puts a flooded
 * bay against a dry one with nothing but air where the surface should end — a wall of water standing up by
 * itself. So the whole void goes into `SeaFill.dry`, and the waterline never reaches it however high the
 * sea stands.
 *
 * Sits apart from `GreatHallsCheck` so that the geometry stays answerable without registries: a sea needs a
 * real `BlockState` and this one does not.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class GreatHallsWaterCheck : FunSpec({

    val floorY = -59
    val roofY = 92
    val halls = GreatHalls.voidBetween(floorY, roofY, salt = 0L)
    val world = Subtract(Slab(lowY = -64, highY = 200), halls)
    val storeys = GreatHalls.storeysBetween(floorY, roofY)
    val samples = (-160..160 step 11).flatMap { x -> (-160..160 step 11).map { z -> x to z } }

    test("the sea is kept out of every storey, even standing well over their ceilings") {
        // Deliberately above the highest storey, which is the case that used to flood.
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), level = OverworldField.WATERLINE).copy(dry = halls)

        for ((x, z) in samples) {
            val dryness = sea.drynessAt(x, z)
            val wetness = sea.wetnessAt(x, z)
            val solid = world.columnSpans(x, z)
            for (storey in storeys) {
                // Only where the storey is actually open — a pier is rock, and rock holds no water anyway.
                val flooded = storey.filter { y -> !solid.contains(y) && sea.fillsAt(y, dryness, wetness) }
                check(flooded.isEmpty()) {
                    "($x, $z) has storey $storey flooded at ${flooded.take(4).joinToString()}"
                }
            }
        }
    }

    test("keeping the halls dry does not dry out the rest of the world") {
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), level = OverworldField.WATERLINE).copy(dry = halls)
        // A level over the halls' roof and under the waterline, which is ordinary sea and must stay wet.
        val overTheRoof = roofY + 8
        val wet = samples.count { (x, z) ->
            sea.fillsAt(overTheRoof, sea.drynessAt(x, z), sea.wetnessAt(x, z))
        }
        check(wet == samples.size) { "$wet of ${samples.size} columns hold sea at y=$overTheRoof; all should" }
    }
})
