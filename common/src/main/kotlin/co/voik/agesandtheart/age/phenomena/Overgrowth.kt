package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BonemealSource
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * What an overgrown Age does (design §5.2.2): the overgrowth eats what it touches, raises plants from
 * what it has eaten, and fire holds it back.
 */
object Overgrowth {
    val ID: Identifier = "overgrowth".location()
    val THORNS_ID: Identifier = "thorn_vine".location()

    private const val ROOT_STRENGTH = 0.8f
    private const val THORN_STRENGTH = 4.0f

    val BLOCK: OvergrowthBlock = OvergrowthBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.PLANT)
            .strength(ROOT_STRENGTH)
            .sound(SoundType.MOSS)
            .randomTicks()
            .noLootTable(),
    )

    val ITEM: Item = BlockItem(
        BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix(),
    )

    val THORNS: ThornVineBlock = ThornVineBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, THORNS_ID))
            .mapColor(MapColor.PLANT)
            .noCollision()
            .noOcclusion()
            .strength(THORN_STRENGTH)
            .sound(SoundType.SWEET_BERRY_BUSH)
            .randomTicks()
            .noLootTable()
            .pushReaction(PushReaction.POPPED),
    )

    val THORNS_ITEM: Item = BlockItem(
        THORNS,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, THORNS_ID)).useBlockDescriptionPrefix(),
    )

    /** Fire, soul fire and magma; lava and a lit campfire are read off their state instead. */
    val HOLDS_BACK: TagKey<Block> = TagKey.create(Registries.BLOCK, "holds_back_overgrowth".location())

    /** What the overgrowth will not eat, beyond whatever cannot be broken at all. */
    val IMMUNE: TagKey<Block> = TagKey.create(Registries.BLOCK, "immune_to_overgrowth".location())

    val MUSHROOMS: TagKey<Block> = TagKey.create(Registries.BLOCK, "overgrowth_mushrooms".location())

    /** Plants it never raises, however a plant it could raise is found. */
    val NEVER_RAISES: TagKey<Block> = TagKey.create(Registries.BLOCK, "overgrowth_never_raises".location())

    /** How far a fire reaches to stop the overgrowth spreading. */
    const val HOLD_BACK_RADIUS = 4

    /** How far a fire reaches to burn a thorn. */
    const val BURN_RADIUS = 2

    /** How many samples of the loaded ground near a player are offered each tick, at an ordinary rung. */
    private const val SEED_SWEEPS = 1

    /** The chance that a sample puts a root down: about one every half minute for each player. */
    private const val SEED_CHANCE = 0.0005

    /** The share of seeds that still land where an inferno burns. */
    private const val SEEDED_UNDER_INFERNO = 0.25

    /** A root is never put down closer to a player than this, in blocks. */
    private const val SEED_NO_CLOSER_THAN = 16.0

    /** How many neighbours a root tries to eat on each random tick. */
    private const val SPREAD_ATTEMPTS = 2

    /** The chance, per random tick, that the overgrowth raises something from the ground it holds. */
    private const val RAISE_CHANCE = 0.5f

    /** How long a plant that takes bone meal waits between one dose and the next. */
    private const val BONEMEAL_INTERVAL = 100

    /** How many more random ticks a plant that cannot take bone meal gets, for each of the root's own. */
    private const val EXTRA_PLANT_TICKS = 3

    /** How long a crop standing on the overgrowth takes to grow from its first stage to its last. */
    private const val RIPENING_TICKS = 400

    /** How often a mushroom the overgrowth raises is given a bone meal's worth of growth. */
    private const val HUGE_MUSHROOM_CHANCE = 0.25f

    /** How many plants are tried before an attempt to raise one gives up. */
    private const val FLORA_ATTEMPTS = 4

    /** The thorns already in the cube around a place a thorn might start, beyond which none is started. */
    private const val THORN_CROWD_LIMIT = 3

    private const val AGE = "age"

    /** How soon a new chain's first segment looks to grow, in ticks. */
    private const val THORN_FIRST_BEAT = 20

    /** What the overgrowth raises, and how much more often one is than the next. */
    enum class Raising(val weight: Int) {
        THORNS(16),
        VINES(2),
        MUSHROOM(1),
        FLORA(1),
        ;

        companion object {
            private val TOTAL_WEIGHT = entries.sumOf { it.weight }

            /** The kind a roll in `[0, 1)` lands on. */
            fun at(roll: Double): Raising {
                val target = roll * TOTAL_WEIGHT
                var passed = 0
                for (kind in entries) {
                    passed += kind.weight
                    if (target < passed) return kind
                }
                return entries.last()
            }
        }
    }

    fun isHot(state: BlockState): Boolean {
        val isAFire = state.`is`(HOLDS_BACK)
        val isMolten = state.fluidState.`is`(FluidTags.LAVA)
        val isALitCampfire =
            state.`is`(BlockTags.CAMPFIRES) && state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT)
        return isAFire || isMolten || isALitCampfire
    }

    fun isHotWithin(level: LevelReader, at: BlockPos, radius: Int): Boolean {
        val reach = BlockPos.betweenClosed(at.offset(-radius, -radius, -radius), at.offset(radius, radius, radius))
        return reach.any { level.hasChunkAt(it) && isHot(level.getBlockState(it)) }
    }

    fun isEdible(level: LevelReader, at: BlockPos, state: BlockState): Boolean {
        val isNothing = state.isAir
        val isUnbreakable = state.getDestroySpeed(level, at) < 0
        val isItsOwn = state.`is`(BLOCK) || state.`is`(THORNS)
        val isImmune = state.`is`(IMMUNE)
        val isMolten = state.fluidState.`is`(FluidTags.LAVA)
        return !(isNothing || isUnbreakable || isItsOwn || isImmune || isMolten)
    }

    /**
     * One tick of the phenomenon: now and then a root is put down on the open ground somewhere near a player.
     *
     * The spreading is the roots' own. This is only where it starts, and it is what keeps an Age with
     * overgrowth in it growing over: a held clearing is not safe from the next seed, only from its spread.
     * An inferno in the same Age holds it back ([SEEDED_UNDER_INFERNO]), as it holds back the roots.
     */
    fun seed(level: ServerLevel, density: Double) {
        Sampling.sweep(level, Happenings.timesFor(density, SEED_SWEEPS)) { _, column ->
            if (level.random.nextDouble() >= SEED_CHANCE) return@sweep
            val isBurning = Happenings.befalls(level, Phenomenon.INFERNO)
            if (isBurning && level.random.nextDouble() >= SEEDED_UNDER_INFERNO) return@sweep
            plantRootAt(level, column)
        }
    }

    private fun plantRootAt(level: ServerLevel, column: BlockPos) {
        val ground = Sampling.skyward(level, column).below()
        if (!level.hasChunkAt(ground)) return
        val standing = level.getBlockState(ground)
        val isWater = !standing.fluidState.isEmpty
        val isTooCloseToSomeone = Sampling.watchers(level).any { it.blockPosition().closerThan(ground, SEED_NO_CLOSER_THAN) }
        if (isWater || isTooCloseToSomeone || !isEdible(level, ground, standing)) return
        if (isHotWithin(level, ground, HOLD_BACK_RADIUS)) return
        level.setBlock(ground, BLOCK.defaultBlockState(), Block.UPDATE_ALL)
    }

    /** Turns blocks next to [root] into more of itself, as many tries as [SPREAD_ATTEMPTS] allows. */
    fun spreadFrom(level: ServerLevel, root: BlockPos, random: RandomSource) {
        repeat(SPREAD_ATTEMPTS) { eatBesides(level, root, random) }
    }

    private fun eatBesides(level: ServerLevel, root: BlockPos, random: RandomSource) {
        val target = root.relative(Direction.getRandom(random))
        if (!level.hasChunkAt(target)) return
        if (!isEdible(level, target, level.getBlockState(target))) return
        level.setBlock(target, BLOCK.defaultBlockState(), Block.UPDATE_ALL)
    }

    /** The growth stage of a crop, berry bush or stem; null for a plant that has none. */
    private fun ageOf(plant: BlockState): IntegerProperty? =
        if (plant.block !is VegetationBlock) null
        else plant.properties.filterIsInstance<IntegerProperty>().firstOrNull { it.name == AGE }

    private fun maxAgeOf(age: IntegerProperty): Int = age.possibleValues.max()

    private fun isRipening(state: BlockState): Boolean = ageOf(state)?.let { state.getValue(it) < maxAgeOf(it) } == true

    private fun canTakeBonemeal(level: LevelReader, at: BlockPos, state: BlockState): Boolean {
        val plant = state.block as? BonemealableBlock ?: return false
        return plant.isValidBonemealTarget(level, at, state, BonemealSource.MOB)
    }

    /**
     * The wait before the plant at [at] is next given growth, or null where there is
     * nothing for it to do: a crop with stages left steps through them, and anything else that takes bone
     * meal is given some.
     */
    fun growthDelay(level: LevelReader, at: BlockPos, state: BlockState): Int? {
        val age = ageOf(state)
        return when {
            age != null && isRipening(state) -> RIPENING_TICKS / maxAgeOf(age)
            age == null && canTakeBonemeal(level, at, state) -> BONEMEAL_INTERVAL
            else -> null
        }
    }

    /**
     * Grows the plant standing on [root] one step: a crop one stage on, anything else a bone meal's worth.
     * Says how many ticks until the next step, or null where there is nothing left to grow. A crop gets
     * through all its stages in [RIPENING_TICKS] however many it has.
     */
    fun growAbove(level: ServerLevel, root: BlockPos, random: RandomSource): Int? {
        val above = root.above()
        val plant = level.getBlockState(above)
        val age = ageOf(plant)
        if (age != null) {
            val stage = plant.getValue(age)
            val last = maxAgeOf(age)
            if (stage >= last) return null
            level.setBlock(above, plant.setValue(age, stage + 1), Block.UPDATE_CLIENTS)
            return if (stage + 1 < last) RIPENING_TICKS / last else null
        }
        val block = plant.block as? BonemealableBlock ?: return null
        if (!canTakeBonemeal(level, above, plant)) return null
        if (block.isBonemealSuccess(level, random, above, plant, BonemealSource.MOB)) {
            block.performBonemeal(level, random, above, plant, BonemealSource.MOB)
        }
        return growthDelay(level, above, level.getBlockState(above))
    }

    /** Gives a plant that cannot take bone meal some of its growth early. */
    fun quickenGrowthAbove(level: ServerLevel, root: BlockPos, random: RandomSource) {
        val above = root.above()
        // An overgrowth above would quicken the one above it in turn, and a column of them is exponential.
        if (level.getBlockState(above).`is`(BLOCK)) return
        repeat(EXTRA_PLANT_TICKS) {
            val plant = level.getBlockState(above)
            if (plant.isRandomlyTicking) plant.randomTick(level, above, random)
        }
    }

    fun maybeRaiseFrom(level: ServerLevel, root: BlockPos, random: RandomSource) {
        if (random.nextFloat() >= RAISE_CHANCE) return
        when (Raising.at(random.nextDouble())) {
            Raising.THORNS -> sproutThorns(level, root, random)
            Raising.MUSHROOM -> raise(level, root, random, mushrooms(), mayGrowHuge = true)
            Raising.VINES -> sproutVines(level, root, random)
            Raising.FLORA -> raise(level, root, random, flora(), mayGrowHuge = false)
        }
    }

    private fun sproutThorns(level: ServerLevel, root: BlockPos, random: RandomSource) {
        val way = Direction.getRandom(random)
        val cell = root.relative(way)
        if (!level.hasChunkAt(cell) || !level.getBlockState(cell).isAir) return
        val isCrowded = thornsAround(level, cell) >= THORN_CROWD_LIMIT
        if (isCrowded) return
        level.setBlock(cell, ThornVineBlock.startingFrom(way, random), Block.UPDATE_ALL)
        level.scheduleTick(cell, THORNS, THORN_FIRST_BEAT)
    }

    /**
     * Hangs one of vanilla's other vines on [root], where it can: a vine on a side or the underside, cave and
     * weeping vines below, twisting vines above. Each is asked whether it can stand there rather than told
     * which face suits it, and a growing one carries on growing by its own random ticks.
     */
    private fun sproutVines(level: ServerLevel, root: BlockPos, random: RandomSource) {
        repeat(FLORA_ATTEMPTS) {
            val way = Direction.getRandom(random)
            val cell = root.relative(way)
            if (!level.hasChunkAt(cell) || !level.getBlockState(cell).isAir) return@repeat
            val vine = vineGrowing(way, random) ?: return@repeat
            if (!vine.canSurvive(level, cell)) return@repeat
            level.setBlock(cell, vine, Block.UPDATE_ALL)
            return
        }
    }

    /** A vine that could grow in the cell [way] from a root: the ones hanging below, the one climbing above, or a plain vine on its face. */
    private fun vineGrowing(way: Direction, random: RandomSource): BlockState? = when (way) {
        Direction.DOWN -> {
            val hanging = listOfNotNull(
                Blocks.CAVE_VINES.defaultBlockState(), Blocks.WEEPING_VINES.defaultBlockState(), vineAgainst(way),
            )
            hanging[random.nextInt(hanging.size)]
        }
        Direction.UP -> Blocks.TWISTING_VINES.defaultBlockState()
        else -> vineAgainst(way)
    }

    /** A vine in the cell [way] from a root, clinging to the root's face; null where vines have no such face. */
    private fun vineAgainst(way: Direction): BlockState? =
        VineBlock.PROPERTY_BY_DIRECTION[way.opposite]?.let { Blocks.VINE.defaultBlockState().setValue(it, true) }

    private fun thornsAround(level: LevelReader, cell: BlockPos): Int =
        BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))
            .count { level.hasChunkAt(it) && level.getBlockState(it).`is`(THORNS) }

    private fun raise(level: ServerLevel, root: BlockPos, random: RandomSource, plants: List<Block>, mayGrowHuge: Boolean) {
        val above = root.above()
        if (!level.getBlockState(above).isAir || plants.isEmpty()) return
        repeat(FLORA_ATTEMPTS) {
            val plant = plants[random.nextInt(plants.size)].defaultBlockState()
            if (!plant.canSurvive(level, above)) return@repeat
            level.setBlock(above, plant, Block.UPDATE_ALL)
            if (mayGrowHuge && random.nextFloat() < HUGE_MUSHROOM_CHANCE) growHuge(level, above, random)
            return
        }
    }

    private fun growHuge(level: ServerLevel, at: BlockPos, random: RandomSource) {
        val mushroom = level.getBlockState(at)
        val block = mushroom.block as? BonemealableBlock ?: return
        if (block.isValidBonemealTarget(level, at, mushroom, BonemealSource.INTERACTION)) {
            block.performBonemeal(level, random, at, mushroom, BonemealSource.INTERACTION)
        }
    }

    private fun mushrooms(): List<Block> = BuiltInRegistries.BLOCK.filter { it.defaultBlockState().`is`(MUSHROOMS) }

    private fun flora(): List<Block> = EVERY_PLANT.filter { block ->
        val state = block.defaultBlockState()
        !state.`is`(MUSHROOMS) && !state.`is`(NEVER_RAISES)
    }

    /** A tall plant is two blocks, and one of them alone breaks itself. */
    private val EVERY_PLANT: List<Block> by lazy {
        BuiltInRegistries.BLOCK.filter { it is VegetationBlock && it !is DoublePlantBlock }
    }
}
