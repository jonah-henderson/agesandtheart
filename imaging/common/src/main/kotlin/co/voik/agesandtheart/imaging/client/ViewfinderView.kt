package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.ImageRecorderBlock
import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.Sketches
import co.voik.agesandtheart.imaging.Viewfinder
import co.voik.agesandtheart.platform.Services
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * What looking through an image recorder looks like: no HUD and no hand (the client Mixins ask [isLooking]), the
 * view put in first person and held there (in a development run F5 still works, so the pose can be seen), a slight vignette and fisheye with the 16:9 crop marked (`post_effect/viewfinder`), and
 * a zoom the scroll wheel turns between the player's own field of view and a spyglass's. The fisheye eases as the view
 * narrows (`post_effect/viewfinder_<n>`, one strength a file, since a post pass's uniforms are fixed in it).
 */
object ViewfinderView {

    /** From the gentlest fisheye, a third of the widest view's, to the widest's. */
    private const val SWELL_LEVELS = 12
    private const val LEAST_SWELL_SHARE = 1.0 / 3.0
    private val EFFECTS: List<Identifier> = (1..SWELL_LEVELS).map { Imaging.id("viewfinder_$it") }

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
        return Viewfinder.isLooking(player) || RecorderPreview.isLooking() || Capture.isPending() || ShutterIris.isPlaying()
    }

    /** Hands [Viewfinder] and a set-down recorder what they do here. Called once by each loader's client entrypoint. */
    fun install() {
        Viewfinder.shutterOnTheClient = { Capture.request(Minecraft.getInstance()) }
        ImageRecorderBlock.lookThroughOnTheClient = RecorderPreview::start
    }

    /** The vignette and fisheye off, for the frame a picture is taken from. */
    fun dropTheEffect(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val withoutOurs = player.activePostEffects.filterNot { it in EFFECTS }
        if (withoutOurs != player.activePostEffects) player.setActivePostEffects(withoutOurs)
    }

    /**
     * The fisheye for the zoom: as strong as the widest view's share of a straight lens's stretch at its edges, which a
     * fisheye takes back, and the narrow view's is far less. Held to a third of the widest's so the viewfinder still
     * looks like one.
     */
    private fun effectFor(minecraft: Minecraft): Identifier {
        fun stretch(fieldOfViewDegrees: Double): Double {
            val halfAngle = Math.toRadians(fieldOfViewDegrees / 2.0)
            return tan(halfAngle) / halfAngle - 1.0
        }
        // A set-down recorder's widest view is the one its pictures are taken at; a handheld one's is the player's own.
        val isPreviewing = RecorderPreview.isLooking()
        val widest = if (isPreviewing) Sketches.ORDINARY_FIELD_OF_VIEW.toDouble() else minecraft.options.fov().get().toDouble()
        val share = (stretch(widest * currentZoom()) / stretch(widest)).coerceIn(LEAST_SWELL_SHARE, 1.0)
        val level = ((share - LEAST_SWELL_SHARE) / (1.0 - LEAST_SWELL_SHARE) * (SWELL_LEVELS - 1)).roundToInt()
        return EFFECTS[level]
    }

    @JvmStatic
    fun fieldOfViewModifier(): Float = zoom

    /** The zoom of whichever recorder is being looked through: the one in hand, or the one set down. */
    private fun currentZoom(): Float = if (RecorderPreview.isLooking()) RecorderPreview.zoom() else zoom

    /** How much of the mouse's turn reaches the view: the zoom's share of the widest view, one when not looking. */
    @JvmStatic
    fun turningShare(): Double = if (isLooking()) currentZoom().toDouble() else 1.0

    /**
     * A positive [notches] is the wheel turned away, which narrows the view, as a spyglass's eye would come closer: the
     * set-down recorder's being looked through, or else the one in hand.
     */
    @JvmStatic
    fun scrolled(notches: Double) {
        if (RecorderPreview.isLooking()) {
            RecorderPreview.scrolled(notches)
            return
        }
        zoom = (zoom * NARROWING_A_NOTCH.pow(notches.toFloat())).coerceIn(NARROWEST, WIDEST)
    }

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        Viewfinder.tick(player)
        RecorderPreview.tick(minecraft)
        val raised = Viewfinder.isLooking(player)
        val looking = raised || RecorderPreview.isLooking()
        if (Capture.isPending() || ShutterIris.isPlaying()) return
        val justRaised = raised && cameraBefore == null
        if (justRaised) sayHowToLowerIt(minecraft)
        holdInFirstPerson(minecraft, looking)
        if (!raised) zoom = WIDEST
        val wanted = player.activePostEffects.filterNot { it in EFFECTS } + listOfNotNull(effectFor(minecraft).takeIf { looking })
        if (wanted != player.activePostEffects) player.setActivePostEffects(wanted)
    }

    /** How to lower it and how to zoom, in the action bar, as a boat says how to get out of it. */
    private fun sayHowToLowerIt(minecraft: Minecraft) {
        val sneak = minecraft.options.keyShift.translatedKeyMessage
        ViewfinderHint.show(
            Component.translatable("agesandtheart_imaging.viewfinder.lower", sneak),
            Component.translatable("agesandtheart_imaging.viewfinder.zoom"),
        )
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
