package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Dislocated
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/** Where an Age's ground is read from elsewhere: pure in its seed and extent, and the same for both kinds of rock. */
class DislocationCheck : FunSpec({

    val columns = (-40..40).flatMap { x -> (-40..40).map { z -> x * SAMPLE_STRIDE to z * SAMPLE_STRIDE } }

    fun share(dislocation: Dislocation) =
        columns.count { (x, z) -> !dislocation.shiftAt(x, z).isNone }.toDouble() / columns.size

    test("no extent moves nothing") {
        check(share(Dislocation(SEED, 0.0)) == 0.0) { "a dislocation of no extent moved ground" }
        check(!Dislocation(SEED, 0.0).touchesChunkAt(0, 0)) { "a dislocation of no extent touched a chunk" }
    }

    test("full extent covers a share of the ground, and never the whole of it") {
        val covered = share(Dislocation(SEED, 1.0))
        check(covered in LEAST_COVERED..MOST_COVERED) { "full extent covered $covered of the ground" }
    }

    test("what is foreign at one extent is foreign, and read from the same place, at every larger one") {
        val small = Dislocation(SEED, 0.25)
        val large = Dislocation(SEED, 0.75)
        for ((x, z) in columns) {
            val before = small.shiftAt(x, z)
            if (before.isNone) continue
            check(large.shiftAt(x, z) == before) { "($x, $z) was read from elsewhere at 0.25 and from somewhere else at 0.75" }
        }
    }

    test("a shift is whole chunks and a long way") {
        val dislocation = Dislocation(SEED, 1.0)
        for ((x, z) in columns) {
            val shift = dislocation.shiftAt(x, z)
            if (shift.isNone) continue
            check(shift.x % CHUNK == 0 && shift.z % CHUNK == 0) { "shift ${shift.x}, ${shift.z} is not whole chunks" }
            check(kotlin.math.abs(shift.x) >= LEAST_SHIFT && kotlin.math.abs(shift.z) >= LEAST_SHIFT) {
                "shift ${shift.x}, ${shift.z} is too near"
            }
        }
    }

    test("different patches are read from different places") {
        val dislocation = Dislocation(SEED, 1.0)
        val shifts = columns.map { (x, z) -> dislocation.shiftAt(x, z) }.filter { !it.isNone }.toSet()
        check(shifts.size > 1) { "every patch was read from the same place" }
    }

    test("a dislocated field answers with the column it was moved to, and survives its codec") {
        val dislocation = Dislocation(SEED, 1.0)
        val base = OverworldField.world()
        val field = Dislocated(base, dislocation)
        var moved = 0
        for ((x, z) in columns) {
            val shift = dislocation.shiftAt(x, z)
            val expected = if (shift.isNone) base.columnSpans(x, z) else base.columnSpans(x + shift.x, z + shift.z)
            if (!shift.isNone) moved++
            check(field.columnSpans(x, z).ranges == expected.ranges) { "($x, $z) did not answer with its source column" }
        }
        check(moved > 0) { "nothing was moved" }
        val written = TerrainField.CODEC.encodeStart(JsonOps.INSTANCE, field).getOrThrow()
        val read = TerrainField.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(read == field) { "the field did not survive its codec" }
    }
}) {
    private companion object {
        const val SEED = 0x5EEDL
        const val SAMPLE_STRIDE = 37
        const val LEAST_COVERED = 0.2
        const val MOST_COVERED = 0.55
        const val LEAST_SHIFT = 2048
        const val CHUNK = 16
    }
}
