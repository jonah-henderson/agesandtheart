package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.AgeRenderTypes
import co.voik.agesandtheart.imaging.CarouselButton
import co.voik.agesandtheart.imaging.CarouselMotion
import co.voik.agesandtheart.imaging.ImagingClientLook
import co.voik.agesandtheart.imaging.ImagingComponents
import co.voik.agesandtheart.imaging.ImagingContent
import co.voik.agesandtheart.imaging.PictureSizes
import co.voik.agesandtheart.imaging.SlideCarouselBlock
import co.voik.agesandtheart.imaging.SlideCarouselBlockEntity
import net.minecraft.core.Direction
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

class SlideCarouselRenderState : BlockEntityRenderState() {
    var thrown: ProjectorThrow? = null
    var picture: Identifier? = null
    var showsItsLight = false
    var facing: Direction = Direction.NORTH
    var toLens: Int? = null
    var effect: ProjectorLights.Effect? = null
    var lightTint: Int = WHITE
    var development = 1.0f
    var machinery: CarouselMotion.Pose? = null
    val occupied = BooleanArray(SlideCarouselBlockEntity.SLIDES)

    /** The picture on each slot's slide, or null where it is a blank plate or its picture has not come. */
    val pictures = arrayOfNulls<Identifier>(SlideCarouselBlockEntity.SLIDES)
    val buttonDepth = FloatArray(CarouselButton.entries.size)
}

private const val WHITE = 0xFFFFFF

/**
 * The slide carousel's moving machinery and the projector's throw.
 *
 * The machinery is Jonah's model's moving parts ([CarouselParts]) posed as [CarouselMotion] has them: the disk turned to
 * its slot, a slide in every occupied slot, the selector arm raised or lowered with its hook, the buttons pressed or not.
 *
 * The throw is the raised slide's picture on whatever the cone lights, as light added to what is there, so it shows best
 * in the dark; only once a slide is up and the machinery still. Light reaches the focusing lens whenever the carousel is
 * lit, and passes through the raised slide and out of the front when it is up. Drawn wherever the carousel is, in sight
 * or not, since the picture lands well away from it. The throw is worked out again once a second, or as soon as the
 * assembly changes.
 */
class SlideCarouselRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<SlideCarouselBlockEntity, SlideCarouselRenderState> {

    override fun createRenderState() = SlideCarouselRenderState()

    override fun extractRenderState(
        carousel: SlideCarouselBlockEntity,
        state: SlideCarouselRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(carousel, state, partialTicks, cameraPosition, breakProgress)
        // A carousel drawn is in the client's level, which is the one that knows how its blocks are tinted.
        val level = carousel.level as? ClientLevel ?: return
        val machinery = carousel.motion.poseAt(partialTicks, carousel.frontSlot) { !carousel.slideIn(it).isEmpty }
        state.machinery = machinery
        for (slot in 0..<SlideCarouselBlockEntity.SLIDES) {
            val slide = carousel.slideIn(slot)
            state.occupied[slot] = !slide.isEmpty
            // The raised slide is looked at closely, the rest from a step away.
            val size = if (slot == machinery.atTheArm) PictureSizes.Size.MIDDLE else PictureSizes.Size.SMALL
            state.pictures[slot] = slide.get(ImagingComponents.PICTURE)?.let { PictureTextures.textureFor(it.id, size) }
        }
        for (button in CarouselButton.entries) {
            state.buttonDepth[button.ordinal] = pressedDepth(level.gameTime - carousel.pressedAt[button.ordinal] + partialTicks)
        }
        val front = carousel.front()
        val pictureId = front.get(ImagingComponents.PICTURE)?.id
        val isABlankPlate = front.`is`(ImagingContent.PHOTOGRAPHIC_PLATE)
        state.facing = carousel.blockState.getValue(SlideCarouselBlock.FACING)
        state.toLens = ProjectorThrow.assemblyAt(level, carousel.blockPos, carousel.blockState)?.toLens
        state.showsItsLight = ProjectorThrow.isLit(level, carousel.blockPos, carousel.blockState)
        val behind = level.getBlockState(carousel.blockPos.relative(state.facing.opposite))
        val lightAt = carousel.blockPos.relative(state.facing.opposite)
        val lightEffect = ProjectorLights.effectOf(level, lightAt, behind)
        state.lightTint = if (state.showsItsLight) lightEffect.tint else WHITE
        val throwsAnything = pictureId != null || isABlankPlate
        val isUp = throwsAnything && machinery.isReady
        state.thrown = if (isUp) ProjectorThrows.thrownBy(level, carousel.blockPos, carousel.blockState) else null
        val picture = if (isABlankPlate) PictureTextures.blank() else pictureId?.let { PictureTextures.textureFor(it, PictureSizes.Size.MIDDLE) }
        state.development = pictureId?.let(PictureTextures::development) ?: 1.0f
        val effect = state.thrown?.let { lightEffect }
        state.effect = effect
        // Unfocused, the picture lands blurred: its colours and shapes, and a hint of what a lens would bring.
        val isUnfocused = state.thrown?.assembly?.isFocused == false
        val sharpness = if (isUnfocused && picture != null) BlurredPictures.blurred(picture) ?: picture else picture
        state.picture = if (sharpness != null && effect?.overlay != null) MaskedPictures.masked(sharpness, effect.overlay) else sharpness
    }

