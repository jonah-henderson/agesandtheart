package co.voik.agesandtheart.age.aspect

import io.kotest.core.spec.style.FunSpec

/**
 * The stretch of an axis a word allows, and the curve an evocative word bends inside it (design §4.4).
 *
 * A span is not a value and not a filter: it **compresses the whole axis into a stretch**, so a warm world
 * still has its warmer and cooler places. Everything here is a property of that compression — that it stays
 * inside the stretch, that it never doubles back, and that the bend means the one thing it claims to mean.
 */
class SpanCheck : FunSpec({

    /** Every remapped value lands in the span, bent or not — a bend moves weight, never bounds. */
    test("a remapped value never leaves its span") {
        for (span in SAMPLES) {
            for (step in 0..STEPS) {
                val natural = (Span.NATURAL_LEAST + step * NATURAL_STEP).toFloat()
                val landed = span.remap(natural)
                check(landed >= span.least - SLACK && landed <= span.most + SLACK) {
                    "$span sent $natural to $landed, which is outside it"
                }
            }
        }
    }

    /**
     * And the order survives. Monotonicity is what keeps the climate noise's spatial structure: bend a
     * world cool and its warm places are still its warmest, merely fewer.
     */
    test("a bend never doubles back") {
        for (span in SAMPLES) {
            var last = Float.NEGATIVE_INFINITY
            for (step in 0..STEPS) {
                val landed = span.remap((Span.NATURAL_LEAST + step * NATURAL_STEP).toFloat())
                check(landed >= last - SLACK) { "$span went backwards at step $step: $landed after $last" }
                last = landed
            }
        }
    }

    /**
     * **The bend is where the middle of the world falls**, which is the whole reason it is a place rather
     * than a strength — a reader can hold "half the world sits below this" where they cannot hold "0.7 of
     * skew". The natural axis's own midpoint is what a median maps from.
     */
    test("the bend is where the middle lands") {
        for (span in SAMPLES) {
            val middle = span.remap(NATURAL_MIDDLE)
            val expected = span.least + span.bend * span.width
            check(kotlin.math.abs(middle - expected) < SLACK) {
                "$span put the middle of the world at $middle, not at $expected"
            }
        }
    }

    /** An unbent span is the linear one it always was, so nothing that never mentions a bend moves. */
    test("an unbent span is linear") {
        val span = Span(0.2, 0.8)
        check(span.bend == Span.EVEN) { "a span nobody bent came out at ${span.bend}" }
        check(kotlin.math.abs(span.remap(NATURAL_MIDDLE) - 0.5f) < SLACK) {
            "an unbent 0.2..0.8 put the middle at ${span.remap(NATURAL_MIDDLE)}"
        }
    }

    /** A recipe holds the bend, or an Age would rebuild without the lean its writer gave it. */
    test("a bend survives being written down") {
        for (span in SAMPLES) {
            val spelled = span.spelled()
            val read = Span.read(spelled) ?: error("'$spelled' did not read back as a span at all")
            check(read == span) { "'$spelled' read back as $read, not $span" }
        }
        check(Span.read("0.2..0.8")?.bend == Span.EVEN) { "a span with no bend written did not come back even" }
        check(Span.BEND_MARK !in Span(0.2, 0.8).spelled()) { "an unbent span spelled a bend it does not have" }
    }
})

private val SAMPLES = listOf(
    Span(-1.0, 1.0),
    Span(0.2, 0.8),
    Span(0.4, 0.9, bend = 0.05),
    Span(-0.95, -0.45, bend = 0.95),
    Span(-1.0, 1.0, bend = 0.575),
    // A span pinned to one value: width zero, where a bend has nowhere to move anything.
    Span(0.3, 0.3, bend = 0.2),
)

/** The natural axis's own midpoint, which is what a median maps from. */
private const val NATURAL_MIDDLE = 0.0f

private const val STEPS = 40
private const val NATURAL_STEP = (Span.NATURAL_MOST - Span.NATURAL_LEAST) / STEPS

/** Floats, and a curve — so equality is a neighbourhood rather than a point. */
private const val SLACK = 1e-4f
