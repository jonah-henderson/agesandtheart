package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An old impact crater, left by a storm that fell long before anybody arrived (design §5.2).
 *
 * **The bowl is cut bare and nothing is re-laid** (Jonah, 2026-09-07). Surface rules run at the `SURFACE`
 * stage, well before any feature, so a carve exposes whatever the Age's rock is underneath — and putting a
 * surface back would mean guessing at rules that differ per Age. Whatever is there is there, which also
 * gives the shards the stone they want to stand on rather than turf.
 *
 * **Built on the ruined portal's lesson rather than its machinery** (Jonah, 2026-09-07). A ruined portal
 * avoids looking stamped through three things: a handful of genuinely different variants, a few properties
 * drawn per instance, and a degradation pass that chews what was placed. Its *mechanism* — NBT templates
 * run through a `worldgen/processor_list` — cannot be borrowed, because a processor list only runs on
 * template placement and a template cannot follow the hillside a crater lands on. All three ideas are here
 * directly instead: [Profile] is the variants, [Struck] is the drawn properties, and [erosionAt] and
 * [scatterEjecta] are the chewing.
 *
 * **Its size is bounded by the chunk pyramid, not by taste.** `ChunkStatus.FEATURES` runs with
 * `blockStateWriteRadius(1)`, so a feature may only write into the eight chunks around its own — anything
 * further is dropped on the floor with a log line. From an arbitrary spot in a chunk that guarantees
 * [FREELY_PLACED] blocks in every direction; from the chunk's *middle* it guarantees [PINNED_REACHES].
 * So a crater big enough to need it is pinned to the middle, and only those are.
 */
object ImpactCrater : Feature<CraterScale>(CraterScale.CODEC) {

    override fun place(context: FeaturePlaceContext<CraterScale>): Boolean {
        val level = context.level()
        val random = context.random()
        val scale = context.config()
        val drawn = scale.leastReach + random.nextInt(scale.mostReach - scale.leastReach + ONE)
        // Clamped rather than trusted: a scale asking for more than the chunk pyramid allows would have
        // its outer blocks silently dropped, which reads as a crater with a bite taken out of it.
        val reach = drawn.coerceAtMost(PINNED_REACHES)
        val middle = middleFor(context.origin(), reach)
        if (submerged(level, middle.x, middle.z)) return false
        val struck = Struck.drawnBy(random, reach, roomAround(middle, context.origin()))
        val out = struck.carriesTo()
        if (landsOnASurfaceStructure(level, context.origin(), middle, out)) return false
        val sea = seaOverTheCut(level, middle, struck)
        for (awayX in -out..out) {
            for (awayZ in -out..out) {
                reshape(level, middle.x + awayX, middle.z + awayZ, struck.offsetAt(awayX, awayZ), sea)
            }
        }
        dropWhatIsLeftHanging(level, middle, struck)
        scatterEjecta(level, middle, struck)
        seedWithAstrite(level, random, middle, reach, scale)
        return true
    }

    /**
     * Clear anything the carve left standing on nothing — **asked of the footprint, not of the column being
     * cut**.
     *
     * Clearing what stands on a column as it is carved is right and is not enough: at `teeming` density two
     * craters land close together and the later one takes the ground from under ground the earlier one had
     * already had decorated, which is a column this crater never touches and so never looks at. Grass tufts
     * and snow layers were left hanging in the air over the second hole (Jonah, walked 2026-09-10).
     *
     * **Vanilla's own survival rule is the test**, rather than a list of what counts as decoration: every
     * plant, layer and sapling already knows what it needs under it, so `canSurvive` catches all of them
     * and stays right when a pack adds another. A margin of one is swept beyond the reach because the
     * undermined column is by definition the one just outside the cut — except where the reach already
     * stops at the writable room, since a block past that is a far-chunk write the chunk pyramid drops.
     */
    private fun dropWhatIsLeftHanging(level: WorldGenLevel, middle: BlockPos, struck: Struck) {
        val swept = (struck.carriesTo() + ONE).coerceAtMost(struck.room)
        val cursor = BlockPos.MutableBlockPos()
        for (awayX in -swept..swept) {
            for (awayZ in -swept..swept) {
                val x = middle.x + awayX
                val z = middle.z + awayZ
                val ground = groundAt(level, x, z)
                // Only the band the carve could have reached, and upward: what is below the ground is rock.
                for (y in ground..topOf(level, x, z)) {
                    cursor.set(x, y, z)
                    val standing = level.getBlockState(cursor)
                    if (standing.isAir || standing.canSurvive(level, cursor)) continue
                    level.setBlock(cursor, AIR, Block.UPDATE_CLIENTS)
                }
            }
        }
    }

