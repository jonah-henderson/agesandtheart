package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import kotlin.math.sin

/**
 * A wound, drawn flickering (design §5.1).
 *
 * **The motion is the point.** A black cube somebody placed and a hole in the world look identical while
 * they are still; what separates them is that one is not holding steady. So the cube is rescaled every
 * frame between [SMALLEST] and [LARGEST], on two sine waves whose periods do not divide into each other —
 * which is what makes it read as unstable rather than as a pulse somebody animated.
 *
 * **The colour is wrong and known to be** (2026-08-07). §5.1 asks for pure black and unlit, and this draws
 * the end portal's starfield, because that is the one path in 26.1 already proven to put a whole cube of
 * shader through a block-entity renderer. It is a first pass to walk the *movement* against; the surface
 * belongs to Phase 9 with every other picture the mod is borrowing.
 */
class WoundRenderer : TheEndPortalRenderer() {

    override fun submit(
        state: EndPortalRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val scale = flickerNow()
        poseStack.pushPose()
        // About the block's own middle, so it breathes in place rather than growing off one corner.
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
        submitCube(state.facesToShow, RenderTypes.endPortal(), poseStack, submitNodeCollector)
        poseStack.popPose()
    }

    /**
     * Where the flicker is this instant.
     *
     * Wall-clock rather than the render state's tick, because it must not stop when the game is paused or
     * step in time with anything else on screen: a wound is not part of the world's rhythm. Read straight
     * from the system clock — a client-only draw with no need to be the same on two machines.
     */
    private fun flickerNow(): Float {
        val now = System.currentTimeMillis().toDouble()
        val fast = sin(now / FAST_PERIOD)
        val slow = sin(now / SLOW_PERIOD)
        // Two waves, unequal weights, so the peaks never land in the same place twice running.
        val swing = (fast * FAST_SHARE + slow * (1.0 - FAST_SHARE) + 1.0) / 2.0
        return (SMALLEST + (LARGEST - SMALLEST) * swing).toFloat()
    }

    private companion object {
        /** The range design §5.1 asks for: down to nearly gone, up to half again. */
        const val SMALLEST = 0.8
        const val LARGEST = 1.5

        // Deliberately not a ratio of one another, or the two waves would beat in a visible cycle.
        const val FAST_PERIOD = 83.0
        const val SLOW_PERIOD = 311.0

        /** How much of the swing the fast wave owns — most of it, so it reads as violent rather than as breathing. */
        const val FAST_SHARE = 0.7

        const val MIDDLE = 0.5
    }
}
