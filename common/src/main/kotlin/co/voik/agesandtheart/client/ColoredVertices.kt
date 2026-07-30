package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.Rgba
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * Vertex helpers that keep draw loops reading like English.
 *
 * **In `common` even though they only ever run on a client**, because both loaders' sky code needs them now (see
 * [AgeSky]). `common` compiles against the merged jar, so `VertexConsumer` resolves here; the rule that keeps that
 * honest is simply that nothing server-side may reach these — and nothing does, since the only callers are
 * renderers reached from a client entrypoint.
 */

/** Adds a position vertex tinted with [color]. */
fun VertexConsumer.addColoredVertex(matrix: Matrix4f, x: Float, y: Float, z: Float, color: Rgba): VertexConsumer =
    addVertex(matrix, x, y, z).setColor(color.red, color.green, color.blue, color.alpha)

/** Adds a position vertex at [position], tinted with [color]. */
fun VertexConsumer.addColoredVertex(matrix: Matrix4f, position: Vec3, color: Rgba): VertexConsumer =
    addColoredVertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), color)