    /**
     * One column moved by [offset] — cut down into the rock, or built up out of what the column already
     * had.
     *
     * A raised column is filled with the block *under* the surface and capped with the surface block
     * itself, which is the ejecta being what was thrown out of the hole: the rim of a crater in sand is
     * sand, and in stone it is stone, with nothing here needing to know which.
     */
    private fun reshape(level: WorldGenLevel, x: Int, z: Int, offset: Int, seaOverTheCut: Sea) {
        if (offset == UNMOVED) return
        val surface = groundAt(level, x, z)
        // **Whatever is *standing* on the column as well as the column itself.** The ground heightmap
        // counts what blocks motion, and grass, bushes and flowers do not — so carving to it took the dirt
        // out from under a meadow and left it hanging (Jonah, 2026-09-09, walked: "trees, grass, bushes
        // floating in the air"). Leaves are missed the same way. An impact leaves none of it.
        val standing = topOf(level, x, z)
        if (offset < UNMOVED) {
            // The cut is one hole, so it fills to one waterline — a dry column cut below the sea floods as
            // the drowned one beside it does, and no air pocket is left waiting for something to notice it.
            for (y in surface + offset + ONE..maxOf(surface, standing)) {
                level.setBlock(BlockPos(x, y, z), seaOverTheCut.fillAt(y), Block.UPDATE_CLIENTS)
            }
            return
        }
        // A raised column puts back only what stood over it, so a dry rim never carries water of its own.
        val seaOverTheColumn = seaOver(level, x, z, surface)
        val top = level.getBlockState(BlockPos(x, surface, z))
        val under = level.getBlockState(BlockPos(x, surface - ONE, z))
        for (y in surface + ONE..surface + offset) level.setBlock(BlockPos(x, y, z), under, Block.UPDATE_CLIENTS)
        level.setBlock(BlockPos(x, surface + offset, z), top, Block.UPDATE_CLIENTS)
        // And nothing left poking out of what was thrown over it: a trunk taller than the rim is buried
        // to the rim's height and would otherwise stand out of the top of it.
        for (y in surface + offset + ONE..standing) {
            level.setBlock(BlockPos(x, y, z), seaOverTheColumn.fillAt(y), Block.UPDATE_CLIENTS)
        }
    }

    /** The sea standing over a column, and how high it reaches — [Sea.NONE] where the column is dry. */
    private class Sea(val fluid: BlockState, val reaches: Int) {
        /** What a cut cell at [y] is filled with: the sea at or under its waterline, and air above it. */
        fun fillAt(y: Int): BlockState = if (y <= reaches) fluid else AIR

        companion object {
            /** Nothing stands here, so every height is above the water and fills with air. */
            val NONE = Sea(Blocks.AIR.defaultBlockState(), Int.MIN_VALUE)
        }
    }

