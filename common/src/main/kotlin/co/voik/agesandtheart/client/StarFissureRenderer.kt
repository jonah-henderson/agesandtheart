package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * The starfield drawn to the **whole** block, where vanilla's end portal draws a slab of it.
 *
 * `TheEndPortalRenderer` puts its cube through a transformation that lifts it 0.375 and scales its height
 * to 0.375 — right for one layer lying on a floor, which is the only place vanilla ever puts one. A star
 * fissure is a *shaft* of them, and at that size each block drew a disc with a gap above and below it, so
 * falling down one looked like passing a stack of plates rather than falling through a tear (Jonah,
 * 2026-08-06, walked).
 *
 * Dropping the transformation is the whole of it: the cube is already a unit cube, and every face is drawn
 * because [co.voik.agesandtheart.worldgen.fissure.StarFissureBlockEntity] asks for them.
 */
class StarFissureRenderer : TheEndPortalRenderer() {

    override fun submit(
        state: EndPortalRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        submitCube(state.facesToShow, RenderTypes.endPortal(), poseStack, submitNodeCollector)
    }
}
