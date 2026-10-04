package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeComponents
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
import net.minecraft.world.phys.Vec3

/**
 * Writes a found linking book, so it arrives leading somewhere.
 *
 * ```json
 * { "function": "agesandtheart:bind_linking_book", "stray_chance": 0.1 }
 * ```
 *
 * Almost all of them point **home** — the Overworld's spawn — because that is what makes a found linking
 * book worth carrying into an Age: it is the way back, and the whole reason the item is seeded as loot at
 * all rather than only crafted.
 *
 * A `stray_chance` of them instead point at **wherever they were found**, which is a different and much
 * stranger object: someone else's route, to a place you have to go and see. Chests are generated all over
 * the world, so a stray book is a coordinate you would otherwise never have had a reason to visit.
 */
class BindLinkingBookFunction(
    predicate: Optional<Holder<LootItemCondition>>,
    val strayChance: Float,
) : LootItemConditionalFunction(predicate) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val level = context.level
        val strays = context.random.nextFloat() < strayChance
        // A stray needs to know where it is; without an origin there is nothing to bind it to, so it
        // quietly falls back to home rather than arriving blank.
        val here = context.getOptional(LootContextParams.ORIGIN)
        val target = if (strays && here != null) {
            LinkTarget(level.dimension(), here, 0.0f, level.dimension().identifier().path.replace('_', ' '))
        } else {
            // The world's respawn point rather than literal 0,0 — "home" means where you would wake up.
            val respawn = level.server.respawnData
            val spawn = respawn.globalPos().pos()
            LinkTarget(
                respawn.globalPos().dimension(),
                Vec3(spawn.x + HALF_BLOCK, spawn.y.toDouble(), spawn.z + HALF_BLOCK),
                respawn.yaw(),
                HOME_NAME,
            )
        }
        LinkingBookItem.bindTo(itemStack, target)
        return itemStack
    }

    companion object {
        private const val HALF_BLOCK = 0.5
        private const val HOME_NAME = "overworld"

        /** None stray by default: a found book should be reliably the way home unless a pack says otherwise. */
        private const val NO_STRAYS = 0.0f

        val MAP_CODEC: MapCodec<BindLinkingBookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("stray_chance", NO_STRAYS)
                        .forGetter(BindLinkingBookFunction::strayChance),
                )
                .apply(instance, ::BindLinkingBookFunction)
        }
    }
}
