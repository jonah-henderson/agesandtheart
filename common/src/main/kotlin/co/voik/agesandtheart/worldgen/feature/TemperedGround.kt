package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.util.RandomSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import com.mojang.serialization.MapCodec

/**
 * Stone baked by its distance from lava (design §7.1.2).
 *
 * Four bands, and the three that are ours are the lesson: too close comes out scorched, the right remove
 * is temperstone, further out is raw, and beyond that the ground is untouched. A player who finds one
 * formation sees the whole rule laid out in space — and because the raw band is a block rather than plain
 * stone, the rule is one they can carry away and use.
 */
object TemperedGround : Feature {

    val CODEC: MapCodec<TemperedGround> = MapCodec.unit { TemperedGround }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val seeds = contactSurfaces(level, origin)
        if (seeds.isEmpty()) return false
        return bake(level, seeds)
    }

    /**
     * The lava in this chunk that touches something other than lava.
     *
     * Lava enclosed by lava bakes nothing, and skipping it makes the cost follow the contact area rather
     * than the volume — which is the difference between a lava sea being affordable and not.
     */
    private fun contactSurfaces(level: WorldGenLevel, origin: BlockPos): List<BlockPos> {
        val found = mutableListOf<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()
        for (offsetX in 0..<CHUNK) {
            for (offsetZ in 0..<CHUNK) {
                for (y in level.minY..<(level.minY + level.height)) {
                    cursor.set(origin.x + offsetX, y, origin.z + offsetZ)
                    if (!deepEnough(cursor)) continue
                    val isHeat = level.getBlockState(cursor).`is`(COOKS_STONE)
                    if (isHeat && touchesAnythingElse(level, cursor)) found += cursor.immutable()
                }
            }
        }
        return found
    }

    private fun touchesAnythingElse(level: WorldGenLevel, at: BlockPos): Boolean =
        Direction.entries.any { way -> !level.getBlockState(at.relative(way)).`is`(COOKS_STONE) }

    /**
     * Walk outward from the contact surfaces, converting each band as it is reached.
     *
     * Breadth-first rather than a radius test per block: it visits each position once instead of sampling a
     * ball around every candidate, and the distance it yields is the distance *through rock*, so stone
     * shielded behind other stone is not baked through it.
     */
    private fun bake(level: WorldGenLevel, seeds: List<BlockPos>): Boolean {
        val seen = HashSet<BlockPos>()
        var frontier = seeds
        var baked = false
        for (distance in 1..RAW_BAND) {
            val next = mutableListOf<BlockPos>()
            for (at in frontier) {
                for (way in Direction.entries) {
                    val neighbour = at.relative(way)
                    if (!seen.add(neighbour)) continue
                    if (!bakeable(level.getBlockState(neighbour))) continue
                    level.setBlock(neighbour, becomes(distance), UPDATE_NONE)
                    next += neighbour
                    baked = true
                }
            }
            if (next.isEmpty()) break
            frontier = next
        }
        return baked
    }

    /**
     * What a block resting [at] would bake into, or null where nothing should happen to it.
     *
     * The runtime half of the same rule [bake] lays down, so a raw block a player carries to a lava pool
     * behaves exactly as the ground around a natural one — read from the world rather than restated, which
     * is what stops the lesson and the practice drifting apart.
     */
    fun bakedAt(level: BlockGetter, at: BlockPos): BlockState? {
        val heat = distanceToLava(level, at) ?: return null
        val baked = becomes(heat)
        return baked.takeIf { !level.getBlockState(at).`is`(baked.block) }
    }

    /**
     * How far [at] stands from the nearest lava, through rock, or null if it is out of reach.
     *
     * Searched from the block outward rather than from lava inward, which is the cheaper direction for one
     * position — the reverse of what [bake] wants for a whole chunk.
     */
    private fun distanceToLava(level: BlockGetter, at: BlockPos): Int? {
        val seen = hashSetOf(at)
        var frontier = listOf(at)
        for (distance in 1..RAW_BAND) {
            val next = mutableListOf<BlockPos>()
            for (position in frontier) {
                for (way in Direction.entries) {
                    val neighbour = position.relative(way)
                    if (!seen.add(neighbour)) continue
                    if (level.getBlockState(neighbour).`is`(COOKS_STONE)) return distance
                    next += neighbour
                }
            }
            frontier = next
        }
        return null
    }

    private fun becomes(distance: Int): BlockState = when {
        distance <= SCORCHED_BAND -> AgeContent.SCORCHED_TEMPERSTONE_BLOCK.defaultBlockState()
        distance <= TEMPERED_BAND -> AgeContent.TEMPERSTONE_BLOCK.defaultBlockState()
        else -> AgeContent.RAW_TEMPERSTONE_BLOCK.defaultBlockState()
    }

    /**
     * Whether the heat may work on this block.
     *
     * Base stone and the raw form, so an ore or a deposit sitting in the ground is left where it is.
     */
    private fun bakeable(state: BlockState): Boolean {
        val isOrdinaryGround = state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_NETHER)
        return isOrdinaryGround || state.`is`(Blocks.TUFF) || state.`is`(AgeContent.RAW_TEMPERSTONE_BLOCK)
    }

    /**
     * How deep a natural formation may be found — **where the bands GENERATE, and nothing about the rule.**
     *
     * A lava sea's rim yielded enough for the armour without anybody trying, which is not what a material
     * gated on a whole Age's character is worth (Jonah, 2026-09-09, walked). So the ground only cooks its
     * own stone down here, and finding temperstone is a descent.
     *
     * **It was briefly a rule and that was wrong** (Jonah, same walk). Putting the depth into [bakedAt]
     * would also have governed the practice — carrying raw stone to shallow lava would have done nothing —
     * which closes the loophole and makes no physical sense whatever: heat tempers stone because it is
     * heat, and a player who watched a formation and then reproduced it at the wrong altitude would learn
     * only that the game had refused them for no reason they could see. **A gate on generation is a fact
     * about where a thing is found; a gate on the rule is a lie about how it works.**
     *
     * So the loophole stays open on purpose: read the bands, carry the stone to any lava, and it tempers.
     * That is the practice working, and it is the reward for having understood the formation.
     */
    private fun deepEnough(at: BlockPos): Boolean = at.y <= NOTHING_ABOVE

    /** Clearly under any waterline, so a lava sea's shore is out and its floor is in. */
    private const val NOTHING_ABOVE = 32

    /**
     * How far the heat reaches, in blocks of rock.
     *
     * A skin on the boundary rather than a halo around it: a lava sea's contact area is large enough that a
     * generous reach would put more of the material in one Age than the economy is worth. The raw band is
     * the widest because it is the one meant to be carried away.
     */
    private const val SCORCHED_BAND = 2
    private const val TEMPERED_BAND = 4
    private const val RAW_BAND = 6

    /**
     * What counts as heat.
     *
     * A tag rather than lava, so a later material of ours — or a pack's — can cook stone without this
     * feature knowing it exists.
     */
    private val COOKS_STONE: TagKey<Block> = TagKey.create(Registries.BLOCK, "cooks_stone".location())

    private const val CHUNK = 16

    /** No neighbour updates: every block placed during generation is already settled. */
    private const val UPDATE_NONE = 2
}
