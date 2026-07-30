package co.voik.agesandtheart.age.word.grammar

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable

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
data class GrammarWord(val id: ResourceLocation, val production: Production) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    override fun toString(): String = name

    companion object {
        fun mapCodec(id: ResourceLocation): MapCodec<GrammarWord> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Production.CODEC.fieldOf("production").forGetter(GrammarWord::production),
            ).apply(instance) { production -> GrammarWord(id, production) }
        }
    }
}
