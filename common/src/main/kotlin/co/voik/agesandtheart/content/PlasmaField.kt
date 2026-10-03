package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.LevelChunk

/**
 * The heat over a plasma sea (design §7.1.2), measured up from its surface. Within [PlasmaSea.FIELD_REACH]
 * blocks are incinerated unless [Plasma.spares] them, and creatures take plasma damage, more the closer they
 * are, which only a whole deretheni suit stops; to [SCORCHING_REACH], whatever burns catches and creatures
 * are set alight.
 */
object PlasmaField {

    const val SCORCHING_REACH = 2 * PlasmaSea.FIELD_REACH

    private const val CHUNKS_A_TICK = 4
    /** A second: a creature cannot be hurt again sooner, so a faster beat would only waste every other check. */
    private const val CREATURES_EVERY = 20L

    /** Plasma damage a check, a block over the surface; it falls away evenly to an eighth at the field's edge. */
    private const val MOST_DAMAGE = 8.0f
    private const val MOST_WEAR = 4
    private const val SECONDS_ALIGHT = 4.0f

    /** How likely something that burns catches, each time the field passes over it. */
    private const val CATCHES = 0.125

    /** Called from `AgeTick` for an Age somebody is in: creatures every second, blocks a few chunks a tick. */
    fun burn(level: ServerLevel) {
        val generator = level.chunkSource.generator
        if (!PlasmaSea.isAnywhereIn(generator)) return
        if (level.gameTime % CREATURES_EVERY == 0L) scorchCreatures(level, generator)
        val inView = Sampling.inViewNearestFirst(level)
        if (inView.isEmpty()) return
        val from = ((level.gameTime * CHUNKS_A_TICK) % inView.size).toInt()
        burnOver(level, generator, (0..<CHUNKS_A_TICK).map { step -> inView[(from + step) % inView.size] })
    }

    /** One whole pass over every chunk within [radius] of [centre], creatures included — `/age plasma pass`. */
    fun passAround(level: ServerLevel, centre: ChunkPos, radius: Int) {
        val generator = level.chunkSource.generator
        if (!PlasmaSea.isAnywhereIn(generator)) return
        scorchCreatures(level, generator)
        val around = (-radius..radius).flatMap { dx ->
            (-radius..radius).map { dz -> ChunkPos.pack(centre.x + dx, centre.z + dz) }
        }
        burnOver(level, generator, around)
    }

    private fun scorchCreatures(level: ServerLevel, generator: ChunkGenerator) {
        for (entity in level.allEntities) {
            if (entity !is LivingEntity || entity.isSpectator) continue
            val surface = PlasmaSea.surfaceAt(generator, entity.blockX, entity.blockZ) ?: continue
            val height = entity.blockY - surface
            when (height) {
                in 1..PlasmaSea.FIELD_REACH -> scorch(level, entity, nearnessAt(height))
                in PlasmaSea.FIELD_REACH + 1..SCORCHING_REACH -> entity.igniteForSeconds(SECONDS_ALIGHT)
            }
        }
    }

    /** One at the block over the surface, falling to an eighth at the field's edge. */
    private fun nearnessAt(height: Int): Float = (PlasmaSea.FIELD_REACH + 1 - height).toFloat() / PlasmaSea.FIELD_REACH

    private fun scorch(level: ServerLevel, entity: LivingEntity, nearness: Float) {
        val isSuited = entity is ServerPlayer && ProtectiveSuit.wearingTheWholeSuit(entity)
        if (!isSuited) {
            entity.hurtServer(level, Plasma.damageIn(level), MOST_DAMAGE * nearness)
            return
        }
        ProtectiveSuit.wearOut(entity as ServerPlayer, points = maxOf(1, (MOST_WEAR * nearness).toInt()))
    }

    private fun burnOver(level: ServerLevel, generator: ChunkGenerator, chunks: List<Long>) {
        for (packed in chunks) {
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            burnOver(level, generator, chunk)
        }
    }

    private fun burnOver(level: ServerLevel, generator: ChunkGenerator, chunk: LevelChunk) {
        val position = BlockPos.MutableBlockPos()
        for (localX in 0..<CHUNK_WIDTH) for (localZ in 0..<CHUNK_WIDTH) {
            val worldX = chunk.pos.getBlockX(localX)
            val worldZ = chunk.pos.getBlockZ(localZ)
            val surface = PlasmaSea.surfaceAt(generator, worldX, worldZ) ?: continue
            incinerateTheLowest(level, position.set(worldX, surface, worldZ), surface)
            for (height in PlasmaSea.FIELD_REACH + 1..SCORCHING_REACH) {
                setAlight(level, position.set(worldX, surface + height, worldZ))
            }
        }
    }

    /** The lowest thing in the field burns away, a block a column each pass, so the ground burns upward. */
    private fun incinerateTheLowest(level: ServerLevel, position: BlockPos.MutableBlockPos, surface: Int) {
        for (height in 1..PlasmaSea.FIELD_REACH) {
            position.setY(surface + height)
            val standing = level.getBlockState(position)
            if (Plasma.spares(standing)) continue
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState())
            level.levelEvent(LevelEvent.LAVA_FIZZ, position, 0)
            return
        }
    }

    /** Fire over what burns, as lava sets it: only into air, and only by chance. */
    private fun setAlight(level: ServerLevel, position: BlockPos.MutableBlockPos) {
        if (!level.getBlockState(position).ignitedByLava()) return
        if (level.random.nextDouble() >= CATCHES) return
        val above = position.above()
        if (level.getBlockState(above).isAir) level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above))
    }

    private const val CHUNK_WIDTH = 16
}
