package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.word.Claims
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Pool
import co.voik.agesandtheart.age.word.Tier
import com.github.ajalt.mordant.rendering.TextStyle
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Verdict
import co.voik.agesandtheart.preview.authoring.WordFile

/**
 * The sections of the word screen, in the order the decisions fall: what it is called, how specific it
 * is, what it affects, what it does, then the tags, the comment and the rarity.
 *
 * Each is a list of rows. What a row means differs per section, so they are built here and the editor
 * only moves a cursor over them.
 */
enum class Part(
    val title: String,
    val about: String,
    /** A blank line before it and nothing else — the same grouping the tool's own menu uses. */
    val startsGroup: Boolean = false,
    val perilous: Boolean = false,
) {
    NAME("word", ""),
    TIER("specificity", ""),
    TEMPLATE("base dimension", ""),
    LISTING("rarity", ""),

    /**
     * Claims about a **property** the world has — a temperature, a colour, how large a sun is. Keyed by
     * the parameter they set.
     */
    PROPERTIES("properties", "settings an aspect offers, set to a value", startsGroup = true),

    /**
     * Claims about **which members** fill a part of the world — this landform, anything tagged cavernous,
     * more of that biome. Keyed by a member or a tag.
     *
     * The two sections are the world model's own division (§1): an aspect holds a value, or it holds
     * members, and a word speaks to one or the other. **Which claims are firm and which are loose cuts
     * across both** and so is never the split — a temperature is a band or a nudge, a member is a name or
     * a tag, and either way that is how precisely the word speaks rather than what it speaks about.
     */
    POPULATIONS("populations", "sets of entries such as biomes and spawns, which a word modifies"),

    /**
     * Everything the word does, said back in one place.
     *
     * It was `targets` and listed only where the word reaches, which is a fact about the word rather than
     * a thing you can act on — and the reach is derived, so there was nothing to do there at all. What a
     * writer wants before saving is the whole of it: where it lands, what it costs, what it sets, what it
     * does to each population, and what is wrong with it.
     */
    REVIEW("review", "", startsGroup = true),
    COMMENT("comment", ""),

    SAVE("save", "", startsGroup = true),
    SAVE_AND_LEAVE("save & exit", ""),

    /** Last, and marked, because it is the one thing here that cannot be undone. */
    DELETE("delete", "", perilous = true),
}

/**
 * **How hard a word claims a property** — whether it insists, or merely offers and gives way to the book.
 *
 * Only properties have this: a claim on a population cannot fail, or is a removal, and neither has
 * anything to yield (`Word.biases`). So it lives here and the population's own steps live in [Step].
 */
enum class Insistence(val required: Boolean, val title: String, val about: String) {
    REQUIRED(true, "required", "always applies, and overrides anything else"),
    REQUESTED(false, "requested", "applies only where the book said nothing"),
}

/**
 * **The pipeline a claim about a population goes through**, in order, and the headings the section shows.
 *
 * Start with the pool the aspect curates. [CHOOSE] ends it there — that member is the answer and nothing
 * is searched for. Otherwise [ADD] widens the pool and [REMOVE] narrows it, removals after additions so
 * word order decides nothing (§3.5); [KEEP] is the same step said by complement, and the one nearly every
 * narrowing word is written with. [BIAS] leans what survived and can never fail, which is why it comes
 * last and why it has no offered half of its own.
 */
enum class Step(val title: String, val about: String, val adds: String) {
    CHOOSE("choose", "this member and no other; nothing is searched for", "choose a member outright"),
    ADD("add", "puts a member into the pool that curation left out", "add a member to the pool"),
    KEEP("keep", "only members answering these tags survive", "keep only what carries a tag"),
    REMOVE("remove", "takes members out, by name or by a tag they carry", "remove a member or a tag"),
    BIAS("bias", "leans the draw between whatever is left; it never removes anything", "lean toward or away"),
}

/**
 * One line of a section: what it says, and the handle the editor acts on.
 *
 * [note] is what is shown beneath the list when this row has the cursor, and **may run to several lines**
 * — a parameter wants to say where it can be used, what it is, and what values it takes, and one line of that
 * is a sentence with the other two cut off.
 */
data class Row(val handle: String, val shown: List<Ink>, val note: String = "")

/**
 * What each section shows for a word.
 *
 * Read-only and pure — the editor asks this for a frame and asks [Verdict] for the strip beneath it, so
 * nothing on screen is worked out twice or in two places.
 */
class Parts(private val corpus: Corpus) {

    /**
     * Which of a parameter's targets the inline help is about, where it has more than one.
     *
     * The same name is a different parameter on different aspects — `size` on a landform is how big an island
     * is, on a sun how large the star is — so one of them is active and tab moves between them.
     */
    var helpAspect: Int = 0

    fun rowsOf(part: Part, candidate: Candidate, word: Word?): List<Row> = when (part) {
        Part.NAME -> nameRows(candidate)
        Part.TIER -> tierRows(candidate)
        Part.TEMPLATE -> templateRows(candidate)
        Part.PROPERTIES -> effectRows(candidate, word)
        Part.POPULATIONS -> pickRows(candidate, word)
        Part.REVIEW -> reviewRows(candidate, word)
        Part.COMMENT -> commentRows(candidate)
        Part.LISTING -> listingRows(candidate)
        Part.SAVE, Part.SAVE_AND_LEAVE -> doingRows(part, candidate)
        Part.DELETE -> deleteRows(candidate)
    }

    /** Whether this section holds a list you add to and delete from, which decides what `a` and `d` mean. */
    fun isAList(part: Part) = part in setOf(Part.PROPERTIES, Part.POPULATIONS)

    /** Whether the cursor may rest on this row at all — a heading names what is under it and does nothing. */
    fun isAHeading(row: Row) = row.handle.startsWith("heading/")

    // -- word and specificity ------------------------------------------------------------------------

