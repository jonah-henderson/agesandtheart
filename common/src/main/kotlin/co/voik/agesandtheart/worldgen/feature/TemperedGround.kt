package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.field.StandingFluid
import co.voik.agesandtheart.worldgen.field.TerrainField
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
        val bakedDeep = seeds.isNotEmpty() && bake(level, seeds)
        val bakedAtTheRim = pocketInTheCraterWall(level, generator, random, origin)
        return bakedDeep || bakedAtTheRim
    }

    /**
     * **The exception to the depth: a small pocket in a volcano's crater wall** (Jonah). Finding temperstone
     * is a descent, or it is braving an active volcano for the little its rim has baked — a 3×3 finger
     * pushed into the rock from the lava's edge, banded by true distance to lava like any formation, so it
     * shows the whole rule in a handful of blocks.
     *
     * Only a volcano's own lake counts, found by name as its vents find it: a lava sea's shore is still
     * out. One pocket at most per chunk of crater edge, and only in [POCKET_CHANCE] of them.
     */
    private fun pocketInTheCraterWall(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val ageGenerator = generator as? AgeChunkGenerator ?: return false
        val lakes = ageGenerator.seaFill.carried.firstOrNull { it.named == StandingFluid.CRATER_LAKES }?.where
            ?: return false
        if (random.nextFloat() >= POCKET_CHANCE) return false
        val edges = craterEdgesIn(level, lakes, origin).toMutableList()
        // Most of a crater's wall is a thick lining with nothing to bake within reach, so a few edges are
        // tried and the first that bakes anything is the pocket.
        repeat(minOf(POCKET_TRIES, edges.size)) {
            val (lava, into) = edges.removeAt(random.nextInt(edges.size))
            if (dig(level, lava, into)) return true
        }
        return false
    }

    /** Lava near a crater lake's surface in this chunk with bakeable rock beside it, and the way to that rock. */
    private fun craterEdgesIn(level: WorldGenLevel, lakes: TerrainField, origin: BlockPos): List<Pair<BlockPos, Direction>> {
        val edges = mutableListOf<Pair<BlockPos, Direction>>()
        for (offsetX in 0..<CHUNK step LAKE_STRIDE) {
            for (offsetZ in 0..<CHUNK step LAKE_STRIDE) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                for (body in lakes.columnSpans(x, z).ranges) {
                    val surface = body.last
                    if (surface <= NOTHING_ABOVE) continue
                    for (y in surface downTo maxOf(body.first, surface - NEAR_THE_SURFACE)) {
                        val at = BlockPos(x, y, z)
                        if (!level.getBlockState(at).`is`(COOKS_STONE)) continue
                        Direction.Plane.HORIZONTAL
                            .filter { wallAt(level.getBlockState(at.relative(it))) }
                            .forEach { edges += at to it }
                    }
                }
            }
        }
        return edges
    }

    /**
     * Whether a crater's lava rests against rock here: the obsidian a lake is held in, or stone the heat may
     * work on. The lining is passed through and kept — it is what holds the lake — and the pocket is baked
     * into the rock behind it.
     */
    private fun wallAt(state: BlockState): Boolean = state.`is`(Blocks.OBSIDIAN) || bakeable(state)

    /**
     * What a rim pocket may bake: the rock, and the skin a hot climate paints a cone with — dirt, sand,
     * gravel, terracotta — which is all there is within reach of the lava's surface on most rims.
     */
    private fun bakeableAtTheRim(state: BlockState): Boolean =
        bakeable(state) || state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) ||
            state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.GRAVEL) ||
            state.`is`(Blocks.SANDSTONE) || state.`is`(Blocks.RED_SANDSTONE)

    /** A finger [POCKET_DEEP] blocks into the rock from [lava], three across, each block banded by its heat. */
    private fun dig(level: WorldGenLevel, lava: BlockPos, into: Direction): Boolean {
        val across = into.clockWise
        var baked = false
        for (deep in 1..POCKET_DEEP) {
            for (side in -1..1) {
                for (rise in -1..1) {
                    val at = lava.relative(into, deep).relative(across, side).above(rise)
                    if (!bakeableAtTheRim(level.getBlockState(at))) continue
                    val heat = distanceToLava(level, at) ?: continue
                    level.setBlock(at, becomes(heat), UPDATE_NONE)
                    baked = true
                }
            }
        }
        return baked
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

    /** How many of a crater's edge chunks hold a pocket — a few to a caldera, so the rim stays a gamble. */
    private const val POCKET_CHANCE = 0.25f

    /** Scorched, tempered and raw, the whole lesson in one pocket. */
    private const val POCKET_DEEP = 6

    /** How far under the lava's surface a pocket may start: the wall a player can reach from the rim. */
    private const val NEAR_THE_SURFACE = 3

    /** How many edges one chunk tries before giving up on a pocket. */
    private const val POCKET_TRIES = 6

    /** Every other column, which still finds every stretch of shore and halves the field's work. */
    private const val LAKE_STRIDE = 2

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
