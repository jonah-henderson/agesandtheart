package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Density
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * A structure the language can express — and the unit mastery unlocks (design §4.5). [available] is the
 * wire for that gating; everything is on today.
 *
 * **Only load-bearing structures earn one.** A word inferable from position, or that does not alter
 * meaning, is not in the language at all — which is why `of`, `over` and `with` are absent.
 */
enum class Production(val key: String, val available: Boolean = true) : StringRepresentable {
    /**
     * `and` — **keep both, and keep them apart**. Needed only where it changes the meaning, which differs
     * by what the value claims (§3.2): a predicative pair unjoined contends and one is displaced, where a
     * populative pair unjoined already unions everywhere, so `and` is what divides them.
     */
    CONJUNCTION("conjunction"),

    /** `only` — this and nothing else. The pin that naming alone deliberately never does. */
    RESTRICTION("only"),

    /** `except` — anything but this. Already half-expressible beneath, since preference weights are signed. */
    EXCEPTION("except"),

    /**
     * `teeming villages` — **how much of the thing there is**, bound to the term that follows it.
     *
     * The first production that binds one page to one other page rather than joining peers, and §4.5's
     * third rung. What it modifies is a claim, so the rung travels with the value into the recipe rather
     * than becoming a word of its own in the world model.
     */
    QUANTIFICATION("quantifier"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Production> = StringRepresentable.fromEnum(Production::values)
    }
}

/**
 * A word whose whole meaning is structural — `and`, `only`, `except`. Apart from
 * [co.voik.agesandtheart.age.word.Word] because they share nothing: an ordinary word carries a precision
 * tier and a tag query, and a structural one carries neither.
 *
 * Datapack content (`data/<namespace>/art/grammar/<name>.json`), so a pack may rename or translate the
 * joining word while the *productions* stay ours.
 */
data class GrammarWord(
    val id: Identifier,
    val production: Production,
    /**
     * Which rung a [Production.QUANTIFICATION] page names, and null for every other production.
     *
     * The exception to "a structural word carries no value", and it earns it: the *ability* to quantify is
     * one production, but a writer needs a page per rung to say which — so the production is the unlock and
     * this is the word. `Polarity` made the same crossing in the other direction (§4.3.1).
     */
    val rung: Density? = null,
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    override fun toString(): String = name

    companion object {
        fun mapCodec(id: Identifier): MapCodec<GrammarWord> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Production.CODEC.fieldOf("production").forGetter(GrammarWord::production),
                DENSITY_CODEC.optionalFieldOf("rung").forGetter { Optional.ofNullable(it.rung) },
            ).apply(instance) { production, rung -> GrammarWord(id, production, rung.orElse(null)) }
        }

        private val DENSITY_CODEC: Codec<Density> =
            Codec.STRING.comapFlatMap(
                { key ->
                    val rung = Density.named(key)
                    if (rung == null) DataResult.error { "no rung called '$key'" } else DataResult.success(rung)
                },
                Density::key,
            )
    }
}
