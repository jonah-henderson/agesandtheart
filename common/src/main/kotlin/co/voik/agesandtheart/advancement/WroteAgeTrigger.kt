package co.voik.agesandtheart.advancement

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.advancements.triggers.SimpleCriterionTrigger
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/**
 * A book bound at the desk: the words it was written with, and what its landmass, surface and features are
 * made of — resolved only if an advancement asks.
 */
class WrittenAge(val words: List<Identifier>, composition: () -> AgeComposition?) {
    val madeOf: Set<String> by lazy { composition()?.let(::materialsOf).orEmpty() }

    private fun materialsOf(composition: AgeComposition): Set<String> {
        val rock = (0..<composition.membersIn(Aspect.TERRAIN).coerceAtLeast(1))
            .flatMap { composition.optionsFor(Aspect.TERRAIN, it).allOf(Terrain.STONE) }
        val skin = composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL)
        val features = composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES)
            .filter { it.polarity != Polarity.EXCEPT }
            .flatMap { it.substances }
        return (rock + skin + features).filter { it != Parameter.UNCHANGED }.toSet()
    }
}

/**
 * An Age written at the desk. `made_of` passes when its landmass, surface or features are made of any of
 * the blocks named; `naming` when the book was written with every one of the words named.
 */
class WroteAgeTrigger : SimpleCriterionTrigger<WroteAgeTrigger.Instance>() {

    override fun codec(): Codec<Instance> = Instance.CODEC

    fun trigger(player: ServerPlayer, written: WrittenAge) {
        trigger(player) { it.matches(written) }
    }

    data class Instance(
        val playerCondition: Optional<Holder<LootItemCondition>>,
        val madeOf: List<Identifier>,
        val naming: List<Identifier>,
    ) : SimpleCriterionTrigger.SimpleInstance {

        override fun player(): Optional<Holder<LootItemCondition>> = playerCondition

        fun matches(written: WrittenAge): Boolean {
            val namesEveryWord = written.words.containsAll(naming)
            val isMadeOfOne = madeOf.isEmpty() || madeOf.any { it.toString() in written.madeOf }
            return namesEveryWord && isMadeOfOne
        }

        companion object {
            val CODEC: Codec<Instance> = RecordCodecBuilder.create { instance ->
                instance.group(
                    LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::playerCondition),
                    Identifier.CODEC.listOf().optionalFieldOf("made_of", emptyList()).forGetter(Instance::madeOf),
                    Identifier.CODEC.listOf().optionalFieldOf("naming", emptyList()).forGetter(Instance::naming),
                ).apply(instance, ::Instance)
            }
        }
    }
}