    private fun nameRows(candidate: Candidate): List<Row> {
        val id = candidate.listingKey
        val called = WordFile.displayOf(id)
        return listOf(
            Row(
                handle = "name",
                shown = listOf(Ink("id       ", Palette.faint), Ink(candidate.name.ifBlank { "(unnamed)" }, Palette.value)),
                note = "the file name, and what a book is written in\n" +
                    (if (WordFile.exists(candidate.name)) "    a file already exists for this" else ""),
            ),
            Row(
                handle = "display",
                shown = listOf(
                    Ink("shown as ", Palette.faint),
                    Ink(called ?: WordFile.fallbackDisplay(id), if (called == null) Palette.faint else Palette.value),
                ),
                note = "what a player reads — on the page, the desk and the book\n" +
                    if (called == null) "    nothing set, so the id is opened out and title-cased" else "",
            ),
        )
    }

    private fun tierRows(candidate: Candidate) = Tier.entries.map { tier ->
        Row(
            handle = tier.key,
            shown = listOf(
                Ink(if (candidate.tier == tier) "${Glyph.FILLED} " else "${Glyph.HOLLOW} ", Palette.chosen),
                Ink(tier.key.padEnd(13), if (candidate.tier == tier) Palette.value else Palette.faint),
                Ink(whatATierMeans(tier), Palette.faint),
            ),
        )
    }

    private fun whatATierMeans(tier: Tier) = when (tier) {
        Tier.EVOCATIVE -> "broad effects applied to the whole Age"
        Tier.RESTRICTIVE -> "narrows parameters to certain bounds, e.g. warm temperatures"
        Tier.EXACT -> "names specific blocks, materials, mobs, structures, landforms"
    }

    // -- targets -------------------------------------------------------------------------------------

    /**
     * Where the word speaks — **read, never set.**
     *
     * A word used to declare this alongside deriving it, and the declaration's only real job was aiming a
     * bare `query`. Keying the query says the same thing in one place, so the declaration went and this
     * became what it always should have been: a reading of what the word's own claims add up to.
     */
    /**
     * **The whole word on one page**, in the order somebody checks it: what it costs, where it lands, what
     * it sets, what it does to each population, and why it exists.
     *
     * Read-only, and **empty sections are absent rather than stated**. What a word has not said is already
     * on the section list to its left as a blank mark, so saying it again here is a line spent telling a
     * writer something they can see, and the page is worth having only if scanning it is quick.
     */
    private fun reviewRows(candidate: Candidate, word: Word?): List<Row> {
        if (word == null) {
            return listOf(Row("none", listOf(Ink("this word will not load", Palette.refused))))
        }
        val listing = WordFile.listingFor(candidate.listingKey)
        return buildList {
            addAll(under("cost", costRows(candidate, word, listing)))
            addAll(under("aspects", landingRows(candidate, word)))
            addAll(under("properties", propertyReview(candidate)))
            addAll(under("populations", populationReview(candidate)))
            addAll(under("comment", candidate.commentLines.map { said(it) }))
            if (isEmpty()) add(Row("none", listOf(Ink("nothing said yet", Palette.faint))))
        }
    }

    /**
     * A heading and its rows, or nothing at all where there are none.
     *
     * The blank line before it carries a heading's handle so the cursor passes over it, which is the same
     * rule that keeps it off the heading itself.
     */
    private fun under(title: String, rows: List<Row>): List<Row> = if (rows.isEmpty()) emptyList() else listOf(
        Row("heading/space/$title", emptyList()),
        Row("heading/$title", listOf(Ink(title, Palette.heading))),
    ) + rows

    /** A read-only line: a label, its value, and whatever is worth saying about it after. */
    private fun told(label: String, value: String, after: String = "", tone: TextStyle = Palette.value) = Row(
        handle = "told/$label/$value",
        shown = listOf(
            Ink("    "),
            Ink(label.padEnd(REVIEW_LABEL), Palette.faint),
            Ink(value.padEnd(REVIEW_VALUE), tone),
            Ink(after, Palette.faint),
        ),
    )

    private fun said(line: String) = Row("said", listOf(Ink("    "), Ink(line, Palette.faint)))

    private fun costRows(candidate: Candidate, word: Word, listing: WordFile.Listing) = buildList {
        add(told("ink", "${word.price}", "${word.tier.key} × ${word.versatility} part(s) of the world"))
        listing.rarity?.let { add(told("rarity", it, "how hard it is to find")) }
        inkOf(candidate)?.let { add(told("ink quality", it, "what it takes to write")) }
        candidate.template?.let { add(told("base dimension", it, "the world a book starts from")) }
    }

    private fun landingRows(candidate: Candidate, word: Word) =
        word.aspects.sortedBy { it.ordinal }.map { aspect ->
            told(aspect.page, whatItHolds(aspect), whyItReaches(aspect, candidate).lines().first().trim())
        }

    private fun propertyReview(candidate: Candidate) = Insistence.entries.flatMap { insistence ->
        val always = candidate.settingOn(insistence).entries.sortedBy { it.key }
            .map { (parameter, value) -> told(parameter, value, insistence.title) }
        val pools = candidate.poolsOn(insistence).map { pool ->
            told(
                pool.said,
                "${pool.draws} of ${pool.facets.size}",
                "${insistence.title} pool ${Glyph.BULLET} ${pool.facets.keys.sorted().joinToString(" ")}",
            )
        }
        always + pools
    }

    /** One line per part of the world a claim about a population lands in, saying every step it takes. */
    private fun populationReview(candidate: Candidate): List<Row> {
        val everywhere = candidate.leansEverywhere.entries.sortedByDescending { it.value }
            .map { (named, weight) -> told(Word.EVERYWHERE, "leans $named", "%+.2f".format(weight)) }
        val keyed = Aspect.entries.sortedBy { it.ordinal }.flatMap { aspect ->
            buildList {
                candidate.chooses[aspect]?.let { add(told(aspect.page, "chooses $it", "nothing is searched for")) }
                candidate.admits[aspect]?.sorted()?.forEach { add(told(aspect.page, "adds $it", "into the pool")) }
                candidate.restricts[aspect]?.entries?.sortedByDescending { it.value }?.forEach { (tag, weight) ->
                    add(told(aspect.page, "keeps only $TAG_MARK$tag", "%+.2f".format(weight)))
                }
                candidate.excludes[aspect]?.sorted()?.forEach { add(told(aspect.page, "removes $it", "out of the pool")) }
                candidate.biases[aspect]?.entries?.sortedByDescending { it.value }?.forEach { (named, weight) ->
                    add(told(aspect.page, "leans $named", "%+.2f".format(weight)))
                }
            }
        }
        return everywhere + keyed
    }

