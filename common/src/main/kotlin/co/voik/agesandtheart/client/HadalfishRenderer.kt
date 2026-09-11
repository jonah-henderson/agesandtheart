package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.light.DeepLights
import co.voik.agesandtheart.content.Hadalfish
import co.voik.agesandtheart.location
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.model.monster.guardian.GuardianModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.EyesLayer
import net.minecraft.client.renderer.entity.state.GuardianRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier

/**
 * The hadalfish, drawn as a guardian with a lit eye.
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
    MobRenderer<Hadalfish, GuardianRenderState, GuardianModel>(
        context,
        GuardianModel(context.bakeLayer(ModelLayers.GUARDIAN)),
        SHADOW,
    ) {

    init {
        addLayer(LitEye(this))
    }

    override fun createRenderState(): GuardianRenderState = GuardianRenderState()

    /**
     * The fish, and then the light its eye is.
     *
     * **After the model, so the glow is laid over it** — and outside the model's own transformations,
     * which `super` pushes and pops, so what is left is the entity's position with nothing turned.
     */
    override fun submit(
        state: GuardianRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        super.submit(state, poseStack, collector, camera)
        if (!state.isInvisible) submitTheEye(state, poseStack, collector, camera)
    }

    /**
     * A red point that the abyss does not put out.
     *
     * **The eye's texture is not enough on its own, and cannot be.** `RenderTypes.eyes` is built on
     * `MATRICES_FOG_SNIPPET`, so the layer above fades with the water like everything else and the fish is
     * invisible exactly where it matters — at the range the circling phase is meant to be read from. This
     * is drawn on [AgeRenderTypes.lightThroughFog] instead, which no fog touches.
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
        state: GuardianRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val range = sqrt(state.distanceToCameraSq)
        val lit = DeepLights.litAt(range) ?: return
        // Where the eye is: up the body, then out through the face it is set in. The head's yaw rather
        // than the body's, because a guardian's eye follows what it is looking at and so does the model.
        //
        // **Hung clear of the face rather than sitting on it**, or the fish's own model cuts most of the
        // bloom away — the same fault the blocks had, and the same distance. Out along the *facing* rather
        // than toward the camera, which is what a lure is: it hangs in front of the thing, so it is
        // visible when the fish is looking at you and hidden when it is not.
        val yaw = state.yRot * DEGREES_TO_RADIANS
        val outward = state.boundingBoxWidth / 2.0 + DeepLights.CLEAR_OF_ITS_OWN_BLOCK * state.scale
        // Said once: the offset the pose is moved by and the point the bloom is turned to face are the
        // same three numbers, not two readings of them.
        val fromTheMiddle = Vec3(-sin(yaw) * outward, state.eyeHeight.toDouble(), cos(yaw) * outward)
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
            DeepLights.acrossAt(range),
            drawnOn = AgeRenderTypes.lightThroughFog,
        )
        poseStack.popPose()
    }

    override fun getTextureLocation(state: GuardianRenderState): Identifier = BORROWED

    override fun getModelTint(state: GuardianRenderState): Int = ABYSSAL

    /**
     * The one eye, at full brightness and close to.
     *
     * **This is the near half of a pair.** `RenderTypes.eyes` ignores the lightmap but is built on
     * `MATRICES_FOG_SNIPPET`, so it fades with the water like everything else — which is right for what
     * this is, the detail you see once the fish is on you. What carries across an abyss is the unfogged
     * bloom in [submitTheEye], and the two are the same colour on purpose.
     *
     * The texture is the guardian's own eye and nothing else: `hadalfish_eye.png` is a copy of Mojang's
     * sheet with every pixel dropped except the sixteen that are the eye, in [DeepLights.LIT]'s pale cyan
     * rather than the guardian's red — because every light in the deep has to look like every other one.
     * Generated rather than drawn, so it lines up with the model's UVs exactly by construction.
     */
    private class LitEye(renderer: HadalfishRenderer) :
        EyesLayer<GuardianRenderState, GuardianModel>(renderer) {
        override fun renderType(): RenderType = GLOW
    }

    companion object {
        private val BORROWED: Identifier =
            Identifier.withDefaultNamespace("textures/entity/guardian/guardian.png")

        private val GLOW: RenderType = RenderTypes.eyes("textures/entity/hadalfish_eye.png".location())

        /**
         * A cold, drowned cast over the guardian's teal, so it does not read as one that swam down here.
         * Dark on purpose: what should be visible first is the eye.
         */
        private const val ABYSSAL = 0xFF4A6A7A.toInt()

        private const val SHADOW = 0.9f

        private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
    }
}
