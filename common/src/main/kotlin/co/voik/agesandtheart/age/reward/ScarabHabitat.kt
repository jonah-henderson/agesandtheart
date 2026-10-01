package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.compat.hasChunkAtColumn
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.worldgen.feature.ScarabColony
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * Whether an Age will hold a scarab colony, and where the ground that would hold one lies (design §7.1.2).
 *
 * Four conditions, each satisfied or not: a warm enough world, a jungle in it, torchflowers growing wild
 * where that jungle is, and mud with sand beside it. Separate answers rather than one score, because the
 * medallion has to say *which* thing is missing.
 *
 * The Age's half is read off the recipe and so can be answered anywhere in it; the ground's half is read
 * off blocks, and only in loaded chunks. The colony claims ground by [freeSiteAt], which is the test
 * [siteNear] sweeps with, so the instrument and the creature cannot disagree about what good ground is.
 *
 * **Only arrival asks the Age** (Jonah, 2026-10-01). Where a scarab nests and breeds is the ground's half
 * alone, read where the scarab is — warm by its biome or by a heat source beside it, and open to the sky or
 * lit — so one carried through a portal can found a colony anywhere a player builds it a home.
 */
object ScarabHabitat {

    /** The patch of `worldgen/placed_feature/torchflowers.json`, which is the only thing that grows them. */
    val TORCHFLOWERS: Identifier = "torchflowers".location()

