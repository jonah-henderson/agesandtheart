package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.generation.StructureWeathering
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.AzaleaBlock
import net.minecraft.world.level.block.BambooStalkBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.HangingMossBlock
import net.minecraft.world.level.block.HangingRootsBlock
import net.minecraft.world.level.block.HugeMushroomBlock
import net.minecraft.world.level.block.LilyPadBlock
import net.minecraft.world.level.block.MossyCarpetBlock
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.level.block.SporeBlossomBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import net.minecraft.world.level.block.SugarCaneBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.WeakHashMap

/**
 * What the firestorm leaves (design §5.3 "The asteroid"): whatever daylight reaches burnt — leaves, plants, mushrooms,
 * vines and moss gone, snow and ice gone, glass broken, logs charred to basalt, wood to blackstone, soil turned to coarse dirt and here and
 * there melted to magma, and a few embers — written directly, a chunk or two a tick behind the dust, nearest the
 * players first.
 *
 * **By daylight, not by what stands overhead**, so a mushroom shelters nothing under it, nor does a lip of rock, and
 * the burning reaches as far into a cave's mouth as any light does. Each block is judged by the sky light beside it.
 *
 * **One rule of a block.** It is safe to run over a chunk twice, so which chunks are done is a convenience kept for as
 * long as the server runs, and nothing is lost when it is forgotten.
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

    /** Burns everything in [chunk] daylight reaches, from the top of each column down as far as [DEEPEST_BURNED]. */
    fun scorch(level: ServerLevel, chunk: LevelChunk) {
        val cursor = BlockPos.MutableBlockPos()
        val beside = BlockPos.MutableBlockPos()
        for (x in chunk.pos.minBlockX..chunk.pos.maxBlockX) {
            for (z in chunk.pos.minBlockZ..chunk.pos.maxBlockZ) column(level, chunk, x, z, cursor, beside)
        }
    }

    /**
     * One column, from its top down. **All its snow and ice goes**, light or none — packed ice is dark inside, and the
     * light is not worked out again as it melts, so a rule by daylight left an iceberg's heart, and ice under a fire or
     * a fragment's magma, standing — and whatever stood on it falls ([collapseOnto]). Everything else burns as far as
     * daylight reaches it.
     */
    private fun column(level: ServerLevel, chunk: LevelChunk, x: Int, z: Int, cursor: BlockPos.MutableBlockPos, beside: BlockPos.MutableBlockPos) {
        val top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        val lowest = maxOf(level.minY, top - DEEPEST_BURNED)
        var isFrozenFromTheTop = true
        var highestMeltUnderSomething: Int? = null
        for (y in top downTo lowest) {
            cursor.set(x, y, z)
            val state = chunk.getBlockState(cursor)
            if (state.isAir) continue
            val isFrozen = isSnowOrIce(state)
            isFrozenFromTheTop = isFrozenFromTheTop && isFrozen
            if (isFrozen) {
                level.setBlock(cursor, meltedAt(level, cursor, isFrozenFromTheTop), QUIETLY)
                if (!isFrozenFromTheTop && highestMeltUnderSomething == null) highestMeltUnderSomething = y
                continue
            }
            if (!isInDaylight(level, cursor, beside)) continue
            val burned = burned(state, x, z)
            if (burned is Burned.Into) level.setBlock(cursor, burned.state, QUIETLY)
            val isLeftStanding = burned == Burned.Stops || (burned is Burned.Into && !burned.state.isAir)
            if (isLeftStanding) leaveAnEmber(level, cursor, x, z)
        }
        highestMeltUnderSomething?.let { collapseOnto(level, x, it, z, top) }
    }

    /**
     * What stood on ice that has melted from under it, falling: every block from just above [y] up to [top] that
     * still stands over nothing, as a falling block, lowest first so each finds the one below already gone. What
     * holds things stays where it is.
     */
    private fun collapseOnto(level: ServerLevel, x: Int, y: Int, z: Int, top: Int) {
        val above = BlockPos.MutableBlockPos()
        for (rising in y + 1..top) {
            above.set(x, rising, z)
            val state = level.getBlockState(above)
            val isLoose = !state.isAir && state.fluidState.isEmpty && !state.hasBlockEntity()
            val isOverNothing = level.getBlockState(above.below()).let { it.isAir || !it.fluidState.isEmpty }
            if (isLoose && isOverNothing) FallingBlockEntity.fall(level, above.immutable(), state)
        }
    }

    /** Whether daylight reaches [at]: whether the sky light on any side of it is at least [IN_DAYLIGHT]. */
    fun isInDaylight(level: ServerLevel, at: BlockPos, beside: BlockPos.MutableBlockPos = BlockPos.MutableBlockPos()): Boolean =
        Direction.entries.any { side -> level.getBrightness(LightLayer.SKY, beside.setWithOffset(at, side)) >= IN_DAYLIGHT }

    private sealed interface Burned {
        /** Burned into [state]. */
        data class Into(val state: BlockState) : Burned

        /** What will not burn, or has burned already. */
        data object Stops : Burned
    }

    // NeoForge deprecates `ignitedByLava` for a level-aware form, as `StructureWeathering` says.
    @Suppress("DEPRECATION")
    private fun burned(state: BlockState, x: Int, z: Int): Burned {
        val isFluid = !state.fluidState.isEmpty
        val holdsThings = state.hasBlockEntity()
        val isAlreadyCharred = state.`is`(Blocks.BASALT) || state.`is`(Blocks.COARSE_DIRT) || state.`is`(Blocks.MAGMA_BLOCK)
        // Grass, moss, mud and every dirt; a field and a path besides, which no soil tag holds.
        val isSoil = state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.MOSS_BLOCKS) ||
            state.`is`(Blocks.FARMLAND) || state.`is`(Blocks.DIRT_PATH)
        return when {
            isFluid || holdsThings -> Burned.Stops
            isAlreadyCharred -> Burned.Stops
            isGlass(state) || isGrowing(state) -> Burned.Into(Blocks.AIR.defaultBlockState())
            isSoil -> Burned.Into(groundBurnedAt(x, z))
            state.ignitedByLava() -> Burned.Into(StructureWeathering.blackened(state) ?: Blocks.AIR.defaultBlockState())
            else -> Burned.Stops
        }
    }

    private fun isGlass(state: BlockState): Boolean {
        val block = state.block
        return block is TransparentBlock || block is StainedGlassPaneBlock || state.`is`(Blocks.GLASS_PANE)
    }

    /**
     * What snow or ice at [at] goes to: nothing above the sea, and sea at or under it where it was piled from the top
     * or water touches it, so a frozen sea and its icebergs open; nothing where it was sealed in rock, so no cave
     * floods.
     */
    private fun meltedAt(level: ServerLevel, at: BlockPos, isFrozenFromTheTop: Boolean): BlockState {
        val isUnderTheSea = at.y < level.seaLevel
        val touchesWater = Direction.entries.any { level.getFluidState(at.relative(it)).`is`(FluidTags.WATER) }
        val isSea = isUnderTheSea && (isFrozenFromTheTop || touchesWater)
        return if (isSea) Blocks.WATER.defaultBlockState() else Blocks.AIR.defaultBlockState()
    }

    /** Snow and ice of every kind. */
    private fun isSnowOrIce(state: BlockState): Boolean =
        state.`is`(Blocks.SNOW) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(Blocks.POWDER_SNOW) || state.`is`(BlockTags.ICE)

    /** Whatever grows: leaves and plants, flowers and crops, vines and lichen, mushrooms large and small, moss. */
    private fun isGrowing(state: BlockState): Boolean {
        val isByTag = GROWING_TAGS.any(state::`is`)
        val isByKind = GROWING_KINDS.any { it.isInstance(state.block) }
        return state.canBeReplaced() || isByTag || isByKind || state.block in BURNS_AWAY
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

    /** How bright the sky light beside a block is for daylight to reach it: any at all, so only what no daylight finds is safe. */
    const val IN_DAYLIGHT = 1

    private val GROWING_TAGS = listOf(
        BlockTags.LEAVES, BlockTags.FLOWERS, BlockTags.SAPLINGS, BlockTags.CROPS, BlockTags.REPLACEABLE_BY_TREES,
        BlockTags.SWORD_EFFICIENT, BlockTags.CAVE_VINES, BlockTags.WART_BLOCKS, BlockTags.WOOL_CARPETS,
    )

    /** What was once growing and burns away though vanilla does not call it flammable. */
    private val BURNS_AWAY = setOf(Blocks.HAY_BLOCK, Blocks.DRIED_KELP_BLOCK, Blocks.TARGET)

    /** What grows that no tag holds, by the kind of block it is. */
    private val GROWING_KINDS: List<Class<out Block>> = listOf(
        MushroomBlock::class.java, HugeMushroomBlock::class.java, SugarCaneBlock::class.java, CactusBlock::class.java,
        BambooStalkBlock::class.java, SweetBerryBushBlock::class.java, MossyCarpetBlock::class.java,
        HangingMossBlock::class.java, HangingRootsBlock::class.java, SporeBlossomBlock::class.java,
        AzaleaBlock::class.java, LilyPadBlock::class.java,
    )

    /** Clients told, neighbours not: a forest burning out of the world in one tick must not ask every leaf beside it. */
    private const val QUIETLY = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** How far down a column the fire is looked for: a tall tree, a house of a few storeys, a cave's mouth. */
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