    /** A section that is one thing to do, so its whole list is the doing of it. */
    private fun doingRows(part: Part, candidate: Candidate): List<Row> = listOf(
        Row(
            handle = part.name,
            shown = listOf(Ink(part.title, if (candidate.isDerived) Palette.faint else Palette.value)),
            note = if (candidate.isDerived) "an auto-generated word has no file to write" else part.about,
        ),
    )

    /**
     * What an aspect holds, said rather than named.
     *
     * `Holds` is the code's word for this and a fine one there; on screen the constant alone answers a
     * question nobody asked — "weighted_set" says nothing to somebody looking at `biomes`.
     */
    private fun whatItHolds(aspect: Aspect): String {
        val kind = when (aspect.holds) {
            Holds.NOTHING -> "properties only, no members"
            Holds.RANGE -> "one value on an axis"
            Holds.CATALOGUE -> "one member, from a list"
            Holds.WEIGHTED_SET -> "a table of kinds, weighed"
            Holds.POPULATION -> "members written one at a time"
        }
        return kind + if (aspect.open) " ${Glyph.BULLET} anything in the game" else ""
    }

    /** Which of the word's own claims put it here — the answer to "why is this on the list". */
    private fun whyItReaches(aspect: Aspect, candidate: Candidate): String {
        val because = buildList {
            if (candidate.restricts.containsKey(aspect)) add("it keeps only what is tagged, in ${aspect.page}")
            if (candidate.admits.containsKey(aspect)) add("it adds to the ${aspect.page}'s pool")
            if (candidate.excludes.containsKey(aspect)) add("it takes something out of the ${aspect.page}")
            if (candidate.biases.containsKey(aspect)) add("it leans the ${aspect.page}")
            val parameters = Insistence.entries.flatMap { candidate.everythingOn(it).keys }
            val here = parameters.filter { aspect.ownsParameterNamed(it.substringAfterLast('.')) }
            if (here.isNotEmpty()) add("it turns ${here.sorted().joinToString(" ")}")
            candidate.chooses[aspect]?.let { add("it chooses $it outright") }
        }
        return because.joinToString("\n    ").ifEmpty { "" }
    }

    // -- effects -------------------------------------------------------------------------------------

    /**
     * All four slots in one list, under a heading each.
     *
     * They were three separate sections and that was the wrong cut: `alps` does its whole job with a
     * named preset, so every effects screen read as empty until you found `more` several sections down.
     * One list means what a word does is in one place, whatever shape it took.
     */
    private fun effectRows(candidate: Candidate, word: Word?): List<Row> = buildList {
        for (insistence in Insistence.entries) {
            add(
                Row(
                    handle = "heading/${insistence.name}",
                    shown = listOf(Ink(insistence.title, Palette.heading)),
                    note = insistence.about,
                ),
            )
            // **Under the heading rather than at the foot of the list.** The half a claim belongs to is
            // the question these used to open with, so putting one pair in each group asks it by where
            // you are standing — and a word with a dozen effects does not bury the way to add another.
            add(Row("+/${insistence.name}", listOf(Ink("    + add an effect", Palette.faint))))
            add(Row("+pool/${insistence.name}", listOf(Ink("    + add a pool, drawn per Age", Palette.faint))))
            for ((parameter, value) in candidate.settingOn(insistence).entries.sortedBy { it.key }) {
                add(facetRow("${insistence.name}/$parameter", parameter, value, word))
            }
            // **Each pool under its own heading**, because what a pool is *for* is that its facets belong
            // together — a writer laying `sun.colour` beside `sun.size` is saying the Age varies in its
            // sun, and a single flat list of eight facets says only that it varies.
            for ((at, pool) in candidate.poolsOn(insistence).withIndex()) {
                add(drawsRow(insistence, at, pool))
                for ((parameter, value) in pool.facets.entries.sortedBy { it.key }) {
                    add(facetRow("pool/${insistence.name}/$at/$parameter", parameter, value, word, deeper = true))
                }
            }
        }
        candidate.mints?.let { pattern ->
            add(Row("heading/mints", listOf(Ink("makes", Palette.heading)), "a new member, out of a pattern the game already has"))
            add(
                Row(
                    handle = "mints",
                    shown = listOf(
                        Ink("    "),
                        Ink(pattern.padEnd(PARAMETER_COLUMN), Palette.parameter),
                        Ink(if (candidate.mintsSomethingThatFlows) "holds a fluid" else "holds a block", Palette.value),
                    ),
                    note = "the substance comes from the clause it is written in — `ink springs`",
                ),
            )
        }
    }

    private fun facetRow(handle: String, parameter: String, value: String, word: Word?, deeper: Boolean = false) =
        Row(
            handle = handle,
            shown = listOf(
                Ink(if (deeper) "      " else "    "),
                Ink(parameter.padEnd(if (deeper) PARAMETER_COLUMN - 2 else PARAMETER_COLUMN), Palette.parameter),
                Ink(value, Palette.value),
            ),
            note = parameterNote(parameter, value, word),
        )

    /**
     * A pool's own heading — what it is about, and how much of itself an Age takes.
     *
     * [Pool.said] rather than a name somebody chose: what a pool is about is already spelled in the
     * parameters it holds, and every name anyone invented for one was a word this codebase did not have.
     */
    private fun drawsRow(insistence: Insistence, at: Int, pool: Pool): Row = Row(
        handle = "draws/${insistence.name}/$at",
        shown = listOf(
            Ink("    "),
            Ink(pool.said.padEnd(PARAMETER_COLUMN), Palette.tag),
            Ink("${pool.draws} of ${pool.facets.size} drawn per Age", Palette.faint),
        ),
        note = drawsNote(pool),
    )

