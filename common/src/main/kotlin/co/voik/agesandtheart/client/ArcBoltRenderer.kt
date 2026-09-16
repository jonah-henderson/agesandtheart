package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.ArcBolt
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.LightningBoltRenderer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Draws an [ArcBolt] as **vanilla's lightning, turned to point at what was bitten and scaled down**.
 *
 * **None of the geometry is ours and that is the whole design.** `LightningBoltRenderer.submit` is public
 * and its render state is a single `seed`, so the entire jagged four-layer additive bolt is reachable by
 * handing vanilla's renderer a `PoseStack` that has already been rotated and shrunk. What we write is the
 * transform; what a player sees is the game's own lightning.
 *
 * **Vanilla's bolt grows along +Y**, tight at the origin and fanning by the far end — which is the shape
 * this wants without any coaxing: an arc leaves the rod at a point and splays onto whatever it lands on.
 */
class ArcBoltRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<ArcBolt, ArcBoltRenderState>(context) {

    /**
     * Vanilla's, built from the context we were handed.
     *
     * Held rather than looked up: `EntityRendererProvider.Context` is exactly what its constructor wants,
     * so the one line that would otherwise have been a dispatcher lookup is a constructor call.
     */
    private val vanilla = LightningBoltRenderer(context)

    override fun createRenderState() = ArcBoltRenderState()

    /**
     * The whole arc, not just the rod it leaves from. Whoever is bitten is looking at what was bitten, and
     * culled on the rod alone the bolt vanished whenever the rod was off screen.
     */
    override fun getBoundingBoxForCulling(entity: ArcBolt): AABB =
        AABB(entity.position(), entity.position().add(entity.reachesTo)).inflate(FORK_MARGIN)

    override fun extractRenderState(entity: ArcBolt, state: ArcBoltRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.reaches = entity.reachesTo
        // **Off the entity id rather than rolled per frame.** A fresh seed each frame would make one bolt
        // flicker through a different shape sixty times a second; vanilla keeps one per bolt for its whole
        // life and never sends it, since which way a bolt forks is a client-side detail.
        state.seed = entity.id.toLong() * A_BOLT_APART
    }

    override fun submit(
        state: ArcBoltRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val span = state.reaches.length()
        if (span > NOTHING) {
            poseStack.pushPose()
            // Vanilla's bolt is drawn up the Y axis; this turns that axis onto the line to the victim.
            val heading = state.reaches.normalize()
            poseStack.mulPose(
                Quaternionf().rotateTo(
                    UPRIGHT.x.toFloat(), UPRIGHT.y.toFloat(), UPRIGHT.z.toFloat(),
                    heading.x.toFloat(), heading.y.toFloat(), heading.z.toFloat(),
                ),
            )
            // Along the arc so it ends on the victim, and across it so the fork is legible at a few blocks
            // rather than at the hundred and twenty-eight vanilla draws for. **The two are separate dials**
            // because they trade against each other: the geometry's thickness rides on the across scale,
            // so a wider fork is also a fatter bolt and a tight one is a thread.
            val along = span.toFloat() / A_VANILLA_BOLT
            poseStack.scale(along * FORK_ACROSS, along, along * FORK_ACROSS)
            // A fresh state per bolt, because vanilla's lambda reads the seed off it at flush rather than
            // at submit — one shared instance would give every bolt in the frame the last one's shape.
            val drawn = LightningBoltRenderState()
            drawn.seed = state.seed
            vanilla.submit(drawn, poseStack, collector, camera)
            poseStack.popPose()
        }
        super.submit(state, poseStack, collector, camera)
    }

    private companion object {
        /** How tall vanilla draws one: eight steps of sixteen blocks. */
        private const val A_VANILLA_BOLT = 128.0f

        /**
         * How far it forks across, against vanilla's own proportions.
         *
         * One is exactly vanilla's shape at a smaller size, which fans about a third of its own length.
         * Below one it tightens into a thread and above it splays. **The bolt's thickness rides on this**,
         * so a bolt that reads as too thin wants this raised and will fan more for it.
         */
        private const val FORK_ACROSS = 1.0f

        private const val NOTHING = 1.0e-6
        private val UPRIGHT = Vec3(0.0, 1.0, 0.0)

        /** Room round the arc's straight line for the fork to splay into. */
        private const val FORK_MARGIN = 1.0

        /** Any spread will do; this only has to make neighbouring entity ids fork differently. */
        private const val A_BOLT_APART = 0x9E_37_79_B1L
    }
}

class ArcBoltRenderState : EntityRenderState() {
    var reaches: Vec3 = Vec3.ZERO
    var seed: Long = 0L
}
