package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.AgeRenderTypes
import co.voik.agesandtheart.imaging.ImagingClientLook
import co.voik.agesandtheart.imaging.ImagingComponents
import co.voik.agesandtheart.imaging.ImagingContent
import co.voik.agesandtheart.imaging.PictureSizes
import co.voik.agesandtheart.imaging.SlideCarouselBlock
import co.voik.agesandtheart.imaging.SlideCarouselBlockEntity
import net.minecraft.core.Direction
import com.mojang.blaze3d.vertex.PoseStack
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
}

private const val WHITE = 0xFFFFFF

/**
 * The projector's throw, drawn from its carousel: the front slide's picture on whatever the cone lights, as light
 * added to what is there, so it shows best in the dark. Drawn wherever the carousel is, in sight or not, since the
 * picture lands well away from it. The throw is worked out again once a second, or as soon as the assembly changes.
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
        val front = carousel.front()
        val pictureId = front.get(ImagingComponents.PICTURE)?.id
        val isABlankPlate = front.`is`(ImagingContent.PHOTOGRAPHIC_PLATE)
        state.facing = carousel.blockState.getValue(SlideCarouselBlock.FACING)
        state.toLens = ProjectorThrow.assemblyAt(level, carousel.blockPos, carousel.blockState)?.toLens
        // A whole block of glass right against the front leaves no room for the light to be seen in.
        val ahead = carousel.blockPos.relative(state.facing)
        val isGlassedRightUp = state.toLens == 1 && level.getBlockState(ahead).isCollisionShapeFullBlock(level, ahead)
        state.showsItsLight = ProjectorThrow.isLit(level, carousel.blockPos, carousel.blockState) && !isGlassedRightUp
        val behind = level.getBlockState(carousel.blockPos.relative(state.facing.opposite))
        val lightAt = carousel.blockPos.relative(state.facing.opposite)
        val lightEffect = ProjectorLights.effectOf(level, lightAt, behind)
        state.lightTint = if (state.showsItsLight) lightEffect.tint else WHITE
        val throwsAnything = pictureId != null || isABlankPlate
        state.thrown = if (throwsAnything) ProjectorThrows.thrownBy(level, carousel.blockPos, carousel.blockState) else null
        val picture = if (isABlankPlate) PictureTextures.blank() else pictureId?.let { PictureTextures.textureFor(it, PictureSizes.Size.MIDDLE) }
        state.development = pictureId?.let(PictureTextures::development) ?: 1.0f
        val effect = state.thrown?.let { lightEffect }
        state.effect = effect
        state.picture = if (picture != null && effect?.overlay != null) MaskedPictures.masked(picture, effect.overlay) else picture
    }

    override fun submit(state: SlideCarouselRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.showsItsLight) {
            val millis = System.currentTimeMillis()
            collector.submitCustomGeometry(poseStack, AgeRenderTypes.addedLight) { pose, buffer ->
                ProjectorRays.draw(pose, buffer, state.blockPos, state.facing, state.toLens, state.lightTint, millis)
            }
        }
        val thrown = state.thrown ?: return
        val picture = state.picture ?: return
        val effect = state.effect ?: return
        val origin = state.blockPos
        val flicker = 1.0f - effect.flicker * ProjectorRays.flicker(System.currentTimeMillis(), origin.asLong())
        val flickered = ProjectorLights.brightnessOf(effect, thrown.assembly.light) * flicker * state.development
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

    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = VIEW_DISTANCE

    private companion object {
        const val FLOATS_A_CORNER = 5
        const val OPAQUE = 0xFF shl 24
        const val FULL_BRIGHT = 0xF000F0
        const val VIEW_DISTANCE = 96
    }
}

/** Each carousel's throw, kept until its assembly changes or a second has passed, since walls change and nothing says so. */
object ProjectorThrows {

    private class Kept(val assembly: ProjectorThrow.Assembly, val thrown: ProjectorThrow, val madeAt: Long)

    private val kept = HashMap<BlockPos, Kept>()

    private const val KEPT_FOR_MILLIS = 1_000L

    fun thrownBy(level: net.minecraft.world.level.Level, carousel: BlockPos, state: net.minecraft.world.level.block.state.BlockState): ProjectorThrow? {
        val assembly = ProjectorThrow.assemblyAt(level, carousel, state)
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