    /** What the count actually comes to, said back — the whole point of allowing a range to be written. */
    private fun drawsNote(pool: Pool): String {
        val options = pool.draws.options
        val counted = options.distinct().sorted().joinToString(" or ") { many ->
            val chances = options.count { it == many }
            if (chances == 1) "$many" else "$many (${chances} in ${options.size})"
        }
        return "takes $counted of ${pool.facets.size}\n" +
            "    a number, a range like 1..3, or 1|2|2 to make one likelier"
    }

    /**
     * What the parameter is, where it lands, and whether it takes the value — the three things the file does
     * not tell you.
     *
     * The sentence comes off [Parameter.help], where the parameter is declared, so there is one copy of it and
     * `ParameterHelpCheck` insists every parameter has one.
     */
    private fun parameterNote(parameter: String, value: String, word: Word?): String {
        val bare = parameter.substringAfterLast('.')
        val meant = parameter.substringBefore('.').takeIf { it != parameter }
        val landing = word?.aspects.orEmpty()
            .filter { meant == null || it.page == meant }
            .filter { corpus.vocabulary.turnsAParameter(it, bare) }
            .sortedBy { it.ordinal }
        if (landing.isEmpty()) return "nothing this word targets has a parameter called '$bare'"

        val at = helpAspect.mod(landing.size)
        val active = landing[at]
        val offered = Verdict.parametersNamed(active, bare, corpus)
        val parameter = offered.firstOrNull()
        val alternatives = value.split('|').map(String::trim).filter(String::isNotEmpty)
        val refused = alternatives.filterNot { one -> offered.any { it.accepts(one) } }

        return buildList {
            add("available for" + if (landing.size > 1) "   (tab to change)" else "")
            add("    " + landing.joinToString("  ") { aspect ->
                if (aspect == active) "${Glyph.FOCUS}${aspect.page}" else " ${aspect.page}"
            })
            parameter?.help?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (parameter?.holds == Holds.RANGE) {
                add("the axis runs ${Span.NATURAL_LEAST} to ${Span.NATURAL_MOST}, and it is not vanilla's own scale")
            }
            if (refused.isNotEmpty()) add("it does not take ${refused.joinToString("|")}")
            parameter?.let { addAll(valueLines(it, active)) }
        }.joinToString("\n")
    }

    /** How many aspects the parameter on [row] lands on, which is what tab has to cycle through. */
    fun targetsOf(row: Row, candidate: Candidate, word: Word?): Int {
        val parameter = row.handle.substringAfter('/', "").ifEmpty { return 0 }
        val bare = parameter.substringAfterLast('.')
        val meant = parameter.substringBefore('.').takeIf { it != parameter }
        return word?.aspects.orEmpty()
            .filter { meant == null || it.page == meant }
            .count { corpus.vocabulary.turnsAParameter(it, bare) }
    }

    /**
     * What the parameter takes, a value to a line and capped.
     *
     * Capped on both axes deliberately: an open parameter has every block in the game behind it, and a
     * note that ran to forty lines would push the list it belongs to off the screen.
     */
    private fun valueLines(parameter: Parameter, on: Aspect?): List<String> {
        val every = optionsFor(parameter, on)
        val said = every.take(VALUES_SHOWN).map { option ->
            "    ${option.label.padEnd(VALUE_LABEL)}${option.note}"
        }
        val more = every.size - said.size
        return if (more <= 0) said else said + "    ${Glyph.ELIDED} and $more more"
    }

    /** Every value a parameter would take, for the picker — an axis says its grammar instead. */
    fun optionsFor(parameter: Parameter, on: Aspect? = null): List<Picker.Option> = when {
        parameter.holds == Holds.RANGE -> listOf(
            Picker.Option("0.5..1.0", "a band", "this and no other; two bands must overlap or the Age breaks"),
            Picker.Option(">0.4", "a floor", "at least this, giving way to anything already inside it"),
            Picker.Option("<0.2", "a ceiling", "at most this"),
            Picker.Option("+0.3", "a nudge", "more than it would have been; nudges add up"),
            Picker.Option("~0.2", "a spread", "wider, or narrower, about the middle of the band"),
        ) + bandsFor(parameter)
        // **A material takes a block, so it offers the blocks.** It used to offer `unchanged` and the
        // words "or any registry id", which is a list of one and an instruction to go and find the rest —
        // with eleven hundred of them a keystroke away in the corpus this screen already holds.
        parameter.material -> parameter.options.map { Picker.Option(it, it, "leave the preset's own") } +
            blocksFor(parameter)
        // **A population takes a registry id, so it offers the registry.** `grown`, `built`, `grows` and
        // `lives` are the biomes, structure sets, features and creatures an Age holds, and each used to
        // offer `unchanged`, `nothing`, and the words "or any registry id" — the id being the whole of
        // what a writer came to say, and the only thing not on the list.
        parameter.open -> parameter.options.map { Picker.Option(it, it, "") } + membersOf(on, parameter)
        else -> parameter.options.mapIndexed { at, option ->
            val said = parameter.optionHelp[option].orEmpty()
            val note = listOfNotNull(
                said.ifEmpty { null },
                if (at == 0) "the default, so asking for it says nothing" else null,
            ).joinToString("  ${Glyph.BULLET}  ")
            Picker.Option(option, option, note)
        }
    }

