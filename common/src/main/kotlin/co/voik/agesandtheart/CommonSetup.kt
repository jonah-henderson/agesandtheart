package co.voik.agesandtheart

import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.content.DeepWaterLogging
import co.voik.agesandtheart.sky.Skies
import co.voik.ephemeris.LevelWeather

/**
 * Shared initialisation, invoked by each loader's entrypoint. Common code sees only the vanilla
 * codebase plus our own abstractions; loader-specific wiring (registration, events) stays in the
 * loader entrypoints.
 */
object CommonSetup {
    fun init() {
        // An Age keeps weather of its own — the seam is the library's, what an Age asks for is ours.
        LevelWeather.source { level -> AgeWeather.of(level) }
        // And is dressed and aired as it opens, by whichever route opened it.
        Ages.attach()
        Skies.attach()
        // Does nothing at all unless one of our own tools started this server — see [LauncherWatch].
        LauncherWatch.attach()
        Constants.LOG.info("Ages and the Art initialising")
    }

    /**
     * Shared work that cannot be done until every block, item and fluid of ours exists.
     *
     * **A second entry point because registration is where the loaders differ most.** Fabric registers
     * during its init call and NeoForge on a mod-bus event, so "after `init`" means nothing that both can
     * honour — each calls this at the point it knows its own content is in.
     */
    fun afterContentRegistered() {
        DeepWaterLogging.settleTheCache()
    }
}
