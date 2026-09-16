package co.voik.agesandtheart.command

import co.voik.agesandtheart.worldgen.field.Spans

/** Blocks to a chunk, as a shift. */
internal const val CHUNK_BITS = 4

internal const val PER_CENT = 100.0

/** Degrees in a turn, for naming the quarter a curtain crosses. */
internal const val WHOLE_COMPASS = 360

/**
 * The nearest of the eight points to [bearingDegrees], where zero is north.
 *
 * Three things say a direction in prose and each phrases it differently, so what they share is the
 * arithmetic and not the sentence. **Note this is a bearing rather than a yaw** — Minecraft's zero
 * faces south, so anything reading an entity's rotation has half a turn to add first.
 */
internal fun compassPointFor(bearingDegrees: Float): String {
    val points = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    val step = (Math.round(bearingDegrees / (WHOLE_COMPASS / points.size)) % points.size + points.size) % points.size
    return points[step]
}

internal const val HALF_COMPASS = 180.0f

/** Spans as a reader can check against a coordinate, which is the whole use of a probe. */
internal fun said(spans: Spans): String =
    if (spans.ranges.isEmpty()) "nothing"
    else spans.ranges.joinToString { range -> "y=${range.first}..${range.last}" }

