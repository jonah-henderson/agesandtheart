package co.voik.agesandtheart.age.aspect

/**
 * Which way a line runs, from where a word left its axis — see [Terrain.BEARING]. A line has no direction,
 * so the whole axis is one half-turn, and an axis nobody bounded runs north to south.
 *
 * **Word semantics rather than geometry**, which is why this sits with the aspect that decides it and not
 * with the field toolkit that draws it. `worldgen.field` is a foundation, and it used to translate an axis
 * itself — so the toolkit could not be compiled, or reasoned about, without the Art's vocabulary. A field
 * takes an angle now, and how a word's axis maps onto one is known only here.
 */
fun bearingAt(axis: Double?): Double =
    (axis?.let(Span.NATURAL::fractionOf) ?: Span.NATURAL.fractionOf(NORTH_SOUTH)) * HALF_TURN

/**
 * The bearing an unbounded axis runs along. A point on the axis rather than a value of its own: a writer
 * says a word, and the word bounds the axis.
 */
const val NORTH_SOUTH = Span.NATURAL_LEAST

private const val HALF_TURN = Math.PI
