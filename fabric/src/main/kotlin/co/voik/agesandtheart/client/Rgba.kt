package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * An RGBA colour with channels in `0.0..1.0`. A small, testable value type so rendering code
 * passes named colours around instead of loose float quadruples.
 */
data class Rgba(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1.0f)

/** Linear interpolation toward [other]; [amount] 0 = this, 1 = other. */
fun Rgba.lerp(other: Rgba, amount: Float): Rgba = Rgba(
    red + (other.red - red) * amount,
    green + (other.green - green) * amount,
    blue + (other.blue - blue) * amount,
    alpha + (other.alpha - alpha) * amount,
)

/** Adds a position vertex tinted with [color] — keeps draw loops reading like English. */
fun VertexConsumer.addColoredVertex(matrix: Matrix4f, x: Float, y: Float, z: Float, color: Rgba): VertexConsumer =
    addVertex(matrix, x, y, z).setColor(color.red, color.green, color.blue, color.alpha)

/** Adds a position vertex at [position], tinted with [color]. */
fun VertexConsumer.addColoredVertex(matrix: Matrix4f, position: Vec3, color: Rgba): VertexConsumer =
    addColoredVertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), color)
