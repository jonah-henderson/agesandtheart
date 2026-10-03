package co.voik.agesandtheart.advancement

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.advancements.triggers.SimpleCriterionTrigger
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/** Where a link went, as far as an advancement cares. */
enum class LinkedInto(private val key: String) : StringRepresentable {
    AN_AGE("age"),
    THE_OVERWORLD("overworld"),
    ELSEWHERE("elsewhere"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<LinkedInto> = StringRepresentable.fromEnum(LinkedInto::values)
    }
}

/** Which kind of book a link went through. */
enum class LinkedWith(private val key: String) : StringRepresentable {
    DESCRIPTIVE_BOOK("descriptive_book"),
    LINKING_BOOK("linking_book"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<LinkedWith> = StringRepresentable.fromEnum(LinkedWith::values)
    }
}

/**
 * One link, as it lands. [firstVisit] is whether no player had set foot in the Age before — narrower than
 * "no linking book has ever been brought here", and near enough for one advancement.
 */
data class Link(val into: LinkedInto, val with: LinkedWith, val carriesALinkingBook: Boolean, val firstVisit: Boolean)

/**
 * A link through a book, from a hand or a lectern. `carrying_a_linking_book` asks whether the player
 * arrived holding one, and `first_visit` whether nobody had been to the Age before.
 */
class LinkedTrigger : SimpleCriterionTrigger<LinkedTrigger.Instance>() {

    override fun codec(): Codec<Instance> = Instance.CODEC

    fun trigger(player: ServerPlayer, link: Link) {
        trigger(player) { it.matches(link) }
    }

    data class Instance(
        val playerCondition: Optional<Holder<LootItemCondition>>,
        val into: Optional<LinkedInto>,
        val with: Optional<LinkedWith>,
        val carryingALinkingBook: Optional<Boolean>,
        val firstVisit: Optional<Boolean>,
    ) : SimpleCriterionTrigger.SimpleInstance {

        override fun player(): Optional<Holder<LootItemCondition>> = playerCondition

        fun matches(link: Link): Boolean {
            val wentThere = into.map { it == link.into }.orElse(true)
            val wentThatWay = with.map { it == link.with }.orElse(true)
            val carriedWhatWasAsked = carryingALinkingBook.map { it == link.carriesALinkingBook }.orElse(true)
            val isAsFirst = firstVisit.map { it == link.firstVisit }.orElse(true)
            return wentThere && wentThatWay && carriedWhatWasAsked && isAsFirst
        }

        companion object {
            val CODEC: Codec<Instance> = RecordCodecBuilder.create { instance ->
                instance.group(
                    LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::playerCondition),
                    LinkedInto.CODEC.optionalFieldOf("into").forGetter(Instance::into),
                    LinkedWith.CODEC.optionalFieldOf("with").forGetter(Instance::with),
                    Codec.BOOL.optionalFieldOf("carrying_a_linking_book").forGetter(Instance::carryingALinkingBook),
                    Codec.BOOL.optionalFieldOf("first_visit").forGetter(Instance::firstVisit),
                ).apply(instance, ::Instance)
            }
        }
    }
}
