package co.voik.agesandtheart.generation

import co.voik.agesandtheart.book.DescriptiveBookRecipe
import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import co.voik.ephemeris.RuntimeLevelConfig
import co.voik.ephemeris.RuntimeLevelEvents
import co.voik.ephemeris.RuntimeLevels
import co.voik.ephemeris.sky.LevelAppearance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.dimension.end.EnderDragonFight
import net.minecraft.world.level.levelgen.Heightmap
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import java.util.WeakHashMap
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.worldgen.dni.DniCity

/**
 * Loader-agnostic lifecycle for Ages. Opening and discarding a level is Ephemeris' [RuntimeLevels]; this
 * layer owns the bookkeeping, which recipe and dimension type a level is opened with, and the
 * persistence-replay policy.
 *
 * Ephemeris does not restore levels on restart, so Age ids are tracked in [AgeSavedData] and re-opened via
 * [reloadSaved] from each loader's "server started" event.
 */
object Ages {
    /**
     * Settle every Age's air as it opens, however it came to be open.
     *
     * On the library's own event rather than at each call site: writing an Age, linking to one and replaying
     * the saved list on boot all end in the same place, and the replay used to be the one that missed.
     */
    fun attach() {
        RuntimeLevelEvents.whenOpened(::settleTheAir)
        RuntimeLevelEvents.whenOpened(::lendTheDragon)
    }

    /**
     * The recipe [level] was written from, or null where it is no Age of ours.
     *
     * The namespace test comes first because this is asked of every level, lightning bolt and open
     * instrument, and a level outside our namespace should cost one string comparison.
     */
    fun recipeOf(level: ServerLevel): AgeRecipe? {
        val id = level.dimension().identifier()
        if (id.namespace != Constants.MOD_ID) return null
        return AgeSavedData.get(level.server).recipe(id)
    }

    /**
     * **The dragon belonging to the world the book was written over**, for an Age wearing a type of ours.
     *
     * `ServerLevel` makes the fight in its own constructor when its dimension type asks for one, so an Age
     * that kept the End's rock has it already and this leaves that one alone. An Age that *named a
     * landform* wears one of ours instead, and ours cannot ask: the flag would double a set of three, and
     * a dragon is a fact about the world rather than about the height band the type exists to declare. So
     * it is lent on opening, exactly as the air is (§4, and the same reading as the roof and the skylight).
     *
     * `setDragonFight` carries `@VisibleForTesting` and is still the right seam. The alternatives are a
     * Mixin into a constructor to set one field, or a fourth and fifth dimension type; a public method
     * vanilla already maintains beats both.
     */
    private fun lendTheDragon(level: ServerLevel) {
        // Vanilla made one already, and a second would race the first over the same saved data.
        if (level.dimensionType().hasEnderDragonFight()) return
        val recipe = recipeOf(level) ?: return
        val worldItWasWrittenOver = level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(recipe.template.dimensionType).value()
        if (!worldItWasWrittenOver.hasEnderDragonFight()) return

        val fight = level.dataStorage.computeIfAbsent(EnderDragonFight.TYPE)
        fight.init(level, level.seed, BlockPos.ZERO)
        @Suppress("DEPRECATION")
        level.setDragonFight(fight)
    }

    /**
     * Creates a brand-new Age and records it for persistence. Null if a level of that id is already loaded,
     * or the id is held for an Age still to be renamed to it — which is how a book bound to such an Age
     * opens nothing until it arrives, rather than a second world of the same name.
     */
    fun create(server: MinecraftServer, id: Identifier, recipe: AgeRecipe): ServerLevel? {
        val dimensionKey = ResourceKey.create(Registries.DIMENSION, id)
        if (server.getLevel(dimensionKey) != null) return null // already loaded
        if (AgeSavedData.get(server).isReserved(id)) return null
        // Record the recipe *before* opening: [open] builds the world from what is recorded.
        AgeSavedData.get(server).add(id, recipe)
        val level = open(server, id)
        Constants.LOG.info("Created Age {} [{}]", id, recipe)
        return level
    }

