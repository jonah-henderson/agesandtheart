package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AstriteGolem
import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.animal.golem.IronGolemModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.state.IronGolemRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

class AstriteGolemRenderState : IronGolemRenderState() {
    /** How full its charge is, from 0 to 1. */
    var chargeShown: Float = 1f

    /** Its shot, from the pose's origin at its feet, while one is shown. */
    var laserFrom: Vec3? = null
    var laserTo: Vec3? = null
    var animationTime: Float = 0f
}

/**
 * The golem, drawn as an iron one in a violet cast that dims as its charge runs down, and its laser while a shot is shown.
 *
 * **Vanilla's model and vanilla's texture**, which is the same bargain the rime crystal's tint and the
 * skates' leather make: it references Mojang's art rather than shipping any, and the asset pass will give
 * this its own. The tint is what keeps it from reading as an ordinary iron golem in the meantime.
 *
 * The state has to be `IronGolemRenderState` because the model is written against one; the flower and the
 * crackiness on it are simply left alone, this golem having neither.
 */
class AstriteGolemRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<AstriteGolem, AstriteGolemRenderState, IronGolemModel>(
        context,
        IronGolemModel(context.bakeLayer(ModelLayers.IRON_GOLEM)),
        SHADOW,
    ) {

    override fun createRenderState(): AstriteGolemRenderState = AstriteGolemRenderState()

    override fun extractRenderState(golem: AstriteGolem, state: AstriteGolemRenderState, partialTicks: Float) {
        super.extractRenderState(golem, state, partialTicks)
        state.chargeShown = golem.charge.toFloat() / AstriteGolem.FULL_CHARGE
        state.animationTime = golem.tickCount + partialTicks
        val aim = golem.shownLaserTarget
        if (aim == null) {
            state.laserFrom = null
            state.laserTo = null
            return
        }
        val feet = golem.getPosition(partialTicks)
        state.laserFrom = Vec3(0.0, golem.bbHeight * FIRES_FROM_UP_THE_BODY, 0.0)
        state.laserTo = aim.getPosition(partialTicks).add(0.0, aim.bbHeight * HITS_UP_THE_BODY, 0.0).subtract(feet)
    }

    override fun submit(state: AstriteGolemRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        super.submit(state, poseStack, collector, camera)
        val from = state.laserFrom ?: return
        val to = state.laserTo ?: return
        val cameraFromFeet = camera.pos.subtract(state.x, state.y, state.z)
        BeamDrawing.submitLaser(poseStack, collector, from, to, LASER_COLOUR, state.animationTime, cameraFromFeet)
    }

    override fun getTextureLocation(state: AstriteGolemRenderState): Identifier = BORROWED

    override fun getModelTint(state: AstriteGolemRenderState): Int = DRAINED.lerp(ASTRITE, state.chargeShown).packed()

    companion object {
        private val BORROWED: Identifier = Identifier.withDefaultNamespace("textures/entity/iron_golem/iron_golem.png")

        /**
         * A violet a shade deeper than [co.voik.agesandtheart.content.AgeContent.ASTRITE_TINT], opaque, as an
         * ARGB tint over the borrowed texture.
         */
        private val ASTRITE = Rgba.of(0xFF8C5CFF.toInt())

        /** Its cast with no charge left: the violet nearly gone to grey. */
        private val DRAINED = Rgba.of(0xFF5A5068.toInt())

        private val LASER_COLOUR = Rgba.of(AgeContent.ASTRITE_TINT)
        private const val FIRES_FROM_UP_THE_BODY = 0.75
        private const val HITS_UP_THE_BODY = 0.5

        private const val SHADOW = 0.7f
    }
}
