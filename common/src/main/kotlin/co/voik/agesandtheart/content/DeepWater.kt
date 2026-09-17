package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import co.voik.agesandtheart.generation.AgeChunkGenerator
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.AABB
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kotlin.math.abs

/**
 * The abyss, and the rules that decide where it may exist — design §7.1.2's deep-ocean material.
 *
 * **An Age's abyss is a plane, and asking each column for itself was the mistake — CORRECTED 2026-09-10
 * (Jonah, walked).** The first version said "deep water holds where [DEEPEST_VANILLA_SEA] unbroken blocks
 * of water stand over it", read per column, and what that draws is not an abyss. A trench coming to
 * eighty-one blocks in one column and seventy-nine in the next grew a *lone spike* of deep water standing
 * in ordinary sea; and anything a surface feature dipped into — an iceberg above a trench — knocked its
 * column under the line while its neighbours stayed over it. No threshold fixes that, because the
 * raggedness is what a hard cut-off over noisy rock always does.
 *
 * **So placement and persistence ask different questions, deliberately.**
 *
 * - **Where an abyss *is*** is [lineIn]: one Y for the whole Age, [DEEPEST_VANILLA_SEA] under its sea's
 *   surface. A horizontal plane through the terrain, which is what an abyss looks like and what it is.
 * - **Whether a given block may *stay*** is [standsAt]: under the plane, and with no air within
 *   [DEPRESSURISED_UNDER_AIR] blocks overhead. **Everything under the line is abyss** — a flooded cave
 *   included — and what takes the pressure out of water is an air pocket over it, not shallowness.
 *
 * **Three mechanisms hold it, because nothing in Minecraft maintains an invariant for you.**
 *
 * - **[seaAt], for ground as it is generated.** Chunk generation writes block states without firing block
 *   updates, so no tick of ours ever runs on fresh ground and the generator has to place the right block
 *   itself. It is applied to *every* water the column fill produced — the aquifer answers before the sea
 *   does, and a rule reaching only `SeaFill`'s output left pockets of plain water through the deep.
 * - **[DeepWaterBlock]'s own ticks, for ground that changes afterwards.** A scheduled tick catches a
 *   neighbour moving; a *random* tick catches what no neighbour update can reach. It reverts what may not
 *   stand and takes in ordinary water that may.
 * - **[seep], for water that never had an abyss to grow from** — built, poured, or left by a landform.
 *
 * **The continuous-span scan survives in exactly one place: [crushing].** What is *actually* standing over
 * you is what presses on you, so a pond on a roof over an ocean is a pond and a roof, and a diving bell
 * with a metre of water on its lid is still a diving bell. That was always the right question about
 * pressure and never the right one about what a block is made of.
 */
object DeepWater {

    /**
     * Our own fluids, so a scan can tell the deep from the ordinary without asking a loader for objects.
     *
     * A tag rather than a `Services` call because this is read in a loop per block — and because a pack
     * that wants a second abyssal fluid should be able to have one without touching code.
     */
    val DEEP_WATER: TagKey<Fluid> = TagKey.create(Registries.FLUID, "deep_water".location())

    /**
     * Whether deep water counts [fluid] as itself: **any water, not just ours**, which closes the seam an
     * abyss under a sea was drawn with. Each loader's deep-water fluid answers `isSame` with this.
     *
     * `FluidRenderer.getHeight` fills a fluid's block to the brim only when the fluid above it is the same
     * one, and otherwise drops it to `getOwnHeight` — about seven eighths. With the identity answer, deep
     * water under ordinary water was a *different* fluid, so it rendered an eighth of a block short and left
     * a visible horizontal gap between the two bodies (Jonah, walked 2026-09-11).
     *
     * **It is read in exactly two places and both want this answer.** The renderer is one; the other is
     * `FlowingFluid.hasSameAbove`, which decides the fluid's *physical* height — and a block of deep water
     * with a sea on top of it is plainly full.
     *
     * **Nothing about flow moves, because `isSame` is asymmetric here and that is fine.** Every other call
     * in the fluid engine — spreading, levels, `canPassThroughWall` — asks the *neighbour's*
     * implementation with deep water as the argument, and vanilla's water still answers no. What governs
     * the seam is the fluid's `canBeReplacedWith`, which already reads the same tag.
     *
     * One consequence worth knowing: the water above stops drawing its **bottom** face, since that test
     * does route through here. The abyss keeps its own top face, so the surface a diver sees under the sea
     * is still there — it is simply flush now instead of floating an eighth of a block below.
     */
    fun countsAsTheSameFluid(fluid: Fluid): Boolean = fluid.`is`(FluidTags.WATER)

