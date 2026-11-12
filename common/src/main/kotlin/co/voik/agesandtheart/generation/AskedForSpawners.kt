package co.voik.agesandtheart.generation

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags
import net.minecraft.util.Continuation
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.saveddata.WanderingTraderData
import kotlin.math.roundToInt

/**
 * Phantoms a book asked for (`phantom`, `teeming phantom`): vanilla's phantom spawner, with the sleepless left out of
 * it — they come at night to anyone under the open sky, rested or not — and its wait between swarms divided by how
 * thickly the book asked. Still never by day, and only where there is a sky ([VanillaSpawners]).
 */
internal class PhantomsAskedFor(private val amount: Double) : CustomSpawner {
    private var nextTick = 0

    override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
        val mayCome = spawnEnemies && level.dimensionType().hasSkyLight() && level.gameRules.get(GameRules.SPAWN_PHANTOMS)
        if (!mayCome || --nextTick > 0) return
        val random = level.random
        nextTick = ((SHORTEST_WAIT + random.nextInt(WAIT_SPREAD)) * TICKS_PER_SECOND / amount).roundToInt().coerceAtLeast(1)
        if (level.skyDarken < NIGHT) return
        for (player in level.players()) {
            val at = player.blockPosition()
            val underTheOpenSky = at.y >= level.seaLevel && level.canSeeSky(at)
            if (player.isSpectator || !underTheOpenSky) continue
            val difficulty = level.getCurrentDifficultyAt(at)
            if (!difficulty.isHarderThan(random.nextFloat() * DIFFICULTY_SPREAD)) continue
            swarmOver(level, at, random)
        }
    }

    private fun swarmOver(level: ServerLevel, player: BlockPos, random: RandomSource) {
        val at = player.above(LOWEST_ABOVE + random.nextInt(HEIGHT_SPREAD))
            .east(-SIDEWAYS + random.nextInt(2 * SIDEWAYS + 1))
            .south(-SIDEWAYS + random.nextInt(2 * SIDEWAYS + 1))
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, at, level.getBlockState(at), level.getFluidState(at), EntityTypes.PHANTOM)) return
        val difficulty = level.getCurrentDifficultyAt(at)
        var group: SpawnGroupData? = null
        repeat(1 + random.nextInt(difficulty.difficulty.id + 1)) {
            val phantom = EntityTypes.PHANTOM.create(level, EntitySpawnReason.NATURAL) ?: return@repeat
            phantom.snapTo(at, 0.0f, 0.0f)
            group = phantom.finalizeSpawn(level, difficulty, EntitySpawnReason.NATURAL, group)
            level.addFreshEntityWithPassengers(phantom)
        }
    }

    private companion object {
        // Vanilla's own, from `PhantomSpawner`.
        const val SHORTEST_WAIT = 60
        const val WAIT_SPREAD = 60
        const val TICKS_PER_SECOND = 20
        const val NIGHT = 5
        const val DIFFICULTY_SPREAD = 3.0f
        const val LOWEST_ABOVE = 20
        const val HEIGHT_SPREAD = 15
        const val SIDEWAYS = 10
    }
}

/**
 * Wandering traders a book asked for (`wandering_trader`, `trader_llama`): vanilla's trader spawner with the day's
 * chance held at its top and its further one-in-ten dropped, so a trader comes most days, and the day's wait divided by
 * how thickly the book asked. Each leads two llamas, or more where the book asked for llamas. The schedule is kept in
 * the Age's own storage, as vanilla's is in the overworld's.
 */
internal class TradersAskedFor(private val amount: Double, private val llamas: Int) : CustomSpawner {
    private val random = RandomSource.create()
    private var tickDelay = CHECK_EVERY
    private var schedule: WanderingTraderData? = null

    override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
        if (!level.gameRules.get(GameRules.SPAWN_WANDERING_TRADERS) || --tickDelay > 0) return
        tickDelay = CHECK_EVERY
        val data = schedule ?: level.dataStorage.computeIfAbsent(WanderingTraderData.TYPE).also { schedule = it }
        val waited = data.spawnDelay() - CHECK_EVERY
        data.setSpawnDelay(waited)
        if (waited > 0) return
        data.setSpawnDelay((A_DAY / amount).roundToInt().coerceAtLeast(CHECK_EVERY))
        if (random.nextInt(PERCENT) <= HELD_CHANCE) spawn(level)
    }

    private fun spawn(level: ServerLevel) {
        val player = level.randomPlayer ?: return
        val meeting = level.poiManager.find({ it.`is`(PoiTypes.MEETING) }, { true }, player.blockPosition(), RADIUS, PoiManager.Occupancy.ANY)
        val near = meeting.orElse(player.blockPosition())
        val at = spawnPositionNear(level, near, RADIUS) ?: return
        val unwelcome = level.getBiome(at).`is`(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)
        if (!hasRoom(level, at) || unwelcome) return
        val trader = EntityTypes.WANDERING_TRADER.spawn(level, at, EntitySpawnReason.EVENT) ?: return
        repeat(llamas) {
            val llamaAt = spawnPositionNear(level, trader.blockPosition(), LLAMA_RADIUS) ?: return@repeat
            EntityTypes.TRADER_LLAMA.spawn(level, llamaAt, EntitySpawnReason.EVENT)?.setLeashedTo(trader, true)
        }
        trader.despawnDelay = STAYS_FOR
        trader.setWanderTarget(near)
        trader.setHomeTo(near, HOME_RADIUS)
    }

    private fun spawnPositionNear(level: LevelReader, around: BlockPos, radius: Int): BlockPos? {
        val placement = SpawnPlacements.getPlacementType(EntityTypes.WANDERING_TRADER)
        val heightmap = SpawnPlacements.getHeightmapType(EntityTypes.WANDERING_TRADER)
        repeat(ATTEMPTS) {
            val x = around.x + random.nextInt(radius * 2) - radius
            val z = around.z + random.nextInt(radius * 2) - radius
            val at = BlockPos(x, level.getHeight(heightmap, x, z), z)
            if (placement.isSpawnPositionOk(level, at, EntityTypes.WANDERING_TRADER)) return at
        }
        return null
    }

    private fun hasRoom(level: LevelReader, at: BlockPos): Boolean = !level.findBlocksIn(at, at.offset(1, 2, 1))
        .forEachUntil { position, state -> Continuation.continueIf(state.getCollisionShape(level, position).isEmpty) }

    companion object {
        /** The llamas a trader leads where nobody asked for them, as vanilla's does. */
        const val LLAMAS = 2

        /** The most llamas one trader leads, however many the book asked for. */
        const val MOST_LLAMAS = 6

        // Vanilla's own, from `WanderingTraderSpawner`.
        private const val CHECK_EVERY = 1200
        private const val A_DAY = 24000.0
        private const val PERCENT = 100
        private const val HELD_CHANCE = 75
        private const val RADIUS = 48
        private const val LLAMA_RADIUS = 4
        private const val ATTEMPTS = 10
        private const val STAYS_FOR = 48000
        private const val HOME_RADIUS = 16
    }
}