    /** A claimed column: the nest a scarab turned its mud into, one scarab to a nest. */
    val NEST: ResourceKey<PoiType> = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, "scarab_nest".location())

    /**
     * Everything about the Age itself that a colony asks, rather than about any ground in it.
     *
     * [writtenByAPlayer] is the provenance every reward reads (design §7.7): a found book's Age can meet the
     * other three and still never hold a colony.
     */
    data class AgeReading(
        val writtenByAPlayer: Boolean,
        val warmth: Warmth,
        val anyJungle: Boolean,
        val torchflowers: Torchflowers,
    ) {
        val isWarmEnough: Boolean get() = warmth == Warmth.SUITS
        val growsTorchflowersWild: Boolean get() = torchflowers == Torchflowers.WILD_IN_THE_JUNGLE

        /** Whether everything but the ground is there. */
        val wouldHoldAColony: Boolean get() = writtenByAPlayer && isWarmEnough && anyJungle && growsTorchflowersWild
    }

    /**
     * **The colonies an Age is generated with**, as a decoration layer — or null where no colony would live in
     * it (design §7.1.2). Asked of the recipe, as [readAge] is less the jungle, which [ScarabColony] finds on
     * the patch itself: a patch under no jungle holds none.
     */
    fun colonyLayer(registries: HolderLookup.Provider, recipe: AgeRecipe): Decoration.Layer? {
        val composition = recipe.composition ?: return null
        val torchflowers = torchflowersIn(composition) { biome -> isJungle(registries, biome) }
        val wouldLiveHere = recipe.authored && warmthOf(composition) == Warmth.SUITS &&
            torchflowers == Torchflowers.WILD_IN_THE_JUNGLE
        if (!wouldLiveHere) return null
        return Decoration.layerOf(GenerationStep.Decoration.TOP_LAYER_MODIFICATION, listOf(COLONIES))
    }

    /**
     * **One placed feature for every Age**, as the grove is (`PaperTreeWindow`): the colony scans the chunk it
     * is placed in for itself, so it asks nothing of where it is put.
     */
    private val COLONIES: Holder<PlacedFeature> = Holder.direct(PlacedFeature(Holder.direct(ScarabColony), emptyList()))

    /** The Age half of the habitat, or null where [recipe] names a bespoke world with no composition to read. */
    fun readAge(level: ServerLevel, recipe: AgeRecipe): AgeReading? {
        val composition = recipe.composition ?: return null
        return AgeReading(
            writtenByAPlayer = recipe.authored,
            warmth = warmthOf(composition),
            anyJungle = anyJungle(level),
            torchflowers = torchflowersIn(composition) { biome -> isJungle(level, biome) },
        )
    }

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
    data class Site(val mud: BlockPos, val sandWithinReach: Boolean, val warm: Boolean) {
        val wouldHoldAColony: Boolean get() = sandWithinReach && warm
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
     * Whether this Age can grow a jungle anywhere at all.
     *
     * **Asked of the biome source's own table rather than of [nearestJungle]**, which is a sweep and so can
     * only ever say "not within reach". The two were one question until a walk found the medallion
     * announcing an Age had no jungle while `/locate biome` stood one up a few thousand blocks away — the
     * sweep reaches [JUNGLE_SWEEP] and `/locate` reaches far further, so the sweep was answering a question
     * it had no standing to answer. This one is exact and costs nothing: a biome not in the source's set
     * cannot be placed by it.
     */
    fun anyJungle(level: ServerLevel): Boolean =
        level.chunkSource.generator.biomeSource.possibleBiomes().any { it.`is`(BiomeTags.IS_JUNGLE) }

    /**
     * The nearest jungle to [from], or null where a wide sweep finds none.
     *
     * **"None" here means none within [JUNGLE_SWEEP], not none in the Age** — [anyJungle] is the question
     * about the Age.
     *
     * The biome census's method: `getBaseHeight` samples the noise column and `getNoiseBiome` indexes the
     * table, so this reaches far past what has been generated and touches no region file. Sampled at the
     * ground because biomes here are three-dimensional — a fixed height reports the sky or the deep.
     */
    fun nearestJungle(level: ServerLevel, from: BlockPos): BlockPos? {
        val generator = level.chunkSource.generator
        val biomes = generator.biomeSource
        val randomState = level.chunkSource.randomState()
        // A resolver rather than a sampler: 26.3 asks a source for one of these and it answers by quart.
        val resolver = biomes.createUncachedResolver(randomState)

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
                val biome = resolver.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(ground),
                    QuartPos.fromBlock(blockZ),
                )
                if (!biome.`is`(BiomeTags.IS_JUNGLE)) continue
                nearest = BlockPos(blockX, ground, blockZ)
                nearestDistance = distance
            }
        }
        return nearest
    }

    /**
     * The best ground for a nest within reach of [from], or null where no mud a scarab could see from there
     * lies open to the sky or lit.
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
                val mud = mudAt(level, from.x + x, from.z + z, from.y) ?: continue
                // Ground a colony already holds is no site, however good, and not a lack either.
                if (touchesAClaim(level, mud)) continue
                val site = Site(
                    mud = mud,
                    sandWithinReach = sandNear(level, mud),
                    warm = isWarmAt(level, mud),
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

    /**
     * The mud a scarab at [nearY] could claim in the column at [x], [z], or null where it could not: open
     * to the sky or lit, warm, sand within reach, and **at least a column clear of every claimed one**,
     * diagonals included (design §7.1.2).
     */
    fun freeSiteAt(level: ServerLevel, x: Int, z: Int, nearY: Int): BlockPos? {
        val mud = mudAt(level, x, z, nearY) ?: return null
        val isGoodGround = isWarmAt(level, mud) && sandNear(level, mud)
        return if (isGoodGround && !touchesAClaim(level, mud)) mud else null
    }

    /**
     * Whether a nest at [mud] is warm: a biome about as warm as the jungle, or something giving off heat
     * within [HEAT_REACH] of it (`#agesandtheart:gives_heat`).
     */
    fun isWarmAt(level: ServerLevel, mud: BlockPos): Boolean {
        val temperature = level.getBiome(mud).value().baseTemperature
        if (temperature in NESTS_FROM..NESTS_UP_TO) return true
        return BlockPos.betweenClosedStream(
            mud.offset(-HEAT_REACH, -HEAT_REACH, -HEAT_REACH),
            mud.offset(HEAT_REACH, HEAT_REACH, HEAT_REACH),
        ).anyMatch { level.getBlockState(it).`is`(GIVES_HEAT) }
    }

    /**
     * The ground a scarab sees in the column at [x], [z] from [nearY], which is a height in the open air of
     * where it is — its own, or the air over a nest's mud. Loaded chunks only.
     *
     * Where nothing in the column stands higher than [nearY], the ground is the column's top, however far
     * down. Otherwise something does — a roof, a canopy, or a bank — and the column is read at [nearY]: air
     * there means the ground is the first block under it, and solid means the top of the bank, a few
     * blocks up at most. So a scarab under a roof works its own floor, and sand on a pit's rim is still the
     * rim's.
     */
    fun groundNear(level: ServerLevel, x: Int, z: Int, nearY: Int): BlockPos? {
        if (!level.hasChunkAtColumn(x, z)) return null
        val top = surfaceOf(level, x, z) ?: return null
        if (top.y <= nearY) return top
        val reading = BlockPos(x, nearY, z)
        return if (level.getBlockState(reading).isAir) floorUnder(level, reading) else topOfTheBank(level, reading)
    }

    private fun floorUnder(level: ServerLevel, from: BlockPos): BlockPos? =
        (1..SEES_BELOW).map(from::below).firstOrNull { !level.getBlockState(it).isAir }

    private fun topOfTheBank(level: ServerLevel, from: BlockPos): BlockPos? =
        (0..SEES_ABOVE).map(from::above).firstOrNull { level.getBlockState(it.above()).isAir }

    /** Whether a nest stands in [column] or in any of the eight around it, at any height. */
    fun touchesAClaim(level: ServerLevel, column: BlockPos): Boolean =
        level.poiManager.getInSquare(::isNest, column, NEIGHBOURING_COLUMNS, PoiManager.Occupancy.ANY)
            .findAny()
            .isPresent

    /** Every nest within [radius] of [from], nearest first. */
    fun nestsNear(level: ServerLevel, from: BlockPos, radius: Int): List<BlockPos> =
        level.poiManager.getInRange(::isNest, from, radius, PoiManager.Occupancy.ANY)
            .map(PoiRecord::getPos)
            .sorted(Comparator.comparingDouble { nest -> nest.distSqr(from) })
            .toList()

    /** Whether a biome id names a jungle, for the confinement a book may have written on the flowers. */
    fun isJungle(level: ServerLevel, biome: Identifier): Boolean = isJungle(level.registryAccess(), biome)

    fun isJungle(registries: HolderLookup.Provider, biome: Identifier): Boolean =
        registries
            .lookupOrThrow(Registries.BIOME)
            .get(ResourceKey.create(Registries.BIOME, biome))
            .map { holder -> holder.`is`(BiomeTags.IS_JUNGLE) }
            .orElse(false)

    private fun isNest(poi: Holder<PoiType>): Boolean = poi.`is`(NEST)

    /**
     * The mud a scarab at [nearY] would see in the column at [x], [z], or null: open to the sky, or under
     * cover with at least [ENOUGH_LIGHT] on the air over it, so a lamp stands in for the sun.
     */
    private fun mudAt(level: ServerLevel, x: Int, z: Int, nearY: Int): BlockPos? {
        val ground = groundNear(level, x, z, nearY) ?: return null
        if (!level.getBlockState(ground).`is`(Blocks.MUD)) return null
        val isOpenToTheSky = surfaceOf(level, x, z) == ground
        val isLit = level.getRawBrightness(ground.above(), NO_DARKENING) >= ENOUGH_LIGHT
        return if (isOpenToTheSky || isLit) ground else null
    }

    /** Whether the colony would find sand to carry from [mud] — what a pillar is actually built of. */
    private fun sandNear(level: ServerLevel, mud: BlockPos): Boolean {
        for (x in -SAND_REACH..SAND_REACH) {
            for (z in -SAND_REACH..SAND_REACH) {
                val ground = groundNear(level, mud.x + x, mud.z + z, mud.y + 1) ?: continue
                if (level.getBlockState(ground).`is`(BlockTags.SAND)) return true
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
    fun surfaceOf(level: ServerLevel, x: Int, z: Int): BlockPos? {
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
    const val SAND_REACH = 12

    /** About the jungle's own temperature, 0.95, and the palm beach's and the mushroom fields' at 0.9. */
    private const val NESTS_FROM = 0.9f
    private const val NESTS_UP_TO = 1.0f

    /** How near a heat source must be to warm a nest. */
    private const val HEAT_REACH = 3

    private val GIVES_HEAT: TagKey<Block> = TagKey.create(Registries.BLOCK, "gives_heat".location())

    /** Vanilla's light for a crop to grow by, which a torch gives five blocks off. */
    private const val ENOUGH_LIGHT = 9
    private const val NO_DARKENING = 0

    /** How far a scarab looks up a bank and down to a floor for the ground of a column that is not open. */
    private const val SEES_ABOVE = 4
    private const val SEES_BELOW = 12

    /** A claim keeps the columns beside it, diagonals included, clear of other claims. */
    private const val NEIGHBOURING_COLUMNS = 1
}
