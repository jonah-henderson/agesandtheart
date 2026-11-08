package co.voik.agesandtheart.generation

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos

/**
 * A deep burial takes every start to its depth; a shallow one only some, a block or two down — `lost villages` mostly
 * standing, now and then sunk (Jonah).
 */
class StructureHeightsCheck : FunSpec({

    val starts = (0..<STARTS).map { BlockPos(it * CHUNK * VILLAGE_SPACING, 0, (it * 7919) % 20000 * CHUNK) }

    test("buried villages are all buried, deep") {
        val sunk = starts.map { StructureHeights.offsetAt(BURIED, it) }
        check(sunk.all { it != null && it <= -SURELY_SUNK }) { "a buried village stood at ${sunk.firstOrNull { it == null || it > -SURELY_SUNK }}" }
    }

    test("about half of lost villages sink, one to three blocks") {
        val sunk = starts.mapNotNull { StructureHeights.offsetAt(LOST, it) }
        val share = sunk.size.toDouble() / STARTS
        check(share in LOST_SHARE_ROUGHLY) { "$share of lost villages sank" }
        check(sunk.all { it in -3..-1 }) { "a lost village sank ${sunk.minOrNull()}" }
    }

    test("nought leaves a start as written") {
        check(starts.all { StructureHeights.offsetAt(0.0, it) == null }) { "a start at nought moved" }
    }
}) {
    private companion object {
        const val STARTS = 2000
        const val CHUNK = 16
        const val VILLAGE_SPACING = 34
        const val BURIED = -0.5
        const val LOST = -0.08
        const val SURELY_SUNK = 6
        val LOST_SHARE_ROUGHLY = 0.35..0.65
    }
}
