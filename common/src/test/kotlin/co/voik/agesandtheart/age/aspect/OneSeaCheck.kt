package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Lift
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The one sea an Age laid out by the terrain table sets its landforms at ([Terrain.SeaSetting]). Asked of every
 * landform rather than of a few, so one added later that does not meet the sea fails here rather than on a walk.
 */
@Tags(NEEDS_REGISTRIES)
class OneSeaCheck : FunSpec({

    val shared = Terrain.SeaSetting(Terrain.ONE_SEA_LEVEL, isShared = true)
    val alone = Terrain.SeaSetting(Terrain.ONE_SEA_LEVEL, isShared = false)
    val grounded = Terrain.entries.filter { it.hasGroundBeneath && !it.isVanillas }
    val sizes = listOf(Options.NONE) + listOf("-1.0..-1.0", "1.0..1.0").map { Options(mapOf(Terrain.SIZE.name to listOf(it))) }

    test("every landform standing on ground pours the one sea where it shares an Age") {
        for (terrain in grounded) for (options in sizes) {
            val waterline = terrain.waterlineAt(options, SALT, shared)
            check(waterline == Terrain.ONE_SEA_LEVEL) { "${terrain.key} pours its sea at $waterline in a divided Age, with $options" }
        }
    }

    test("alone, a landform with a sea of its own pours it at the one sea, and one with none stays without") {
        for (terrain in grounded) for (options in sizes) {
            val own = terrain.waterlineAt(options, SALT)
            val set = terrain.waterlineAt(options, SALT, alone)
            val hasASeaOfItsOwn = own != null && own > VerticalWindow.MIN_Y
            val expected = if (hasASeaOfItsOwn) Terrain.ONE_SEA_LEVEL else own
            check(set == expected) { "${terrain.key} alone pours its sea at $set where its own is $own, with $options" }
        }
    }

    test("the river country's sea is vanilla's, and an Age written before generator 20 keeps the one it had") {
        val former = Options(mapOf(Terrain.FORMER_LAND.name to listOf(Parameter.TRUE)))
        check(Terrain.RIVERLANDS.waterlineAt(Options.NONE, SALT) == Terrain.ONE_SEA_LEVEL)
        check(Terrain.RIVERLANDS.waterlineAt(former, SALT) == RiverlandsField.FORMER_WATERLINE)
    }

    test("a squeezed lift keeps the top of the world under it, keeps its order, and sets the datum at the sea") {
        for (datumY in listOf(VerticalWindow.MIN_Y, -38, 0, 46)) {
            val lift = Lift(datumY, Terrain.ONE_SEA_LEVEL, Lift.squeezeToFit(datumY, Terrain.ONE_SEA_LEVEL))
            check(lift.heightOf(datumY) == Terrain.ONE_SEA_LEVEL)
            check(lift.heightOf(VerticalWindow.HIGHEST_BLOCK_Y) <= VerticalWindow.HIGHEST_BLOCK_Y) {
                "a datum at $datumY lifted the top of the world to ${lift.heightOf(VerticalWindow.HIGHEST_BLOCK_Y)}"
            }
            val heights = (VerticalWindow.MIN_Y..VerticalWindow.HIGHEST_BLOCK_Y).map(lift::heightOf)
            check(heights.zipWithNext().all { (lower, higher) -> lower <= higher }) { "a datum at $datumY lifted out of order" }
        }
    }
}) {
    private companion object {
        const val SALT = 0x5EAL
    }
}
