package co.voik.agesandtheart.advancement

import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.generation.Ages
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.advancements.triggers.SimpleCriterionTrigger
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/** A rare material an Age may hold, as the advancements name them. */
enum class AgeHolding(private val key: String) : StringRepresentable {
    RIME("rime_crystal"),
    TEMPERSTONE("temperstone"),
    ASTRITE("astrite"),
    ARC_CRYSTAL("arc_crystal"),
    GLOOMGRIT("gloomgrit"),
    DERETHENI("deretheni"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AgeHolding> = StringRepresentable.fromEnum(AgeHolding::values)

        fun of(material: EarlyGameRareMaterial): AgeHolding = when (material) {
            EarlyGameRareMaterial.RIME -> RIME
            EarlyGameRareMaterial.TEMPERSTONE -> TEMPERSTONE
            EarlyGameRareMaterial.ARC_CRYSTAL -> ARC_CRYSTAL
            EarlyGameRareMaterial.GLOOMGRIT -> GLOOMGRIT
        }
    }
}

/** What an Age is found to be on arriving in it, read off its recipe as the geologist's survey is. */
data class Arrival(val holds: Set<AgeHolding>, val collapsing: Boolean) {
    companion object {
        /** The Age [level] is, or null where it is not an Age of ours. */
        fun at(level: ServerLevel): Arrival? {
            val recipe = Ages.recipeOf(level) ?: return null
            val spending = Spending.of(level.server, recipe)
            val composition = (recipe.world as? AgeWorld.Composed)?.composition
            val materials = composition
                ?.let { EarlyGameRareMaterials.grownIn(it, recipe.seed, spending) }
                .orEmpty()
                .map(AgeHolding::of)
            val meteoric = Happenings.befalls(level, Phenomenon.METEORS)
            val perilous = Danger.of(level.server, recipe).paysOut
            val holds = buildSet {
                addAll(materials)
                if (meteoric) add(AgeHolding.ASTRITE)
                if (perilous) add(AgeHolding.DERETHENI)
            }
            return Arrival(holds, collapsing = spending.bought(Manifestation.COLLAPSE) > 0)
        }
    }
}

/** Arriving in an Age, by any way in — one holding a rare material, or one coming apart. */
class EnteredAgeTrigger : SimpleCriterionTrigger<EnteredAgeTrigger.Instance>() {

    override fun codec(): Codec<Instance> = Instance.CODEC

    fun trigger(player: ServerPlayer, arrival: Arrival) {
        trigger(player) { it.matches(arrival) }
    }

    data class Instance(
        val playerCondition: Optional<Holder<LootItemCondition>>,
        val holding: Optional<AgeHolding>,
        val collapsing: Optional<Boolean>,
    ) : SimpleCriterionTrigger.SimpleInstance {

        override fun player(): Optional<Holder<LootItemCondition>> = playerCondition

        fun matches(arrival: Arrival): Boolean {
            val holdsIt = holding.map { it in arrival.holds }.orElse(true)
            val isAsFarGone = collapsing.map { it == arrival.collapsing }.orElse(true)
            return holdsIt && isAsFarGone
        }

        companion object {
            val CODEC: Codec<Instance> = RecordCodecBuilder.create { instance ->
                instance.group(
                    LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::playerCondition),
                    AgeHolding.CODEC.optionalFieldOf("holding").forGetter(Instance::holding),
                    Codec.BOOL.optionalFieldOf("collapsing").forGetter(Instance::collapsing),
                ).apply(instance, ::Instance)
            }
        }
    }
}
