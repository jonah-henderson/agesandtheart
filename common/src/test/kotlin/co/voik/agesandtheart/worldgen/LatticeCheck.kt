package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/**
 * The lattice underground, checked for what makes it a lattice rather than a sponge: whole storeys only,
 * shafts that reach every storey they cross, and rock between passages thicker than the passages
 * themselves at every size — the promise that a colossal one still reads as tunnels through stone and
 * never as blocks of stone hanging in a void.
 */
class LatticeCheck : FunSpec({

    val lowY = -59
    val highY = 40
    val sizes = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)

    test("every open run in a column is a whole storey or a shaft, and inside the band") {
        for (size in sizes) {
            val lattice = LatticeField.passages(lowY, highY, salt = 7L, scale = SizeScale.factorAt(size))
            for (x in -100..100 step 3) for (z in -100..100 step 5) {
                for (run in lattice.columnSpans(x, z).ranges) {
                    check(run.first >= lowY && run.last <= highY) { "size $size: ($x, $z) opens $run outside the band" }
                    val tall = run.last - run.first + 1
                    check(tall == lattice.height || tall > lattice.spacingY) {
                        "size $size: ($x, $z) opens a run $tall tall, neither a storey (${lattice.height}) nor a shaft"
                    }
                }
            }
        }
    }

    test("a crossing opens from the lowest storey's floor to the highest storey's roof") {
        val lattice = LatticeField.passages(lowY, highY, salt = 7L)
        val tunnels = lattice.columnSpans(lattice.offsetX, lattice.offsetZ + lattice.width).ranges
        check(tunnels.size >= 2) { "only ${tunnels.size} storeys fitted an ordinary band" }
        val shaft = lattice.columnSpans(lattice.offsetX, lattice.offsetZ).ranges
        check(shaft == listOf(tunnels.first().first..tunnels.last().last)) {
            "the shaft at the crossing is $shaft, against storeys $tunnels"
        }
    }

    test("a colossal lattice in a hills band always has two storeys, so it always has shafts") {
        val hillsTop = 19
        for (salt in 0L..<200L) {
            val lattice = LatticeField.passages(lowY, hillsTop, salt, scale = SizeScale.COLOSSAL)
            val storeys = lattice.columnSpans(lattice.offsetX, lattice.offsetZ + lattice.width).ranges
            check(storeys.size == 2) { "salt $salt laid ${storeys.size} colossal storeys between $lowY and $hillsTop" }
        }
    }

    test("the rock between passages is thicker than the passages, at every size") {
        for (size in sizes) {
            val lattice = LatticeField.passages(lowY, highY, scale = SizeScale.factorAt(size))
            check(lattice.spacingXZ - lattice.width > lattice.width) {
                "size $size: tunnels ${lattice.width} wide every ${lattice.spacingXZ} leave too little rock"
            }
            check(lattice.spacingY - lattice.height > lattice.height) {
                "size $size: storeys ${lattice.height} tall every ${lattice.spacingY} leave too little rock"
            }
        }
    }

    test("each step up the size axis makes the passages bigger") {
        val widths = sizes.map { LatticeField.passages(lowY, highY, scale = SizeScale.factorAt(it)).width }
        check(widths.zipWithNext().all { (smaller, larger) -> larger > smaller }) { "widths by size: $widths" }
    }

    test("two Ages lay their grids in different places") {
        val one = LatticeField.passages(lowY, highY, salt = 1L)
        val other = LatticeField.passages(lowY, highY, salt = 2L)
        check(one != other) { "salts 1 and 2 laid the same lattice" }
    }

    test("the lattice round-trips through its codec") {
        val written: TerrainField = LatticeField.passages(lowY, highY, salt = 7L, scale = SizeScale.COLOSSAL)
        val encoded = TerrainField.CODEC.encodeStart(JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the lattice would not encode: $failure") }
        val read = TerrainField.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the lattice would not read back: $failure") }
        check(read == written) { "read back as a different lattice" }
    }
})
