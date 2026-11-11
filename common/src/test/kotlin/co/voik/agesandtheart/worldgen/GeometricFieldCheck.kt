package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * The geometric landform built of a shipped formation's shapes: what stands rises off the plain, what is dug sinks
 * into it, and nothing of either reaches past the plain on the other side.
 */
@Tags(NEEDS_REGISTRIES)
class GeometricFieldCheck : FunSpec({

    fun shipped(name: String): Formation {
        val json = JsonParser.parseString(File("src/main/resources/data/agesandtheart/worldgen/feature/$name.json").readText())
        return Formation.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow { AssertionError("$name: $it") }
    }

    /** The highest solid block of every column in a square about the origin, where most of the shapes stand. */
    fun topsOf(field: TerrainField): List<Int> =
        (-200..200 step 2).flatMap { x -> (-200..200 step 2).map { z -> field.columnSpans(x, z).highestSolidY ?: Int.MIN_VALUE } }

    test("obelisks stand on the plain, as tall as the tallest of the pyramids") {
        val tops = topsOf(GeometricField.world("grid", shipped("obelisks")))
        check(tops.all { it >= PLAIN_TOP }) { "the plain was cut into: ${tops.min()}" }
        check(tops.count { it > PLAIN_TOP } > 0) { "nothing stood on the plain" }
        check(tops.max() in PLAIN_TOP + 28..PLAIN_TOP + 40) { "the tallest obelisk stood ${tops.max() - PLAIN_TOP} over the plain" }
    }

    test("pits are dug into the plain, and nothing stands on it") {
        val tops = topsOf(GeometricField.world("grid", shipped("pits")))
        check(tops.all { it <= PLAIN_TOP }) { "something stood on the plain: ${tops.max()}" }
        check(tops.count { it < PLAIN_TOP } > 0) { "nothing was dug" }
    }

    test("spaced, the shapes stand further apart, and unspaced they stand as they did") {
        fun standing(field: TerrainField) = topsOf(field).count { it > PLAIN_TOP }
        val ordinary = standing(GeometricField.world("grid"))
        check(standing(GeometricField.world("grid", spacing = 0.0)) == ordinary) { "a spacing of nothing moved the pyramids" }
        check(standing(GeometricField.world("grid", spacing = 1.0)) < ordinary) { "spaced further apart, as many stood" }
        check(standing(GeometricField.world("grid", spacing = -1.0)) > ordinary) { "spaced closer, no more stood" }
    }

    test("a great one stands where a landmark is drawn") {
        val landmarks = LandVariety.landmarksFor(strength = 2.0, landScale = SizeScale.ORDINARY, salt = 7L)
        val withLandmarks = GeometricField.world("grid", shipped("obelisks"), salt = 7L, landmarks = landmarks)
        val plain = topsOf(GeometricField.world("grid", shipped("obelisks"), salt = 7L))
        val marked = topsOf(withLandmarks)
        val wideTops = (-1500..1500 step 8).flatMap { x ->
            (-1500..1500 step 8).map { z -> withLandmarks.columnSpans(x, z).highestSolidY ?: Int.MIN_VALUE }
        }
        check(marked.zip(plain).all { (withThem, without) -> withThem >= without }) { "a landmark took something away" }
        check(wideTops.max() > PLAIN_TOP + 60) { "no great obelisk stood anywhere: the tallest was ${wideTops.max() - PLAIN_TOP}" }
    }
})

private const val PLAIN_TOP = 63
