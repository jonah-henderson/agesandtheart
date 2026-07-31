package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import co.voik.agesandtheart.sky.Skies
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Loader-agnostic lifecycle for Ages. Dimension creation is delegated to the platform
 * [co.voik.agesandtheart.platform.services.AgeBackend]; this layer owns the bookkeeping and the
 * persistence-replay policy.
 *
 * No backend auto-restores dimensions on restart, so Age ids are tracked in [AgeSavedData] and
 * re-opened via [reloadSaved] from each loader's "server started" event.
 */
object Ages {
    fun isSupported(): Boolean = Services.AGE_BACKEND.isSupported

    /** Creates a brand-new Age and records it for persistence. Null if it exists or is unsupported. */
    fun create(server: MinecraftServer, id: Identifier, recipe: AgeRecipe): ServerLevel? {
        val backend = Services.AGE_BACKEND
        if (!backend.isSupported) return null
        val dimensionKey = ResourceKey.create(Registries.DIMENSION, id)
        if (server.getLevel(dimensionKey) != null) return null // already loaded
        val saved = AgeSavedData.get(server)
        // Record the recipe *before* opening: the backend builds the world from what is recorded.
        saved.add(id, recipe)
        val level = backend.openAge(server, id)
        if (level == null) {
            saved.remove(id)
            return null
        }
        Constants.LOG.info("Created Age {} [{}]", id, recipe)
        return level
    }

    /** Opens an existing Age (get-or-open). Used for travel and restart-replay. */
    fun open(server: MinecraftServer, id: Identifier): ServerLevel? =
        Services.AGE_BACKEND.openAge(server, id)

    /** Mints a fresh, distinct Age id (`agesandtheart:age_<n>`) from the persistent counter. */
    fun allocateId(server: MinecraftServer): Identifier =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "age_${AgeSavedData.get(server).allocateIndex()}")

    /**
     * The Age [id], written from [recipe] if it does not exist yet. Null if unsupported or it failed.
     * An Age that already exists keeps the recipe it was written from — [recipe] says what to write,
     * not what to become.
     */
    fun ensure(server: MinecraftServer, id: Identifier, recipe: AgeRecipe): ServerLevel? =
        if (id in AgeSavedData.get(server).ages) open(server, id) else create(server, id, recipe)

    /**
     * Puts a player down on solid ground in an Age.
     *
     * A per-dimension spawn point cannot be set afterwards (Fantasy's runtime worlds use
     * `DerivedLevelData`, whose `setSpawn` does nothing), so footing is searched for outward from the
     * origin instead.
     */
    fun teleport(player: ServerPlayer, level: ServerLevel) {
        // Before the move, not after: one TCP stream carries both, so a sky sent first cannot arrive after
        // the dimension change. See `Skies.tellAbout`.
        Skies.tellAbout(player, level)
        val (landingX, landingZ) = findFooting(level)
        level.getChunk(SectionPos.blockToSectionCoord(landingX), SectionPos.blockToSectionCoord(landingZ))
        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, landingX, landingZ)
        // `teleportTo` gained a relative-movement set and a "set camera" flag. Nothing here is relative and
        // the camera should follow, which is the empty set and `true`.
        player.teleportTo(
            level, landingX + 0.5, (surfaceY + 1).toDouble(), landingZ + 0.5,
            emptySet(), player.yRot, player.xRot, true,
        )
    }

    /**
     * The nearest column to the origin standing clear of the sea. Asks the generator rather than the
     * world, so no chunk is generated until one is chosen — which is what makes a wide search affordable.
     */
    private fun findFooting(level: ServerLevel): Pair<Int, Int> {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val waterline = generator.seaLevel
        for ((offsetX, offsetZ) in outwardFromOrigin()) {
            val height = generator.getBaseHeight(offsetX, offsetZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            if (height > waterline) return offsetX to offsetZ
        }
        return 0 to 0
    }

    /** Coarse lattice of candidate columns, nearest ring first. */
    private fun outwardFromOrigin(): Sequence<Pair<Int, Int>> = sequence {
        yield(0 to 0)
        for (ring in 1..FOOTING_RINGS) {
            val extent = ring * FOOTING_STEP
            for (along in -extent..extent step FOOTING_STEP) {
                yield(along to -extent)
                yield(along to extent)
                yield(-extent to along)
                yield(extent to along)
            }
        }
    }

    // A step under a chunk, out far enough to clear the widest island spacing we place.
    private const val FOOTING_STEP = 12
    private const val FOOTING_RINGS = 24

    /**
     * Discards an Age: its dimension and its saved chunks both go. Returns whether it existed and was
     * removed. Anyone standing in it is [evict]ed first.
     */
    fun delete(server: MinecraftServer, id: Identifier): Boolean {
        val saved = AgeSavedData.get(server)
        if (id !in saved.ages) return false
        evict(server, id)
        if (!Services.AGE_BACKEND.deleteAge(server, id)) return false
        saved.remove(id)
        Constants.LOG.info("Deleted Age {}", id)
        return true
    }

    /** Discards every Age, returning how many went. */
    fun deleteAll(server: MinecraftServer): Int =
        // Copied first: deleting mutates the set we would otherwise be iterating.
        AgeSavedData.get(server).ages.toList().count { delete(server, it) }

    /** Sends anyone inside an Age back to the overworld spawn, so nothing is left in a dead dimension. */
    private fun evict(server: MinecraftServer, id: Identifier) {
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) ?: return
        val home = server.overworld()
        // The world spawn moved behind `LevelData.RespawnData`, which carries a `GlobalPos`.
        val spawn = home.levelData.respawnData.pos()
        for (player in level.players().toList()) {
            player.teleportTo(
                home, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5,
                emptySet(), player.yRot, player.xRot, true,
            )
        }
    }

    /** Re-opens every persisted Age. Call once per server start (from a loader lifecycle hook). */
    fun reloadSaved(server: MinecraftServer) {
        val backend = Services.AGE_BACKEND
        if (!backend.isSupported) return
        val ages = AgeSavedData.get(server).ages
        if (ages.isEmpty()) return
        Constants.LOG.info("Re-opening {} saved Age(s)", ages.size)
        for (id in ages) backend.openAge(server, id)
    }
}
