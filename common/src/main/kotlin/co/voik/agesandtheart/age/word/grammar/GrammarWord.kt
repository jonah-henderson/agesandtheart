package co.voik.agesandtheart.age.word.grammar

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * A structure the language can express (design §4.5).
 *
 * **Mastery gates comprehension, never availability.** Holding or reading the page teaches the word
 * outright; what a writer acquires is knowing *how* to use it, and that comes from studying well-formed
 * books rather than from anything switching a production on. So a production has no gate of its own, and
 * every page a writer holds is a page they can lay.
 *
 * **Only load-bearing structures earn one.** A word inferable from position, or that does not alter
 * meaning, is not in the language at all — which is why `of`, `over` and `with` are absent.
 */
enum class Production(
    val key: String,
    /**
     * Fine inks this page costs, the way [co.voik.agesandtheart.age.word.Word.price] prices an ordinary word.
     * Two by default; the [NUCLEUS] is the floor at one. Literals rather than named constants because an
     * enum constructor cannot see its own companion.
     */
    val cost: Int = 2,
) : StringRepresentable {
    /**
     * `Age` — **what the whole book is about**, and the one page every book must have.
     *
     * Structure rather than content, which is why it lives here beside `and` rather than in the corpus: it
     * says nothing about the world, it gives the sentence a head. `beautiful` is not a book; `beautiful
     * Age` is.
     *
     * **Cheap, but never free.** Every page a writer lays costs ink, and the one page every book must have
     * is the floor rather than an exemption — a mandatory page that cost nothing would be a page the writer
     * never really spends, and the ink economy would quietly stop counting the commonest thing in the game.
     */
    NUCLEUS("nucleus", cost = 1),

    /**
     * `and` — **keep both, and keep them apart**. Needed only where it changes the meaning, which differs
     * by what the value claims (§3.2): a predicative pair unjoined contends and one is displaced, where a
     * populative pair unjoined already unions everywhere, so `and` is what divides them.
     */
    CONJUNCTION("conjunction"),

    /** `only` — this and nothing else. The pin that naming alone deliberately never does. */
    RESTRICTION("only"),

    /** `no` — anything but this. Already half-expressible beneath, since preference weights are signed. */
    EXCEPTION("except"),

    /**
     * `teeming villages` — **how much of the thing there is**, bound to the term that follows it.
     *
     * The first production that binds one page to one other page rather than joining peers, and §4.5's
     * third rung. What it modifies is a claim, so the rung travels with the value into the recipe rather
     * than becoming a word of its own in the world model.
     */
    QUANTIFICATION("quantifier"),

    /**
     * `slimes in mushroom_fields` — **where the term applies**, bound to the term before it.
     *
     * The second production to bind one page to one other page, and the one that gives a sentence two
     * levels without a second grammar: an aspect vanilla resolves *through the biome* can be spoken to per
     * biome, and §3.1 names exactly which those are — spawns, features, carvers and atmosphere. Anything
     * else would be circular (a landform cannot be scoped by the biomes its own climate places) or
     * meaningless (a world has one sky).
     *
     * Reuses the biome term page rather than minting a page per biome, which is what keeps it one word.
     */
    CONFINEMENT("in"),

    /**
     * `teeming cats everywhere` — **the term in every place, not only where it already lives** (Jonah,
     * 2026-10-01). Naming a plant or a creature asks for more of it where it grows; this widens it into
     * every biome. A siting like `in`, closing the clause, and spoken only to the parts of the world that
     * grow in places: features and spawns.
     */
    WIDENING("everywhere"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Production> = StringRepresentable.fromEnum(Production::values)
    }
}

/**
 * A word whose whole meaning is structural — `and`, `only`, `except`. Apart from
 * [co.voik.agesandtheart.age.word.Word] because they share nothing: an ordinary word carries claims about
 * the world, and a structural one carries none.
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
     * one production, but a writer needs a page per rung to say which — so the production is the capability
     * and this is the word. `Polarity` made the same crossing in the other direction (§4.3.1).
     */
    val rung: Double? = null,
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    override fun toString(): String = name

    companion object {
        fun mapCodec(id: Identifier): MapCodec<GrammarWord> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Production.CODEC.fieldOf("production").forGetter(GrammarWord::production),
                Codec.DOUBLE.optionalFieldOf("rung").forGetter { Optional.ofNullable(it.rung) },
            ).apply(instance) { production, rung -> GrammarWord(id, production, rung.orElse(null)) }
        }

    }
}
