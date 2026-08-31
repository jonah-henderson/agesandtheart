package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.SandColumn
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier

/** What the renderer needs of a column, taken off it before drawing. */
class SandColumnRenderState : EntityRenderState() {
    var halfWidth = 0.0f
    var reach = 0.0f
    var heading = 0.0f
}

/**
 * A column of sand, standing from the ground it walks on to past the top of the sky.
 *
 * **A placeholder, and knowingly so.** What is drawn here is four flat faces at one tone, which is enough
 * to see a column arrive, widen, wander and close — and nothing like the falling sand it has to read as.
 * The roil that does that runs per fragment on a pipeline of ours, and it is the next step.
 *
 * **Culling is declined outright** ([affectedByCulling]), because the entity's box is its footprint and
 * what is drawn is three hundred blocks taller than that. There is no `getBoundingBoxForCulling` in 26.1 to
 * widen the box through instead, so this is the seam.
 */
class SandColumnRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<SandColumn, SandColumnRenderState>(context) {

    override fun createRenderState(): SandColumnRenderState = SandColumnRenderState()

    override fun extractRenderState(entity: SandColumn, state: SandColumnRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.halfWidth = entity.halfWidth
        state.heading = entity.yRot
        // From the ground the entity rides to the top of the world, which is where a column ends and where
        // its fade will begin.
        state.reach = (entity.level().maxY - entity.y).toFloat()
    }

    /** Never — see the class doc. */
    override fun affectedByCulling(entity: SandColumn): Boolean = false

    override fun submit(
        state: SandColumnRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.halfWidth <= NOTHING || state.reach <= NOTHING) return
        poseStack.pushPose()
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading))
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(SAND)) { pose, buffer ->
            val half = state.halfWidth
            // Wound anticlockwise seen from outside, so all four faces are outward-facing and the near two
            // are the ones that survive backface culling.
            face(pose, buffer, -half, -half, half, -half, state.reach)
            face(pose, buffer, half, -half, half, half, state.reach)
            face(pose, buffer, half, half, -half, half, state.reach)
            face(pose, buffer, -half, half, -half, -half, state.reach)
        }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    /** One side of the prism, from the ground to [reach] above it. */
    private fun face(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        fromX: Float,
        fromZ: Float,
        toX: Float,
        toZ: Float,
        reach: Float,
    ) {
        val tall = reach / TEXTURE_BLOCKS
        corner(pose, buffer, fromX, GROUND, fromZ, LEFT, tall)
        corner(pose, buffer, toX, GROUND, toZ, RIGHT, tall)
        corner(pose, buffer, toX, reach, toZ, RIGHT, TOP)
        corner(pose, buffer, fromX, reach, fromZ, LEFT, TOP)
    }

    private fun corner(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        atX: Float,
        atY: Float,
        atZ: Float,
        u: Float,
        v: Float,
    ) {
        buffer.addVertex(pose, atX, atY, atZ)
            .setColor(TONE, TONE, TONE, VEIL)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            // Unlit, so a column is as pale at midnight as at noon — it is lit by nothing in the world.
            .setLight(FULL_BRIGHT)
            .setNormal(pose, 0.0f, 1.0f, 0.0f)
    }

    private companion object {
        /** Vanilla's own picture; nothing here ships a texture. */
        val SAND: Identifier = Identifier.withDefaultNamespace("textures/block/sand.png")

        const val NOTHING = 0.0f
        const val GROUND = 0.0f
        const val TONE = 1.0f
        const val VEIL = 0.6f
        const val LEFT = 0.0f
        const val RIGHT = 1.0f
        const val TOP = 0.0f

        /** How many blocks one tile of the picture covers, so the grain does not stretch up the column. */
        const val TEXTURE_BLOCKS = 4.0f

        /** 26.1 removed `LightTexture`; the packing is sky in the high half and block in the low. */
        const val BRIGHTEST = 15
        const val SKY_SHIFT = 20
        const val BLOCK_SHIFT = 4
        const val FULL_BRIGHT = (BRIGHTEST shl SKY_SHIFT) or (BRIGHTEST shl BLOCK_SHIFT)
    }
}
