package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.Viewfinder
import co.voik.agesandtheart.platform.Services
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.pow

/**
 * What looking through an image recorder looks like: no HUD and no hand (the client Mixins ask [isLooking]), the
 * view put in first person and held there (in a development run F5 still works, so the pose can be seen), a slight vignette and fisheye with the 16:9 crop marked (`post_effect/viewfinder`), and
 * a zoom the scroll wheel turns between the player's own field of view and a spyglass's.
 */
object ViewfinderView {

    private val EFFECT: Identifier = Imaging.id("viewfinder")

    /** The player's own field of view, and a spyglass's. */
    private const val WIDEST = 1.0f
    private const val NARROWEST = 0.1f

    /** How far one notch of the wheel narrows the view; twenty-two notches go from widest to narrowest. */
    private const val NARROWING_A_NOTCH = 0.9f

    private var zoom = WIDEST

    /** The camera the player had before looking, put back after. */
    private var cameraBefore: CameraType? = null

    /** Looking through a viewfinder, or about to take the picture it showed, which is taken as it was seen. */
    @JvmStatic
    fun isLooking(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return Viewfinder.isLooking(player) || Capture.isPending() || ShutterIris.isPlaying()
    }

    /** Hands [Viewfinder] what the shutter does here. Called once by each loader's client entrypoint. */
    fun install() {
        Viewfinder.shutterOnTheClient = { Capture.request(Minecraft.getInstance()) }
    }

    /** The vignette and fisheye off, for the frame a picture is taken from. */
    fun dropTheEffect(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        if (EFFECT in player.activePostEffects) player.setActivePostEffects(player.activePostEffects - EFFECT)
    }

    @JvmStatic
    fun fieldOfViewModifier(): Float = zoom

    /** A positive [notches] is the wheel turned away, which narrows the view, as a spyglass's eye would come closer. */
    @JvmStatic
    fun scrolled(notches: Double) {
        zoom = (zoom * NARROWING_A_NOTCH.pow(notches.toFloat())).coerceIn(NARROWEST, WIDEST)
    }

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        Viewfinder.tick(player)
        val looking = Viewfinder.isLooking(player)
        if (Capture.isPending() || ShutterIris.isPlaying()) return
        val justRaised = looking && cameraBefore == null
        if (justRaised) sayHowToLowerIt(minecraft)
        holdInFirstPerson(minecraft, looking)
        if (!looking) zoom = WIDEST
        val hasEffect = EFFECT in player.activePostEffects
        if (looking && !hasEffect) player.setActivePostEffects(player.activePostEffects + EFFECT)
        if (!looking && hasEffect) player.setActivePostEffects(player.activePostEffects - EFFECT)
    }

    /** "Press [Sneak] to exit the viewfinder", in the action bar, as a boat says how to get out of it. */
    private fun sayHowToLowerIt(minecraft: Minecraft) {
        val sneak = minecraft.options.keyShift.translatedKeyMessage
        minecraft.gui.hud.setOverlayMessage(Component.translatable("agesandtheart_imaging.viewfinder.lower", sneak), false)
    }

    private fun holdInFirstPerson(minecraft: Minecraft, looking: Boolean) {
        val options = minecraft.options
        if (looking) {
            val justRaised = cameraBefore == null
            if (justRaised) cameraBefore = options.cameraType
            val mayLeaveFirstPerson = Services.PLATFORM.isDevelopment && !justRaised
            if (!mayLeaveFirstPerson && options.cameraType != CameraType.FIRST_PERSON) options.cameraType = CameraType.FIRST_PERSON
        } else {
            cameraBefore?.let { options.cameraType = it }
            cameraBefore = null
        }
    }
}
