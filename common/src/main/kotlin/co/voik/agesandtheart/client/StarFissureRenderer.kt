package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
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

    /**
     * **As far as the Age around it is drawn**, where a block entity is otherwise given sixty-four blocks.
     *
     * Vanilla never had to raise it: a stronghold's portal is something you stand over. A tear is landscape,
     * and the one at an Age's floor is a hundred-odd blocks down a cleared shaft — so at the default it was
     * never visible from the top of its own shaft, and the fall it exists to offer could not be seen to be
     * there. This is `BeaconRenderer`'s own answer to the same question, and it ties the field to the render
     * distance rather than to a number of our own.
     *
     * The cost is a render state per tear per frame over a much larger volume, which the tears at an Age's
     * floor are the worry for rather than the fissures on its surface. If a walk finds one heavy, the
     * batched pass the wounds use is the shape to copy (design §5.2.1) — and [StarFissureVeil] is the proof
     * it would work, drawing the same field from plain geometry with no block entity at all.
     */
    override fun getViewDistance(): Int = Minecraft.getInstance().options.effectiveRenderDistance * A_CHUNK

    override fun submit(
        state: EndPortalRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // **Not while somebody is falling through one.** [StarFissureVeil] leaves a hole where the tear is
        // so the Age can be seen receding through it, and a tear's own downward face is the one thing that
        // can stand in that hole — filling the opening with the very field the opening exists to interrupt.
        // It is why the Age showed only while the camera was still inside the block, where every face
        // points away. Everything else a fissure would draw is behind the veil, so declining all of it
        // costs nothing.
        if (StarFissureVeil.hidingTheTears()) return
        submitCube(state.facesToShow, RenderTypes.endPortal(), poseStack, submitNodeCollector)
    }

    private companion object {
        /** Render distance is in chunks and a view distance is in blocks. */
        const val A_CHUNK = 16
    }
}
