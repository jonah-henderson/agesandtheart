package co.voik.agesandtheart.client

import co.voik.runtimelevels.client.LevelRendering

/**
 * Hands the Art's skies to `co.voik.runtimelevels` — the whole of what the mod does to look like itself.
 *
 * **This is the mod becoming a consumer of the library rather than a co-owner of the Mixins.** Each of
 * these was a Mixin here until the library took the seams; what is left is three registrations, and none of
 * them says anything the library has to understand.
 *
 * Each painter decides for itself whether the level is an Age with a sky worth drawing, which is why they
 * are registered once rather than per dimension: an Age is made at runtime and its look changes when its
 * book is rewritten, and neither can be expressed by registering against a dimension key.
 */
object AgeLooks {

    /** Called from each loader's client entrypoint — the one place that knows a client exists. */
    fun register() {
        LevelRendering.sky { moment ->
            AgeSky.draw(
                Blaze3dSkyCanvas,
                moment.sunAngle,
                moment.moonAngle,
                moment.starAngle,
                moment.moonPhase,
                moment.rainBrightness,
                moment.starBrightness,
            )
        }
        LevelRendering.clouds { moment ->
            AgeClouds.draw(Blaze3dSkyCanvas, moment.cameraPosition, moment.time)
        }
        // The Age's own air first, then what its wounds do to it — corruption darkens whatever was there
        // rather than being blended into it, so a lurid sky still goes black at the throat of a tear.
        LevelRendering.environment { level, layers -> Corruption.paint(level, AgeAir.paint(level, layers)) }
    }
}