    /**
     * Whether the crater, or the ground just past its reach, would land on a structure built at the surface —
     * a village, an outpost, a temple, a star fissure.
     *
     * Structures are placed in a later step than craters, but a crater decorating a neighbouring chunk can be
     * placed after them, and it cut through roads and piled its rim against doors. A crater that would touch one
     * is not placed at all.
     *
     * Read from the structure references of each chunk the crater reaches, which record every structure
     * overlapping a chunk from before any of its blocks are down. **Both bounds are the region's, not a choice**:
     * a feature may read references only within [REFERENCES_READABLE_WITHIN] chunks of its own, and structure
     * starts only within [STARTS_READABLE_WITHIN]. Asking past either crashes generation. Neither loses anything
     * under the crater itself: its reach never leaves the ring the references cover, so a structure the first
     * bound misses can only come within the clearance, and no surface structure spans eight chunks.
     */
    private fun landsOnASurfaceStructure(
        level: WorldGenLevel,
        origin: BlockPos,
        middle: BlockPos,
        out: Int,
    ): Boolean {
        val clearance = out + CLEAR_OF_STRUCTURES
        val leastX = middle.x - clearance
        val mostX = middle.x + clearance
        val leastZ = middle.z - clearance
        val mostZ = middle.z + clearance
        val originChunk = ChunkPos.containing(origin)

        fun isReadable(chunkX: Int, chunkZ: Int, within: Int) =
            originChunk.getChessboardDistance(chunkX, chunkZ) <= within

        fun isBuiltAtTheSurface(structure: Structure) =
            structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES

        fun reachesTheCrater(start: StructureStart) =
            start.pieces.any { piece -> piece.boundingBox.intersects(leastX, leastZ, mostX, mostZ) }

        fun startOf(structure: Structure, reference: Long): StructureStart? {
            val startChunk = ChunkPos.unpack(reference)
            if (!isReadable(startChunk.x, startChunk.z, STARTS_READABLE_WITHIN)) return null
            val chunk = level.getChunk(startChunk.x, startChunk.z, ChunkStatus.STRUCTURE_STARTS)
            return chunk.getStartForStructure(structure)?.takeIf { it.isValid }
        }

        fun anySurfaceStructureFromChunk(chunkX: Int, chunkZ: Int): Boolean {
            val references = level.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES).allReferences
            return references.any { (structure, startChunks) ->
                fun reachesTheCraterFrom(startChunk: Long) =
                    startOf(structure, startChunk)?.let(::reachesTheCrater) == true
                isBuiltAtTheSurface(structure) && startChunks.any(::reachesTheCraterFrom)
            }
        }