    /**
     * Biomes an abyss may not stand in, however deep the column under them is.
     *
     * **The third question, beside the plane and the water table** — see `AgeChunkGenerator.abyssBelongsIn`
     * for why neither of those two can answer it. A tag rather than a list in code for the reason
     * [DEEP_WATER] is one: a pack adding a cave biome should be able to keep the abyss out of it without
     * touching the mod.
     */
    val NO_ABYSS: TagKey<Biome> = TagKey.create(Registries.BIOME, "no_abyss".location())

    /**
     * How much water has to stand over deep water for it to hold.
     *
     * **Measured 2026-09-10 rather than guessed** — `./gradlew :common:oceandepth`, 300,000 scattered
     * columns over 20 seeds, of which 133,330 were open sea. Vanilla's oceans come out at a median of 14
     * blocks, p99 32, p99.99 64, and a single deepest column of **76**. At this threshold **not one of
     * those columns would hold deep water**; at 72 exactly one would. Flooded caves and aquifers — which
     * this rule cannot tell from a sea — top out at 34, so they are nowhere near mattering.
     *
     * **It is belt-and-braces, and worth knowing that.** No route puts deep water in an Overworld today:
     * nothing places it there, and `LiquidBlock.pickupBlock` hands back a water bucket rather than deep
     * water. What the threshold guards is a *future* mistake — a bucket, or a structure that carries some —
     * and, more usefully day to day, it is what keeps an ordinary sea in an Age from growing an abyss
     * nobody wrote. Ages themselves may place it freely (Jonah, 2026-09-10); this is not a fence around
     * them.
     *
     * **Four blocks over the deepest column found is deliberate rather than tight.** The headroom that
     * matters is against the *raise*, not against the tail: `deep` puts 40 to 82 blocks on a waterline, so
     * an Age that asks for an abyss clears this by a wide margin from almost any landform, while an Age
     * that did not ask stays under it.
     */
    const val DEEPEST_VANILLA_SEA = 80

    /**
     * The Y at or below which this level's sea is an abyss, or null for a level that can hold none.
     *
     * **A plane, not a per-column reading — CORRECTED 2026-09-10 (Jonah, walked).** This was "eighty
     * unbroken blocks of water over you", asked of each column on its own, and the shape that produced was
     * wrong in two ways at once. A trench whose span came to eighty-one in one column and seventy-nine in
     * its neighbour grew a *lone spike* of deep water standing in ordinary sea; and no amount of tuning a
     * threshold fixes that, because the raggedness is what a hard cut-off over noisy rock always does.
     *
     * One level for the whole Age has none of that. The boundary is a horizontal plane through the terrain,
     * which is both what an abyss looks like and what it physically is.
     *
     * **Null is the Overworld's carve-out, and a better one than the old fence.** No `AgeChunkGenerator`
     * means no sea of ours to measure, so nothing there may ever be deep water — and any that somehow
     * arrived converts itself on its next tick. Nothing has to know the Overworld by name.
     */
    fun lineIn(level: LevelReader): Int? {
        val generator = (level as? ServerLevel)?.chunkSource?.generator as? AgeChunkGenerator ?: return null
        return generator.seaFill.surfaceY?.let(::lineBelow)
    }

    /** The abyss line under a sea whose topmost water block is [surfaceY]. */
    fun lineBelow(surfaceY: Int): Int = surfaceY - DEEPEST_VANILLA_SEA