    /**
     * Every block a material parameter could take, out of the corpus rather than a registry.
     *
     * `holdsYouUp` narrows it to what can be a world: `Materials.makesAWorld` is what keeps a sign from
     * being the rock an Age is built of, and offering one here would be offering a value the corpus
     * refuses two screens later.
     */
    /**
     * **Where the words already written put themselves on this axis.**
     *
     * An axis runs ${Span.NATURAL_LEAST} to ${Span.NATURAL_MOST} and nothing about the number says what
     * it means — it is not vanilla's temperature, and a writer with no landmarks is guessing. The corpus
     * *is* the landmarks: `icy` says where cold is and `scorching` says where hot is, and neither has to
     * be written down twice to be read here.
     *
     * They are pickable as well as readable, because "the same band as `arid`" is a thing a writer means.
     */
    private fun bandsFor(parameter: Parameter): List<Picker.Option> =
        corpus.vocabulary.authoredWords
            .mapNotNull { word -> word.everySet[parameter.name]?.let { said -> said to word.name } }
            .filter { (said, _) -> Setting.describes(said) }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { (said, _) -> leadingNumberIn(said) }
            .mapIndexed { at, (said, words) ->
                Picker.Option(said, said, "as ${words.sorted().joinToString(" ")}", startsGroup = at == 0)
            }

    /** Where a span sits, for ordering — the first number in it, and the far end where it has none. */
    private fun leadingNumberIn(said: String): Double =
        Regex("-?[0-9]*\\.?[0-9]+").find(said)?.value?.toDoubleOrNull() ?: Span.NATURAL_MOST

