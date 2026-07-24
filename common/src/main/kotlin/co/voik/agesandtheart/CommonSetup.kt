package co.voik.agesandtheart

import co.voik.agesandtheart.platform.Services

/**
 * Shared initialisation, invoked by each loader's entrypoint. Common code sees only the vanilla
 * codebase plus our own abstractions; loader-specific wiring (registration, events) stays in the
 * loader entrypoints.
 */
object CommonSetup {
    fun init() {
        Constants.LOG.info(
            "Ages and the Art initialising on {} ({} environment)",
            Services.PLATFORM.name,
            Services.PLATFORM.environmentName,
        )
    }
}
