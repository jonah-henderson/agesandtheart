package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.field.Ellipsoid
import io.kotest.core.spec.style.FunSpec

/**
 * **The spawner's cache answers exactly what the field would**, which is the whole of what it owes.
 *
 * It exists for speed — `skyIsOpenAt` was 86% of the server thread before it — but the reason it caches
 * the *field's* answer rather than reading a heightmap is that the field is the meaning we want, so
 * agreeing with the field is the only correctness there is.
 *
 * The case worth writing down is **negative coordinates**. A column's slot is the low four bits of its x
 * and z, and a sign error there does not crash or blank out: it quietly hands one column another column's
 * rock, a few hundred blocks from the origin, on whichever side of it nobody happened to walk.
 */
class OpenSkyHeightsCheck : FunSpec({

    // Wide enough that a column and its neighbours differ, and centred so both signs of both axes fall
    // inside it — a slab would agree with any indexing at all, including a broken one.
    val field = Ellipsoid(centerX = 0, centerY = 64, centerZ = 0, radiusXZ = 140.0, radiusY = 40.0)

    fun columnsAcross(step: Int) = (-200..200 step step).flatMap { x -> (-200..200 step step).map { z -> x to z } }

    test("every column answers what the field says") {
        val cache = OpenSkyHeights(field)
        for ((x, z) in columnsAcross(step = 7)) {
            val fromField = field.columnSpans(x, z).highestSolidY
            val fromCache = settled(cache, x, z)
            check(fromCache == fromField) { "at ($x, $z) the cache said $fromCache where the field says $fromField" }
        }
    }

    test("a second reading of a column is the same as the first") {
        val cache = OpenSkyHeights(field)
        val asked = columnsAcross(step = 11)
        val first = asked.map { (x, z) -> settled(cache, x, z) }
        val again = asked.map { (x, z) -> settled(cache, x, z) }
        check(first == again) { "reading the same columns twice gave different answers" }
    }

    test("neighbouring columns are told apart") {
        // The failure this is for: a slot collision hands a column its neighbour's rock, and every
        // individual answer still looks plausible. Only the *spread* gives it away.
        val cache = OpenSkyHeights(field)
        val alongTheRim = (-160..160).map { x -> settled(cache, x, 0) }
        val fromField = (-160..160).map { x -> field.columnSpans(x, 0).highestSolidY }
        check(alongTheRim == fromField) { "the cache's profile across the field is not the field's" }
        check(fromField.distinct().size > 4) { "this field is too flat to prove anything — pick another" }
    }

    test("a column the fill told it of is answered from that, and the field is never asked") {
        val cache = OpenSkyHeights(field)
        cache.remember(3, -5, 77)
        cache.remember(4, -5, null)
        check(cache.highestSolidYAt(3, -5) { error("the stand-in was asked") } == 77) { "the told column was not answered" }
        check(cache.highestSolidYAt(4, -5) { error("the stand-in was asked") } == null) { "the told empty column came back" }
    }

    test("a column nobody told it of is answered by the stand-in at once, and by the field after") {
        val cache = OpenSkyHeights(field)
        check(cache.highestSolidYAt(9, 9) { STAND_IN } == STAND_IN) { "an unknown column was worked out on the asking thread" }
        check(settled(cache, 9, 9) == field.columnSpans(9, 9).highestSolidY) { "the background did not bring the field's answer" }
    }

    test("a column with no rock in it stays absent rather than becoming a height") {
        val cache = OpenSkyHeights(field)
        // Well outside the ellipsoid, so the field has nothing there and must not come back as a number.
        check(settled(cache, 5_000, 5_000) == null) { "empty sky came back as rock" }
        check(settled(cache, 5_000, 5_000) == null) { "empty sky came back as rock on the second ask" }
    }
})

/** Marks the stand-in's answer, which no column reaches. */
private const val STAND_IN = Int.MAX_VALUE

/** [cache]'s answer once the background has brought the field's, waiting a few seconds at most. */
private fun settled(cache: OpenSkyHeights, worldX: Int, worldZ: Int): Int? {
    repeat(SETTLING_TRIES) {
        val answer = cache.highestSolidYAt(worldX, worldZ) { STAND_IN }
        if (answer != STAND_IN) return answer
        Thread.sleep(1)
    }
    error("($worldX, $worldZ) never settled")
}

private const val SETTLING_TRIES = 5_000