    /**
     * Whether deep water may **stay** at [pos] — under the plane, and with no air just overhead.
     *
     * **Air is what depressurises, not shallowness — SETTLED 2026-09-10 (Jonah, walked).** This asked
     * whether enough water stood over the block, which read badly in play: swimming down through the abyss
     * into a flooded cave and along a side passage, the water turned ordinary the moment the roof came in,
     * though nothing about it had opened to the surface. **Everything under the line is abyss.** What takes
     * the pressure out of water is an air pocket over it — a cave with a roof space, a diving bell, a
     * chamber somebody drained — and it does so for [DEPRESSURISED_UNDER_AIR] blocks beneath.
     *
     * The walk stops at the first block that is not water, and what it finds there is the answer: air means
     * shallow, anything solid means the abyss is unbroken.
     */
    fun standsAt(level: LevelReader, pos: BlockPos): Boolean {
        val line = lineIn(level) ?: return false
        return standsAt(level, pos, line)
    }

    /**
     * [standsAt] against a line already known — which is how generation asks, its level being no `ServerLevel`.
     *
     * **Rock overhead is not the end of the question** (Jonah, walked 2026-09-14). An overhang, a shelf or a
     * passage's roof kept the water under it deep right up to the rock, higher than the skin under air beside
     * it, which outlined every one. So where rock stands within [DEPRESSURISED_UNDER_AIR] overhead, the water
     * is followed to see whether the same pool's skin reaches here — [reachesTheSkin].
     */
    fun standsAt(level: LevelReader, pos: BlockPos, line: Int): Boolean {
        if (pos.y > line) return false
        return when (overhead(level, pos.x, pos.y, pos.z)) {
            Overhead.AIR -> false
            Overhead.WATER -> true
            Overhead.ROCK -> !reachesTheSkin(level, pos)
        }
    }

    /** Whether rock stands within [DEPRESSURISED_UNDER_AIR] over [pos], which is where [standsAt] searches. */
    fun isUnderRock(level: LevelReader, pos: BlockPos): Boolean = overhead(level, pos.x, pos.y, pos.z) == Overhead.ROCK

    /** What the walk up from a block, through at most [DEPRESSURISED_UNDER_AIR] blocks of water, ends on. */
    private enum class Overhead { AIR, WATER, ROCK }

    private fun overhead(level: LevelReader, x: Int, y: Int, z: Int): Overhead {
        val at = BlockPos.MutableBlockPos()
        for (step in 1..DEPRESSURISED_UNDER_AIR) {
            at.set(x, y + step, z)
            val above = level.getBlockState(at)
            if (above.fluidState.`is`(FluidTags.WATER)) continue
            return if (above.isAir) Overhead.AIR else Overhead.ROCK
        }
        return Overhead.WATER
    }

    /**
     * Whether the water at [from], followed through water, comes out under air no more than
     * [DEPRESSURISED_UNDER_AIR] above it — the pool's skin, seen from under a shelf or down a passage.
     *
     * A search rather than straight lines, which stopped at a passage's first bend or a step in its roof. It
     * stays within [SKIN_BELOW] of [from]'s own layer and [SKIN_REACH] across, and the skin it finds must lie
     * no more than [SKIN_BELOW] above the lowest water on the way — so a passage may dip under a sill, but not
     * deeper than the skin itself. Highest water first, so it climbs before it spreads. Water three over
     * [from] ends it as deep, since any surface beyond is further up than that; so does [SEARCHED_AT_MOST],
     * since a sealed flooded cave is the abyss.
     */
    private fun reachesTheSkin(level: LevelReader, from: BlockPos): Boolean {
        val lowest = from.y - SKIN_BELOW
        val highest = from.y + SKIN_BELOW
        // One queue per (lowest water on the way, height), taken best first.
        val waiting = Array(SEARCH_QUEUES) { LongArrayFIFOQueue() }
        val seen = LongOpenHashSet()
        fun queueFor(lowestOnTheWay: Int, y: Int) = (from.y - lowestOnTheWay) * SKIN_LAYERS + (highest - y)
        seen.add(from.asLong())
        waiting[queueFor(from.y, from.y)].enqueue(from.asLong())
        val at = BlockPos.MutableBlockPos()
        repeat(SEARCHED_AT_MOST) {
            val queue = waiting.indexOfFirst { !it.isEmpty }
            if (queue < 0) return false
            val packed = waiting[queue].dequeueLong()
            val lowestOnTheWay = from.y - queue / SKIN_LAYERS
            val x = BlockPos.getX(packed)
            val y = BlockPos.getY(packed)
            val z = BlockPos.getZ(packed)
            val above = level.getBlockState(at.set(x, y + 1, z))
            val isTheSkin = above.isAir && y - SKIN_BELOW <= lowestOnTheWay
            if (isTheSkin) return true
            val reachesTooHigh = y == highest && above.fluidState.`is`(FluidTags.WATER)
            if (reachesTooHigh) return false
            for (direction in Direction.entries) {
                val nextX = x + direction.stepX
                val nextY = y + direction.stepY
                val nextZ = z + direction.stepZ
                val isInReach = nextY in lowest..highest &&
                    abs(nextX - from.x) <= SKIN_REACH && abs(nextZ - from.z) <= SKIN_REACH
                if (!isInReach || !seen.add(BlockPos.asLong(nextX, nextY, nextZ))) continue
                if (!level.getBlockState(at.set(nextX, nextY, nextZ)).fluidState.`is`(FluidTags.WATER)) continue
                waiting[queueFor(minOf(lowestOnTheWay, nextY), nextY)].enqueue(BlockPos.asLong(nextX, nextY, nextZ))
            }
        }
        return false
    }