    /**
     * Everything [on] holds, by id — **read off what its own derived words set.**
     *
     * Not off what a word means outright, which a biome word never does: `DerivedWords` builds a
     * population's words with `setting`, deliberately, because a biome *enriches a table* where a sea
     * *is* its block. So the ids are the values those words put on this very parameter, which is also
     * exactly the set a writer could legally type.
     */
    private fun membersOf(on: Aspect?, parameter: Parameter): List<Picker.Option> {
        if (on == null) return emptyList()
        return corpus.vocabulary.derivedWords
            .filter { on in it.aspects }
            .mapNotNull { it.everySet[parameter.name] }
            .filter { it != Parameter.UNCHANGED }
            .distinct()
            .sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it }))
            .map { id -> Picker.Option(id, id, "") }
    }

    private fun blocksFor(parameter: Parameter): List<Picker.Option> =
        corpus.vocabulary.derivedWords
            .mapNotNull { it.material }
            .filter { !parameter.holdsYouUp || Materials.makesAWorld(it.toString()) }
            .map { it.toString() }
            .distinct()
            .sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it }))
            .map { id -> Picker.Option(id, id, "") }

    // -- tags ----------------------------------------------------------------------------------------

    /**
     * **How this word chooses which preset fills an aspect** — all three ways, strongest first.
     *
     * `Word.pullOn` reads them in exactly this order: a preset meant outright answers first, else a weight
     * on that preset by name, else the tags. They were three screens and one of them was labelled with
     * another field's name, so a word doing its whole job by meaning one landform read as an empty word
     * everywhere you looked.
     *
     * Strongest first because that precedence is worth learning: a preset meant outright silently beats
     * every tag on the word, and nothing else says so.
     */
    private fun pickRows(candidate: Candidate, word: Word?): List<Row> = buildList {
        for (step in Step.entries) {
            add(Row("heading/${step.name}", listOf(Ink(step.title, Palette.heading)), step.about))
            add(Row("+/${step.name}", listOf(Ink("    + ${step.adds}", Palette.faint))))
            addAll(stepRows(step, candidate, word))
        }
    }

    /**
     * **A settled population reads as settled.** Choosing ends the pipeline there, so whatever the later
     * steps say about that part of the world is never read — and a row that does nothing should not look
     * like a row that does.
     */
    private fun settledNote(step: Step, candidate: Candidate, aspect: Aspect): String? {
        if (step == Step.CHOOSE) return null
        val chosen = candidate.chooses[aspect] ?: return null
        return "never read: '$chosen' settles the ${aspect.page}, and nothing after that is asked"
    }

    private fun stepRows(step: Step, candidate: Candidate, word: Word?): List<Row> = when (step) {
        Step.CHOOSE -> candidate.chooses.entries.sortedBy { it.key.ordinal }.map { (aspect, key) ->
            Row("chooses/${aspect.page}", populationInk(aspect, key, Palette.chosen), whatItMeans(aspect, key))
        }
        Step.ADD -> candidate.admits.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, keys) ->
            val settled = settledNote(step, candidate, aspect)
            keys.sorted().map { key ->
                Row(
                    "admits/${aspect.page}/$key",
                    populationInk(aspect, key, if (settled == null) Palette.value else Palette.faint),
                    settled ?: addedNote(aspect, key),
                )
            }
        }
        Step.KEEP -> candidate.restricts.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, tags) ->
            val settled = settledNote(step, candidate, aspect)
            tags.entries.sortedByDescending { it.value }.map { (tag, weight) ->
                Row(
                    "restricts/${aspect.page}/$tag",
                    tagInk(tag, weight, aspect.page),
                    settled ?: tagNote(tag, word, aspect),
                )
            }
        }
        Step.REMOVE -> candidate.excludes.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, keys) ->
            val settled = settledNote(step, candidate, aspect)
            keys.sorted().map { key ->
                Row(
                    "excludes/${aspect.page}/$key",
                    populationInk(aspect, key, if (settled == null) Palette.refused else Palette.faint),
                    settled ?: struckNote(aspect, key),
                )
            }
        }
        Step.BIAS -> leaningRows(candidate, word)
    }

    private fun leaningRows(candidate: Candidate, word: Word?): List<Row> {
        val everywhere = candidate.leansEverywhere.entries.sortedByDescending { it.value }.map { (named, weight) ->
            Row("biases/${Word.EVERYWHERE}/$named", leanInk(named, weight, Word.EVERYWHERE), leanNote(null, named))
        }
        val keyed = candidate.biases.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, by) ->
            val settled = settledNote(Step.BIAS, candidate, aspect)
            by.entries.sortedByDescending { it.value }.map { (named, weight) ->
                Row(
                    "biases/${aspect.page}/$named",
                    leanInk(named, weight, aspect.page),
                    settled ?: leanNote(aspect, named),
                )
            }
        }
        return everywhere + keyed
    }

    /** A member on a populations row: what it is, and which part of the world it is in. */
    private fun populationInk(aspect: Aspect, key: String, tone: TextStyle) = listOf(
        Ink("    "),
        Ink(key.padEnd(PARAMETER_COLUMN + KIND_COLUMN), tone),
        Ink("in ${aspect.page}", Palette.faint),
    )

    /** A lean: the thing leaned on, the bar, the number, and where. */
    private fun leanInk(named: String, weight: Double, where: String) = listOf(
        Ink("    "),
        Ink(named.padEnd(PARAMETER_COLUMN), if (named.startsWith(TAG_MARK)) Palette.tag else Palette.value),
        Ink(bar(weight), if (weight < 0) Palette.refused else Palette.settled),
        Ink(" %+.2f".format(weight), Palette.value),
        Ink("  in $where", Palette.faint),
    )

    private fun addedNote(aspect: Aspect, key: String): String =
        if (aspect.presetFor(key) == null) "nothing in the ${aspect.page} is called that"
        else "curation left it out of the pool; this puts it in for this Age"

    private fun struckNote(aspect: Aspect, key: String): String = when {
        key.startsWith(TAG_MARK) -> "everything in the ${aspect.page} carrying $key is taken out"
        aspect.presetFor(key) == null -> "nothing in the ${aspect.page} is called that"
        else -> "taken out of the pool, however it got in"
    }

    /**
     * What the named preset turns out to be.
     *
     * **The authored list, not the closed aspects.** They are not the same question: `phenomena` accepts
     * ids it has never heard of and every value it has is still ours, since nothing in vanilla is a
     * tempest. What makes a name sayable is that we wrote the thing.
     */
    private fun whatItMeans(aspect: Aspect, key: String): String = when {
        aspect.ownsPresetNamed(key) -> "a design of ours, in the ${aspect.page}"
        aspect.presetsAreEntriesOf != null -> "an entry of the ${aspect.page}'s own registry"
        else -> "nothing in the ${aspect.page} is called that"
    }

    /**
     * The page that already means this preset outright, or null where none does.
     *
     * **Landforms all have one now** — `AuthoredPreset.writtenWordFor` mints a page from the landform
     * itself — so offering `alps` again would author a second word meaning what `alps` already means, and
     * nothing downstream would catch it: `Verdict.duplicates` skips derived words deliberately, since a
     * derived word *is* the thing it means and every one of them read as a synonym of itself.
     */
    fun alreadyChosenBy(aspect: Aspect, key: String): String? = corpus.vocabulary.words.distinct()
        .firstOrNull { it.choiceIn(aspect)?.key == key }
        ?.name

    /** Everything this mod wrote that a word may choose outright — see [Word.chooses]. */
    fun oursToName(): List<Pair<Aspect, String>> =
        Aspect.entries.flatMap { aspect -> aspect.authored.map { aspect to it.key } }

    private fun templateRows(candidate: Candidate) = listOf(
        Row(
            handle = "template",
            shown = listOf(
                Ink(candidate.template ?: "overworld", if (candidate.template == null) Palette.faint else Palette.value),
                Ink(if (candidate.template == null) "   (the default; this word does not change it)" else "", Palette.faint),
            ),
            note = "which of Minecraft's dimensions the Age is built on, before the book is read\n" +
                "    a word with one does nothing else: it swaps the world, it does not describe it\n" +
                "    only the three vanilla ones today; the mechanism is not vanilla-only",
        ),
    )

    /** The base dimensions a word may choose, which is the whole of what a template is today. */
    fun baseDimensions(): List<Pair<String, String>> = listOf(
        "overworld" to "Minecraft's overworld: its rock, its biomes, its sky.",
        "infernal" to "The nether — sealed overhead, lit by nothing, a sea of lava.",
        "dark_void" to "The end — islands in a void, and its own sky.",
    )

    /**
     * The tag a populations row is about, where it is about one.
     *
     * Read off the claim rather than off the handle: a member named like a step would otherwise be taken
     * for one, and a lean names members and tags in the same map.
     */
    fun tagOn(candidate: Candidate, handle: String): String? {
        val kept = candidate.restricts.entries.flatMap { (aspect, tags) ->
            tags.keys.map { "restricts/${aspect.page}/$it" to it }
        }
        val struck = candidate.excludes.entries.flatMap { (aspect, keys) ->
            keys.filter { it.startsWith(TAG_MARK) }.map { "excludes/${aspect.page}/$it" to it.drop(1) }
        }
        val leaned = (candidate.biases.entries.map { it.key.page to it.value } +
            listOf(Word.EVERYWHERE to candidate.leansEverywhere)).flatMap { (page, by) ->
            by.keys.filter { it.startsWith(TAG_MARK) }.map { "biases/$page/$it" to it.drop(1) }
        }
        return (kept + struck + leaned).firstOrNull { it.first == handle }?.second
    }

    private fun tagInk(tag: String, weight: Double, only: String): List<Ink> = listOf(
        Ink("    "),
        Ink("$TAG_MARK$tag".padEnd(PARAMETER_COLUMN), Palette.tag),
        Ink(bar(weight), if (weight < 0) Palette.refused else Palette.settled),
        Ink(" %+.2f".format(weight), Palette.value),
        Ink(if (only.isEmpty()) "" else "  in $only only", Palette.faint),
    )

    /** A signed weight drawn from the middle, so pushing away and pulling toward look different. */
    private fun bar(weight: Double): String {
        val filled = (kotlin.math.abs(weight) * BAR_WIDTH).toInt().coerceIn(0, BAR_WIDTH)
        return Glyph.FULL.repeat(filled) + Glyph.EMPTY.repeat(BAR_WIDTH - filled)
    }

    /** What the tag finds, which is what you cannot see from the file. */
    private fun tagNote(tag: String, word: Word?, only: Aspect? = null): String {
        if (word == null) return ""
        if (tag !in corpus.vocabulary.carriedTags) {
            val snapshot = corpus.snapshot?.serverOnly?.get(tag)
            return snapshot?.let { "nothing offline; a server had it on ${it.size}" } ?: "nothing carries it"
        }
        val aspects = (only?.let(::listOf) ?: word.aspects.toList()).sortedBy { it.ordinal }
        return aspects.joinToString("  ") { aspect -> "${aspect.page} ${keptIn(word, aspect)}" }
    }

    private fun keptIn(word: Word, aspect: Aspect): String {
        val askable = corpus.vocabulary.askableIn(aspect).size
        val kept = corpus.vocabulary.carriersOf(word, aspect).size
        val remembered = corpus.snapshot?.reachOf(aspect, word.wanted.firstOrNull().orEmpty())
        val onAServer = remembered?.let { " (${it.found} of ${it.carriers} on a server)" }.orEmpty()
        return "$kept/$askable$onAServer"
    }

    /**
     * What a requested tag leans on. Not [tagNote], which counts what a *narrowing* word keeps — a
     * request narrows nothing, so that number is zero for every one of them.
     */
    /**
     * What a lean actually falls on — a member by name, or everything carrying a tag.
     *
     * A null [aspect] is the `all` lean an evocative word makes, which falls wherever the tag is carried.
     */
    private fun leanNote(aspect: Aspect?, named: String): String {
        if (!named.startsWith(TAG_MARK)) {
            val where = aspect ?: return "'$named' is a member, so leaning it everywhere reaches nothing"
            return if (where.presetFor(named) == null) "nothing in the ${where.page} is called that"
            else "leans the draw toward it; it can still lose"
        }
        val tag = named.drop(1)
        val looking = if (aspect == null) Aspect.entries else listOf(aspect)
        val carrying = looking.flatMap { corpus.vocabulary.candidatesFor(it) }
            .filter { tag in corpus.vocabulary.tagsOf(it) }
        if (carrying.isEmpty()) return "nothing carries it, so the lean falls on nothing"
        return "${carrying.size} carrier(s): ${carrying.take(CARRIERS_SHOWN).joinToString(" ") { it.key }}"
    }

    // -- the rest ------------------------------------------------------------------------------------

    private fun weightRows(candidate: Candidate) =
        candidate.weights.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, byPreset) ->
            byPreset.entries.sortedByDescending { it.value }.map { (preset, weight) ->
                Row(
                    handle = "weight/${aspect.page}/$preset",
                    shown = listOf(
                        Ink("    "),
                        Ink(LEANS.padEnd(KIND_COLUMN), Palette.faint),
                        Ink(preset.padEnd(PARAMETER_COLUMN), Palette.value),
                        Ink("%+.2f".format(weight).padEnd(8), Palette.value),
                        Ink("in ${aspect.page}", Palette.faint),
                    ),
                )
            }
        }

    /**
     * A labelled value, lined up on a column [wide] enough for every label beside it.
     *
     * **The width is passed rather than assumed.** It was a constant of twelve, which `required ink
     * quality` is eight characters past — so its value started where the label ended and the two ran
     * together. A page knows its own labels; nothing else can.
     */
    private fun field(name: String, value: String?, wide: Int) = listOf(
        Ink(name.padEnd(wide), Palette.faint),
        Ink(value ?: "—", if (value == null) Palette.faint else Palette.value),
    )

    private fun commentRows(candidate: Candidate): List<Row> {
        val lines = candidate.commentLines
        return listOf(
            Row(
                handle = "comment",
                shown = listOf(
                    Ink(if (lines.isEmpty()) "nothing written" else "${lines.size} line(s)", Palette.value),
                ),
                note = "enter opens \$EDITOR",
            ),
        ) + lines.take(COMMENT_PREVIEW).map { Row("", listOf(Ink("  $it", Palette.faint))) }
    }

    /**
     * What ink this word demands, from whichever of the two places holds it.
     *
     * A registry entry's is a tag on the entry rather than a name in `art/ink/`, which is what lets another
     * mod's ore be worth the good ink without anybody editing our files. A page minted from one of our own
     * designs has no entry to tag and is listed by name like the rest.
     */
    private fun inkOf(candidate: Candidate): String? =
        if (candidate.inkedByTag) corpus.registryOf(candidate.id)?.let { WordFile.inkTagOn(candidate.id.toString(), it) }
        else WordFile.listingFor(candidate.listingKey).ink

    private fun listingRows(candidate: Candidate): List<Row> {
        val listing = WordFile.listingFor(candidate.listingKey)
        val ink = inkOf(candidate)
        val labels = listOf("rarity", "required ink quality")
        val wide = labels.maxOf { it.length } + LABEL_GUTTER
        return listOf(
            Row("rarity", field(labels[0], listing.rarity, wide)),
            Row(
                handle = "ink",
                shown = field(labels[1], ink, wide),
                note = if (candidate.inkedByTag) "written as a tag on ${candidate.id}" else "",
            ),
        )
    }

    private fun deleteRows(candidate: Candidate): List<Row> = listOf(
        Row(
            handle = "delete",
            shown = listOf(
                Ink("${Glyph.WARN} ", Palette.refused),
                Ink(
                    if (candidate.isDerived) "auto-generated words cannot be deleted" else "delete '${candidate.name}'",
                    if (candidate.isDerived) Palette.faint else Palette.refused,
                ),
            ),
            note = if (candidate.isDerived) {
                "this word exists because the game has ${candidate.id}"
            } else {
                "the file, its rarity, its ink and what it is shown as\n    there is no undo for this"
            },
        ),
    )

    companion object {
        const val BAR_WIDTH = 10
        const val COMMENT_PREVIEW = 12
        const val VALUES_SHOWN = 6
        const val VALUE_LABEL = 14

        /** Where a facet's value starts, so every row in the section lines up on it. */
        const val PARAMETER_COLUMN = 22

        /** Where a review row's second column starts. */
        const val MARK_COLUMN = 14

        /** The gap between a label and the value it labels, wherever the two share a row. */
        const val LABEL_GUTTER = 2

        /** The three columns of a review line: what it is about, what it says, and the aside after. */
        const val REVIEW_LABEL = 16
        const val REVIEW_VALUE = 34

        /** Where the value starts on a populations row, past the word saying what it does to the draw. */
        const val KIND_COLUMN = 10

        /** How many carriers a lean's note names before it stops. */
        const val CARRIERS_SHOWN = 6

        /**
         * What each kind of claim actually does — **named for its force, not for how it is spelled.**
         *
         * `outright` and `by name` said how you wrote it and looked like two ways of writing one thing.
         * They are not: measured over twenty seeds, a word that only *means* a landform seats it twenty
         * times and one that only *weighs* it seats it seven. A weight never narrows at all
         * (`Word.constrainsPresetsIn` is false for one) — it admits the preset to the draw and makes it
         * likelier, where a meaning is the answer and no search happens.
         */
        const val SETTLES = "settles"
        const val KEEPS = "keeps"
        const val LEANS = "leans"
        const val OFFERS = "offers"

    }
}

