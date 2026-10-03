package co.voik.agesandtheart.advancement

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.advancements.triggers.SimpleCriterionTrigger
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/**
 * A writer's desk opened at a rung of furnishing (`art/writers_desk.json`): 0 is the bare desk, and each
 * rung after it the next listed there. Asked by rung rather than by implement count, so the counts can be
 * retuned without touching the advancements.
 */
class DeskFurnishedTrigger : SimpleCriterionTrigger<DeskFurnishedTrigger.Instance>() {

    override fun codec(): Codec<Instance> = Instance.CODEC

    fun trigger(player: ServerPlayer, rung: Int) {
        trigger(player) { rung >= it.atLeast }
    }

    data class Instance(
        val playerCondition: Optional<Holder<LootItemCondition>>,
        val atLeast: Int,
    ) : SimpleCriterionTrigger.SimpleInstance {

        override fun player(): Optional<Holder<LootItemCondition>> = playerCondition

        companion object {
            val CODEC: Codec<Instance> = RecordCodecBuilder.create { instance ->
                instance.group(
                    LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::playerCondition),
                    Codec.INT.fieldOf("rung").forGetter(Instance::atLeast),
                ).apply(instance, ::Instance)
            }
        }
    }
}
