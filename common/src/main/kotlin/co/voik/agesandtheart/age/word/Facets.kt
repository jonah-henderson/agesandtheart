package co.voik.agesandtheart.age.word

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.random.Random

/**
 * A group of facets an Age takes some of, and how many — what makes one scorched Age differ from the last
 * without letting it stop being scorched (design §4.4).
 *
 * **There may be several, and none of them has a name.** One pool per word could only ever say "three of
 * these five", which is variety by accident: an inferno drawing three of its suns and its sky could roll
 * all three sun facets and no sky at all. Two pools, one per thing being varied, say "a sun facet or two,
 * and the sky" — the same variety made deliberate.
 *
 * A name was the obvious way to tell them apart and is deliberately not here: every name anyone invented
 * for these — `look`, `overhead` — was a word the codebase did not already have, where what a pool is
 * about is already spelled in the parameters it holds. So they are a list, [said] reads the label off the
 * facets, and the draw is salted by position.
 */
data class Facets(val facets: Map<String, String>, val draws: Draws) {

    /**
     * What this pool is about, **read off its facets rather than declared** — the aspect they all qualify
     * where they qualify one, and their own names where they do not.
     *
     * `sun.cast`, `sun.colour` and `sun.size` are the sun; `humidity`, `colour` and `haze` are not any one
     * thing and say so by listing themselves.
     */
    val said: String
        get() {
            val named = facets.keys.map { it.substringBefore(QUALIFIER, "") }
            val shared = named.distinct().singleOrNull()
            return if (shared.isNullOrEmpty()) facets.keys.sorted().joinToString(" ") else shared
        }

    /** The facets this Age takes, drawn with [random] — the whole pool where [draws] reaches its size. */
    fun drawnWith(random: Random): Map<String, String> {
        val many = draws.at(random)
        if (many <= 0) return emptyMap()
        if (many >= facets.size) return facets
        // Sorted first so the map's own iteration order cannot reach the answer, then shuffled. An earlier
        // version sorted by a hash and drew the *same* facets every time: the draw only moves low bits,
        // and the keys' hashes differ by far more than that, so nothing ever reordered.
        return facets.keys.sorted().shuffled(random).take(many).associateWith(facets::getValue)
    }

    companion object {
        /** What separates the aspect a parameter is meant for from the parameter — `Word`'s own spelling. */
        private const val QUALIFIER = '.'

        val CODEC: Codec<Facets> = RecordCodecBuilder.create { instance ->
            instance.group(
                // **Required, unlike the count it replaced.** `pool` with no `draws` was a pool the Age
                // never took, which read as three facets in the file and was three facets nothing would
                // ever apply. A pool has to say how much of itself it is.
                Draws.CODEC.fieldOf("draws").forGetter(Facets::draws),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("facets").forGetter(Facets::facets),
            ).apply(instance) { draws, facets -> Facets(facets, draws) }
        }
    }
}

/**
 * How many of a [Facets] an Age takes — **every count it might be, one entry per chance.**
 *
 * Three spellings, and one rule behind them: everything listed is an option and one is taken at random.
 * `2` is always two. `1..3` is one, two or three. `1|2|2|3` is the same three with two twice as likely,
 * which is how a writer weights a count without a distribution needing a name — and it is the `|` the
 * parameter values already spell alternatives with, so it is not a second thing to learn.
 *
 * A range may start at zero, which is a pool that sometimes does nothing at all; a pool that *never* does
 * anything is refused at load, being a claim written down and never read.
 */
data class Draws(val spelled: String) {

    /** Every count this may take, one entry per chance — a repeat is a heavier chance, not a duplicate. */
    val options: List<Int> by lazy { read(spelled).orEmpty() }

    val least: Int get() = options.minOrNull() ?: 0
    val most: Int get() = options.maxOrNull() ?: 0

    /** One of them, and the same one every time an Age is rebuilt — see `Word.setsDrawnAt`. */
    fun at(random: Random): Int = options.getOrElse(random.nextInt(options.size.coerceAtLeast(1))) { 0 }

    override fun toString(): String = spelled

    companion object {
        private const val ALTERNATIVE = '|'
        private const val THROUGH = ".."

        /** Exactly this many, for the code that builds a pool rather than reading one. */
        fun of(many: Int) = Draws(many.toString())

        /**
         * The counts [spelled] offers, or null where it offers none — a bad spelling is a word that fails
         * to load and says so, rather than a pool that quietly never fires.
         */
        fun read(spelled: String): List<Int>? {
            val options = spelled.split(ALTERNATIVE).map(String::trim).filter(String::isNotEmpty)
            if (options.isEmpty()) return null
            return options.flatMap { one -> countsIn(one) ?: return null }.ifEmpty { null }
        }

        private fun countsIn(one: String): List<Int>? {
            if (!one.contains(THROUGH)) return one.toIntOrNull()?.takeIf { it >= 0 }?.let(::listOf)
            val least = one.substringBefore(THROUGH).trim().toIntOrNull() ?: return null
            val most = one.substringAfter(THROUGH).trim().toIntOrNull() ?: return null
            if (least < 0 || most < least) return null
            return (least..most).toList()
        }

        val CODEC: Codec<Draws> = Codec.STRING.comapFlatMap(
            { spelled ->
                val counts = read(spelled)
                when {
                    counts == null -> DataResult.error {
                        "'$spelled' is no count — write a number, a range like 1..3, or 1|2|2 to weight one"
                    }
                    counts.max() == 0 -> DataResult.error { "'$spelled' never draws anything" }
                    else -> DataResult.success(Draws(spelled))
                }
            },
            Draws::spelled,
        )
    }
}
