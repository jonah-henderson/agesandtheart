package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a chamber's lake on vanilla's rock comes through vanilla's carving whole (`HeldShore`). At this seed a cave runs
 * through the lake under (-43, 84), and before the lake was kept the carving left a dry shaft there with the lake
 * standing round it as walls (Jonah, 2026-11-17).
 */
@Tags(NEEDS_SERVER)
class ChamberLakesOnVanillasRockCheck : FunSpec({
    val server = DrivenServer.shared

    test("a cave through a chamber's lake on vanilla's rock leaves the lake whole") {
        server.run("age write vanillalakes $SEED age chambered underground green algae")
        server.run("execute in agesandtheart:vanillalakes run forceload add -48 80 -38 88")
        val dry = LAKE.filterNot { y -> "passed" in server.run("execute in agesandtheart:vanillalakes if block -43 $y 84 minecraft:water") }
        check(dry.isEmpty()) { "the lake stood dry under (-43, 84) at y=$dry" }
    }
}) {
    private companion object {
        const val SEED = -4846092010930367354L
        val LAKE = -41..-35
    }
}
