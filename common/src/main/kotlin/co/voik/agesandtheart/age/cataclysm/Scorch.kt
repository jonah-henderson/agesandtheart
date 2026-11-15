package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.generation.StructureWeathering
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.WeakHashMap

/**
 * What the firestorm leaves (design §5.3 "The asteroid"): leaves burnt away, logs charred to basalt, wood to
 * blackstone, plants gone, soil turned to coarse dirt and here and there melted to magma, and a few embers — written directly, one column at a time,
 * a chunk or two a tick behind the dust, nearest the players first.
 *
 * **One rule of a column**, which the scorched generation and the catch-up on load will call too. It is safe to
 * run over a column twice, so which chunks are done is a convenience kept for as long as the server runs, and
 * nothing is lost when it is forgotten.
 */
object Scorch {

    private val scorched = WeakHashMap<ServerLevel, MutableSet<Long>>()

    fun forget(level: ServerLevel) {
        scorched.remove(level)
    }

    /** Scorches up to [count] loaded chunks in [level] that are not yet, the nearest to anybody first. */
    fun some(level: ServerLevel, count: Int) {
        val done = scorched.getOrPut(level) { mutableSetOf() }
        repeat(count) {
            val chunk = nearestUnscorched(level, done) ?: return
            scorch(level, chunk)
            done += ChunkPos.pack(chunk.pos.x, chunk.pos.z)
        }
    }

    private fun nearestUnscorched(level: ServerLevel, done: Set<Long>): LevelChunk? {
        for (reach in 0..SEARCHED_OUT_TO) {
            for (player in level.players()) {
                val centre = player.chunkPosition()
                for (at in ring(centre, reach)) {
                    if (ChunkPos.pack(at.x, at.z) in done) continue
                    return level.chunkSource.getChunkNow(at.x, at.z) ?: continue
                }
            }
        }
        return null
    }

    /** The chunk positions exactly [reach] chunks from [centre], as a square's edge. */
    private fun ring(centre: ChunkPos, reach: Int): List<ChunkPos> {
        if (reach == 0) return listOf(centre)
        return buildList {
            for (offset in -reach..reach) {
                add(ChunkPos(centre.x + offset, centre.z - reach))
                add(ChunkPos(centre.x + offset, centre.z + reach))
            }
            for (offset in -reach + 1..<reach) {
                add(ChunkPos(centre.x - reach, centre.z + offset))
                add(ChunkPos(centre.x + reach, centre.z + offset))
            }
        }
    }

    fun scorch(level: ServerLevel, chunk: LevelChunk) {
        val cursor = BlockPos.MutableBlockPos()
        for (x in chunk.pos.minBlockX..chunk.pos.maxBlockX) {
            for (z in chunk.pos.minBlockZ..chunk.pos.maxBlockZ) column(level, chunk, x, z, cursor)
        }
    }

    /**
     * Burns down through [x], [z] from its top until something will not burn: the ground, stone, water, or a
     * block that holds things, which is left as it stands.
     */
    private fun column(level: ServerLevel, chunk: LevelChunk, x: Int, z: Int, cursor: BlockPos.MutableBlockPos) {
        val top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        val lowest = maxOf(level.minY, top - DEEPEST_BURNED)
        for (y in top downTo lowest) {
            cursor.set(x, y, z)
            val state = chunk.getBlockState(cursor)
            if (state.isAir) continue
            when (val burned = burned(state, x, z)) {
                Burned.Stops -> {
                    leaveAnEmber(level, cursor, x, z)
                    return
                }
                is Burned.Into -> {
                    level.setBlock(cursor, burned.state, QUIETLY)
                    if (burned.stops) {
                        leaveAnEmber(level, cursor, x, z)
                        return
                    }
                }
            }
        }
    }

    private sealed interface Burned {
        /** Burned into [state]; [stops] where it is the ground, which nothing below it is reached through. */
        data class Into(val state: BlockState, val stops: Boolean = false) : Burned

        /** What will not burn, or has burned already, and shelters everything under it. */
        data object Stops : Burned
    }

    // NeoForge deprecates `ignitedByLava` for a level-aware form, as `StructureWeathering` says.
    @Suppress("DEPRECATION")
    private fun burned(state: BlockState, x: Int, z: Int): Burned {
        val isFluid = !state.fluidState.isEmpty
        val holdsThings = state.hasBlockEntity()
        val isSnow = state.`is`(Blocks.SNOW) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(Blocks.POWDER_SNOW)
        val isAPlant = state.canBeReplaced() || state.`is`(BlockTags.FLOWERS) || state.`is`(BlockTags.SAPLINGS) ||
            state.`is`(BlockTags.REPLACEABLE_BY_TREES) || state.`is`(BlockTags.CROPS)
        val isAlreadyCharred = state.`is`(Blocks.BASALT) || state.`is`(Blocks.COARSE_DIRT) || state.`is`(Blocks.MAGMA_BLOCK)
        // Grass, moss, mud and every dirt; a field and a path besides, which no soil tag holds.
        val isSoil = state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(Blocks.FARMLAND) || state.`is`(Blocks.DIRT_PATH)
        return when {
            isFluid || holdsThings -> Burned.Stops
            isAlreadyCharred -> Burned.Stops
            state.`is`(BlockTags.LEAVES) || isSnow || isAPlant -> Burned.Into(Blocks.AIR.defaultBlockState())
            isSoil -> Burned.Into(groundBurnedAt(x, z), stops = true)
            state.ignitedByLava() -> Burned.Into(StructureWeathering.blackened(state) ?: Blocks.AIR.defaultBlockState())
            else -> Burned.Stops
        }
    }

    /** The soil burned to coarse dirt, or here and there melted to magma. */
    private fun groundBurnedAt(x: Int, z: Int): BlockState {
        val isMeltedHere = Math.floorMod((x * MAGMA_MIXER_X) xor (z * MAGMA_MIXER_Z), MAGMA_ONE_IN) == 0
        return if (isMeltedHere) Blocks.MAGMA_BLOCK.defaultBlockState() else Blocks.COARSE_DIRT.defaultBlockState()
    }

    private fun leaveAnEmber(level: ServerLevel, ground: BlockPos, x: Int, z: Int) {
        val isEmberHere = Math.floorMod((x * EMBER_MIXER_X) xor (z * EMBER_MIXER_Z), EMBERS_ONE_IN) == 0
        if (!isEmberHere) return
        val above = ground.above()
        val catches = level.getBlockState(above).isAir && BaseFireBlock.canBePlacedAt(level, above, Direction.UP)
        if (catches) level.setBlock(above, BaseFireBlock.getState(level, above), Block.UPDATE_ALL)
    }

    /** Clients told, neighbours not: a forest burning out of the world in one tick must not ask every leaf beside it. */
    private const val QUIETLY = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** How far down a column the fire reaches through what burns: a tall tree, a house of a few storeys. */
    private const val DEEPEST_BURNED = 64

    /** How far out from a player the scorch looks for chunks, which is past any ordinary view distance. */
    private const val SEARCHED_OUT_TO = 16

    private const val MAGMA_ONE_IN = 11
    private const val MAGMA_MIXER_X = 83_492_791
    private const val MAGMA_MIXER_Z = 50_331_653

    private const val EMBERS_ONE_IN = 53
    private const val EMBER_MIXER_X = 73_856_093
    private const val EMBER_MIXER_Z = 19_349_663
}
