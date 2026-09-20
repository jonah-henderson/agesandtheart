package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Whether an Age will hold a scarab colony, and where the ground that would hold one lies (design §7.1.2).
 *
 * Four conditions, each satisfied or not: a warm enough world, a jungle in it, torchflowers growing wild
 * where that jungle is, and mud with sand beside it. Separate answers rather than one score, because the
 * medallion has to say *which* thing is missing.
 *
 * The Age's half is read off the recipe and so can be answered anywhere in it; the ground's half is read
 * off blocks, and only in loaded chunks. The colony's own claim test asks what [siteNear] asks, so the
 * instrument and the creature cannot disagree about what good ground is.
 */
object ScarabHabitat {

    /** The patch of `worldgen/placed_feature/torchflowers.json`, which is the only thing that grows them. */
    val TORCHFLOWERS: Identifier = "torchflowers".location()

    /** Whether the air suits a beetle, and which way it is wrong where it does not. */
    enum class Warmth { SUITS, TOO_COLD, TOO_HOT }

    /** Whether the Age's own generation puts torchflowers where the jungle is. */
    enum class Torchflowers {
        /** Written for the whole Age, or confined to a jungle — both reach the colony. */
        WILD_IN_THE_JUNGLE,

        /** Written, but pinned somewhere that is not a jungle, so the colony never meets one. */
        AWAY_FROM_THE_JUNGLE,

        /** Nothing here plants them, which is every Age until a book says the word. */
        NONE,
    }

    /** Ground the medallion found: mud is what is looked for, and the other two are what may be missing. */
    data class Site(val mud: BlockPos, val sandWithinReach: Boolean, val underTheJungle: Boolean) {
        val wouldHoldAColony: Boolean get() = sandWithinReach && underTheJungle
    }

    /**
     * Whether the Age is warm enough for a colony and not so hot that nothing could live in it
     * (design §7.1.2).
     *
     * Read off the climate numbers rather than off the biome, because naming a jungle outright puts one in
     * a frozen world and a named jungle reports its own warmth whatever surrounds it.
     *
     * **Any territory, not every one**, unlike [EarlyGameRareMaterials]'s gates: a colony lives in one
     * place, so a divided Age with a warm half is a home. Too cold means there is nowhere warm at all.
     * An Age nobody bent runs vanilla's whole range and so suits.
     */
    fun warmthOf(composition: AgeComposition): Warmth {
        val territories = composition.membersIn(Aspect.CLIMATE).coerceAtLeast(ONE_CLIMATE)
        val spans = (0..<territories).map { member ->
            Span.read(composition.optionsFor(Aspect.CLIMATE, member).of(ClimateAxis.TEMPERATURE.parameter))
                ?: Span.NATURAL
        }
        val somewhereLivable = spans.any { it.most >= WARM_ENOUGH && it.least <= SEARING }
        if (somewhereLivable) return Warmth.SUITS
        return if (spans.all { it.most < WARM_ENOUGH }) Warmth.TOO_COLD else Warmth.TOO_HOT
    }

    /**
     * Whether this Age's generation grows torchflowers where its jungle is.
     *
     * **Read off the Age, never off the block** (design §7.1.2): a planted torchflower is the same block as
     * a grown one, so asking the world would let any jungle be filled in by hand. [isJungle] is handed in
     * so this stays a function of the recipe and can be asked with no registries.
     */
    fun torchflowersIn(composition: AgeComposition, isJungle: (Identifier) -> Boolean): Torchflowers {
        val claim = Features.claimNaming(composition, TORCHFLOWERS) ?: return Torchflowers.NONE
        // Age-wide reaches the jungle too, which is the second phrasing §7.1.2 accepts.
        val confinedTo = claim.confinedTo ?: return Torchflowers.WILD_IN_THE_JUNGLE
        return if (isJungle(confinedTo)) Torchflowers.WILD_IN_THE_JUNGLE else Torchflowers.AWAY_FROM_THE_JUNGLE
    }

