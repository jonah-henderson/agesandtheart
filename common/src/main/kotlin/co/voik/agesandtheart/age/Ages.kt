package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import co.voik.ephemeris.RuntimeLevelEvents
import co.voik.ephemeris.sky.LevelAppearance
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere

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

    /**
     * Settle every Age's air as it opens, however it came to be open.
     *
     * On the library's own event rather than at each call site: writing an Age, linking to one and replaying
     * the saved list on boot all end in the same place, and the replay used to be the one that missed.
     */
    fun attach() {
        RuntimeLevelEvents.whenOpened(::settleTheAir)
    }

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
    fun open(server: MinecraftServer, id: Identifier): ServerLevel? = Services.AGE_BACKEND.openAge(server, id)

    /**
     * The Age's own layer over the environment vanilla built for the level (§3.1's Atmosphere).
     *
     * **On opening rather than in the generator**, because an attribute is a fact about the *level* and
     * nothing in generation reads one — and on every open rather than once, because a level is built afresh
     * from the recipe each time the server starts.
     */
    private fun settleTheAir(level: ServerLevel) {
        val saved = AgeSavedData.get(level.server)
        val id = level.dimension().identifier()
        if (id !in saved.ages) return
        val recipe = saved.recipe(id)
        val composition = recipe.composition ?: return
        Atmosphere.settle(
            level,
            composition.optionsFor(Aspect.ATMOSPHERE, 0),
            composition.optionsFor(Aspect.SKY, 0),
            recipe.seed,
        )
    }

    /** Mints a fresh, distinct Age id (`agesandtheart:age_<n>`) from the persistent counter. */
    /**
     * An id for a new Age, taken from what its writer [called] it where that can be made into one.
     *
     * A dimension id may only hold `[a-z0-9/._-]`, so a name is folded to that and numbered if it is
     * already taken. Anything left with nothing usable — punctuation, another script — falls back to the
     * counter. The id is also the seed source, so two Ages of the same name still differ.
     */
    fun allocateId(server: MinecraftServer, called: String = ""): Identifier {
        val data = AgeSavedData.get(server)
        val stem = folded(called)
        if (stem.isNotEmpty()) {
            for (attempt in 1..NAME_ATTEMPTS) {
                val path = if (attempt == 1) stem else "${stem}_$attempt"
                val candidate = Identifier.fromNamespaceAndPath(Constants.MOD_ID, path)
                if (candidate !in data.ages) return candidate
            }
        }
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID, "age_${data.allocateIndex()}")
    }

    private fun folded(name: String): String =
        name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("_")
            .take(MAX_NAME_LENGTH)
            .trim('_')

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
        // Nothing under eager delivery, which is what we run: the player already knows every Age. It marks
        // the route all the same, so going lazy is one call to `LevelAppearance.lazily` and no hunting for
        // the places a player starts travelling.
        LevelAppearance.expecting(player, level.dimension())
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

    /** How many numbered variants of a name to try before falling back to the counter. */
    private const val NAME_ATTEMPTS = 64
    private const val MAX_NAME_LENGTH = 48

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
        LevelAppearance.forget(ResourceKey.create(Registries.DIMENSION, id))
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