    /**
     * How far under an air pocket the water is ordinary again.
     *
     * Three, so a bell or a cave roof carries a visible skin of shallow water rather than a hard edge.
     */
    const val DEPRESSURISED_UNDER_AIR = 3

    /** How far across the water is followed looking for the pool's skin. */
    private const val SKIN_REACH = 16

    /** How far under its surface block the skin runs, and so how far the search strays from a block's layer. */
    private const val SKIN_BELOW = DEPRESSURISED_UNDER_AIR - 1

    private const val SKIN_LAYERS = 2 * SKIN_BELOW + 1

    private const val SEARCH_QUEUES = (SKIN_BELOW + 1) * SKIN_LAYERS

    /** How much water the search reads before calling what it has not seen out of the abyss. */
    private const val SEARCHED_AT_MOST = 256

    /** What the abyss will not carry — see `tags/block/kept_out_of_the_abyss.json`. */
    val KEPT_OUT: TagKey<Block> = TagKey.create(Registries.BLOCK, "kept_out_of_the_abyss".location())

    /**
     * Put a decorated chunk's abyss right: take in the water structures brought with them, and clear out
     * whatever living thing grew in it.
     *
     * **This runs after decoration rather than instead of it** (Jonah, walked 2026-09-10). Kelp and seagrass
     * appeared in trenches and around ocean monuments, which looked like a rule of ours leaking — and is
     * not. `KelpFeature` and `SeagrassFeature` test `is(Blocks.WATER)` against the literal vanilla block, so
     * neither can place in deep water at all; what they grew in is the water an **ocean monument places with
     * itself**, and a shipwreck or a ruin does the same. Structures generate in the same stage as
     * vegetation, so there is nowhere to stand between them — the plants are already down by the time
     * anything of ours is asked, and this removes them rather than preventing them.
     *
     * **The structure survives and only its water and its weeds change.** Prismarine, sea lanterns and
     * everything else inorganic are deliberately absent from [KEPT_OUT]: a monument standing in the abyss is
     * exactly what should be down there, and it should be full of abyss.
     *
     * **A section's palette dismisses nearly all of it before a block is read** — `Wounds`' argument and
     * `ChargedMetal`'s. A section of unbroken deep water holds neither ordinary water nor anything living,
     * so it is skipped whole.
     */
    fun settleTheAbyss(level: LevelReader, chunk: ChunkAccess, line: Int, abyssReaches: (Int, Int) -> Boolean) {
        if (line < chunk.minY) return
        takeInTheAbyss(chunk, line, abyssReaches)
        settleTheEdges(level, chunk, line)
    }

