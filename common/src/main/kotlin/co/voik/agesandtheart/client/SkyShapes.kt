package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.plus
import co.voik.agesandtheart.math.times
import net.minecraft.world.phys.Vec3

/**
 * The geometry a sky renderer needs that is not about any one sky: a camera-surrounding box and a fade
 * curve. Keeping it small is the point — cloud decks, star reveals and celestial bodies belong to a
 * renderer, and hoisting any here would rebuild the coupling the Spire/general split exists to remove.
 *
 * There used to be a third, `isSkyHidden`, restoring the cases vanilla refuses to draw a sky in. It is
 * gone because vanilla now makes that check itself in `LevelRenderer.addSkyPass`, before anything of ours
 * is reached.
 */
object SkyShapes {

    /** A box of the given radius, each face split into [segments]² quads (so a gradient over it is smooth). */
    fun subdividedCube(radius: Double, segments: Int): List<List<Vec3>> {
        val low = -radius
        val size = 2.0 * radius
        // Each face: an origin corner and two full edge vectors spanning it. Winding is irrelevant (culling off).
        val faces = listOf(
            Triple(Vec3(low, radius, low), Vec3(size, 0.0, 0.0), Vec3(0.0, 0.0, size)), // top
            Triple(Vec3(low, low, low), Vec3(size, 0.0, 0.0), Vec3(0.0, 0.0, size)), // bottom
            Triple(Vec3(radius, low, low), Vec3(0.0, size, 0.0), Vec3(0.0, 0.0, size)), // east
            Triple(Vec3(low, low, low), Vec3(0.0, size, 0.0), Vec3(0.0, 0.0, size)), // west
            Triple(Vec3(low, low, radius), Vec3(size, 0.0, 0.0), Vec3(0.0, size, 0.0)), // south
            Triple(Vec3(low, low, low), Vec3(size, 0.0, 0.0), Vec3(0.0, size, 0.0)), // north
        )
        val quads = ArrayList<List<Vec3>>(faces.size * segments * segments)
        for ((origin, edgeU, edgeV) in faces) {
            for (i in 0..<segments) {
                val u0 = i.toDouble() / segments
                val u1 = (i + 1).toDouble() / segments
                for (j in 0..<segments) {
                    val v0 = j.toDouble() / segments
                    val v1 = (j + 1).toDouble() / segments
                    quads += listOf(
                        origin + edgeU * u0 + edgeV * v0,
                        origin + edgeU * u0 + edgeV * v1,
                        origin + edgeU * u1 + edgeV * v1,
                        origin + edgeU * u1 + edgeV * v0,
                    )
                }
            }
        }
        return quads
    }

    /** Hermite fade from 0 at [edge0] to 1 at [edge1]. */
    fun smoothstep(edge0: Double, edge1: Double, x: Double): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }
}
