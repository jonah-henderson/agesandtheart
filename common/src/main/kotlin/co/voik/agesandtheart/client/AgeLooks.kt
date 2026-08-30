package co.voik.agesandtheart.client

import co.voik.agesandtheart.AgeClientLook
import co.voik.ephemeris.client.AuroraPainter
import co.voik.ephemeris.client.LevelRendering

/**
 * What the Art adds to a level's air, over and above what Ephemeris already draws.
 *
 * **The suns, moons, stars and cloud decks are gone from here entirely** — Ephemeris registers painters of
 * its own for those, reading the same `SkySpec` the server sent. What is left is the one thing no library
 * could have: an Age's air, and what its wounds do to it.
 *
 * Registered once rather than per dimension, because an Age is made at runtime and its look changes when
 * its book is rewritten, and neither can be expressed by registering against a dimension key.
 */
object AgeLooks {

    /** Called from each loader's client entrypoint — the one place that knows a client exists. */
    fun register() {
        // **What this machine will draw, which is ours to decide and the library's to obey.** Read afresh
        // each frame rather than set once, so turning it down in a settings screen takes effect at once.
        AuroraPainter.mostCurtainsDrawn = { AgeClientLook.auroraCurtains.get() }

        // The Age's own air first, then what its wounds do to it — corruption darkens whatever was there
        // rather than being blended into it, so a lurid sky still goes black at the throat of a tear.
        LevelRendering.environment { level, layers -> Corruption.paint(level, AgeAir.paint(level, layers)) }
    }
}
