package co.voik.agesandtheart.age.word

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.random.Random

/**
 * A pool of offers an Age takes some of, and how many — what makes one scorched Age differ from the last
 * without letting it stop being scorched (design §4.4).
 *
 * **An offer is one or more settings, taken together or not at all.** A halo is a bow's colours, its size
 * and how much rain it wants said at once; drawn one at a time it comes out as an ordinary bow that
 * happens to be white, which is not the thing anybody meant. A single setting is the ordinary case and is
 * an offer of one, so nothing about the common shape changed.
 *
 * **A group counts as one thing drawn**, however many settings it holds. That is what makes it a group:
 * `draws 1 of 2` between a three-setting halo and a lone glow is a coin toss between two ideas, not
 * between four values.
 *
 * **There may be several pools, and none of them has a name.** One pool per word could only ever say
 * "three of these five", which is variety by accident: an inferno drawing three of its suns and its sky
 * could roll all three sun facets and no sky at all. Two pools, one per thing being varied, say "a sun
 * facet or two, and the sky" — the same variety made deliberate.
 *
 * A name was the obvious way to tell them apart and is deliberately not here: every name anyone invented
 * for these — `look`, `overhead` — was a word the codebase did not already have. So they are a list, and
 * the draw is salted by position.
 */
data class Facets(val offers: List<Map<String, String>>, val draws: Draws) {

    /**
     * Every setting this pool could ever make, whatever it draws — the capability question.
     *
     * Flattened, because nearly everything that asks wants to know which parameters the pool touches
     * rather than how they are bundled. What the *draw* works on is [offers].
     */
    val facets: Map<String, String>
        get() = offers.fold(emptyMap()) { standing, offer -> standing + offer }

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

    /** The settings this Age takes, drawn with [random] — the whole pool where [draws] reaches its size. */
    fun drawnWith(random: Random): Map<String, String> {
        val many = draws.at(random)
        if (many <= 0) return emptyMap()
        val taken = if (many >= offers.size) {
            offers
        } else {
            // Sorted first so the list's own order cannot reach the answer, then shuffled. An earlier
            // version sorted by a hash and drew the *same* facets every time: the draw only moves low
            // bits, and the keys' hashes differ by far more than that, so nothing ever reordered.
            offers.indices.sortedBy { offers[it].keys.sorted().joinToString() }.shuffled(random)
                .take(many).map(offers::get)
        }
        return taken.fold(emptyMap()) { standing, offer -> standing + offer }
    }

    companion object {
        /** What separates the aspect a parameter is meant for from the parameter — `Word`'s own spelling. */
        private const val QUALIFIER = '.'

        private val ONE_EACH: Codec<Map<String, String>> = Codec.unboundedMap(Codec.STRING, Codec.STRING)

        /**
         * **A map where every setting stands alone, a list of maps where some of them go together.**
         *
         * Written back the same way round, so a pool of ordinary facets still reads as the object it
         * always was and only one holding a group spells its groups out. The common case should not pay
         * for the one that needed more room.
         */
        private val OFFERS: Codec<List<Map<String, String>>> =
            Codec.either(ONE_EACH, ONE_EACH.listOf()).xmap(
                { either ->
                    either.map(
                        { flat -> flat.entries.map { mapOf(it.key to it.value) } },
                        { grouped -> grouped },
                    )
                },
                { offers ->
                    if (offers.all { it.size <= 1 }) {
                        Either.left(offers.fold(emptyMap()) { standing, offer -> standing + offer })
                    } else {
                        Either.right(offers)
                    }
                },
            )

        val CODEC: Codec<Facets> = RecordCodecBuilder.create { instance ->
            instance.group(
                // **Required, unlike the count it replaced.** `pool` with no `draws` was a pool the Age
                // never took, which read as three facets in the file and was three facets nothing would
                // ever apply. A pool has to say how much of itself it is.
                Draws.CODEC.fieldOf("draws").forGetter(Facets::draws),
                OFFERS.fieldOf("facets").forGetter(Facets::offers),
            ).apply(instance) { draws, offers -> Facets(offers, draws) }
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
