package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NoiseField
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/**
 * The hollow underground's promise: nothing is left but a crust and a floor, and the void under the crust
 * never touches the open space of the world it was cut from — so it is never seen from the surface, from
 * a cliff face or from a seabed, only broken into.
 */
class HollowedOutCheck : FunSpec({

    val crust = 8
    val floorY = -59

    fun voidTouchesOpenSpace(base: TerrainField, xs: IntRange, zs: IntRange, ys: IntRange): String? {
        val hollowed = HollowedOut(base, crust, floorY)
        fun isOpenInBase(x: Int, y: Int, z: Int) = !base.columnSpans(x, z).contains(y)
        fun isVoid(x: Int, y: Int, z: Int) = !isOpenInBase(x, y, z) && !hollowed.columnSpans(x, z).contains(y)
        for (x in xs) for (z in zs) for (y in ys) {
            if (!isVoid(x, y, z)) continue
            val neighbours = listOf(x + 1 to z, x - 1 to z, x to z + 1, x to z - 1)
            val besideOpenSpace = neighbours.any { (nx, nz) -> isOpenInBase(nx, y, nz) } ||
                isOpenInBase(x, y + 1, z) || isOpenInBase(x, y - 1, z)
            if (besideOpenSpace) return "the void at ($x, $y, $z) opens onto the world's own open space"
        }
        return null
    }

    test("a flat world keeps its floor and a crust, and nothing between") {
        val hollowed = HollowedOut(Slab(lowY = -64, highY = 60), crust, floorY)
        val kept = hollowed.columnSpans(5, 5).ranges
        val (floor, surface) = kept
        check(kept.size == 2 && floor.first == -64 && floor.last >= floorY) { "kept $kept" }
        check(surface == 60 - crust + 1..60) { "the crust is $surface, not the top $crust blocks" }
    }

    test("a cliff face stays sealed") {
        val cliff = Union(listOf(Slab(lowY = -64, highY = 20), Box(0, -64, -50, 50, 80, 50)))
        val failure = voidTouchesOpenSpace(cliff, -12..12, -3..3, -64..90)
        check(failure == null) { failure ?: "" }
    }

    test("an overhang's underside stays sealed") {
        val overhang = Union(listOf(Slab(lowY = -64, highY = 20), Box(-50, 40, -50, 50, 80, 50)))
        val failure = voidTouchesOpenSpace(overhang, -3..3, -3..3, -64..90)
        check(failure == null) { failure ?: "" }
    }

    test("rolling hills stay sealed") {
        val failure = voidTouchesOpenSpace(NoiseField.hills(), 0..40, 0..40, -64..160)
        check(failure == null) { failure ?: "" }
    }

    test("the hollow round-trips through its codec") {
        val written: TerrainField = HollowedOut(Slab(lowY = -64, highY = 60), crust, floorY)
        val encoded = TerrainField.CODEC.encodeStart(JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the hollow would not encode: $failure") }
        val read = TerrainField.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the hollow would not read back: $failure") }
        check(read == written) { "read back as a different hollow" }
    }
})