    override fun submit(state: SlideCarouselRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        drawTheMachinery(state, poseStack, collector)
        val thrown = state.thrown ?: return
        val picture = state.picture ?: return
        val effect = state.effect ?: return
        val origin = state.blockPos
        val flicker = 1.0f - effect.flicker * ProjectorRays.flicker(System.currentTimeMillis(), origin.asLong())
        val spread = if (thrown.assembly.isFocused) 1.0f else UNFOCUSED_LIGHT
        val flickered = ProjectorLights.brightnessOf(effect, thrown.assembly.light) * flicker * state.development * spread
        val colour = OPAQUE or ARGB.scaleRGB(ARGB.multiply(effect.tint, thrown.assembly.tint), flickered.coerceIn(0.0f, 1.0f))
        // Light added to what is already there, so a dim light washes out in daylight rather than throwing a dark patch. A
        // purpose-built light may be drawn as a decal instead, clear however the wall is lit, where a player sets it so.
        val isADecal = effect.isPerfect && ImagingClientLook.perfectThrow.get() == ImagingClientLook.PerfectThrow.DECAL
        val drawnAs = if (isADecal) RenderTypes.eyes(picture) else RenderTypes.energySwirl(picture, 0.0f, 0.0f)
        collector.submitCustomGeometry(poseStack, drawnAs) { pose, buffer ->
            val pieces = thrown.pieces
            var at = 0
            while (at < pieces.size) {
                buffer.addVertex(pose, pieces[at] - origin.x, pieces[at + 1] - origin.y, pieces[at + 2] - origin.z)
                    .setColor(colour)
                    .setUv(pieces[at + 3], pieces[at + 4])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, 0.0f, 1.0f, 0.0f)
                at += FLOATS_A_CORNER
            }
        }
    }

    /**
     * The disk, its slides, the arm and hook and the buttons, in the model's own pixels turned the way the carousel
     * faces; and the light inside it, where it is lit.
     */
    private fun drawTheMachinery(state: SlideCarouselRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val parts = CarouselParts.parts ?: return
        val machinery = state.machinery ?: return
        val light = state.lightCoords
        poseStack.pushPose()
        poseStack.translate(HALF, 0.0f, HALF)
        poseStack.rotateDegrees(Axis.YP, -quarterTurnsOf(state.facing) * QUARTER_TURN)
        poseStack.translate(-HALF, 0.0f, -HALF)
        poseStack.scale(PIXEL, PIXEL, PIXEL)

        turnedAbout(poseStack, parts.axle, Axis.YP, -machinery.diskSlot * DEGREES_A_SLOT) {
            CarouselParts.draw(DISK, poseStack, collector, light)
        }
        for (slot in 0..<SlideCarouselBlockEntity.SLIDES) {
            if (!state.occupied[slot]) continue
            turnedAbout(poseStack, parts.axle, Axis.YP, (slot - machinery.diskSlot) * DEGREES_A_SLOT) {
                // The slide at the arm turns up with the hook, about the hook's pivot where the lowered arm meets it.
                val hookTurn = if (slot == machinery.atTheArm) -HALF_TURN * machinery.hookUp else 0.0f
                turnedAbout(poseStack, parts.slidePivot, Axis.ZP, hookTurn) {
                    CarouselParts.draw(SLIDE, poseStack, collector, light, picture = state.pictures[slot])
                }
            }
        }

        poseStack.pushPose()
        poseStack.translate(0.0f, -ARM_DROP * machinery.armDrop, 0.0f)
        CarouselParts.draw(ARM, poseStack, collector, light)
        turnedAbout(poseStack, parts.hookPivot, Axis.ZP, -HALF_TURN * machinery.hookUp) {
            CarouselParts.draw(HOOK, poseStack, collector, light)
        }
        poseStack.popPose()

        for (button in CarouselButton.entries) {
            poseStack.pushPose()
            // In toward the base, which the buttons stand out from on the model's west.
            poseStack.translate(BUTTON_TRAVEL * state.buttonDepth[button.ordinal], 0.0f, 0.0f)
            CarouselParts.draw(BUTTONS.getValue(button), poseStack, collector, light)
            poseStack.popPose()
        }

        if (state.showsItsLight) drawTheLightInside(state, parts, machinery, poseStack, collector)
        poseStack.popPose()
    }

    /**
     * From the light behind into the focusing lens; and, with a slide up, from the lens to the slide and from the slide on
     * to the lens ahead, gathering to its middle, or out of the front spilling and fading where there is none. Light past
     * the slide carries the picture's colours, so it alone is tinged.
     */
    private fun drawTheLightInside(
        state: SlideCarouselRenderState,
        parts: CarouselParts.Parts,
        machinery: CarouselMotion.Pose,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        val lensMiddle = middleOf(parts.lensLow, parts.lensHigh)
        val slideMiddle = middleOf(parts.heldSlideLow, parts.heldSlideHigh)
        val millis = System.currentTimeMillis()
        val seed = state.blockPos.asLong()
        val tint = state.lightTint
        collector.submitCustomGeometry(poseStack, AgeRenderTypes.addedLight) { pose, buffer ->
            val behind = Vec3(lensMiddle.x, lensMiddle.y, BACK)
            val lensBack = Vec3(lensMiddle.x, lensMiddle.y, parts.lensHigh.z.toDouble())
            ProjectorRays.beam(pose, buffer, behind, lensBack, INTO_THE_LENS, LENS_RADIUS, INSIDE, 1.0f, tint, millis, seed)
            if (!machinery.isReady) return@submitCustomGeometry
            val lensFront = Vec3(lensMiddle.x, lensMiddle.y, parts.lensLow.z.toDouble())
            val slideBack = Vec3(slideMiddle.x, slideMiddle.y, parts.heldSlideHigh.z.toDouble())
            val slideFront = Vec3(slideMiddle.x, slideMiddle.y, parts.heldSlideLow.z.toDouble())
            ProjectorRays.beam(pose, buffer, lensFront, slideBack, LENS_RADIUS, SLIDE_RADIUS, INSIDE, 1.0f, tint, millis, seed)
            val toLens = state.toLens
            if (toLens != null) {
                // The middle of the lens block's near face, toLens blocks ahead of a model whose front is at nought: the
                // plate stands off the middle, and its light is gathered to the lens's middle all the same.
                val atTheLens = Vec3(BLOCK / 2.0, BLOCK / 2.0, BLOCK - BLOCK * toLens)
                ProjectorRays.beam(pose, buffer, slideFront, atTheLens, SLIDE_RADIUS, GATHERED_AT_THE_LENS, INSIDE, LIGHT_AT_THE_LENS, tint, millis, seed, tinged = true)
            } else {
                val spilled = Vec3(slideMiddle.x, slideMiddle.y, -SPILLS_OUT)
                ProjectorRays.beam(pose, buffer, slideFront, spilled, SLIDE_RADIUS, SPILLED_WIDE, INSIDE, 0.0f, tint, millis, seed, tinged = true)
            }
        }
    }

    private fun middleOf(low: org.joml.Vector3f, high: org.joml.Vector3f) =
        Vec3((low.x + high.x) / 2.0, (low.y + high.y) / 2.0, (low.z + high.z) / 2.0)

    /** [draw] turned [degrees] about [axis] through [pivot]. */
    private inline fun turnedAbout(poseStack: PoseStack, pivot: org.joml.Vector3f, axis: Axis, degrees: Float, draw: () -> Unit) {
        poseStack.pushPose()
        poseStack.translate(pivot.x, pivot.y, pivot.z)
        poseStack.rotateDegrees(axis, degrees)
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z)
        draw()
        poseStack.popPose()
    }

    /** How far in a button is, nought to one, [ticksSince] its press: straight in, held a moment, and back out. */
    private fun pressedDepth(ticksSince: Float): Float = when {
        ticksSince < 0.0f -> 0.0f
        ticksSince < PRESSING -> ticksSince / PRESSING
        ticksSince < PRESSING + HELD_IN -> 1.0f
        ticksSince < PRESSING + HELD_IN + RELEASING -> 1.0f - (ticksSince - PRESSING - HELD_IN) / RELEASING
        else -> 0.0f
    }

    /** How many quarter turns clockwise from above the blockstate turns the model, which faces north. */
    private fun quarterTurnsOf(facing: Direction): Int = when (facing) {
        Direction.EAST -> 1
        Direction.SOUTH -> 2
        Direction.WEST -> 3
        else -> 0
    }

    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = VIEW_DISTANCE

    private companion object {
        const val FLOATS_A_CORNER = 5
        const val OPAQUE = 0xFF shl 24
        const val FULL_BRIGHT = 0xF000F0
        const val VIEW_DISTANCE = 96

        const val DISK = "disk"
        const val SLIDE = "slide"
        const val ARM = "arm"
        const val HOOK = "hook"
        val BUTTONS = mapOf(CarouselButton.CLOCKWISE to "button_clockwise", CarouselButton.COUNTER_CLOCKWISE to "button_counter_clockwise")

        const val HALF = 0.5f
        const val PIXEL = 1.0f / 16.0f
        const val QUARTER_TURN = 90.0f
        const val HALF_TURN = 180.0f
        const val DEGREES_A_SLOT = 360.0f / SlideCarouselBlockEntity.SLIDES

        /** How far the arm comes down onto a slot, and a button goes in, in pixels. */
        const val ARM_DROP = 2.0f
        const val BUTTON_TRAVEL = 0.4f

        /** A press, in ticks: in, held, out. */
        const val PRESSING = 2.0f
        const val HELD_IN = 3.0f
        const val RELEASING = 4.0f

        /** The light inside, in pixels: from the back of the block, across the lens, the slide, and out of the front. */
        const val BACK = 16.0
        const val BLOCK = 16.0
        const val INTO_THE_LENS = 3.0
        const val LENS_RADIUS = 2.0
        const val SLIDE_RADIUS = 1.2
        const val INSIDE = 1.6f

        /** Out to a lens: still a hand across where it meets the glass, so it never narrows to a line. */
        const val GATHERED_AT_THE_LENS = 2.6
        const val LIGHT_AT_THE_LENS = 0.55f

        /** With no lens: a block and a little out, widening, and fading to nothing. */
        const val SPILLS_OUT = 18.0
        const val SPILLED_WIDE = 9.0

        /** How much of a lens's light an unfocused throw lands with, spread so much wider. */
        const val UNFOCUSED_LIGHT = 0.5f
    }
}

/** Each carousel's throw, kept until its assembly changes or a second has passed, since walls change and nothing says so. */
object ProjectorThrows {

    private class Kept(val assembly: ProjectorThrow.Assembly, val thrown: ProjectorThrow, val madeAt: Long)

    private val kept = HashMap<BlockPos, Kept>()

    private const val KEPT_FOR_MILLIS = 1_000L

    fun thrownBy(level: net.minecraft.world.level.Level, carousel: BlockPos, state: net.minecraft.world.level.block.state.BlockState): ProjectorThrow? {
        val assembly = ProjectorThrow.assemblyAt(level, carousel, state) ?: ProjectorThrow.unfocusedAt(level, carousel, state)
        if (assembly == null) {
            kept.remove(carousel)
            return null
        }
        val now = System.currentTimeMillis()
        val current = kept[carousel]
        val isStillGood = current != null && current.assembly == assembly && now - current.madeAt < KEPT_FOR_MILLIS
        if (isStillGood) return current.thrown
        val thrown = ProjectorThrow.of(level, carousel, assembly)
        kept[carousel.immutable()] = Kept(assembly, thrown, now)
        return thrown
    }

    fun forget() {
        kept.clear()
    }
}
