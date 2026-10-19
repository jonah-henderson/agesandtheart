package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.light.DeepLights
import co.voik.agesandtheart.content.Hadalfish
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3
import net.minecraft.client.model.geom.ModelPart
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.sqrt
import net.minecraft.client.model.monster.guardian.GuardianModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.state.GuardianRenderState
import net.minecraft.resources.Identifier

/**
 * The hadalfish, drawn as a guardian whose eye is a light.
 *
 * **Vanilla's model and vanilla's texture**, the bargain the astrite golem and the rime crystal already
 * make: it references Mojang's art rather than shipping any, and the asset pass gives this an anglerfish of
 * its own. What is *not* borrowed is the size — that rides on `Attributes.SCALE`, so the hitbox grows with
 * the picture and there is nothing to keep in step here.
 *
 * **It is deliberately the ordinary guardian and not the elder** (Jonah, 2026-09-10), which is a look
 * rather than a shortcut: the elder's washed-out palette is the worse of the two, and a mini-boss can be
 * the smaller texture scaled up.
 *
 * **No beam is drawn, and nothing here suppresses one.** `GuardianRenderer` draws its laser off
 * `hasActiveAttackTarget`, which is synched data only `GuardianAttackGoal` ever sets — and [Hadalfish]
 * never registers that goal. Extending `MobRenderer` directly rather than `GuardianRenderer` is what keeps
 * the beam's whole apparatus, and its frustum override, out of this class entirely.
 */
class HadalfishRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<Hadalfish, HadalfishRenderState, GuardianModel>(
        context,
        HadalfishModel(context.bakeLayer(ModelLayers.GUARDIAN)),
        SHADOW,
    ) {

    /**
     * The guardian's, with its eye put out wherever its light is drawn on it: a glow is added light, so the pupil
     * behind it showed through rather than being covered, and gave the fish away against the sea lanterns.
     */
    private class HadalfishModel(root: ModelPart) : GuardianModel(root) {
        private val eye = root.getChild("head").getChild("eye")

        override fun setupAnim(state: GuardianRenderState) {
            super.setupAnim(state)
            eye.visible = (state as? HadalfishRenderState)?.showsItsLight != true
        }
    }

    override fun createRenderState(): HadalfishRenderState = HadalfishRenderState()

    override fun extractRenderState(entity: Hadalfish, state: HadalfishRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.isHunting = entity.isHunting
        state.showsItsLight = !state.isInvisible && (state.isHunting || DeepLights.litAt(sqrt(state.distanceToCameraSq)) != null)
    }

    /**
     * The fish, and then the light its eye is.
     *
     * **After the model, so the glow is laid over it** — and outside the model's own transformations,
     * which `super` pushes and pops, so what is left is the entity's position with nothing turned.
     */
    override fun submit(
        state: HadalfishRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        super.submit(state, poseStack, collector, camera)
        if (state.showsItsLight) submitTheEye(state, poseStack, collector, camera)
    }

    /**
     * A point of light that the abyss does not put out, drawn on [AgeRenderTypes.lightThroughFog], which no
     * fog touches: anything lit through the model fades with the water and is gone at the range the circling
     * phase is read from.
     *
     * **And it is meant to carry as far as anything can** (Jonah, 2026-09-10). An abyss has no dynamic
     * light in it, so a diver with twenty-four blocks of sight and nothing on the horizon is not being
     * oppressed, only inconvenienced — there has to be something to steer by. This is the candle on the
     * dark hill: you see the light and nothing whatever around it.
     *
     * **The colour, the size and how far it carries are [DeepLights]', not this class's**, and that is the
     * design rather than tidiness: a lure and a sea lantern have to be indistinguishable at range, so
     * approaching either is a decision taken without knowing which it is. Two things outside both can cut
     * it shorter — the tracking range (`AgeContent.SEEN_FROM_CHUNKS_AWAY`) and the player's own render
     * distance, which caps tracking at sixteen blocks a chunk.
     */
    private fun submitTheEye(
        state: HadalfishRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val range = sqrt(state.distanceToCameraSq)
        val lit = if (state.isHunting) DeepLights.litWhileHunting(range, state.ageInTicks) else DeepLights.litAt(range)
        if (lit == null) return
        val across = if (state.isHunting) DeepLights.acrossWhileHunting(range, state.ageInTicks) else DeepLights.acrossAt(range)
        // **On the eye itself** (Jonah, 2026-10-19), where it hung clear of the face as a lure: a guardian has
        // nothing for a lure to hang from. Found by the model's own transforms, so it follows the head's turn and
        // pitch, then lifted a little toward whoever is looking so the head's faces do not cut the bloom away.
        val face = eyeOnTheModel(state)
        val towardCamera = camera.pos.subtract(Vec3(state.x, state.y, state.z).add(face))
        val fromTheMiddle = face.add(towardCamera.normalize().scale(LIFTED_OFF_THE_EYE))
        val at = Vec3(state.x, state.y, state.z).add(fromTheMiddle)
        poseStack.pushPose()
        poseStack.translate(fromTheMiddle.x, fromTheMiddle.y, fromTheMiddle.z)
        AddedLight.halo(
            collector,
            poseStack,
            camera.pos.subtract(at),
            lit,
            // **Not scaled by the fish**, however tempting: a lure two and a half times the size of a sea
            // lantern is a lure you can pick out at a glance, and the whole design is that you cannot.
            across,
            drawnOn = AgeRenderTypes.lightThroughFog,
        )
        poseStack.popPose()
    }

    /**
     * The front of the model's eye, from the fish's feet: the same transforms `LivingEntityRenderer.submit` lays
     * the model out by, and then the head's, which carries the eye. The body's turn and the head's are separate,
     * since a render state's `yRot` is the head's turn from the body rather than its own heading.
     */
    private fun eyeOnTheModel(state: HadalfishRenderState): Vec3 {
        val eye = Matrix4f()
            .scale(state.scale)
            .rotateY((HALF_A_TURN - state.bodyRot) * DEGREES_TO_RADIANS)
            .scale(-1.0f, -1.0f, 1.0f)
            .translate(0.0f, MODEL_FLOOR, 0.0f)
            .rotate(Quaternionf().rotationZYX(0.0f, state.yRot * DEGREES_TO_RADIANS, state.xRot * DEGREES_TO_RADIANS))
            .transformPosition(Vector3f(0.0f, EYE_DOWN, EYE_FRONT))
        return Vec3(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble())
    }

    override fun getTextureLocation(state: HadalfishRenderState): Identifier = BORROWED

    override fun getModelTint(state: HadalfishRenderState): Int = ABYSSAL

    companion object {
        private val BORROWED: Identifier =
            Identifier.withDefaultNamespace("textures/entity/guardian/guardian.png")

        /**
         * A cold, drowned cast over the guardian's teal, so it does not read as one that swam down here.
         * Dark on purpose: what should be visible first is the eye.
         */
        private const val ABYSSAL = 0xFF4A6A7A.toInt()

        private const val SHADOW = 0.9f

        private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

        /** The front of the guardian model's eye, in its head's own space: 16 pixels down and 8.25 forward. */
        private const val EYE_DOWN = 16.0f / 16.0f
        private const val EYE_FRONT = -8.25f / 16.0f

        /** Where `LivingEntityRenderer` puts a model's origin, which is its own floor turned upside down. */
        private const val MODEL_FLOOR = -1.501f

        private const val HALF_A_TURN = 180.0f

        /** In blocks, and unscaled: enough to clear the curve of the face at an angle. */
        private const val LIFTED_OFF_THE_EYE = 0.4
    }
}

/** A guardian's, whether the fish is hunting, which its lure shows, and whether its light is drawn at all. */
class HadalfishRenderState : GuardianRenderState() {
    var isHunting = false
    var showsItsLight = false
}