    /**
     * The abyss's own edges, once it is in: deep water that could not stand where it lies — under an overhang
     * whose layer opens to air beside it — goes back to ordinary, as the settling tick would take it, and deep
     * water over a block that raises a bubble column is marked for its first tick, so the column rising there
     * when the chunk loads is ours rather than vanilla's.
     *
     * Read through [level] rather than the chunk, since an overhang's layer can open to air in the chunk beside.
     */
    private fun settleTheEdges(level: LevelReader, chunk: ChunkAccess, line: Int) {
        val top = minOf(line, chunk.maxY)
        val deep = deepWater().block
        val at = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (index in chunk.getSectionIndex(chunk.minY)..chunk.getSectionIndex(top)) {
            val section = chunk.getSection(index)
            if (section.hasOnlyAir() || !section.maybeHas { it.`is`(deep) }) continue
            val floor = chunk.getSectionYFromSectionIndex(index) shl SECTION_BITS
            for (y in maxOf(floor, chunk.minY + 1)..minOf(floor + SECTION_TOP, top)) {
                for (x in 0..SECTION_TOP) {
                    for (z in 0..SECTION_TOP) {
                        at.set(originX + x, y, originZ + z)
                        val here = chunk.getBlockState(at)
                        if (!here.`is`(deep) || !here.fluidState.isSource) continue
                        if (!standsAt(level, at, line)) {
                            chunk.setBlockState(at, Blocks.WATER.defaultBlockState())
                            continue
                        }
                        val under = chunk.getBlockState(below.setWithOffset(at, Direction.DOWN))
                        val raisesAColumn = under.`is`(BlockTags.ENABLES_BUBBLE_COLUMN_DRAG_DOWN) ||
                            under.`is`(BlockTags.ENABLES_BUBBLE_COLUMN_PUSH_UP)
                        if (raisesAColumn) chunk.markPosForPostprocessing(at)
                    }
                }
            }
        }
    }

