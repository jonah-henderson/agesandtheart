package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Loader-agnostic lifecycle for Ages. The actual dimension creation is delegated to the
 * platform [co.voik.agesandtheart.platform.services.AgeBackend] (Fantasy on Fabric); this
 * layer owns the "which Ages exist" bookkeeping and persistence-replay policy.
 *
 * Persistence note: neither backend auto-restores dimensions on restart, so we track Age ids
 * in [AgeSavedData] and re-open them via [reloadSavedAges], which each loader calls from its
 * own "server started" event.
 */
object AgeManager {
    fun isSupported(): Boolean = Services.AGE_BACKEND.isSupported

    /** Creates a brand-new Age and records it for persistence. Null if it exists or is unsupported. */
    fun createAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? {
        if (!Services.AGE_BACKEND.isSupported) return null
        val key = ResourceKey.create(Registries.DIMENSION, id)
        if (server.getLevel(key) != null) return null // already loaded
        val level = Services.AGE_BACKEND.openAge(server, id)
        if (level != null) {
            AgeSavedData.get(server).add(id)
            Constants.LOG.info("Created Age {}", id)
        }
        return level
    }

    /** Opens an existing Age (get-or-open). Used for travel and restart-replay. */
    fun openAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? =
        Services.AGE_BACKEND.openAge(server, id)

    /** Mints a fresh, distinct Age id (`agesandtheart:age_<n>`) from the persistent counter. */
    fun allocateAgeId(server: MinecraftServer): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "age_${AgeSavedData.get(server).allocateIndex()}")

    /** Get-or-create the Age for [id] and record it for persistence. Null if unsupported/failed. */
    fun ensureAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? {
        if (!Services.AGE_BACKEND.isSupported) return null
        val level = Services.AGE_BACKEND.openAge(server, id) ?: return null
        AgeSavedData.get(server).add(id)
        return level
    }

    /** Teleports a player onto the surface at the Age's origin. */
    fun teleport(player: ServerPlayer, level: ServerLevel) {
        level.getChunk(0, 0) // force-generate the spawn chunk so the heightmap is valid
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)
        player.teleportTo(level, 0.5, (y + 1).toDouble(), 0.5, player.yRot, player.xRot)
    }

    /** Re-opens every persisted Age. Call once per server start (from a loader lifecycle hook). */
    fun reloadSavedAges(server: MinecraftServer) {
        if (!Services.AGE_BACKEND.isSupported) return
        val ages = AgeSavedData.get(server).ages
        if (ages.isEmpty()) return
        Constants.LOG.info("Re-opening {} saved Age(s)", ages.size)
        for (id in ages) Services.AGE_BACKEND.openAge(server, id)
    }
}
