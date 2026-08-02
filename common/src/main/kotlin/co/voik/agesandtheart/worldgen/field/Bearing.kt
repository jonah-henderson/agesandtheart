package co.voik.agesandtheart.worldgen.field

import kotlin.math.cos
import kotlin.math.sin

/**
 * A straight line laid across the world at some angle, which is what a [Canyon] runs down and an
 * [Escarpment] stands along.
 *
 * Two coordinates rather than one: a shape running along a line varies with **both** how far across the
 * line a column is and how far along it, and keeping them apart is what lets a line meander along its own
 * length rather than merely blur at its edges.
 */

/** How far along [bearing] this column lies. Zero runs the line along +Z, a quarter turn along +X. */
internal fun alongBearing(bearing: Double, worldX: Int, worldZ: Int): Double =
    alongBearing(bearing, worldX.toDouble(), worldZ.toDouble())

/** And how far across it — **signed**, so a shape can tell the two sides of the line apart. */
internal fun acrossBearing(bearing: Double, worldX: Int, worldZ: Int): Double =
    acrossBearing(bearing, worldX.toDouble(), worldZ.toDouble())

/** The same, for a point that has already been warped off the block lattice. */
internal fun alongBearing(bearing: Double, atX: Double, atZ: Double): Double =
    atX * sin(bearing) + atZ * cos(bearing)

internal fun acrossBearing(bearing: Double, atX: Double, atZ: Double): Double =
    atX * cos(bearing) - atZ * sin(bearing)

/** Which way a line runs, as a writer says it — see `Terrain.BEARING`. */
fun bearingNamed(name: String): Double = when (name) {
    "east_west" -> QUARTER_TURN
    "diagonal" -> QUARTER_TURN / 2
    else -> 0.0
}

private const val QUARTER_TURN = Math.PI / 2
