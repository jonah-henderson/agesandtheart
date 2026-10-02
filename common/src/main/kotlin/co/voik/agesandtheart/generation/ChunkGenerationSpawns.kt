package co.voik.agesandtheart.generation

import co.voik.agesandtheart.Constants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.util.random.WeightedList
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.gamerules.GameRules

/**
 * The animals a chunk is made with, **drawn from the Age's own list** — vanilla's
 * `NaturalSpawner.spawnMobsForChunkGeneration`, line for line, but for where the list comes from and that
 * what it places is kept.
 *
 * Vanilla's reads the biome's list straight off the environment, so nothing a sentence asked for reached it:
 * `teeming cats everywhere` on vanilla's rock made every chunk with vanilla's animals, they filled the cap
 * and never despawn, and the runtime spawner that does offer cats never had room to place one.
 */
internal object ChunkGenerationSpawns {

    fun spawn(level: WorldGenRegion, mobs: WeightedList<MobSpawnSettings.SpawnerData>, random: RandomSource) {
        if (!level.level.gameRules.get(GameRules.SPAWN_MOBS) || mobs.isEmpty) return
        val center = level.center
        val sourcePos = center.worldPosition.atY(level.maxY)
        val creatureProbability = level.environmentAttributes()
            .getValue(EnvironmentAttributes.CREATURE_WORLD_GEN_SPAWN_PROBABILITY, sourcePos)
        val xo = center.minBlockX
        val zo = center.minBlockZ
        while (random.nextFloat() < creatureProbability) {
            val spawnerData = mobs.getRandom(random).orElse(null) ?: continue
            spawnGroup(level, spawnerData, random, xo, zo)
        }
    }

    private fun spawnGroup(
        level: WorldGenRegion,
        spawnerData: MobSpawnSettings.SpawnerData,
        random: RandomSource,
        xo: Int,
        zo: Int,
    ) {
        val group = Group(level, spawnerData.type(), random, xo, zo)
        repeat(spawnerData.count().sample(random)) {
            var success = false
            var attempts = 0
            while (!success && attempts < ATTEMPTS_A_MOB) {
                attempts++
                val tried = group.tryHere()
                success = tried == Tried.SPAWNED
                if (tried != Tried.REFUSED_ON_THE_SPOT) group.wander()
            }
        }
    }

    /**
     * What one attempt came to. Vanilla tries the same spot again where the mob was refused on it — its
     * `continue` skips the step to the next one — and moves on otherwise.
     */
    private enum class Tried { SPAWNED, REFUSED_ON_THE_SPOT, MOVE_ON }

    /** One group's walk across the chunk: where it is, and the spawn data its members share. */
    private class Group(
        private val level: WorldGenRegion,
        private val type: EntityType<*>,
        private val random: RandomSource,
        private val xo: Int,
        private val zo: Int,
    ) {
        private var x = xo + random.nextInt(CHUNK)
        private var z = zo + random.nextInt(CHUNK)
        private val startX = x
        private val startZ = z
        private var groupSpawnData: SpawnGroupData? = null

        fun tryHere(): Tried {
            val pos = topNonCollidingPos(level, type, x, z)
            val mayStandHere = type.canSummon() && SpawnPlacements.isSpawnPositionOk(type, level, pos)
            if (!mayStandHere) return Tried.MOVE_ON
            val width = type.width.toDouble()
            val fx = Mth.clamp(x.toDouble(), xo + width, xo + CHUNK - width)
            val fz = Mth.clamp(z.toDouble(), zo + width, zo + CHUNK - width)
            val clear = level.noCollision(type.getSpawnAABB(fx, pos.y.toDouble(), fz))
            val at = BlockPos.containing(fx, pos.y.toDouble(), fz)
            val ruled = clear &&
                SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.CHUNK_GENERATION, at, level.random)
            if (!ruled) return Tried.REFUSED_ON_THE_SPOT
            val mob = created(level, type) ?: return Tried.REFUSED_ON_THE_SPOT
            mob.snapTo(fx, pos.y.toDouble(), fz, random.nextFloat() * FULL_TURN, 0.0f)
            val fits = mob.checkSpawnRules(level, EntitySpawnReason.CHUNK_GENERATION) && mob.checkSpawnObstruction(level)
            if (!fits) return Tried.MOVE_ON
            groupSpawnData = mob.finalizeSpawn(
                level,
                level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.CHUNK_GENERATION,
                groupSpawnData,
            )
            // **Made with the chunk, so kept with it**, as every animal vanilla makes with a chunk already is.
            // A cat is the one that is not — it despawns once it is two minutes old and out of sight — so
            // the cats a sentence asked for were gone from the chunks ahead before anybody reached them.
            // Vanilla's witch-hut cats are kept the same way.
            mob.setPersistenceRequired()
            level.addFreshEntityWithPassengers(mob)
            return Tried.SPAWNED
        }

        fun wander() {
            x += random.nextInt(WANDER) - random.nextInt(WANDER)
            z += random.nextInt(WANDER) - random.nextInt(WANDER)
            while (x < xo || x >= xo + CHUNK || z < zo || z >= zo + CHUNK) {
                x = startX + random.nextInt(WANDER) - random.nextInt(WANDER)
                z = startZ + random.nextInt(WANDER) - random.nextInt(WANDER)
            }
        }
    }

    private fun created(level: WorldGenRegion, type: EntityType<*>): Mob? =
        try {
            type.create(level.level, EntitySpawnReason.NATURAL) as? Mob
        } catch (failure: Exception) {
            Constants.LOG.warn("Failed to create mob", failure)
            null
        }

    /** Vanilla's own `getTopNonCollidingPos`, which is private to it. */
    private fun topNonCollidingPos(level: LevelReader, type: EntityType<*>, x: Int, z: Int): BlockPos {
        val height = level.getHeight(SpawnPlacements.getHeightmapType(type), x, z)
        val pos = BlockPos.MutableBlockPos(x, height, z)
        if (level.dimensionType().hasCeiling()) {
            do pos.move(Direction.DOWN) while (!level.getBlockState(pos).isAir)
            do pos.move(Direction.DOWN) while (level.getBlockState(pos).isAir && pos.y > level.minY)
        }
        return SpawnPlacements.getPlacementType(type).adjustSpawnPosition(level, pos.immutable())
    }

    private const val CHUNK = 16
    private const val ATTEMPTS_A_MOB = 4
    private const val WANDER = 5
    private const val FULL_TURN = 360.0f
}
