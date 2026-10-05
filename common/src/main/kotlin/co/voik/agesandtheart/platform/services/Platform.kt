package co.voik.agesandtheart.platform.services

/**
 * Platform information the mod needs but that each loader reports differently. Loaded via
 * [co.voik.agesandtheart.platform.Services]; Fabric and NeoForge each provide an implementation.
 */
interface Platform {
    /** Whether the game is running in a development environment. */
    val isDevelopment: Boolean

    /** Whether a mod with the given id is loaded. */
    fun isModLoaded(modId: String?): Boolean

    /** The version of the loaded mod with the given id, or null when it is not loaded. */
    fun modVersion(modId: String): String?
}