        val leastChunkX = leastX shr CHUNK_BITS
        val mostChunkX = mostX shr CHUNK_BITS
        val leastChunkZ = leastZ shr CHUNK_BITS
        val mostChunkZ = mostZ shr CHUNK_BITS
        val chunksReached = (leastChunkX..mostChunkX).flatMap { chunkX ->
            (leastChunkZ..mostChunkZ).map { chunkZ -> chunkX to chunkZ }
        }
        return chunksReached
            .filter { (chunkX, chunkZ) -> isReadable(chunkX, chunkZ, REFERENCES_READABLE_WITHIN) }
            .any { (chunkX, chunkZ) -> anySurfaceStructureFromChunk(chunkX, chunkZ) }
    }

    /**
     * The highest sea standing over any column the crater cuts down, which the whole cut fills to.
     *
     * Read before anything is cut, since cutting a column changes what stands over it. Only the columns the
     * cut lowers are asked: the rim opens nothing, and the ejecta's reach may cross water the hole never meets.
     * The fluid is read rather than named, so a crater in an Age whose sea is lava fills with lava.
     */
    private fun seaOverTheCut(level: WorldGenLevel, middle: BlockPos, struck: Struck): Sea {
        val out = struck.carriesTo()
        val everyOffset = (-out..out).flatMap { awayX -> (-out..out).map { awayZ -> awayX to awayZ } }
        fun seaOverColumn(x: Int, z: Int) = seaOver(level, x, z, groundAt(level, x, z))
        return everyOffset
            .filter { (awayX, awayZ) -> struck.offsetAt(awayX, awayZ) < UNMOVED }
            .map { (awayX, awayZ) -> seaOverColumn(middle.x + awayX, middle.z + awayZ) }
            .maxByOrNull { it.reaches } ?: Sea.NONE
    }

    /**
     * What is standing over this column, read rather than assumed.
     *
     * **The fluid is taken from the world instead of named**, so a crater cut into an Age whose sea is lava
     * floods with lava. Walked up from the ground rather than asked of the level's own sea level, because a
     * landform may carry water of its own above the waterline (`SeaFill.wet`) and the cut has to put back
     * exactly what it opened.
     */
    private fun seaOver(level: WorldGenLevel, x: Int, z: Int, surface: Int): Sea {
        val first = BlockPos(x, surface + ONE, z)
        val standing = level.getFluidState(first)
        if (standing.isEmpty) return Sea.NONE
        var top = surface + ONE
        while (top - surface < DEEPEST_SEA_CUT && !level.getFluidState(BlockPos(x, top + ONE, z)).isEmpty) top++
        return Sea(standing.createLegacyBlock(), top)
    }

    /**
     * The debris thrown clear of the rim: single blocks and pairs, thinning outward into ordinary country.
     *
     * **This is the strongest cue that a crater is not a formula**, and it is the one a ruined portal makes
     * too — vanilla scatters netherrack round a portal's base for exactly this reason. A rim that simply
     * stops has an edge; a rim that frays into scattered rock does not.
     */
    private fun scatterEjecta(level: WorldGenLevel, middle: BlockPos, struck: Struck) {
        val out = struck.carriesTo()
        for (awayX in -out..out) {
            for (awayZ in -out..out) {
                val away = sqrt((awayX * awayX + awayZ * awayZ).toDouble())
                val beyond = struck.beyondTheRim(awayX, awayZ, away) ?: continue
                if (hashedAt(awayX, awayZ, struck.grain) > struck.ejecta * beyond) continue
                val x = middle.x + awayX
                val z = middle.z + awayZ
                if (submerged(level, x, z)) continue
                // **Laid as the column's own surface block, with what was under it buried** — see
                // [LaidGround]. This took the block *under* the surface and dropped it on top, which is
                // where the dirt standing on grass came from, and left the grass it covered still grass.
                LaidGround.layOn(level, BlockPos(x, groundAt(level, x, z), z), Block.UPDATE_CLIENTS)
            }
        }
    }

    /**
     * The shards a crater kept, clustered rather than scattered.
     *
     * **Well inside the lip**, because that is where the carve has certainly cut past the soil into rock —
     * out at the shallow edge it may have taken only the turf, and a shard standing in dirt is the one
     * thing this was asked not to look like.
     */
    private fun seedWithAstrite(
        level: WorldGenLevel,
        random: RandomSource,
        middle: BlockPos,
        reach: Int,
        scale: CraterScale,
    ) {
        // Rarity first, amount second — see [CraterScale]. One roll decides whether this crater kept
        // anything at all, and only then is there a count, which is never nought.
        if (random.nextFloat() >= scale.holdsShards) return
        val kept = ONE + random.nextInt(scale.mostShards)
        val inner = (reach * SHARDS_WITHIN).roundToInt().coerceAtLeast(ONE)
        val about = BlockPos(middle.x + spread(random, inner), middle.y, middle.z + spread(random, inner))
        repeat(kept) {
            val x = about.x + spread(random, TOGETHER)
            val z = about.z + spread(random, TOGETHER)
            if (submerged(level, x, z)) return@repeat
            val standing = BlockPos(x, groundAt(level, x, z) + ONE, z)
            val shard = AgeContent.ASTRITE_SHARD_BLOCK.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP)
            if (!shard.canSurvive(level, standing)) return@repeat
            level.setBlock(standing, shard, Block.UPDATE_CLIENTS)
        }
    }

    /**
     * Where the crater sits, which is where it was asked for unless it is too big to fit there.
     *
     * Pinning costs a little scatter and buys seven blocks of reach; only the sizes that cannot be placed
     * freely pay it, so the common small ones still land wherever the placement put them.
     */
    private fun middleFor(origin: BlockPos, reach: Int): BlockPos {
        if (reach <= FREELY_PLACED) return origin
        val chunkX = (origin.x shr CHUNK_BITS) shl CHUNK_BITS
        val chunkZ = (origin.z shr CHUNK_BITS) shl CHUNK_BITS
        return BlockPos(chunkX + HALF_A_CHUNK, origin.y, chunkZ + HALF_A_CHUNK)
    }

    /**
     * How many blocks of writable room this crater's middle has on its tightest side.
     *
     * The box is the origin's own chunk and the ring around it, so it runs from sixteen blocks west of
     * that chunk to thirty-one east. Measured rather than assumed because it is what the debris is clipped
     * against: the crater proper is sized to fit by construction, and the scatter is simply cut off at the
     * edge — which is invisible, since it is thinning out there anyway.
     */
    private fun roomAround(middle: BlockPos, origin: BlockPos): Int {
        val chunkX = (origin.x shr CHUNK_BITS) shl CHUNK_BITS
        val chunkZ = (origin.z shr CHUNK_BITS) shl CHUNK_BITS
        return minOf(
            middle.x - (chunkX - CHUNK),
            (chunkX + CHUNK + CHUNK - ONE) - middle.x,
            middle.z - (chunkZ - CHUNK),
            (chunkZ + CHUNK + CHUNK - ONE) - middle.z,
        )
    }

    /**
     * The top solid block of a column as it stands now, which is one below where the heightmap stops.
     *
     * **A final heightmap, never a worldgen one.** While features are placed, a chunk updates only the four
     * final maps, so `OCEAN_FLOOR_WG` still answers with the ground from before any feature ran. Read through
     * it, a crater could not see another crater: it raised its rim from ground already dug out, and cut
     * under the rim another had thrown up, leaving both hanging.
     */
    private fun groundAt(level: WorldGenLevel, x: Int, z: Int): Int =
        level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - ONE

    /**
     * And the top of *anything at all* in the column, plants, snow and leaves included — final for the same
     * reason as [groundAt], since grass and snow placed after the terrain are exactly what a worldgen map
     * cannot see.
     *
     * The difference between this and [groundAt] is exactly what a meadow standing on the ground is, which
     * is what the carve has to take with it.
     */
    private fun topOf(level: WorldGenLevel, x: Int, z: Int): Int =
        level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - ONE

    /**
     * Whether this column stands under water.
     *
     * **The carve no longer asks**, [reshape] putting the sea back into whatever it opened, so a crater by
     * the shore floods rather than stopping at the waterline. What still asks are the things that want dry
     * land under them: where the ejecta may land and where a shard may stand.
     *
     * **A fluid read rather than two heightmaps**, which is what this used and what made it wrong. The
     * old test was that the surface heightmap stood above the ground one, on the reasoning that only water
     * could be between them — and a blade of grass is between them too, so every decorated column on land
     * read as submerged and would have been skipped outright the moment anything else asked about what was
     * standing on it. Asking whether the block above the ground holds fluid is the question that was meant.
     */
    private fun submerged(level: WorldGenLevel, x: Int, z: Int): Boolean =
        !level.getFluidState(BlockPos(x, groundAt(level, x, z) + ONE, z)).isEmpty

    private fun spread(random: RandomSource, within: Int): Int = random.nextInt(within * TWICE + ONE) - within

    /**
     * A number in nought to one for this offset and this crater, the same every time it is asked.
     *
     * A hash rather than the feature's own [RandomSource]: the erosion has to be a *function of the place*
     * so that neighbouring columns can be asked in any order and a re-generated chunk agrees with itself.
     * Drawing it would make the ragged edge depend on which column happened to be visited first.
     */
    private fun hashedAt(awayX: Int, awayZ: Int, grain: Long): Double =
        unitDouble(mix64(mix64(mix64(grain) + awayX) + awayZ))

    /**
     * How far this column's own ground wanders off the arithmetic, in blocks.
     *
     * **Strongest at the lip and nothing at the floor**, which is what erosion actually does: debris runs
     * downhill and settles, so a bowl's bottom is the smoothest part of it and its rim is the roughest.
     * Applied to the shape rather than to the blocks, so the rim is *ragged* rather than *speckled* — a
     * per-block coin toss reads as damage, and this reads as weather.
     */
    private fun erosionAt(awayX: Int, awayZ: Int, atTheRim: Double, struck: Struck): Int {
        if (atTheRim <= NOTHING) return UNMOVED
        val wander = hashedAt(awayX, awayZ, struck.grain) - HALF
        return (wander * TWICE * struck.eroded * atTheRim * struck.reach * WEARS_BY).roundToInt()
    }

    /**
     * What a crater is shaped like, which is drawn rather than derived.
     *
     * Four profiles that are actually different rather than one formula with a knob, on the ruined
     * portal's reasoning: ten hand-made variants is what stops a player recognising the shape after the
     * second one. [PEAKED] is gated on size because a central uplift is a thing only a big strike makes,
     * which means finding one is finding a big one.
     */
    private enum class Profile {
        /** The plain paraboloid: a hole, and what most of them are. */
        BOWL,

        /** Wide, shallow and flat-floored — one that has been filling in for a very long time. */
        SAUCER,

        /** Narrow, deep and steep-walled, with a floor rather than a point. */
        PUNCH,

        /** A bowl with the ground rebounded into a hill at its middle, as a large strike leaves. */
        PEAKED,
        ;

        /** How deep this profile runs at [inward], nought at the middle and one at the lip. */
        fun depthAt(inward: Double, deepest: Double): Double = when (this) {
            BOWL -> deepest * (ALL_OF_IT - inward * inward)
            SAUCER -> deepest * SAUCER_KEEPS * (ALL_OF_IT - inward * inward * inward)
            PUNCH -> deepest * PUNCH_DIGS * (ALL_OF_IT - squared(squared(inward)))
            PEAKED -> deepest * (ALL_OF_IT - inward * inward) - upliftAt(inward, deepest)
        }

        private fun upliftAt(inward: Double, deepest: Double): Double {
            if (inward >= PEAK_WIDTH) return NOTHING
            return deepest * PEAK_RISES * (ALL_OF_IT - inward / PEAK_WIDTH)
        }

        companion object {
            /** Weighted by how many craters really look like each, with the big-strike shape held back. */
            fun drawnBy(random: RandomSource, reach: Int): Profile {
                val roll = random.nextDouble()
                if (reach >= PEAKS_ABOVE && roll < PEAKED_SHARE) return PEAKED
                if (roll < SAUCER_SHARE) return SAUCER
                if (roll < SAUCER_SHARE + PUNCH_SHARE) return PUNCH
                return BOWL
            }

            private fun squared(value: Double) = value * value

            private const val SAUCER_KEEPS = 0.55
            private const val PUNCH_DIGS = 1.35
            private const val PEAK_WIDTH = 0.30
            private const val PEAK_RISES = 0.62

            private const val PEAKS_ABOVE = 15
            private const val PEAKED_SHARE = 0.30
            private const val SAUCER_SHARE = 0.26
            private const val PUNCH_SHARE = 0.18
        }
    }

    /** One body's hole: where it landed relative to the crater's own middle, how big, and what shape. */
    private class Blow(
        val awayX: Int,
        val awayZ: Int,
        val reach: Int,
        val profile: Profile,
        val lobed: Lobes,
    ) {

        /** How far the lip stands from this blow's middle along the bearing to a column, lobes and all. */
        fun lipToward(offsetX: Int, offsetZ: Int): Double =
            reach * lobed.at(atan2(offsetZ.toDouble(), offsetX.toDouble()))
    }

    /**
     * Everything drawn once for one crater: its blows, how worn it is, and how much it threw clear.
     *
     * The ruined portal's `Properties` in shape — a handful of numbers settled per instance so that the
     * placement code has variety to read rather than variety to invent, and so the two halves of one
     * crater cannot disagree about how worn it is.
     */
    private class Struck(
        val reach: Int,
        val blows: List<Blow>,
        val eroded: Double,
        val ejecta: Double,
        val grain: Long,
        val room: Int,
    ) {

        /**
         * How far out anything this crater does may reach, which bounds every loop over it.
         *
         * Clipped to the writable room, which only ever bites into the debris: the crater proper is sized
         * to fit by [PINNED_REACHES] before this is asked.
         */
        fun carriesTo(): Int =
            ceil(reach * WHOLE_CRATER * EJECTA_CARRIES).toInt().coerceAtMost(room)

        /**
         * How far the ground moves at this offset — down inside a lip, up across a skirt, and nothing
         * beyond.
         *
         * **A hole wins over a rim wherever they overlap**, which is what makes twinned craters read as
         * one event: the second blow's skirt does not build a wall across the first one's floor, it is
         * simply cut away by it, exactly as the later impact would have.
         */
        fun offsetAt(awayX: Int, awayZ: Int): Int {
            var deepest = NOTHING
            var highest = NOTHING
            var atTheRim = NOTHING
            for (blow in blows) {
                val offsetX = awayX - blow.awayX
                val offsetZ = awayZ - blow.awayZ
                val away = sqrt((offsetX * offsetX + offsetZ * offsetZ).toDouble())
                val lip = blow.lipToward(offsetX, offsetZ)
                val skirt = blow.reach * RIM_REACHES
                if (away > lip + skirt) continue
                atTheRim = max(atTheRim, ALL_OF_IT - abs(away - lip) / skirt)
                if (away < lip) {
                    deepest = max(deepest, blow.profile.depthAt(away / lip, blow.reach * DEEPEST_SHARE))
                } else {
                    val across = (away - lip) / skirt
                    highest = max(highest, blow.reach * RIM_RISES * FULL_BUMP * across * (ALL_OF_IT - across))
                }
            }
            if (deepest <= NOTHING && highest <= NOTHING) return UNMOVED
            val shaped = if (deepest > NOTHING) -deepest else highest
            return shaped.roundToInt() + erosionAt(awayX, awayZ, atTheRim, this)
        }

        /**
         * How far past every rim this column lies, nought at the outermost skirt and one at the far edge of
         * the debris — or null for a column that is still part of the crater proper.
         */
        fun beyondTheRim(awayX: Int, awayZ: Int, away: Double): Double? {
            var outermost = NOTHING
            for (blow in blows) {
                val offsetX = awayX - blow.awayX
                val offsetZ = awayZ - blow.awayZ
                val lip = blow.lipToward(offsetX, offsetZ) + blow.reach * RIM_REACHES
                val from = sqrt((offsetX * offsetX + offsetZ * offsetZ).toDouble())
                if (from < lip) return null
                outermost = max(outermost, lip)
            }
            val carries = carriesTo() - outermost
            if (carries <= NOTHING) return null
            return (ALL_OF_IT - (away - outermost) / carries).coerceIn(NOTHING, ALL_OF_IT)
        }

        companion object {
            fun drawnBy(random: RandomSource, reach: Int, room: Int): Struck {
                val first = Blow(NONE, NONE, reach, Profile.drawnBy(random, reach), Lobes.drawnBy(random))
                val twinned = random.nextDouble() < TWINNED
                return Struck(
                    reach = reach,
                    blows = if (twinned) listOf(first, alongside(random, reach)) else listOf(first),
                    eroded = WORN_LEAST + random.nextDouble() * (WORN_MOST - WORN_LEAST),
                    ejecta = THREW_LEAST + random.nextDouble() * (THREW_MOST - THREW_LEAST),
                    grain = random.nextLong(),
                    room = room,
                )
            }

            /**
             * The second body, which lands beside the first and is smaller.
             *
             * Bounded so that its own lip and skirt stay inside the first crater's reach — the write radius
             * is measured from the middle, and a companion that pushed past it would have its far side
             * quietly dropped.
             */
            private fun alongside(random: RandomSource, reach: Int): Blow {
                val smaller = (reach * COMPANION_KEEPS).roundToInt().coerceAtLeast(ONE)
                val out = (reach * WHOLE_CRATER - smaller * WHOLE_CRATER).coerceAtLeast(NOTHING)
                val bearing = random.nextDouble() * FULL_TURN
                val away = random.nextDouble() * out
                return Blow(
                    (kotlin.math.cos(bearing) * away).roundToInt(),
                    (kotlin.math.sin(bearing) * away).roundToInt(),
                    smaller,
                    Profile.drawnBy(random, smaller),
                    Lobes.drawnBy(random),
                )
            }

            private const val TWINNED = 0.18
            private const val COMPANION_KEEPS = 0.45
            private const val WORN_LEAST = 0.25
            private const val WORN_MOST = 1.0
            private const val THREW_LEAST = 0.10
            private const val THREW_MOST = 0.34
        }
    }

    /**
     * What keeps a crater from being a circle — a couple of slow waves round its edge, drawn once.
     *
     * Per-column noise would fray the lip into gravel; two harmonics lobe it at the scale a rim actually
     * varies at, and being drawn once per blow means the two sides of one hole agree about its shape.
     */
    private class Lobes(private val firstPhase: Double, private val secondPhase: Double) {

        fun at(angle: Double): Double =
            ALL_OF_IT + LOBED_BY * (sin(angle * FEW_LOBES + firstPhase) + sin(angle * MANY_LOBES + secondPhase)) / TWICE

        companion object {
            fun drawnBy(random: RandomSource): Lobes =
                Lobes(random.nextDouble() * FULL_TURN, random.nextDouble() * FULL_TURN)

            private const val FEW_LOBES = 3.0
            private const val MANY_LOBES = 5.0
            private const val LOBED_BY = 0.12
        }
    }

    private val AIR: BlockState = Blocks.AIR.defaultBlockState()

    /** How deep the bowl goes at its middle, and how far out and how high the rim carries, all as shares. */
    private const val DEEPEST_SHARE = 0.42
    private const val RIM_REACHES = 0.30
    private const val RIM_RISES = 0.13

    /** How far the rim may wander off the arithmetic, as a share of the reach at full wear. */
    private const val WEARS_BY = 0.09

    /** How far debris carries past the outermost skirt, as a multiple of the crater's own extent. */
    private const val EJECTA_CARRIES = 1.45

    /** A bound on the walk up a flooded column, so a crater under an abyss does not climb the whole sea. */
    private const val DEEPEST_SEA_CUT = 64

    /** Where shards may stand, as a share of the reach — well inside, where the carve reached rock. */
    private const val SHARDS_WITHIN = 0.45
    private const val TOGETHER = 3

    /**
     * How far a whole crater stands from its middle, as a multiple of its reach.
     *
     * The lip is the reach as [Lobes] moved it, up to [Lobes.LOBED_BY] out, and the skirt carries
     * [RIM_REACHES] past that. So the visible crater is nearly half again its own reach, and the reach
     * that fits a box is the box divided by this.
     */
    private const val WHOLE_CRATER = 1.42

    /**
     * What a crater may reach from anywhere in its chunk, and from the middle of it.
     *
     * `blockStateWriteRadius(1)` gives sixteen blocks on the tightest side from anywhere in a chunk, and
     * twenty-three from its middle; [WHOLE_CRATER] is what turns those into a reach. So the biggest hole
     * this can cut is thirty-two blocks across with its rim carrying it to about forty-six, and the debris
     * beyond that is clipped rather than shrinking the crater to make room for it.
     */
    private const val FREELY_PLACED = 11
    private const val PINNED_REACHES = 16

    private const val CHUNK = 16

    private const val CHUNK_BITS = 4

    /** Blocks of open ground kept between a crater's reach and a structure, so no rim or ejecta lands against it. */
    private const val CLEAR_OF_STRUCTURES = 4

    /** How far from its own chunk, in chunks, a feature's region lets it read structure references. */
    private const val REFERENCES_READABLE_WITHIN = 1

    /** And structure starts, which the chunk pyramid holds at a wider ring for decoration's own use. */
    private const val STARTS_READABLE_WITHIN = 8
    private const val HALF_A_CHUNK = 8
    private const val FULL_BUMP = 4.0
    private const val UNMOVED = 0
    private const val NONE = 0
    private const val ONE = 1
    private const val TWICE = 2
    private const val HALF = 0.5
    private const val NOTHING = 0.0
    private const val ALL_OF_IT = 1.0
    private const val FULL_TURN = 2.0 * PI
}

