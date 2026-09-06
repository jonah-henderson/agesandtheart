package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.VolcanicBomb
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

/** What the renderer needs of a bomb, taken off it before drawing. */
class VolcanicBombRenderState : EntityRenderState() {
    val block = MovingBlockRenderState()
}

/**
 * A lump of molten rock in flight, drawn as the block it is.
 *
 * **Vanilla's moving-block submission rather than a model of ours**, which is the same bargain the rime
 * crystal's tint makes: magma is already a block that reads as molten at a distance, and drawing it costs
 * nothing but the state it is drawn from.
 */
class VolcanicBombRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<VolcanicBomb, VolcanicBombRenderState>(context) {

    override fun createRenderState() = VolcanicBombRenderState()

    override fun extractRenderState(entity: VolcanicBomb, state: VolcanicBombRenderState, partialTicks: Float) {
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
        state: VolcanicBombRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        poseStack.pushPose()
        // Centred on the entity rather than on the block's own corner, which is where a block model starts.
        poseStack.translate(HALF_BLOCK, HALF_BLOCK, HALF_BLOCK)
        poseStack.translate(-1.0, -1.0, -1.0)
        collector.submitMovingBlock(poseStack, state.block)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    private companion object {
        const val HALF_BLOCK = 0.5
    }
}
