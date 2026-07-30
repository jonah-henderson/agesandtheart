package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.plus
import co.voik.agesandtheart.math.times
import net.minecraft.client.Camera
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3

/**
 * The few things every sky renderer needs, whichever loader is asking and whichever Age it is drawing.
 *
 * Only three qualify, and keeping it to three is the point: a camera-surrounding box, a fade curve, and the
 * question of whether a sky should be drawn at all. Cloud decks, star reveals and celestial bodies all belong to
 * one renderer or another — hoisting any of them here would quietly rebuild the coupling the Spire/general split
 * exists to remove.
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

    /**
     * The cases vanilla refuses to draw a sky in, restored.
     *
     * Both loaders' hooks fire *before* vanilla's own checks and then suppress the rest of the method, so without
     * this a custom sky draws straight through lava, powder snow, blindness and darkness — which ours did until
     * 2026-07-29.
     *
     * **[isFoggy] is the one asymmetry between the loaders, and it points the right way.** Vanilla computes it in
     * `renderLevel` from `effects().isFoggyAt(...)` and the boss-bar overlay. NeoForge's hook is *handed* it, so it
     * passes the real value; Fabric's `WorldRenderContext` does not carry it, so Fabric passes `false`. Both halves
     * are false for an Age today, so nothing is lost yet — but a future Age with real fog will honour it on
     * NeoForge and cannot on Fabric, and that is a property of Fabric's hook rather than a bug in ours.
     */
    fun isSkyHidden(camera: Camera, isFoggy: Boolean): Boolean {
        if (isFoggy) return true
        val submerged = camera.fluidInCamera
        if (submerged == FogType.POWDER_SNOW || submerged == FogType.LAVA) return true
        val viewer = camera.entity as? LivingEntity ?: return false
        return viewer.hasEffect(MobEffects.BLINDNESS) || viewer.hasEffect(MobEffects.DARKNESS)
    }

    /** Hermite fade from 0 at [edge0] to 1 at [edge1]. */
    fun smoothstep(edge0: Double, edge1: Double, x: Double): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }
}
