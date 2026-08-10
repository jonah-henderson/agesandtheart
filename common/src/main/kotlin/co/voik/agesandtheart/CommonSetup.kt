package co.voik.agesandtheart

import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.platform.Services
import co.voik.agesandtheart.sky.Skies
import co.voik.runtimelevels.LevelWeather

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
        Constants.LOG.info(
            "Ages and the Art initialising on {} ({} environment)",
            Services.PLATFORM.name,
            Services.PLATFORM.environmentName,
        )
    }
}
