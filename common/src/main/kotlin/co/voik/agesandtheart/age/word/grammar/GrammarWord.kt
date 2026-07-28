package co.voik.agesandtheart.age.word.grammar

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable

/**
 * A structure the language can express — and the unit mastery unlocks (design §4.5).
 *
 * Productions are the third leg of the trifecta, and unlocking one is a genuine expansion of what can be
 * *said* rather than a stat bump. [available] is the wire for that: everything is on today, and the point of
 * declaring it now is that gating later costs a data change rather than a mechanism.
 *
 * **Only load-bearing structures earn one** (Jonah's rule). A word that can be inferred from position, or
 * that does not alter meaning, is not in the language at all — which is why `of`, `over` and `with` are
 * absent: position already says which subject a modifier belongs to.
 */
enum class Production(val key: String, val available: Boolean = true) : StringRepresentable {
    /**
     * `and` — **keep both, and keep them apart**, one step further apart than juxtaposition manages.
     *
     * Needed only where it changes the meaning, which differs by what the value claims (§3.2): a
     * *predicative* pair unjoined contends and one is displaced, where joined they mingle or take a
     * territory each; a *populative* pair unjoined already unions everywhere, so `and` is what divides them.
     */
    CONJUNCTION("conjunction"),

    /**
     * `only` — this and nothing else. The pin that naming alone deliberately never does.
     *
     * Parsed before the resolver can act on it, which is fine while this is being built; [available] is
     * what will gate it for *players*, once there is a skill tree to gate against.
     */
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
 * A word whose whole meaning is structural — `and`, `only`, `except`.
 *
 * Kept apart from [co.voik.agesandtheart.age.word.Word] rather than folded into it, because they have
 * nothing in common: an ordinary word carries a precision tier and a query over tag space, and a structural
 * one carries neither. Sharing a type would mean every field of each being meaningless to the other.
 *
 * Datapack content like the rest of the vocabulary (`data/<namespace>/art/grammar/<name>.json`), so a pack
 * may rename or translate the joining word without touching code, while the *productions* stay ours.
 *
 * These are the pages §4.5 says are found **only inside generated Descriptive Books** — you cannot acquire
 * the ability to join two ideas without holding a book that joins them.
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
