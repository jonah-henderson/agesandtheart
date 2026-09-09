package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.DriftingOre
import co.voik.agesandtheart.age.phenomena.OreClusters
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
        // **Built once per body and then only refreshed**, because this runs per body per frame and a top
        // tier is over a hundred cells: rebuilding the list would be a hundred allocations a frame each,
        // for a rock whose shape cannot change. Only where the cells *are* moves.
        if (state.shape != entity.shape || state.tier != entity.tier) {
            state.shape = entity.shape
            state.tier = entity.tier
            state.cells = OreClusters.facesOf(entity.shape, entity.tier).map { cell ->
                val drawn = MovingBlockRenderState()
                drawn.blockState =
                    if (cell.isCrystal) AgeContent.ARC_CRYSTAL_BLOCK_BLOCK.defaultBlockState()
                    else ROCK.defaultBlockState()
                Drawn(cell.at, drawn)
            }
        }
        for (drawn in state.cells) {
            val where = at.offset(drawn.at)
            drawn.block.randomSeedPos = where
            drawn.block.blockPos = where
            // **Lit where it is rather than where it came from.** A body at the build limit is in full
            // skylight and one in a player's shadow is not, and a cluster lit from a single point would
            // read as a sticker at exactly the moment somebody flew up to it.
            if (level != null) {
                drawn.block.biome = level.getBiome(where)
                drawn.block.cardinalLighting = level.cardinalLighting()
                drawn.block.lightEngine = level.lightEngine
            }
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

    private companion object {
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

    /** What [cells] was built from, so it is only built again when the rock is a different rock. */
    var shape: Int = -1
    var tier: Int = -1
}