/**
 * Where a facet is going: a insistence's always-half, or one of that insistence's pools.
 *
 * One value because every flow that collects a facet — pick a parameter, qualify it, type a value — has
 * to carry the destination through unchanged, and a insistence and an optional index threaded separately went
 * out of step the first time a pool was added mid-flow.
 */
data class Into(val insistence: Insistence, val pool: Int? = null)

/**
 * This word with [parameter] set to [value] wherever [into] points — **making the pool where it is new.**
 *
 * A pool with nothing in it cannot be drawn from and has no heading to edit, so one is never created
 * empty and waiting: the first facet is what brings it into being, pointed one past the last.
 */
fun Candidate.putting(into: Into, parameter: String, value: String): Candidate = when {
    into.pool == null -> putting(into.insistence, parameter, value)
    into.pool >= poolsOn(into.insistence).size -> addingAPool(into.insistence, parameter, value)
    else -> puttingInPool(into.insistence, into.pool, parameter, value)
}

/** What is already there, wherever [into] points. */
fun Candidate.holding(into: Into): Map<String, String> =
    if (into.pool == null) settingOn(into.insistence) else poolsOn(into.insistence).getOrNull(into.pool)?.facets.orEmpty()

/** This word leaning [named] by [weight], wherever [aspect] points — `null` being the whole Age. */
fun Candidate.leaning(aspect: Aspect?, named: String, weight: Double): Candidate =
    if (aspect == null) copy(leansEverywhere = leansEverywhere + (named to weight))
    else copy(biases = biases + (aspect to (biases[aspect].orEmpty() + (named to weight))))

