package co.voik.agesandtheart.platform.services

/**
 * Platform information the mod needs but that each loader reports differently. Loaded via
 * [co.voik.agesandtheart.platform.Services]; Fabric and NeoForge each provide an implementation.
 */
interface Platform {
    /** Human-readable loader name, e.g. "Fabric". */
    val name: String

    /** Whether the game is running in a development environment. */
    val isDevelopment: Boolean

    /** "development" or "production". */
    val environmentName: String get() = if (isDevelopment) "development" else "production"

    /** Whether a mod with the given id is loaded. */
    fun isModLoaded(modId: String?): Boolean
}
