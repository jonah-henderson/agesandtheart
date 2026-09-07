package co.voik.agesandtheart.client

import co.voik.ephemeris.Rgba
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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/** What the renderer needs of a lump of molten rock, taken off it before drawing. */
class MoltenLumpRenderState : EntityRenderState() {
    val block = MovingBlockRenderState()

    /** How far it moved over the last tick, which is what a streak is laid along. */
    var travel: Vec3 = Vec3.ZERO
}

/**
 * How hot a lump still looks, from how fast it is still going.
 *
 * **Speed stands in for heat**, which costs nothing and is right about both ends: a body crossing the sky
 * is at its brightest, and one that has ploughed into a pond and is bobbing there has gone out. It also
 * means the cooling needs no clock and nothing sent — the thing that fades is the thing that slows.
 */
private fun heatOf(travel: Vec3): Float =
    (travel.length() / MoltenLumpRenderer.GLOWS_AT).toFloat().coerceIn(0.0f, 1.0f)

/**
 * A lump of rock in flight, drawn as the block it is.
 *
 * **Vanilla's moving-block submission rather than a model of ours**, which is the same bargain the rime
 * crystal's tint makes: magma is already a block that reads as molten at a distance, and drawing it costs
 * nothing but the state it is drawn from.
 *
 * A bomb, one of the gobbets it throws off and a meteor differ in [scale], in the [block] they are made of
 * and in whether they [burn] — three parameters rather than three renderers.
 */
open class MoltenLumpRenderer<T : Entity>(
    context: EntityRendererProvider.Context,
    private val scale: Float,
    private val block: Block = Blocks.MAGMA_BLOCK,
    private val burn: Rgba? = null,
) : EntityRenderer<T, MoltenLumpRenderState>(context) {

    override fun createRenderState() = MoltenLumpRenderState()

    override fun extractRenderState(entity: T, state: MoltenLumpRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val at = BlockPos.containing(entity.x, entity.boundingBox.maxY, entity.z)
        state.block.randomSeedPos = at
        state.block.blockPos = at
        state.block.blockState = block.defaultBlockState()
        // Where it actually went rather than its velocity, so a streak is drawn off movement a viewer can
        // see: a client that is lerping an entity rather than integrating it still moves it.
        state.travel = Vec3(entity.x - entity.xOld, entity.y - entity.yOld, entity.z - entity.zOld)
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
        if (burn != null) submitBurning(state, poseStack, collector, camera, burn)
        super.submit(state, poseStack, collector, camera)
    }

    /**
     * The light one of these carries: a bloom around it and a streak behind it.
     *
     * **Drawn geometry rather than particles**, because a trail made of sprites is a line of dots at these
     * speeds however many are thrown, and cannot glow at all — an ordinary particle blends with the sky
     * where a meteor has to be brighter than it (Jonah, walked).
     */
    private fun submitBurning(
        state: MoltenLumpRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        colour: Rgba,
    ) {
        val heat = heatOf(state.travel)
        if (heat <= GONE_OUT) return
        val towardCamera = camera.pos.subtract(state.x, state.y, state.z)
        val burning = colour.copy(alpha = colour.alpha * heat)
        AddedLight.halo(collector, poseStack, towardCamera, burning, scale * BLOOM_ACROSS)
        AddedLight.streak(collector, poseStack, state.travel, towardCamera, burning, scale * STREAK_ACROSS)
    }

    companion object {
        private const val HALF_BLOCK = 0.5

        /** The bloom and the head of the streak, as multiples of how big the lump is drawn. */
        private const val BLOOM_ACROSS = 1.1
        private const val STREAK_ACROSS = 0.55

        /** How fast a lump has to be going to be at its brightest, in blocks a tick. */
        const val GLOWS_AT = 3.0
        private const val GONE_OUT = 0.02f

        /**
         * A meteor's own light: violet-white, and the same violet its storm hangs in the sky and casts on
         * the ground, so the three read as one arrival.
         */
        val COLD_FIRE = Rgba(0.72f, 0.56f, 1.0f, 0.9f)

        /** A bomb is a block of rock; a gobbet is a splash off one. */
        const val WHOLE_LUMP = 1.0f
        const val GOBBET = 0.4f

        /**
         * A meteor, drawn bigger than a gobbet and smaller than a bomb.
         *
         * **Sized against the light that announced it** rather than against its own hitbox: a walk read
         * the handover from sky to rock as a swap because the rock was much the smaller of the two, so
         * this came up as the sky came down and they meet in the middle.
         */
        const val METEOR = 0.75f

        /** And made of a violet stone rather than magma, since everything else about one is violet. */
        val METEOR_ROCK: Block = Blocks.AMETHYST_BLOCK
    }
}
