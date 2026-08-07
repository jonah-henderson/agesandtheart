package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable

/**
 * **A part of the world as a writer names it** — the aiming pages, and the whole of the player-facing
 * division of the Art (design §4.3.1).
 *
 * Deliberately *not* [Aspect]. An aspect is what the generator is built out of: `terrain` holds the shape
 * of the rock, `climate` the two axes biomes are looked up on, `atmosphere` what the air does. Those are
 * engineering, they are numerous, and several of them are indistinguishable to anybody who has not read
 * the source — a writer does not know or care that rainfall and humidity live in different objects.
 *
 * A domain is what a writer aims at, and it may cover several aspects: `atmosphere` is the air, all of it,
 * however many aspects the air is made of underneath. **Which aspects a domain covers is datapack content**
 * (`art/domain/<name>.json`), so the two layers can be redrawn independently — the whole reason the parser
 * stopped naming aspects at all.
 *
 * The name of the file is the page a writer lays. Nothing else declares an aiming page: a domain *is* one,
 * which is why there is no `art/word/atmosphere.json` beside this and never should be — two files claiming
 * to define one page is exactly the drift this separation exists to prevent.
 */
data class Domain(
    /** Where it was defined. Its path is the page a writer lays: `agesandtheart:firmament` → "firmament". */
    val id: Identifier,
    /** The parts of the generator this page opens. Order is immaterial; nothing reads them in sequence. */
    val aspects: Set<Aspect>,
) {
    /** What a writer says to aim at it. */
    val name: String get() = id.path

    /**
     * The aiming page this domain *is*.
     *
     * Synthesised rather than authored beside it, so a domain and its page cannot disagree about which
     * parts of the world the page opens. It carries no query, names no preset and sets no parameter, which
     * is exactly what makes it an aiming page by shape ([Word.aims]) rather than by a flag.
     */
    val page: Word get() = Word(id = id, tier = Tier.RESTRICTIVE, aspects = aspects, query = emptyMap())

    companion object {
        /** Where a pack puts them. */
        const val DIRECTORY = "art/domain"

        private val ASPECT_CODEC: Codec<Aspect> = StringRepresentable.fromEnum(Aspect::values)

        fun codec(id: Identifier): Codec<Domain> = RecordCodecBuilder.create { instance ->
            instance.group(
                ASPECT_CODEC.listOf().fieldOf("aspects").forGetter { it.aspects.toList() },
            ).apply(instance) { aspects -> Domain(id, aspects.toSet()) }
        }
    }
}
