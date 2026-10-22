package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Fishing
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

/**
 * What fishing an Age gives (`FishingHookMixin`): a fish caught is drawn again by how thickly the book asked for
 * each kind where the bobber is — `teeming cod` is mostly cod — and rich waters ([Fishing.TREASURE]) fish with
 * [RICH_WATERS_LUCK] more luck, which vanilla's table spends on treasure. Where the book struck every fish, a cast that
 * would have brought one up brings up junk or treasure instead, drawn between them as vanilla's table draws.
 */
object AgeFishing {

    /** A kind of fish vanilla's fishing table hands out, the creature it is and its weight there. */
    private data class Fish(val creature: Identifier, val item: Item, val weight: Double)

    /** Vanilla's `gameplay/fishing/fish`. */
    private val FISH = listOf(
        Fish(Identifier.withDefaultNamespace("cod"), Items.COD, 60.0),
        Fish(Identifier.withDefaultNamespace("salmon"), Items.SALMON, 25.0),
        Fish(Identifier.withDefaultNamespace("tropical_fish"), Items.TROPICAL_FISH, 2.0),
        Fish(Identifier.withDefaultNamespace("pufferfish"), Items.PUFFERFISH, 13.0),
    )

    /** Luck of the Sea III is three, so rich waters fish better than the best rod: treasure about one cast in six. */
    private const val RICH_WATERS_LUCK = 5f

    fun luckIn(level: Level): Float {
        val waters = Ages.recipeOf(level as? ServerLevel ?: return 0f)?.composition?.optionsFor(Aspect.WATERS) ?: return 0f
        return if (waters.of(Fishing.TREASURE) == Parameter.TRUE) RICH_WATERS_LUCK else 0f
    }

    /**
     * What [hook] brought up, with each fish drawn again by the book's rungs where it is, and junk or treasure in its
     * place where the book struck every fish; anything else, and an Age that said nothing of fish, as caught.
     */
    fun caught(caught: List<ItemStack>, hook: FishingHook): List<ItemStack> {
        val serverLevel = hook.level() as? ServerLevel ?: return caught
        val at = hook.blockPosition()
        val random = hook.random
        val generator = serverLevel.chunkSource.generator as? AgeChunkGenerator ?: return caught
        val weights = FISH.associateWith { fish -> fish.weight * generator.rungOf(fish.creature, serverLevel, at) }
        val saidNothingOfFish = FISH.all { fish -> Rung.isOrdinary(generator.rungOf(fish.creature, serverLevel, at)) }
        if (saidNothingOfFish) return caught
        val total = weights.values.sum()
        fun isAFish(stack: ItemStack) = FISH.any { stack.`is`(it.item) }
        fun orSomethingElse(stack: ItemStack) = if (isAFish(stack)) notAFish(hook, serverLevel) else listOf(stack)
        if (total <= 0.0) return caught.flatMap(::orSomethingElse)
        fun drawn(stack: ItemStack): ItemStack {
            if (!isAFish(stack)) return stack
            val fish = fishAt(weights, random.nextDouble() * total) ?: return stack
            return ItemStack(fish.item, stack.count)
        }
        return caught.map(::drawn)
    }

    /** Junk or treasure, drawn between them by vanilla's weights and qualities at the luck rich waters give. */
    private fun notAFish(hook: FishingHook, level: ServerLevel): List<ItemStack> {
        val luck = luckIn(level)
        val junk = (JUNK_WEIGHT + JUNK_QUALITY * luck).coerceAtLeast(0f)
        val treasure = (TREASURE_WEIGHT + TREASURE_QUALITY * luck).coerceAtLeast(0f)
        val isTreasure = hook.random.nextFloat() * (junk + treasure) < treasure
        val table = if (isTreasure) BuiltInLootTables.FISHING_TREASURE else BuiltInLootTables.FISHING_JUNK
        val params = LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, hook.position())
            .withParameter(LootContextParams.TOOL, ItemStack(Items.FISHING_ROD))
            .withParameter(LootContextParams.THIS_ENTITY, hook)
            .withLuck(luck)
            .create(LootContextParamSets.FISHING)
        return level.server.reloadableRegistries().getLootTable(table).getRandomItems(params)
    }

    /** Vanilla's `gameplay/fishing` weighs its junk and treasure so, each moved by luck times its quality. */
    private const val JUNK_WEIGHT = 10f
    private const val JUNK_QUALITY = -2f
    private const val TREASURE_WEIGHT = 5f
    private const val TREASURE_QUALITY = 2f

    /** The fish whose share of [weights], laid end to end, holds [point]. */
    private fun fishAt(weights: Map<Fish, Double>, point: Double): Fish? {
        var reached = 0.0
        for ((fish, weight) in weights) {
            reached += weight
            if (point < reached) return fish
        }
        return null
    }
}
