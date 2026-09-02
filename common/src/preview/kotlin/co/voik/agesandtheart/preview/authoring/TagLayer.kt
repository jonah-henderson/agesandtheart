package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.DerivedTags

/**
 * What carries each tag, and **where that came from**.
 *
 * A preset's tags are derived from what the game already states and then an authored table is laid over
 * the top, and by the time `Vocabulary` has them the two are one map. That is right for resolving and
 * wrong for editing: setting a weight on a derived tag *writes* a new entry, clearing one lets the
 * derived value come back, and taking a derived tag off is `drop` rather than deletion. Somebody who
 * cannot see which is which will delete a tag, watch it return, and stop trusting the screen.
 *
 * So the two halves are held apart and merged here — [DerivedTags.read] and `PresetProfile.over` being
 * the same calls `Vocabulary.load` makes, so the answer is the corpus's own and not a second opinion.
 * Merging here rather than reloading is also what makes a weight edit instant: the derivation is the
 * expensive half and nothing an authored table says can change it.
 */
class TagLayer(private val corpus: Corpus) {

    /** Where a carrier's weight came from, which decides what changing it writes. */
    enum class Source(val title: String) {
        /** An entry in `art/preset_tags/`. Editing it edits that line. */
        AUTHORED("authored"),

        /** A rule in `art/derivation/` made it, and no entry exists. Editing it writes one. */
        DERIVED("derived"),

        /** Derived, then taken back off by `drop` — so it is not carried, and the row says why. */
        DROPPED("dropped"),
    }

    data class Carrier(
        val aspect: Aspect,
        val preset: String,
        val weight: Double,
        val source: Source,
        /** What the derivation said before the overlay, where an authored weight is standing over one. */
        val under: Double? = null,
    )

    /** One tag, and everything about it a reader needs before touching a weight. */
    data class Fact(
        val tag: String,
        val carriers: Int,
        /** How many words mention it, either way round — a tag nothing asks for is inert. */
        val asked: Int,
        val aspects: List<Aspect>,
        /** Whether the antonym table can oppose it to anything. */
        val opposed: Boolean,
        /** Whether it exists only where a real server binds registry tags — offline this reads as none. */
        val onlyOnAServer: Boolean,
    )

    /**
     * The derived layer on its own, with nothing authored over it.
     *
     * **Offline this is the fact-derived half only.** Registry tags are bound by a running game, so the
     * `by_tag` rules match nothing here and the `by_kind` ones carry the whole answer — which is a fact
     * about the harness rather than about anyone's world (`notes/the-tag-layer.md` §4).
     */
    private val derived: Map<Aspect, Map<String, Map<String, Double>>> by lazy {
        DerivedTags.read(MinecraftRegistries.worldgen, corpus.vocabulary.derivation, mutableListOf())
    }

    /** The authored tables, re-read after every write so the screen shows what the files say. */
    private var overlay: Map<Aspect, Map<String, TagFile.Authored>> = readTheTables()

    private fun readTheTables() = Aspect.entries.associateWith { TagFile.authored(it.page) }

    private var index: Map<String, List<Carrier>>? = null

    /** Read the tables again — what a write is followed by, and it costs nine small files. */
    fun reread() {
        overlay = readTheTables()
        index = null
    }

    private fun carriers(): Map<String, List<Carrier>> = index ?: build().also { index = it }

    private fun build(): Map<String, List<Carrier>> = buildMap<String, MutableList<Carrier>> {
        for (aspect in Aspect.entries) {
            val authored = overlay[aspect].orEmpty()
            val here = derived[aspect].orEmpty()
            for (preset in corpus.vocabulary.candidatesFor(aspect)) {
                val entry = authored[preset.key]
                val under = here[preset.key].orEmpty()
                val merged = if (entry?.replaces == true) {
                    entry.tags
                } else {
                    (under - entry?.dropped.orEmpty()) + entry?.tags.orEmpty()
                }
                for ((tag, weight) in merged) {
                    val isAuthored = entry?.tags?.containsKey(tag) == true
                    getOrPut(tag) { mutableListOf() } += Carrier(
                        aspect = aspect,
                        preset = preset.key,
                        weight = weight,
                        source = if (isAuthored) Source.AUTHORED else Source.DERIVED,
                        under = if (isAuthored) under[tag] else null,
                    )
                }
                // A dropped tag is carried by nothing and is still the answer to "why is this not on the
                // list any more" — so it stays a row, at no weight.
                for (tag in entry?.dropped.orEmpty()) {
                    getOrPut(tag) { mutableListOf() } += Carrier(aspect, preset.key, 0.0, Source.DROPPED, under[tag])
                }
            }
        }
    }

    /** How many words mention each tag, wanted, pushed against, or merely offered. */
    private val mentions: Map<String, Int> by lazy {
        corpus.vocabulary.words
            .flatMap { (it.wanted + it.unwanted + it.leanedTags).distinct() }
            .groupingBy { it }
            .eachCount()
    }

    /**
     * Every tag there is — **carried, asked for, or opposed.**
     *
     * All three, because the two interesting failures are opposite: a tag the world carries and no word
     * asks for is a distinction the language cannot make, and a tag a word asks for that nothing carries
     * is a word that finds nothing. Listing only the carried half would hide the second entirely.
     */
    fun facts(): List<Fact> {
        val opposed = corpus.vocabulary.antonyms.flatMap { listOf(it.first, it.second) }.toSet()
        val serverOnly = corpus.vocabulary.tagsOnlyAServerGrants
        val everything = carriers()
        return (everything.keys + mentions.keys + opposed).sorted().map { tag ->
            val carrying = everything[tag].orEmpty().filterNot { it.source == Source.DROPPED }
            Fact(
                tag = tag,
                carriers = carrying.size,
                asked = mentions[tag] ?: 0,
                aspects = carrying.map { it.aspect }.distinct().sortedBy { it.ordinal },
                opposed = tag in opposed,
                onlyOnAServer = tag in serverOnly,
            )
        }
    }

    /** Everything carrying [tag], strongest first, with the dropped rows last where they belong. */
    fun carriersOf(tag: String): List<Carrier> =
        carriers()[tag].orEmpty().sortedWith(
            compareBy<Carrier> { it.source == Source.DROPPED }
                .thenByDescending { it.weight }
                .thenBy { it.aspect.ordinal }
                .thenBy { it.preset },
        )

    /** Which words mention [tag], so a rename or a retune can be read against what it would move. */
    fun askedBy(tag: String): List<String> =
        corpus.vocabulary.authoredWords
            .filter { tag in it.wanted || tag in it.unwanted || tag in it.leanedTags }
            .map { it.name }
            .sorted()
}