    /** Ordinary water turned into abyss under [line], and the space inside a block that holds some. */
    private fun takeInTheAbyss(chunk: ChunkAccess, line: Int, abyssReaches: (Int, Int) -> Boolean) {
        val top = minOf(line, chunk.maxY)
        val deep = deepWater()
        val at = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (index in chunk.getSectionIndex(chunk.minY)..chunk.getSectionIndex(top)) {
            val section = chunk.getSection(index)
            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { it.`is`(Blocks.WATER) || it.`is`(KEPT_OUT) || DeepWaterLogging.couldHold(it) }) continue
            val floor = chunk.getSectionYFromSectionIndex(index) shl SECTION_BITS
            for (y in floor..minOf(floor + SECTION_TOP, top)) {
                for (x in 0..SECTION_TOP) {
                    for (z in 0..SECTION_TOP) {
                        at.set(originX + x, y, originZ + z)
                        if (!abyssReaches(at.x, at.z)) continue
                        val here = chunk.getBlockState(at)
                        if (isStillWater(here) || here.`is`(KEPT_OUT)) chunk.setBlockState(at, deep)
                        // A wreck's stairs and slabs, which vanilla left dry: `SimpleWaterloggedBlock`
                        // waterlogs on the fluid's identity rather than on `#minecraft:water`, so a
                        // structure placed in an abyss comes out full of air pockets. See
                        // [DeepWaterLogging] — this is the same ruling as the line above, applied to the
                        // space inside a block rather than to the block itself.
                        // **Only what is actually standing in water.** Everything under the line was taken
                        // at first, which logged glow lichen on a dry cave ceiling and left it pouring a
                        // column of deep water into the dark (Jonah, walked 2026-09-10). What the sweep is
                        // for is the block vanilla would have waterlogged and could not, and vanilla's own
                        // test is whether there is water against it.
                        else if (DeepWaterLogging.couldHold(here) && standsInWater(chunk, at)) {
                            chunk.setBlockState(at, DeepWaterLogging.holding(here))
                        }
                    }
                }
            }
        }
    }

    /** Whether any of the six around [at] is water of either kind, which is what "submerged" comes to. */
    private fun standsInWater(chunk: ChunkAccess, at: BlockPos.MutableBlockPos): Boolean {
        val here = BlockPos(at.x, at.y, at.z)
        for (side in Direction.entries) {
            val beside = here.relative(side)
            if (beside.y < chunk.minY || beside.y > chunk.maxY) continue
            if (chunk.getBlockState(beside).fluidState.`is`(FluidTags.WATER)) return true
        }
        return false
    }

    private const val SECTION_BITS = 4

    private const val SECTION_TOP = 15

    /**
     * Take the pressure out of the water under air the generator has just laid at ([x], [y], [z]).
     *
     * **Rewriting rather than predicting, because the column fill runs bottom-up.** By the time a block of
     * air is placed, the water it depressurises is already in the chunk — so the cheapest correct thing is
     * to go back over the three under it. Walks down through water only; anything else ends it.
     */
    fun airOpenedOver(chunk: ChunkAccess, at: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int) {
        for (step in 1..DEPRESSURISED_UNDER_AIR) {
            at.set(x, y - step, z)
            val under = chunk.getBlockState(at)
            if (under.fluidState.isEmpty) return
            if (under.`is`(deepWater().block)) chunk.setBlockState(at, ordinaryWaterFor(under))
        }
    }

    // ── Generation ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ordinary water turned into abyss where it lies under [line] — the rule read forwards, for generation.
     *
     * It has to exist separately from [standsAt] because chunk generation writes block states straight into
     * a chunk without firing block updates, so the settling tick never runs on generated ground and the
     * generator has to put the right block down the first time.
     *
     * **Applied to everything the column fill produces, not only to the sea.** The aquifer answers before
     * the sea does — a cave under the abyss is filled from the water table, which knows nothing about any
     * of this — so a rule reaching only `SeaFill`'s own output left great pockets of ordinary water
     * scattered through the deep. Anything that comes out water and lies under the line is abyss.
     */
    fun seaAt(y: Int, line: Int, sea: BlockState): BlockState =
        if (y <= line && sea.`is`(Blocks.WATER)) deepWater() else sea

    /**
     * The registered block, looked up once.
     *
     * By id rather than through a service, for the reason `AgeFluids.DEEP_WATER` records: nothing in common
     * needs the fluid *object*, and a `Services` entry carrying one block would be a seam for its own sake.
     */
    private var found: BlockState? = null
    private var warnedOfItsAbsence = false

    /**
     * The abyss as a block state, or null where it is not registered yet.
     *
     * **A found state is remembered and a miss never is**, because whether the abyss exists yet is a
     * question of loader startup order: a miss remembered early would pin ordinary water for the process.
     */
    fun deepWaterOrNull(): BlockState? = found ?: BuiltInRegistries.BLOCK
        .getOptional(AgeFluids.DEEP_WATER.block)
        .map { it.defaultBlockState() }
        .orElse(null)
        ?.also { found = it }

    /**
     * The abyss as a block state, or ordinary water where it is not registered.
     *
     * Public because anything *writing* an abyss needs it — the generator's own fill, and `DeepSeaVent`,
     * which cuts a room out of the sea floor and has to put the sea back into it rather than air.
     */
    fun deepWater(): BlockState = deepWaterOrNull() ?: ordinaryWaterInstead()

    private fun ordinaryWaterInstead(): BlockState {
        if (!warnedOfItsAbsence) {
            Constants.LOG.warn("Deep water is not registered; an abyss will come out as ordinary water")
            warnedOfItsAbsence = true
        }
        return Blocks.WATER.defaultBlockState()
    }

    /**
     * Still ordinary water, the only water the abyss takes in. Water falling or spreading through the deep
     * stays ordinary: taking it in carries the deep up a falling tongue block by block until it reaches where
     * the deep may not stand, which gives the water back, and the block below takes it in again — without end.
     */
    fun isStillWater(state: BlockState): Boolean = state.`is`(Blocks.WATER) && state.fluidState.isSource

    /**
     * The ordinary water a deep block gives back, **keeping its level rather than becoming a source**: both
     * blocks carry the same `LEVEL`, and a flowing tongue that promoted itself on the way out would make water
     * from nothing.
     */
    fun ordinaryWaterFor(deep: BlockState): BlockState =
        Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, deep.getValue(LiquidBlock.LEVEL))

    /**
     * Take [at] into the abyss, whether it is still ordinary water, a block holding some, or the ordinary
     * part of a whirlpool. Both [DeepWaterBlock] and [DeepBubbleColumnBlock] spread the abyss through this.
     *
     * **A block that holds the abyss does not tick**, being a stair rather than a fluid, so the spread
     * reaches only what the abyss itself touches. That is enough for what changes at runtime — a stair
     * placed in the deep, a wreck opened into — and generation's own sweep has already done the interiors
     * ([settleTheAbyss]).
     *
     * **A whirlpool is taken in like the water it holds**, and ticks to carry the spread on up and down
     * itself: one raised through ordinary water before the abyss reached it would otherwise stand
     * ordinary through the deep for good.
     */
    fun takeIn(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        if (DeepWaterLogging.couldHold(state)) {
            if (standsAt(level, at)) level.setBlockAndUpdate(at, DeepWaterLogging.holding(state))
            return
        }
        val deepWhirlpool = DeepBubbleColumnBlock.deepened(state)
        val taken = deepWhirlpool ?: deepWater().takeIf { isStillWater(state) } ?: return
        if (!standsAt(level, at)) return
        level.setBlockAndUpdate(at, taken)
        level.scheduleTick(at, taken.block, TAKEN_IN_SETTLES_IN)
    }

    /**
     * Deep water forming in water that never had any — the seedless half.
     *
     * **The block's own rule can only grow an abyss from an abyss**, which is not enough: a sea deep enough
     * to be one may simply have been built, or poured, or left by a landform whose waterline nothing raised.
     * Nothing else lays one — the deluge pours ordinary water too, and is not in every Age (Jonah,
     * 2026-09-10) — so a column of ordinary water has to be able to turn on its own.
     *
     * **Ages only, and that is the dimension carve-out.** This is called from `Happenings`, which walks the
     * Ages a player is standing in and nothing else, so no Overworld and no End — a positive check rather
     * than a list of exclusions somebody has to remember to extend.
     *
     * **One block seeded, and the block rule does the rest.** [DeepWaterBlock] takes in the ordinary water
     * it touches a step at a time, so seeding a block on the line and letting it spread beats converting a
     * hundred of them inside a sampling visit.
     */
    fun seep(level: ServerLevel) {
        val line = lineIn(level) ?: return
        Sampling.sweep(level, SEEPS_PER_CHUNK) { chunk, at ->
            // Nothing to do in a column whose water never gets down to the line. `MOTION_BLOCKING` counts
            // fluid, so its height is the top of whatever water stands here — one lookup, and it dismisses
            // every column of an ordinary Age.
            val inChunkX = at.x and CHUNK_MASK
            val inChunkZ = at.z and CHUNK_MASK
            if (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, inChunkX, inChunkZ) <= line) return@sweep

            val spot = at.atY(line)
            val water = level.getBlockState(spot)
            if (!isStillWater(water)) return@sweep
            if (!standsAt(level, spot)) return@sweep
            level.setBlockAndUpdate(spot, deepWater())
        }
    }

    /**
     * How many column samples each nearby chunk is offered per tick.
     *
     * One, which is `ServerLevel.tickPrecipitation`'s own rate for snow and ice — the reference `Sampling`
     * names for what cheap costs. An abyss that takes a few seconds to notice it should exist is behaving
     * like every other environmental process in the game.
     */
    private const val SEEPS_PER_CHUNK = 1

    /** One tick, which is "instantly" as far as anybody watching is concerned. */
    private const val TAKEN_IN_SETTLES_IN = 1

    private const val CHUNK_MASK = 15

    // ── Pressure ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Mark one body standing in the deep as being under pressure, called by the block that *is* the deep.
     *
     * **Driven by the water rather than swept for from the players — SETTLED 2026-09-10 (Jonah).** This was
     * a per-second search outward from each player, which needed a radius, a deduplication and a box shape,
     * and got the box wrong: a drowned on the floor of a hundred-block abyss sat outside a ball drawn round
     * a player near its top, and took nothing. `entityInside` has no such geometry to get wrong — whatever
     * is in the water is what the water acts on, whether anyone is watching or not.
     *
     * **What it hands out is an effect, and [PressureEffect] is what then hurts.** The water says who is in
     * it; the effect says what that costs, holds the icon and the timer, and is the surface a second
     * consequence can hang off later. Refreshed on every visit rather than applied once, so the timer reads
     * as a state rather than a countdown.
     *
     * **`#immune_to_pressure` is read off the body being pressed and never off what it is riding.** A
     * mount in the tag is exempt on its own account — a nautilus drowning under its own rider reads as a
     * bug — but it shelters nobody, or the saddle would be a better suit than the suit. The mount buys
     * travel through the abyss; the rider is pressed exactly as if swimming (design §7.1.2).
     */
    fun press(level: Level, body: Entity) {
        if (level !is ServerLevel) return
        if (body !is LivingEntity || body.isSpectator) return
        if (body.type.builtInRegistryHolder().`is`(IMMUNE_TO_PRESSURE)) return
        if (body.hasEffect(AgeContent.PRESSURE_EFFECT)) return
        body.addEffect(MobEffectInstance(AgeContent.PRESSURE_EFFECT, HELD_FOR, NO_STRONGER, AMBIENT, SHOWN))
    }

    /**
     * Whether the deep still has hold of [body] — what decides when [PressureEffect] lets go.
     *
     * **The fluid at the body rather than a reach from the water**, because the effect has to answer this
     * for itself once a tick with no block to ask. Feet and eyes both, so neither wading out of a pool nor
     * swimming with your head clear counts as having left it.
     */
    fun stillUnderPressure(level: ServerLevel, body: LivingEntity): Boolean =
        level.getFluidState(body.blockPosition()).`is`(DEEP_WATER) ||
            level.getFluidState(BlockPos.containing(body.eyePosition)).`is`(DEEP_WATER)

    /**
     * One second of the deep, and the terms are the ones that have always been here.
     *
     * **Two ways out, and they do different jobs.** A turtle helmet doubles the clock; a deretheni suit
     * stops it and is charged for the saving in the same coin the lava and the cold charge it in.
     *
     * **Uniform, and that is a fix rather than a simplification.** The damage used to square against a depth
     * of twenty-four blocks of abyss, and a real abyss measures twenty-six — so with a turtle helmet's
     * allowance the sharp term topped out near a sixth of a heart a second and the place was survivable by
     * standing still in it. Exactly the mistake the fog made, in the same place, for the same reason. The
     * budget the design asks for (§7.1.2) is **time in the abyss** rather than depth into it, which is
     * also what the flat fog already says: crossing the line is the event.
     *
     * The beat is read off the clock rather than off the effect's own duration, which [press] keeps
     * refreshing and which therefore counts nothing.
     */
    fun crush(level: ServerLevel, body: LivingEntity) {
        if (level.gameTime % PRESSED_EVERY != 0L) return
        // Worn by anything, but only a player has one.
        if (body is ServerPlayer && ProtectiveSuit.wearingTheWholeSuit(body)) {
            ProtectiveSuit.wearOut(body)
            return
        }
        val crushed = DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(PRESSURE))
        body.hurtServer(level, crushed, crushing(body))
    }

    /**
     * How hard, which is one number and a halving.
     *
     * **The turtle helmet buys a share rather than a depth**, now that there is no depth to buy: it was an
     * allowance in blocks, which meant nothing once the damage stopped counting them. Halving keeps it the
     * pre-deretheni counterplay design §7.1.2 wants — it doubles how long you may stay, and ends nothing.
     */
    private fun crushing(body: LivingEntity): Float =
        if (body.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.TURTLE_HELMET)) CRUSHES_BY / 2.0f else CRUSHES_BY

    /**
     * Damage a second, unprotected.
     *
     * **The dial, halved after the first survival walk** (Jonah, 2026-09-10). Four killed in five seconds,
     * which was quick enough that the abyss could not be *looked* at — you crossed the line and were dead
     * before the place registered. Two gives ten seconds, and twenty with a turtle helmet: still lethal
     * quickly, but long enough for a peek around, which is what the descent-under-a-budget method wants to
     * be about (design §7.1.2).
     */
    private const val CRUSHES_BY = 2.0f

    /** Once a second, which is also vanilla's invulnerability window — see [crush]. */
    private const val PRESSED_EVERY = 20L

    /**
     * How long the mark lasts without being renewed.
     *
     * Short, because [PressureEffect] takes itself off the moment the water is gone and this is only the
     * net under that — long enough that a tick where the block does not report the body does not flicker
     * the icon, short enough that it could never read as grace.
     */
    private const val HELD_FOR = 40

    private const val NO_STRONGER = 0

    /** Not ambient: this is being done to you, and the HUD should say so at full strength. */
    private const val AMBIENT = false
    private const val SHOWN = true

    val PRESSURE: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, "pressure".location())

    /** What the abyss does not crush — see `tags/entity_type/immune_to_pressure.json`. */
    val IMMUNE_TO_PRESSURE: TagKey<EntityType<*>> =
        TagKey.create(Registries.ENTITY_TYPE, "immune_to_pressure".location())
}
