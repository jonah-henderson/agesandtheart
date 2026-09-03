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
    /**
     * Where a weight came from — **and so what changing it will do.**
     *
     * Four rather than three, because "authored" was covering two situations that undo differently: a
     * weight standing alone leaves nothing behind when it is cleared, and one standing *over* a rule lets
     * that rule back. A reader clearing the second and watching a number reappear is a reader who stops
     * trusting the screen, so the two have their own names.
     */
    enum class Source(val title: String) {
        /** An entry in `art/preset_tags/` where no rule granted the tag. Clearing it takes it off. */
        AUTHORED("authored"),

        /** An entry standing over a rule that granted it too. Clearing it lets the rule's weight back. */
        OVERRIDDEN("overridden"),

        /** A rule in `art/derivation/` made it, and no entry exists. Changing it writes one. */
        DERIVED("derived"),

        /**
         * A server said so, and nothing here can see it.
         *
         * Registry tags are bound by a running game, so every `by_tag` rule matches nothing offline —
         * `ore` is the case, and it read as a tag nothing carries. `--refresh` writes down what a server
         * answered and this is that, read back: the members are the snapshot's and the weight is what the
         * rules grant, which is the sum the server was doing.
         */
        REMEMBERED("from a server"),

        /** Derived, then taken back off by `drop` — so it is not carried, and the row says why. */
        DROPPED("dropped"),
    }

    data class Member(
        val aspect: Aspect,
        val preset: String,
        val weight: Double,
        val source: Source,
        /** What the derivation said before the overlay, where an authored weight is standing over one. */
        val under: Double? = null,
    )

    /**
     * Everything [aspect] could hold that is not already tagged [tag] — what adding one may choose from.
     *
     * A closed aspect offers the presets this pack wrote; an open one offers every id the corpus knows,
     * which is what its derived words choose. **Whether it carries anything at all is said separately**:
     * a member no rule and no line has ever described is one the Art cannot reach by any vague word, and
     * a first tag on one is a bigger act than another tag on something already described.
     */
    fun untaggedIn(aspect: Aspect, tag: String): List<Untagged> {
        val already = membersTagged(tag).filter { it.aspect == aspect }.map { it.preset }.toSet()
        val curated = corpus.vocabulary.candidatesFor(aspect).map { it.key }.toSet()
        val everything = if (!aspect.open) {
            aspect.authored.map { it.key }
        } else {
            corpus.vocabulary.derivedWords.mapNotNull { it.choiceIn(aspect)?.key }.distinct()
        }
        return (everything + curated).distinct().filterNot { it in already }
            .sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it }))
            .map { Untagged(it, carriesNothing = tagsOn(aspect, it).isEmpty()) }
    }

    /** A member that could be tagged, and whether anything — rule or line — has ever described it. */
    data class Untagged(val preset: String, val carriesNothing: Boolean)

    /** One tag, and everything about it a reader needs before touching a weight. */
    data class Fact(
        val tag: String,
        val members: Int,
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

    private var index: Map<String, List<Member>>? = null

    /** Read the tables again — what a write is followed by, and it costs nine small files. */
    fun reread() {
        overlay = readTheTables()
        index = null
    }

    private fun carriers(): Map<String, List<Member>> = index ?: build().also { index = it }

    /**
     * What a server said carries each tag only it can grant — the snapshot, read back as members.
     *
     * Which aspect a member belongs to is the snapshot's own answer: `reach` is keyed by aspect page, so
     * the tag names its aspects and the member is placed in whichever of them can hold it. The weight is
     * what the rules grant, since that is the arithmetic the server was doing and the part we have.
     */
    private fun remembered(): Map<String, List<Member>> {
        val snapshot = corpus.snapshot ?: return emptyMap()
        return snapshot.serverOnly.mapValues { (tag, members) ->
            val aspects = Aspect.entries.filter { snapshot.reachOf(it, tag) != null }
            members.mapNotNull { id ->
                val aspect = aspects.firstOrNull { it.presetFor(id) != null } ?: return@mapNotNull null
                Member(aspect, id, granted(aspect, tag), Source.REMEMBERED)
            }
        }.filterValues { it.isNotEmpty() }
    }

    /** The strongest weight any rule of [aspect] grants [tag] — what the server's own merge came to. */
    private fun granted(aspect: Aspect, tag: String): Double {
        val rules = corpus.vocabulary.derivation[aspect] ?: return 0.0
        return (rules.byTag.values + rules.byKind.values).mapNotNull { it[tag] }.maxOrNull() ?: 0.0
    }

    private fun build(): Map<String, List<Member>> = built().mapValues { (tag, here) ->
        // A member the snapshot named and this corpus also has an entry for is one row, not two: what is
        // written down here wins, exactly as it wins over a rule.
        val already = here.map { it.aspect to it.preset }.toSet()
        here + remembered()[tag].orEmpty().filterNot { (it.aspect to it.preset) in already }
    }.let { standing ->
        standing + remembered().filterKeys { it !in standing }
    }

    private fun built(): Map<String, List<Member>> = buildMap<String, MutableList<Member>> {
        for (aspect in Aspect.entries) {
            val authored = overlay[aspect].orEmpty()
            val here = derived[aspect].orEmpty()
            for (preset in corpus.vocabulary.candidatesFor(aspect)) {
                val entry = authored[preset.key]
                val under = here[preset.key].orEmpty()
                val merged = merged(entry, under)
                for ((tag, weight) in merged) {
                    val isAuthored = entry?.tags?.containsKey(tag) == true
                    val beneath = if (isAuthored) under[tag] else null
                    getOrPut(tag) { mutableListOf() } += Member(
                        aspect = aspect,
                        preset = preset.key,
                        weight = weight,
                        source = when {
                            !isAuthored -> Source.DERIVED
                            beneath == null -> Source.AUTHORED
                            else -> Source.OVERRIDDEN
                        },
                        under = beneath,
                    )
                }
                // A dropped tag is carried by nothing and is still the answer to "why is this not on the
                // list any more" — so it stays a row, at no weight.
                for (tag in entry?.dropped.orEmpty()) {
                    getOrPut(tag) { mutableListOf() } += Member(aspect, preset.key, 0.0, Source.DROPPED, under[tag])
                }
            }
        }
    }

    /** The derivation with the overlay laid over it — one merge, wherever the answer is wanted. */
    private fun merged(entry: TagFile.Authored?, under: Map<String, Double>): Map<String, Double> =
        if (entry?.replaces == true) entry.tags else (under - entry?.dropped.orEmpty()) + entry?.tags.orEmpty()

    /** Everything [preset] carries in [aspect], from either half — the answer to "is this tagged at all". */
    private fun tagsOn(aspect: Aspect, preset: String): Map<String, Double> =
        merged(overlay[aspect].orEmpty()[preset], derived[aspect].orEmpty()[preset].orEmpty())

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
                members = carrying.size,
                asked = mentions[tag] ?: 0,
                aspects = carrying.map { it.aspect }.distinct().sortedBy { it.ordinal },
                opposed = tag in opposed,
                onlyOnAServer = tag in serverOnly,
            )
        }
    }

    /** Everything carrying [tag], strongest first, with the dropped rows last where they belong. */
    /**
     * What carries [tag] — **grouped by where the weight came from**, or in one flat alphabetical run.
     *
     * Grouped is the tuning order: what somebody wrote by hand is what somebody has already thought
     * about, and reading it against the derived mass underneath is the pass. Alphabetical is the looking
     * order — you know the preset's name and want its row.
     */
    fun membersTagged(tag: String, grouped: Boolean = true): List<Member> {
        val everything = carriers()[tag].orEmpty()
        val byName = compareBy<Member>({ it.aspect.page }, { it.preset })
        return if (!grouped) everything.sortedWith(byName)
        else everything.sortedWith(compareBy<Member> { it.source.ordinal }.then(byName))
    }

    /** Which words mention [tag], so a rename or a retune can be read against what it would move. */
    fun askedBy(tag: String): List<String> =
        corpus.vocabulary.authoredWords
            .filter { tag in it.wanted || tag in it.unwanted || tag in it.leanedTags }
            .map { it.name }
            .sorted()
}