/** The half of this word [insistence] names, whole — what it always does, and every pool it draws from. */
fun Candidate.claimsOn(insistence: Insistence): Claims =
    if (insistence.required) Claims(sets, pools) else requests

/** What that half always does, drawn or not. */
fun Candidate.settingOn(insistence: Insistence): Map<String, String> = claimsOn(insistence).sets

/** The pools on that half, in the order they were written — which is also how the draw is salted. */
fun Candidate.poolsOn(insistence: Insistence): List<Pool> = claimsOn(insistence).pools

/** Everything that half could ever turn, whatever an Age's draw settles on. */
fun Candidate.everythingOn(insistence: Insistence): Map<String, String> = claimsOn(insistence).everything

/** This word with [insistence]'s claims replaced — the one funnel every edit below goes through. */
fun Candidate.withClaims(insistence: Insistence, claims: Claims): Candidate =
    if (insistence.required) copy(sets = claims.sets, pools = claims.pools)
    else copy(requests = requests.copy(sets = claims.sets, pools = claims.pools))

/** This word with [parameter] claimed at [insistence] and always, rather than drawn from a pool. */
fun Candidate.putting(insistence: Insistence, parameter: String, value: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(sets = it.sets + (parameter to value)) })

/** This word without [parameter] among what it claims at [insistence] always. */
fun Candidate.without(insistence: Insistence, parameter: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(sets = it.sets - parameter) })

/** This word with [parameter] set to [value] in the pool [at], among what it claims at [insistence]. */
fun Candidate.puttingInPool(insistence: Insistence, at: Int, parameter: String, value: String): Candidate =
    changingPool(insistence, at) { it.copy(facets = it.facets + (parameter to value)) }

/**
 * This word without [parameter] in the pool [at] — **and without the pool where that was the last of it.**
 *
 * A pool with nothing in it draws from nothing and has no heading left to edit, so the only way back
 * would be the file.
 */
fun Candidate.withoutInPool(insistence: Insistence, at: Int, parameter: String): Candidate {
    val left = poolsOn(insistence).getOrNull(at)?.facets?.minus(parameter).orEmpty()
    if (left.isEmpty()) return withoutPool(insistence, at)
    return changingPool(insistence, at) { it.copy(facets = left, draws = Draws.of(it.draws.most.coerceAtMost(left.size))) }
}

/** This word with a pool added to [insistence] — one facet and a count of one, which is the least a pool is. */
fun Candidate.addingAPool(insistence: Insistence, parameter: String, value: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let {
        it.copy(pools = it.pools + Pool(mapOf(parameter to value), Draws.of(1)))
    })

fun Candidate.withoutPool(insistence: Insistence, at: Int): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(pools = it.pools.filterIndexed { where, _ -> where != at }) })

/** This word with the pool [at] drawing [draws] of itself. */
fun Candidate.drawing(insistence: Insistence, at: Int, draws: Draws): Candidate =
    changingPool(insistence, at) { it.copy(draws = draws) }

private fun Candidate.changingPool(insistence: Insistence, at: Int, change: (Pool) -> Pool): Candidate =
    withClaims(insistence, claimsOn(insistence).let { claims ->
        claims.copy(pools = claims.pools.mapIndexed { where, pool -> if (where == at) change(pool) else pool })
    })
