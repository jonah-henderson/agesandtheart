package co.voik.agesandtheart.platform.services

import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Platform abstraction for runtime dimension creation — the one part of the Age mechanic that genuinely
 * differs per loader — though both now sit on Ephemeris, and differ only in the class name.
 */
interface AgeBackend {
    /** Whether this loader can create runtime dimensions yet. */
    val isSupported: Boolean

    /**
     * Get-or-create the persistent Age dimension with the given [id], returning its level
     * (or `null` if unsupported or creation failed). Idempotent: opening an existing Age
     * reuses its saved chunks. Must be called on the server thread.
     */
    fun openAge(server: MinecraftServer, id: Identifier): ServerLevel?

    /**
     * Unregisters the Age dimension [id] and discards its saved chunks, returning whether it worked.
     * Callers get players out first — see [co.voik.agesandtheart.age.Ages.delete], which owns that policy.
     * Must be called on the server thread.
     */
    fun deleteAge(server: MinecraftServer, id: Identifier): Boolean
}
