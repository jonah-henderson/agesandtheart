package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks

/** What the renderer needs of a lump of molten rock, taken off it before drawing. */
class MoltenLumpRenderState : EntityRenderState() {
    val block = MovingBlockRenderState()
}

/**
 * A lump of molten rock in flight, drawn as the block it is.
 *
 * **Vanilla's moving-block submission rather than a model of ours**, which is the same bargain the rime
 * crystal's tint makes: magma is already a block that reads as molten at a distance, and drawing it costs
 * nothing but the state it is drawn from.
 *
 * [scale] is the only thing that separates a bomb from one of the gobbets it throws off, which is why this
 * is one renderer and not two.
 */
open class MoltenLumpRenderer<T : Entity>(
    context: EntityRendererProvider.Context,
    private val scale: Float,
) : EntityRenderer<T, MoltenLumpRenderState>(context) {

    override fun createRenderState() = MoltenLumpRenderState()

    override fun extractRenderState(entity: T, state: MoltenLumpRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val at = BlockPos.containing(entity.x, entity.boundingBox.maxY, entity.z)
        state.block.randomSeedPos = at
        state.block.blockPos = at
        state.block.blockState = Blocks.MAGMA_BLOCK.defaultBlockState()
        val level = entity.level()
        if (level is ClientLevel) {
            state.block.biome = level.getBiome(at)
            state.block.cardinalLighting = level.cardinalLighting()
            state.block.lightEngine = level.lightEngine
        }
    }

    override fun submit(
        state: MoltenLumpRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        poseStack.pushPose()
        // Scaled first, so the centring below happens in the scaled frame — a block model starts at its
        // own corner, and half of it is half of whatever size it has been drawn at.
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-HALF_BLOCK, -HALF_BLOCK, -HALF_BLOCK)
        collector.submitMovingBlock(poseStack, state.block)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    companion object {
        private const val HALF_BLOCK = 0.5

        /** A bomb is a block of rock; a gobbet is a splash off one. */
        const val WHOLE_LUMP = 1.0f
        const val GOBBET = 0.4f
    }
}