/**
 * How big a crater is and what it may have kept.
 *
 * One feature with a scale rather than two features, because a small crater and a large one differ in
 * nothing but their numbers — everything that makes them look unalike is drawn inside the feature.
 *
 * **Whether a crater kept anything and how much are two dials, not one** (Jonah, walked 2026-09-10). They
 * were a single `nextInt(mostShards + 1)`, which at a `mostShards` of one is a coin toss: half of all small
 * craters held none and the rest held exactly one, with no way to make the find rarer without making it
 * impossible, or commoner without making it two. Splitting them puts rarity on [holdsShards] and leaves
 * [mostShards] to say how much a crater that kept something kept — so the floor, once it holds any, is one.
 */
data class CraterScale(
    val leastReach: Int,
    val mostReach: Int,
    val holdsShards: Float,
    val mostShards: Int,
) : FeatureConfiguration {

    companion object {
        val CODEC: Codec<CraterScale> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("least_reach").forGetter(CraterScale::leastReach),
                Codec.INT.fieldOf("most_reach").forGetter(CraterScale::mostReach),
                Codec.FLOAT.fieldOf("holds_shards").forGetter(CraterScale::holdsShards),
                Codec.INT.fieldOf("most_shards").forGetter(CraterScale::mostShards),
            ).apply(instance, ::CraterScale)
        }
    }
}
