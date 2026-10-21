package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.generation.FoundDestinations
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.core.Holder
import java.util.Optional
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.core.BlockPos
import co.voik.agesandtheart.worldgen.structure.ArrivalMarker

/**
 * Writes a found linking book, so it arrives leading somewhere.
 *
 * ```json
 * { "function": "agesandtheart:bind_linking_book", "stray_chance": 0.1 }
 * ```
 *
 * Almost all of them point **home** — into the Overworld — because that is what makes a found linking book worth
 * carrying into an Age: it is the way back, and the whole reason the item is seeded as loot at all rather than only
 * crafted. Where in it is [wandering]'s: spawn, or somewhere else worth arriving at ([FoundDestinations]).
 *
 * A `stray_chance` of them instead point at **wherever they were found**, which is a different and much
 * stranger object: someone else's route, to a place you have to go and see. Chests are generated all over
 * the world, so a stray book is a coordinate you would otherwise never have had a reason to visit.
 *
 * With an `arrival`, every book points at the structure the chest stands in instead, at its [ArrivalMarker]
 * — the way back to a lost library from anywhere. Outside any such structure they fall back to home.
 *
 * ```json
 * { "function": "agesandtheart:bind_linking_book",
 *   "arrival": { "marker": "minecraft:linking_book_target", "facing": "south", "name": "lost library" } }
 * ```
 */
class BindLinkingBookFunction(
    predicate: Optional<Holder<LootItemCondition>>,
    val strayChance: Float,
    val arrival: ArrivalMarker?,
    val wandering: FoundDestinations.Wandering,
) : LootItemConditionalFunction(predicate) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val level = context.level
        val strays = context.random.nextFloat() < strayChance
        // A stray needs to know where it is; without an origin there is nothing to bind it to, so it
        // quietly falls back to home rather than arriving blank.
        val here = context.getOptional(LootContextParams.ORIGIN)
        val structureArrival = if (arrival != null && here != null) arrival.around(level, BlockPos.containing(here)) else null
        val target = if (structureArrival != null) {
            structureArrival
        } else if (strays && here != null) {
            LinkTarget(level.dimension(), here, 0.0f, WordNames.placeName(level.dimension()))
        } else {
            FoundDestinations.home(level.server, context.random, wandering, mayLookForStructures = true)
        }
        LinkingBookItem.bindTo(itemStack, target)
        return itemStack
    }

    companion object {
        /** None stray by default: a found book should be reliably the way home unless a pack says otherwise. */
        private const val NO_STRAYS = 0.0f

        val MAP_CODEC: MapCodec<BindLinkingBookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("stray_chance", NO_STRAYS)
                        .forGetter(BindLinkingBookFunction::strayChance),
                )
                .and(
                    ArrivalMarker.CODEC.optionalFieldOf("arrival")
                        .forGetter { Optional.ofNullable(it.arrival) },
                )
                .and(
                    FoundDestinations.Wandering.CODEC.optionalFieldOf("wandering", FoundDestinations.Wandering.DEFAULT)
                        .forGetter(BindLinkingBookFunction::wandering),
                )
                .apply(instance) { predicate, strayChance, arrival, wandering ->
                    BindLinkingBookFunction(predicate, strayChance, arrival.orElse(null), wandering)
                }
        }
    }
}
