package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.sky.KnownLooks
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/**
 * The overcast an Age was written with, in place of vanilla's one drifting sheet — the painter for clouds
 * and the sibling of [AgeSky].
 *
 * **An Age wanting decks must not set a fully transparent `minecraft:visual/cloud_color`**: `LevelRenderer`
 * skips the cloud pass entirely on a zero alpha, taking ours with it. It also skips when the player has
 * clouds off, which is theirs to decide.
 */
object AgeClouds {

    /** Said once rather than every frame — see the note in [draw]. */
    private var said: Int? = null

    /** Draws the Age's decks, or returns false having drawn nothing so vanilla's clouds run instead. */
    fun draw(canvas: SkyCanvas, eye: Vec3, timeTicks: Float): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val spec = KnownLooks.of(level.dimension()) ?: return false
        if (spec.decks.isEmpty()) return false

        // **Instrumentation, and it answers the one question left about the Spire's missing decks**
        // (2026-08-08). Everything upstream is verified: the server sends two decks and a cloud colour at
        // alpha 204, so `LevelRenderer`'s gate — which skips the whole pass on a transparent cloud colour —
        // must be passing, and this must be running. If it is, then we cancel vanilla's clouds and draw
        // nothing visible, which puts the fault squarely in `drawCloudDeck` under 26.1's render changes.
        // Once seen either way, delete this.
        if (said == null) {
            said = spec.decks.size
            Constants.LOG.info("AgeClouds drew {} deck(s) for {}", said, level.dimension().identifier())
        }
        // Outermost last: the decks write depth, so the near one must be drawn after the far one to occlude it.
        for (deck in spec.decks) canvas.drawCloudDeck(deck, eye, timeTicks)
        return true
    }
}