    /**
     * The nearest jungle to [from], or null where a wide sweep finds none.
     *
     * The biome census's method: `getBaseHeight` samples the noise column and `getNoiseBiome` indexes the
     * table, so this reaches far past what has been generated and touches no region file. Sampled at the
     * ground because biomes here are three-dimensional — a fixed height reports the sky or the deep.
     */
    fun nearestJungle(level: ServerLevel, from: BlockPos): BlockPos? {
        val generator = level.chunkSource.generator
        val biomes = generator.biomeSource
        val randomState = level.chunkSource.randomState()
        val climate = randomState.sampler()

        var nearest: BlockPos? = null
        var nearestDistance = Long.MAX_VALUE
        for (x in -JUNGLE_SWEEP..JUNGLE_SWEEP step JUNGLE_STRIDE) {
            for (z in -JUNGLE_SWEEP..JUNGLE_SWEEP step JUNGLE_STRIDE) {
                val distance = squaredDistance(x, z)
                if (distance >= nearestDistance) continue
                val blockX = from.x + x
                val blockZ = from.z + z
                val ground = generator.getBaseHeight(
                    blockX, blockZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState,
                )
                val biome = biomes.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(ground),
                    QuartPos.fromBlock(blockZ),
                    climate,
                )
                if (!biome.`is`(BiomeTags.IS_JUNGLE)) continue
                nearest = BlockPos(blockX, ground, blockZ)
                nearestDistance = distance
            }
        }
        return nearest
    }

    /**
     * The best ground for a nest within reach of [from], or null where no mud lies open to the sky.
     *
     * A whole site wins however far off it is, and between two of a kind the nearer does; a partial one is
     * still worth reporting, being what a player can walk to and judge.
     *
     * **Loaded chunks only.** A block is a fact about a chunk that exists, and the heightmap of an
     * unloaded one reads as the bottom of the world rather than as "unknown".
     */
    fun siteNear(level: ServerLevel, from: BlockPos): Site? {
        var best: Site? = null
        var bestDistance = Long.MAX_VALUE
        for (x in -MUD_SWEEP..MUD_SWEEP) {
            for (z in -MUD_SWEEP..MUD_SWEEP) {
                val distance = squaredDistance(x, z)
                val cannotBeatWhatWeHave = distance >= bestDistance && best?.wouldHoldAColony == true
                if (cannotBeatWhatWeHave) continue
                val column = BlockPos(from.x + x, from.y, from.z + z)
                if (!level.hasChunkAt(column)) continue
                val mud = surfaceOf(level, column.x, column.z) ?: continue
                if (!level.getBlockState(mud).`is`(Blocks.MUD)) continue
                val site = Site(
                    mud = mud,
                    sandWithinReach = sandNear(level, mud),
                    underTheJungle = level.getBiome(mud).`is`(BiomeTags.IS_JUNGLE),
                )
                val held = best
                val better = when {
                    held == null -> true
                    site.wouldHoldAColony != held.wouldHoldAColony -> site.wouldHoldAColony
                    else -> distance < bestDistance
                }
                if (better) {
                    best = site
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /** Whether the colony would find sand to carry from [mud] — what a pillar is actually built of. */
    private fun sandNear(level: ServerLevel, mud: BlockPos): Boolean {
        for (x in -SAND_REACH..SAND_REACH) {
            for (z in -SAND_REACH..SAND_REACH) {
                val column = BlockPos(mud.x + x, mud.y, mud.z + z)
                if (!level.hasChunkAt(column)) continue
                val surface = surfaceOf(level, column.x, column.z) ?: continue
                if (level.getBlockState(surface).`is`(BlockTags.SAND)) return true
            }
        }
        return false
    }

    /**
     * The topmost block of a column, or null where the column is empty.
     *
     * `WORLD_SURFACE` rather than a motion-blocking heightmap, because "exposed at the surface" (§7.1.2) is
     * the top of the column: mud under a floor is not exposed, and mud under a leaf still is.
     */
    private fun surfaceOf(level: ServerLevel, x: Int, z: Int): BlockPos? {
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        return if (top <= level.minY) null else BlockPos(x, top - 1, z)
    }

    private fun squaredDistance(x: Int, z: Int): Long = x.toLong() * x + z.toLong() * z

    /** One climate territory is what an Age nobody divided has — see [AgeComposition.climates]. */
    private const val ONE_CLIMATE = 1

    /**
     * Where `OverworldBiomeBuilder` stops calling a temperature temperate, the same table
     * [ClimateAxis.landmarks] reads. Below this an Age has no warm ground anywhere.
     */
    private const val WARM_ENOUGH = 0.2

    /**
     * Above this the world is a furnace and nothing lives in it.
     *
     * Ours and uncalibrated: vanilla's table has no landmark above the desert at 0.55, and the jungle lives
     * at the top of that band, so the threshold must sit well clear of it or warmth would refuse the very
     * climates the jungle needs. It bites only on an Age pinned to the very top.
     */
    private const val SEARING = 0.85

    /** How far the biome sweep reaches, and how coarsely — a jungle is far wider than the stride. */
    private const val JUNGLE_SWEEP = 1024
    private const val JUNGLE_STRIDE = 32

    /** How far the ground sweep reaches. About what is loaded around a player, and no further. */
    private const val MUD_SWEEP = 96

    /** How far a colony will carry sand to its pillar. */
    private const val SAND_REACH = 12
}
