package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * **A shape must not come apart when it is made bigger.**
 *
 * A formation is built from coordinates, and resizing scales each of them on its own — so two parts that
 * touch at one size need not touch at another. `obelisks` was a shaft topping at `max_y` 11 under a cap
 * based at 12: adjacent as written, and three blocks apart at `colossal`, where the cap floated over the
 * column (Jonah, 2026-09-03).
 *
 * The invariant is the one that catches that without knowing what any shape is meant to look like: **a
 * column that is solid may not break into more pieces when the shape is made bigger.** A ring keeps its
 * hole and an arch its opening, because both have them at every size; and a column the shape did not
 * reach before is left out of it, since an edge moving under rounding is not a shape coming apart.
 */
@Tags(NEEDS_REGISTRIES)
class ShippedShapesCheck : FunSpec({

    val shipped = File("src/main/resources/data/agesandtheart/worldgen/feature")

    /** Every shape of every formation the pack ships, by the file it came from. */
    val shapes: List<Pair<String, TerrainField>> = shipped.listFiles()
        .orEmpty()
        .filter { it.extension == "json" }
        .sortedBy { it.name }
        // Formations only. Not every configured feature the pack ships is one built out of shapes — a
        // volcano's vents are found in the terrain rather than described — and reading `shapes` off one
        // that has none is a null in a spec constructor rather than a legible failure.
        .filter { file ->
            JsonParser.parseString(file.readText()).asJsonObject.get("type")?.asString == FORMATION
        }
        .flatMap { file ->
            // Beside the type rather than under a `config`: 26.3 folded a feature's configuration into
            // the feature, so its fields are the object's own.
            val feature = JsonParser.parseString(file.readText()).asJsonObject
            feature.getAsJsonArray("shapes").mapIndexed { at, shape ->
                val decoded = TerrainField.CODEC.parse(JsonOps.INSTANCE, shape)
                    .getOrThrow { failure -> AssertionError("${file.name} shape $at: $failure") }
                "${file.nameWithoutExtension}[$at]" to decoded
            }
        }

    test("the pack ships shapes at all") {
        check(shapes.isNotEmpty()) { "no formation shapes were read from ${shipped.absolutePath}" }
    }

    /**
     * **`colossal` is a hundred blocks, whatever the shape is measured across.**
     *
     * The governing dimension differs — height for an obelisk, a spike and an arch; diameter for a ring
     * and a boulder; the base for a pyramid — but every one of them is the *largest* dimension the shape
     * has, so one rule covers them all without this having to know which is which.
     *
     * A standard nobody checks is a standard that drifts: the shapes were authored at four different
     * scales before it was one (Jonah, 2026-09-03).
     */
    test("a colossal formation is about a hundred blocks") {
        for ((name, shape) in shapes) {
            val huge = shape.resized(COLOSSAL, 0)
            val reach = kotlin.math.ceil(huge.horizontalReach).toInt()
            var lowest = Int.MAX_VALUE
            var highest = Int.MIN_VALUE
            var widest = 0
            for (offsetX in -reach..reach) {
                for (offsetZ in -reach..reach) {
                    val spans = huge.columnSpans(offsetX, offsetZ).ranges
                    if (spans.isEmpty()) continue
                    lowest = minOf(lowest, spans.first().first)
                    highest = maxOf(highest, spans.last().last)
                    widest = maxOf(widest, maxOf(kotlin.math.abs(offsetX), kotlin.math.abs(offsetZ)))
                }
            }
            check(lowest != Int.MAX_VALUE) { "$name lays nothing at all when made colossal" }
            val tall = highest - lowest + 1
            val across = widest * 2 + 1
            val governing = maxOf(tall, across)
            check(governing in SMALLEST..LARGEST) {
                "$name is $governing blocks across its longest side when colossal ($tall tall, $across " +
                    "wide), where the standard is about $ABOUT"
            }
        }
    }

    test("no shape breaks into more pieces when it is resized") {
        for ((name, shape) in shapes) {
            val reach = kotlin.math.ceil(shape.horizontalReach).toInt()
            for (factor in listOf(2.0, 4.0)) {
                val bigger = shape.resized(factor, 0)
                for (offsetX in -reach..reach) {
                    for (offsetZ in -reach..reach) {
                        val whole = shape.columnSpans(offsetX, offsetZ).ranges.size
                        // A column outside the shape that a bigger one reaches has not come apart; its
                        // edge has simply moved, which every rounding at a boundary does.
                        if (whole == 0) continue
                        val scaledX = (offsetX * factor).toInt()
                        val scaledZ = (offsetZ * factor).toInt()
                        val grown = bigger.columnSpans(scaledX, scaledZ).ranges.size
                        check(grown <= whole) {
                            "$name came apart at ${factor}x: the column at ($offsetX, $offsetZ) is $whole " +
                                "piece(s) as written and $grown at ($scaledX, $scaledZ) when resized"
                        }
                    }
                }
            }
        }
    }
}) {
    private companion object {
        /** What `colossal` multiplies a size by — `FeatureShape.sizeFactor` at the top of the axis. */
        const val COLOSSAL = 4.0

        const val ABOUT = 100
        const val SMALLEST = 80
        const val LARGEST = 120

        const val FORMATION = "agesandtheart:formation"
    }
}
