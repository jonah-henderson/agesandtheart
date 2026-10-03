package co.voik.agesandtheart.advancement

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.advancements.triggers.SimpleCriterionTrigger
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/** How a word reached a player: read off a page or a book, or named by one of the devices. */
enum class LearnedBy(private val key: String) : StringRepresentable {
    READING("reading"),
    SURVEY("survey"),
    ANALYSIS("analysis"),
    OBSERVATION("observation"),
    MASTERY("mastery"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<LearnedBy> = StringRepresentable.fromEnum(LearnedBy::values)
    }
}

/** A word learned — any word, a particular one, or one learned a particular way. */
class LearnedWordTrigger : SimpleCriterionTrigger<LearnedWordTrigger.Instance>() {

    override fun codec(): Codec<Instance> = Instance.CODEC

    fun trigger(player: ServerPlayer, word: Identifier, by: LearnedBy) {
        trigger(player) { it.matches(word, by) }
    }

    data class Instance(
        val playerCondition: Optional<Holder<LootItemCondition>>,
        val word: Optional<Identifier>,
        val by: Optional<LearnedBy>,
    ) : SimpleCriterionTrigger.SimpleInstance {

        override fun player(): Optional<Holder<LootItemCondition>> = playerCondition

        fun matches(learned: Identifier, how: LearnedBy): Boolean {
            val isTheWord = word.map { it == learned }.orElse(true)
            val isTheWay = by.map { it == how }.orElse(true)
            return isTheWord && isTheWay
        }

        companion object {
            val CODEC: Codec<Instance> = RecordCodecBuilder.create { instance ->
                instance.group(
                    LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::playerCondition),
                    Identifier.CODEC.optionalFieldOf("word").forGetter(Instance::word),
                    LearnedBy.CODEC.optionalFieldOf("by").forGetter(Instance::by),
                ).apply(instance, ::Instance)
            }
        }
    }
}
