package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * One way a word can be read: what it offers the words after it, what it accepts from those before, whether it ends a
 * phrase, and what it means on its own. Most words have one; `beds` has two, and its neighbours pick.
 *
 * ```json
 * { "terminal": true, "part": "structures", "kind": "structure",
 *   "means": [ { "chooses": { "structures": "minecraft:villages" } } ] }
 * ```
 */
data class Sense(
    /** Whether this ends a phrase, as written: a thing, turning what reached it into effects. Null takes its kind's. */
    val terminal: Boolean? = null,
    /** The part of the world this speaks for, as written; null takes its kind's. */
    val part: Aspect? = null,
    /** What it means where it ends a phrase, beside what it accepted. */
    val means: List<Effect> = emptyList(),
    val offers: List<Offer> = emptyList(),
    /** Its own acceptances, which win over its kind's for the same concept. */
    val accepts: List<Acceptance> = emptyList(),
    val kind: Identifier? = null,
    /** The structure word's code this sense runs as, where it is one: `and`, `of`, `in`. */
    val behaviour: Identifier? = null,
    /** Whether it takes the whole page of whatever it takes something of, leaving none of it to travel on. */
    val consumesWholePage: Boolean = false,
    /** Whether this is the sense read where its neighbours fit two. */
    val isDefault: Boolean = false,
    /** The kind [kind] names, once loaded. Never written. */
    val inherited: Kind? = null,
) {
    /** Whether this ends a phrase, its kind's answer where it gives none. */
    val endsAPhrase: Boolean get() = terminal ?: inherited?.terminal ?: false

    /** The part this speaks for, its kind's where it names none. */
    val speaksFor: Aspect? get() = part ?: inherited?.part

    /** Every acceptance it has: its own, then its kind's for any concept it does not accept itself. */
    val acceptances: List<Acceptance>
        get() {
            val own = accepts.map(Acceptance::concept).toSet()
            return accepts + inherited?.accepts.orEmpty().filter { it.concept !in own }
        }

    /** How it accepts [concept], or null where it does not. */
    fun acceptanceOf(concept: Identifier): Acceptance? = acceptances.firstOrNull { it.concept == concept }

    fun withKind(kind: Kind): Sense = copy(inherited = kind)

    companion object {
        /** An own meaning is fixed: there is no offer for it to take a value from. */
        private val MEANS: Codec<List<Effect>> = EffectTemplate.LIST_CODEC.flatXmap(
            { templates ->
                val fixed = templates.filterIsInstance<EffectTemplate.Fixed>()
                if (fixed.size == templates.size) DataResult.success(fixed.map(EffectTemplate.Fixed::effect))
                else DataResult.error { "what a sense means on its own takes no value: it has no offer to take one from" }
            },
            { effects -> DataResult.success(effects.map(EffectTemplate::Fixed)) },
        )

        val MAP_CODEC: MapCodec<Sense> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.BOOL.optionalFieldOf("terminal").forGetter { Optional.ofNullable(it.terminal) },
                Parts.CODEC.optionalFieldOf("part").forGetter { Optional.ofNullable(it.part) },
                MEANS.optionalFieldOf("means", emptyList()).forGetter(Sense::means),
                Offer.CODEC.listOf().optionalFieldOf("offers", emptyList()).forGetter(Sense::offers),
                Acceptance.CODEC.listOf().optionalFieldOf("accepts", emptyList()).forGetter(Sense::accepts),
                PackNames.CODEC.optionalFieldOf("kind").forGetter { Optional.ofNullable(it.kind) },
                PackNames.CODEC.optionalFieldOf("behaviour").forGetter { Optional.ofNullable(it.behaviour) },
                Codec.BOOL.optionalFieldOf("consumes_whole_page", false).forGetter(Sense::consumesWholePage),
                Codec.BOOL.optionalFieldOf("default", false).forGetter(Sense::isDefault),
            ).apply(instance) { terminal, part, means, offers, accepts, kind, behaviour, consumesWholePage, isDefault ->
                Sense(
                    terminal.orElse(null), part.orElse(null), means, offers, accepts, kind.orElse(null), behaviour.orElse(null),
                    consumesWholePage, isDefault,
                )
            }
        }
    }
}

/**
 * A template senses refer to, so a creature's acceptances are written once for every creature: what it accepts, and
 * whether a sense of it ends a phrase and which part it speaks for, where the sense does not say. One file per kind
 * under `art/kind/`.
 */
data class Kind(
    val id: Identifier,
    val accepts: List<Acceptance> = emptyList(),
    val terminal: Boolean? = null,
    val part: Aspect? = null,
) {
    companion object {
        fun mapCodec(id: Identifier): MapCodec<Kind> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Acceptance.CODEC.listOf().optionalFieldOf("accepts", emptyList()).forGetter(Kind::accepts),
                Codec.BOOL.optionalFieldOf("terminal").forGetter { Optional.ofNullable(it.terminal) },
                Parts.CODEC.optionalFieldOf("part").forGetter { Optional.ofNullable(it.part) },
            ).apply(instance) { accepts, terminal, part -> Kind(id, accepts, terminal.orElse(null), part.orElse(null)) }
        }
    }
}

/** A part of the world as a file names it: by its page. */
internal object Parts {
    val CODEC: Codec<Aspect> = Codec.STRING.comapFlatMap(
        { page -> Aspect.byPage(page)?.let { DataResult.success(it) } ?: DataResult.error { "'$page' is no part of the world" } },
        Aspect::page,
    )
}
