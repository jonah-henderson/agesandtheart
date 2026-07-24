package co.voik.agesandtheart.platform.services

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Platform abstraction for runtime dimension creation — the one part of the Age mechanic that
 * genuinely differs per loader.
 *
 * Runtime dimensions are done with Fantasy on Fabric (see `FabricAgeBackend`), which is
 * Fabric-only; NeoForge has no backend yet (`NeoForgeAgeBackend` is an unsupported stub).
 * Loaded via [co.voik.agesandtheart.platform.Services], like [Platform].
 */
interface AgeBackend {
    /** Whether this loader can create runtime dimensions yet. */
    val isSupported: Boolean

    /**
     * Get-or-create the persistent Age dimension with the given [id], returning its level
     * (or `null` if unsupported or creation failed). Idempotent: opening an existing Age
     * reuses its saved chunks. Must be called on the server thread.
     */
    fun openAge(server: MinecraftServer, id: ResourceLocation): ServerLevel?
}
