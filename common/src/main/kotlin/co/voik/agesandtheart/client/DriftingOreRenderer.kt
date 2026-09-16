package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.DriftingOre
import co.voik.agesandtheart.content.OreClusters
import co.voik.agesandtheart.content.AgeContent
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * A body of drifting ore, drawn as the cluster of blocks it is (design §7.1.2).
 *
 * **Whole blocks, submitted one at a time**, which the 26.1 renderer treats as an ordinary thing to do:
 * `submitMovingBlock` is what a falling block and a piston-shoved block already use, and a cluster is that
 * a hundred times. Nothing is baked, no vertex buffer is built and no pipeline is registered — the
 * contraption-style machinery every mod written before this version needs is machinery 26.1 does not.
 *
 * **Only the cells anything can see are submitted.** A cell walled in on six faces draws nothing and still
 * costs a submit, and a weathered cube is mostly walled in: at the top tier that is 133 submits instead of
 * 169. See [OreClusters.facesOf], which is also the honest measure of what a body costs to draw.
 *
 * **And each cell can see its neighbours**, which is what stops the whole thing flickering. A
 * `MovingBlockRenderState` answers `getBlockState` with air everywhere but its own position — right for the
 * one falling block it was written for, and wrong for a cluster: every cell drew all six faces, so each
 * shared face was drawn twice in exactly the same plane and z-fought as the camera moved. The state *is*
 * the level `tesselateBlock` culls against, so [ClusterView] answering with the rest of the cluster is the
 * whole fix — and it deletes the hidden geometry rather than merely stopping it fighting.
 *
 * **The shape is a number, so nothing about the cluster is sent.** Server and client weather the same cube
 * from the same seed, so the body a player is standing on is the body the server is moving.
 */
class DriftingOreRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<DriftingOre, DriftingOreRenderState>(context) {

    override fun createRenderState() = DriftingOreRenderState()

    override fun extractRenderState(entity: DriftingOre, state: DriftingOreRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val at = BlockPos.containing(entity.x, entity.y, entity.z)
        val level = entity.level() as? ClientLevel
        // **A render state is new every frame and nothing can be kept on it.**
        // `EntityRenderer.createRenderState(entity, partialTicks)` is final and allocates one through
        // `createRenderState()` before every extract, so a memo hung here never hits — it looked like it
        // worked and cached nothing. The cells themselves are memoised in `OreClusters.facesOf` instead,
        // which is where the expensive half was; what is left is one small state per drawn cell, which is
        // exactly what `FallingBlockRenderer` allocates per falling block per frame.
        //
        // These cannot be pooled across bodies either: `submitMovingBlock` stores the state *by reference*
        // and draws it at flush, so two bodies sharing one would both come out where the second is.
        val cluster = clusterOf(entity.shape, entity.tier)
        val span = OreClusters.spanOf(entity.tier).toInt()
        state.cells = OreClusters.facesOf(entity.shape, entity.tier).map { cell ->
            val drawn = ClusterView()
            val where = at.offset(cell.at)
            drawn.origin = at
            drawn.cluster = cluster
            drawn.span = span
            // **A seed that does not travel with the body.** Deepslate has four variants — plain and
            // mirrored, each at two turns — picked from `blockState.getSeed(randomSeedPos)`, so feeding it
            // the *world* position re-rolled every cell of the cluster each time the body crossed a block
            // boundary and the whole rock visibly re-tiled as it drifted. The cell's own offset never
            // moves; the shape and tier are mixed in so two bodies of different rock are not tiled alike.
            drawn.randomSeedPos = cell.at.offset(entity.shape, entity.tier, entity.shape)
            drawn.blockPos = where
            drawn.blockState = cluster.getValue(cell.at)
            // **Lit where it is rather than where it came from.** A body at the build limit is in full
            // skylight and one in a player's shadow is not, and a cluster lit from a single point would
            // read as a sticker at exactly the moment somebody flew up to it.
            if (level != null) {
                drawn.biome = level.getBiome(where)
                drawn.cardinalLighting = level.cardinalLighting()
                drawn.lightEngine = level.lightEngine
            }
            Drawn(cell.at, drawn)
        }
    }

    /**
     * Every cell of a body and what it is drawn as, by offset — **memoised, because it never changes**.
     *
     * This is what each cell is handed so it can see its neighbours, so it has to hold the whole cluster
     * rather than the visible shell: a shell cell's inward face is hidden by a cell nothing else can see.
     */
    private fun clusterOf(shape: Int, tier: Int): Map<BlockPos, BlockState> =
        clusters.computeIfAbsent(shape * DriftingOre.MOST_TIERS + tier) {
            OreClusters.of(shape, tier).associate { cell ->
                cell.at to
                    if (cell.isCrystal) AgeContent.ARC_CRYSTAL_BLOCK_BLOCK.defaultBlockState()
                    else ROCK.defaultBlockState()
            }
        }

    override fun submit(
        state: DriftingOreRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        for (drawn in state.cells) {
            poseStack.pushPose()
            // A block model is drawn from its own corner, so a cell at offset `o` fills `o` to `o + 1` —
            // which is what puts the cube's middle on the entity and makes the drawing agree with
            // `DriftingOre.makeBoundingBox`, since the offsets run from `-side / 2`.
            poseStack.translate(drawn.at.x.toDouble(), drawn.at.y.toDouble(), drawn.at.z.toDouble())
            collector.submitMovingBlock(poseStack, drawn.block)
            poseStack.popPose()
        }
        super.submit(state, poseStack, collector, camera)
    }

    /** One cell of the cluster: where it sits against the middle, and the block being drawn there. */
    class Drawn(val at: BlockPos, val block: MovingBlockRenderState)

    /**
     * A cell that knows what is beside it, so vanilla can cull the faces nobody can see.
     *
     * `getBlockState` is the only thing `shouldRenderFace` asks, and the height window comes with it: the
     * base class is a world one block tall, which would put half a cluster outside it.
     */
    private class ClusterView : MovingBlockRenderState() {
        var origin: BlockPos = BlockPos.ZERO
        var cluster: Map<BlockPos, BlockState> = emptyMap()
        var span: Int = 1

        override fun getBlockState(pos: BlockPos): BlockState =
            cluster[pos.subtract(origin)] ?: Blocks.AIR.defaultBlockState()

        override fun getMinY(): Int = origin.y - span

        override fun getHeight(): Int = span * 2
    }

    private companion object {
        private val clusters = java.util.concurrent.ConcurrentHashMap<Int, Map<BlockPos, BlockState>>()

        /**
         * What the rock between the crystal is drawn as, until the asset pass draws one of ours.
         *
         * Deepslate rather than stone: it is dark enough that the crystal in it reads as the bright thing,
         * which is the whole of what a body is meant to look like from a distance.
         */
        private val ROCK = Blocks.DEEPSLATE
    }
}

class DriftingOreRenderState : EntityRenderState() {
    var cells: List<DriftingOreRenderer.Drawn> = emptyList()
}
