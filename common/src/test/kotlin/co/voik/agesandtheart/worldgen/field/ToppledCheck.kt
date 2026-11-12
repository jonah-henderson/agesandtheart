package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec

/** `fallen obelisks`: a standing shape knocked over, its stump where it stood and the rest lying on the ground beyond. */
class ToppledCheck : FunSpec({

    /** An obelisk's shaft: three across, ten high, standing on the ground. */
    val shaft = Box(-1, 0, -1, 1, 9, 1)

    test("the stump stands where the shape stood, and nothing of it above") {
        val toppled = Toppled(shaft)
        check(toppled.columnSpans(0, 0).ranges == listOf(0..<Toppled.STUMP)) { "the stump is ${toppled.columnSpans(0, 0).ranges}" }
    }

    test("the rest lies on the ground beyond the stump, as deep as the shape was across") {
        val toppled = Toppled(shaft)
        val lying = (2..20).filter { z -> toppled.columnSpans(0, z).ranges.isNotEmpty() }
        check(lying.size == 10 - Toppled.STUMP) { "${lying.size} blocks of the shaft lie, at $lying" }
        check(lying.first() > 2) { "the shaft lies against its stump, at ${lying.first()}" }
        val across = toppled.columnSpans(0, lying.first()).ranges
        check(across == listOf(0..2)) { "the shaft lies as $across, not on the ground three high" }
        check(toppled.horizontalReach >= lying.last()) { "the reach ${toppled.horizontalReach} stops short of ${lying.last()}" }
    }

    test("a shape no taller than it is deep stays standing") {
        val squat = Box(-4, 0, -4, 4, 3, 4)
        check(Toppled.of(squat) === squat) { "a squat block was knocked over" }
        check(Toppled.of(shaft) is Toppled) { "the shaft was left standing" }
    }

    test("it reads back as it was written") {
        val toppled: TerrainField = Toppled(shaft)
        val written = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, toppled).getOrThrow()
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, written).getOrThrow()
        check(read == toppled) { "$written read back as $read" }
    }
})