    /**
     * Opens the Age [id] from its recorded recipe, or returns it if it is open already. Opening an existing
     * Age reuses its saved chunks. Used for travel and restart-replay; must be called on the server thread.
     * An [id] with no recorded recipe opens as a book that says nothing would ([DescriptiveBookRecipe.sayingNothing]).
     */
    fun open(server: MinecraftServer, id: Identifier): ServerLevel {
        val recipe = AgeSavedData.get(server).recipe(id) ?: DescriptiveBookRecipe.sayingNothing(server, id)
        return RuntimeLevels.open(
            server,
            id,
            RuntimeLevelConfig(
                dimensionType = server.registryAccess()
                    .lookupOrThrow(Registries.DIMENSION_TYPE)
                    .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGeneration.dimensionType(recipe))),
                generator = AgeGeneration.chunkGenerator(server, recipe),
                seed = recipe.seed,
                customSpawners = AgeGeneration.spawnersFor(server, recipe),
                horizonAtTheFloor = AgeGeneration.hasNothingBeneathIt(recipe),
            ),
        )
    }

    /**
     * The Age's own layer over the environment vanilla built for the level (§3.1's Atmosphere).
     *
     * **On opening rather than in the generator**, because an attribute is a fact about the *level* and
     * nothing in generation reads one — and on every open rather than once, because a level is built afresh
     * from the recipe each time the server starts.
     */
    private fun settleTheAir(level: ServerLevel) {
        val recipe = recipeOf(level) ?: return
        val composition = recipe.composition ?: return
        Atmosphere.settle(level, recipe.seed, composition, recipe.template)
    }

    /**
     * An id for a new Age, taken from what its writer [called] it where that can be made into one.
     *
     * A dimension id may only hold `[a-z0-9/._-]`, so a name is folded to that and numbered if it is
     * already taken. Anything left with nothing usable — punctuation, another script — falls back to the
     * counter. The id is also the seed source, so two Ages of the same name still differ.
     */
    fun allocateId(server: MinecraftServer, called: String = ""): Identifier =
        idCalled(server, called)
            ?: Identifier.fromNamespaceAndPath(Constants.MOD_ID, "age_${AgeSavedData.get(server).allocateIndex()}")

    /** A free id taken from what an Age is [called], or null where nothing usable is left of the name. */
    fun idCalled(server: MinecraftServer, called: String): Identifier? {
        val data = AgeSavedData.get(server)
        val stem = folded(called)
        if (stem.isEmpty()) return null
        for (attempt in 1..NAME_ATTEMPTS) {
            val path = if (attempt == 1) stem else "${stem}_$attempt"
            val candidate = Identifier.fromNamespaceAndPath(Constants.MOD_ID, path)
            val isFree = candidate !in data.ages && !data.isReserved(candidate)
            if (isFree) return candidate
        }
        return null
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
     * The Age [id], written from [recipe] if it does not exist yet. Null if it could not be written.
     * An Age that already exists keeps the recipe it was written from — [recipe] says what to write,
     * not what to become.
     */
    fun ensure(server: MinecraftServer, id: Identifier, recipe: AgeRecipe): ServerLevel? =
        if (id in AgeSavedData.get(server).ages) open(server, id) else create(server, id, recipe)

    /**
     * Puts a player down on solid ground in an Age.
     *
     * A per-dimension spawn point cannot be set afterwards — a runtime level is given `DerivedLevelData`,
     * whose `setSpawn` does nothing — so footing is searched for outward from the origin instead.
     */
    fun teleport(player: ServerPlayer, level: ServerLevel) {
        // Nothing under eager delivery, which is what we run: the player already knows every Age. It marks
        // the route all the same, so going lazy is one call to `LevelAppearance.lazily` and no hunting for
        // the places a player starts travelling.
        LevelAppearance.expecting(player, level.dimension())
        val arrival = arrivals.getOrPut(level) { workOutTheArrivalIn(level) }
        openUpArrival(level, arrival)
        // `teleportTo` gained a relative-movement set and a "set camera" flag. Nothing here is relative and
        // the camera should follow, which is the empty set and `true`.
        player.teleportTo(
            level, arrival.at.x + 0.5, arrival.at.y.toDouble(), arrival.at.z + 0.5,
            emptySet(), arrival.facing?.toYRot() ?: player.yRot, player.xRot, true,
        )
    }

    /**
     * Where a visitor lands and which way they face. [facing] is null for footing found in the rock, where
     * a visitor keeps the heading they had; set, it marks a built doorway that [openUpArrival] must not widen.
     */
    data class Arrival(val at: BlockPos, val facing: Direction? = null)

    /**
     * Where a visitor lands — the block their feet occupy.
     *
     * **Shared with the linking panel** (§7.8.1) so that what a book shows and where it puts you cannot be
     * two different places. Loads the chunk it answers about, since neither caller can use a height read
     * off ungenerated ground.
     */
    fun arrivalIn(level: ServerLevel): BlockPos = arrivals.getOrPut(level) { workOutTheArrivalIn(level) }.at

    /**
     * Where each Age's arrival is, worked out once per level.
     *
     * **Because working it out is the slowest thing a first link does.** [findFooting] samples columns of
     * `getBaseHeight`, each a full run of the generator's density functions, and both linking and the
     * linking panel ask. Measured on a cold Age before it was memoised: 16,269ms to find the arrival
     * against 122ms to roll and open the whole world.
     *
     * A generator is rebuilt identically on every open, so the second answer would always be the first —
     * except that `AgeConfig.searchesForFooting` can change between server runs, which is why this is a
     * memo rather than anything persisted. Weakly keyed on the level, so an arrival lasts exactly as long
     * as the level it was worked out for: an Age deleted and written again, or one of the same name in
     * another world, is a different level and works out its own.
     */
    private val arrivals = WeakHashMap<ServerLevel, Arrival>()

    /** Into the D'ni city where the Age has one (design §7.6), and otherwise onto footing near the origin. */
    private fun workOutTheArrivalIn(level: ServerLevel): Arrival {
        DniCity.arrivalIn(level)?.let { return it }
        val (landingX, landingZ) = findFooting(level)
        return Arrival(footingIn(level, landingX, landingZ))
    }

    /**
     * Where a visitor stands in one column: the space above the highest thing that will hold them.
     *
     * **Asked of the generator, never of the world.** Reading a heightmap means the chunk exists, and
     * generating one to full costs seconds on a cold Age — eight and a half of them, measured, against
     * seventeen milliseconds for the search that chose the column. It also blocks the server thread, so
     * nothing else could start until it finished. The generator answers the same question about the same
     * terrain without a chunk, and the chunk gets generated anyway a moment later as the ring's own centre.
     *
     * What is given up is decoration: this is the shape of the rock, so a visitor may arrive inside a tree
     * that grew there. [openUpArrival] already handles being arrived somewhere solid.
     *
     * Three answers, because a column need not have a floor in it. Ordinarily the top of the surface, or
     * the first floor under the roof in a world shut overhead. Where the column holds nothing at all — an
     * Age of open sky — the waterline, so a visitor arrives in the air rather than on the world's floor.
     * Where it is solid to the top, the highest a player fits.
     */
    private fun footingIn(level: ServerLevel, x: Int, z: Int): BlockPos {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val topOfTheColumn = if (level.dimensionType().hasCeiling()) {
            floorUnderTheRoof(generator.getBaseColumn(x, z, level, randomState), level)
        } else {
            generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, randomState) - 1
        }
        val standing = when {
            topOfTheColumn < level.minY -> level.seaLevel
            topOfTheColumn + 1 + HEADROOM > level.maxY -> level.maxY - HEADROOM
            else -> topOfTheColumn + 1
        }
        return BlockPos(x, standing, z)
    }

    /**
     * Clears somewhere to stand at [arrival], for an Age that has no room there.
     *
     * Only on the way in, never on the way to a panel: a book that carved a pocket merely by being opened
     * would edit an Age nobody had visited.
     *
     * At a built doorway only the visitor's own column: the city's arrival is one block from its frame.
     */
    private fun openUpArrival(level: ServerLevel, arrival: Arrival) {
        val (at, facing) = arrival
        val cursor = BlockPos.MutableBlockPos()
        fun blocked(y: Int) = !level.getBlockState(cursor.set(at.x, y, at.z)).isAir
        if (!blocked(at.y) && !blocked(at.y + 1)) return

        Constants.LOG.info("Carving room to arrive at {} in {}", at, level.dimension().identifier())
        val reach = if (facing == null) 1 else 0
        for (y in at.y..at.y + HEADROOM) {
            for (x in at.x - reach..at.x + reach) {
                for (z in at.z - reach..at.z + reach) {
                    level.setBlockAndUpdate(cursor.set(x, y, z), Blocks.AIR.defaultBlockState())
                }
            }
        }
        // A floor under the pocket, or one carved out of a hillside drops the visitor through it.
        for (x in at.x - reach..at.x + reach) {
            for (z in at.z - reach..at.z + reach) {
                val under = cursor.set(x, at.y - 1, z)
                if (level.getBlockState(under).isAir) level.setBlockAndUpdate(under, FOOTING_BLOCK)
            }
        }
    }

    /** What a carved arrival stands on, where there was nothing. */
    private val FOOTING_BLOCK get() = Blocks.STONE.defaultBlockState()

    /**
     * The floor of a world that is **shut overhead**, found by walking down past the roof.
     *
     * A surface heightmap answers the *roof* in such a world, so asking one put a player on top of an
     * infernal Age looking at the sky (Jonah, 2026-08-14, walked). Vanilla has the same problem in the
     * nether and answers it the same way: come down from the ceiling and take the first floor with room
     * to stand on it.
     *
     * The column is read rather than the heightmap because a heightmap has no notion of "the second solid
     * thing down", which is the whole of what is wanted here.
     */
    private fun floorUnderTheRoof(column: NoiseColumn, level: ServerLevel): Int {
        fun isAirAt(y: Int) = column.getBlock(y).isAir
        fun standingRoomAt(y: Int) = !isAirAt(y) && isAirAt(y + 1) && isAirAt(y + 2)

        var y = level.maxY - HEADROOM
        // Down through whatever air is above the roof, then through the roof itself.
        while (y > level.minY && isAirAt(y)) y--
        while (y > level.minY && !isAirAt(y)) y--
        while (y > level.minY) {
            if (standingRoomAt(y)) return y
            y--
        }
        // A column with no floor under its roof at all, which the caller reads as holding nothing.
        return level.minY - 1
    }

    /** Room for a player above the floor they are put on, which is what makes a floor one. */
    private const val HEADROOM = 2

    /**
     * A column standing clear of the sea, or failing that the shallowest one found.
     *
     * Asks the generator rather than the world, so no chunk is generated until one is chosen — which is
     * what makes a wide search affordable at all. Answering with the shallowest column rather than the
     * origin costs nothing, since its height was sampled on the way past.
     */
    private fun findFooting(level: ServerLevel): Pair<Int, Int> {
        if (!AgeConfig.searchesForFooting.get()) return ORIGIN

        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val waterline = generator.seaLevel
        fun heightAt(column: Pair<Int, Int>) =
            generator.getBaseHeight(column.first, column.second, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)

        var shallowest = ORIGIN
        var shallowestHeight = Int.MIN_VALUE
        for (column in candidateColumns()) {
            val height = heightAt(column)
            if (height > waterline) return column
            if (height > shallowestHeight) {
                shallowestHeight = height
                shallowest = column
            }
        }
        return shallowest
    }

    /**
     * Every column worth trying, the widely spaced ones first.
     *
     * Every candidate is a full run of the generator's density functions, so how many there are and what
     * order they come in is the whole of the cost. Widely across the whole reach, closely only near home:
     * the wide lattice finds any landmass broader than its own step wherever it is, and the close one adds
     * precision about small ground, which is only worth crossing a world for if it is nearby.
     */
    internal fun candidateColumns(): Sequence<Pair<Int, Int>> = sequence {
        yieldAll(outwardFromOrigin(WIDE_STEP, FOOTING_REACH))
        yieldAll(outwardFromOrigin(FOOTING_STEP, CLOSE_REACH))
    }.distinct()

    /** Lattice of candidate columns at [step], nearest ring first, out to [reach]. */
    private fun outwardFromOrigin(step: Int, reach: Int): Sequence<Pair<Int, Int>> = sequence {
        yield(ORIGIN)
        for (ring in 1..reach / step) {
            val extent = ring * step
            for (along in -extent..extent step step) {
                yield(along to -extent)
                yield(along to extent)
                yield(-extent to along)
                yield(extent to along)
            }
        }
    }

    private val ORIGIN = 0 to 0

    // A step under a chunk, out far enough to clear the widest island spacing we place.
    private const val FOOTING_STEP = 12

    /** Four chunks, and a multiple of [FOOTING_STEP] so its lattice is a subset of the close one. */
    private const val WIDE_STEP = 48

    /** How far the wide lattice reaches, which is how far an Age is searched at all. */
    private const val FOOTING_REACH = 288

    /** How far the close lattice reaches, past which small ground is not worth the columns to find. */
    private const val CLOSE_REACH = 72

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
        deletionListeners.forEach { it(id) }
        if (!RuntimeLevels.delete(server, id)) return false
        saved.remove(id)
        LevelAppearance.forget(ResourceKey.create(Registries.DIMENSION, id))
        Constants.LOG.info("Deleted Age {}", id)
        return true
    }

    /**
     * Moves the Age [from] to the id [to], chunks and all, and opens it there. Returns whether it moved; where
     * it did not, the Age is open at [from] as before.
     *
     * For an Age whose id was a placeholder — a crystal viewer's preview, made before its book had a title
     * ([co.voik.agesandtheart.desk.PreviewedAges]). Refused where anyone is standing in it, since closing a
     * level under a player is not something a rename should do, or where [to] is taken.
     */
    fun rename(server: MinecraftServer, from: Identifier, to: Identifier): Boolean {
        val saved = AgeSavedData.get(server)
        if (from !in saved.ages || to in saved.ages) return false
        val leaving = ResourceKey.create(Registries.DIMENSION, from)
        if (server.getLevel(leaving)?.players()?.isNotEmpty() == true) return false
        if (!RuntimeLevels.move(server, from, to)) {
            // Closed and not moved, or not closed at all: either way it is still at its old id.
            open(server, from)
            return false
        }
        saved.rename(from, to)
        LevelAppearance.forget(leaving)
        open(server, to)
        Constants.LOG.info("Renamed Age {} to {}", from, to)
        return true
    }

    /**
     * Holds [to] for the Age [from] and renames it when [renameIfOwed] is next asked of it — or at the next
     * boot, before anything opens. For an Age that cannot be closed yet because its ring is generating.
     */
    fun renameLater(server: MinecraftServer, from: Identifier, to: Identifier) {
        AgeSavedData.get(server).renameLater(from, to)
        Constants.LOG.info("Age {} will be renamed to {} once it is warmed", from, to)
    }

    /** Renames [from] if a rename is owed to it. The rename stays owed, to be tried at boot, if it fails. */
    fun renameIfOwed(server: MinecraftServer, from: Identifier) {
        val to = AgeSavedData.get(server).pendingRenames[from] ?: return
        if (!rename(server, from, to)) Constants.LOG.warn("Could not rename {} to {} yet; it will be tried again at boot", from, to)
    }

    /**
     * Tells [listener] the id of every Age [delete] discards, before its level closes — including an Age
     * whose level was never opened, which Ephemeris' `RuntimeLevelEvents.whenClosing` does not report.
     */
    fun whenDeleted(listener: (Identifier) -> Unit) {
        deletionListeners.add(listener)
    }

    private val deletionListeners = mutableListOf<(Identifier) -> Unit>()

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

    /**
     * Makes every rename still owed from a server that stopped before it could, while no Age is open — so
     * each is a move of a closed folder.
     */
    private fun settleOwedRenames(server: MinecraftServer) {
        val saved = AgeSavedData.get(server)
        for ((from, to) in saved.pendingRenames.toList()) {
            val isStillAnAge = from in saved.ages && to !in saved.ages
            if (!isStillAnAge) {
                saved.forgetRename(from)
                continue
            }
            if (!RuntimeLevels.move(server, from, to)) {
                Constants.LOG.warn("Could not rename {} to {} at boot; it stays owed", from, to)
                continue
            }
            saved.rename(from, to)
            LevelAppearance.forget(ResourceKey.create(Registries.DIMENSION, from))
            Constants.LOG.info("Renamed Age {} to {}, owed since the last run", from, to)
        }
    }

    /** Re-opens every persisted Age. Call once per server start (from a loader lifecycle hook). */
    fun reloadSaved(server: MinecraftServer) {
        settleOwedRenames(server)
        val ages = AgeSavedData.get(server).ages
        if (ages.isEmpty()) return
        Constants.LOG.info("Re-opening {} saved Age(s)", ages.size)
        for (id in ages) open(server, id)
    }
}
