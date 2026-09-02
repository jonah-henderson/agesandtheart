package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.word.Tier
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
enum class Part(val title: String, val about: String, val perilous: Boolean = false) {
    NAME("word", ""),
    TIER("specificity", ""),
    TEMPLATE("base dimension", "the world a book starts from, where this word chooses one"),
    ASPECTS("targets", "where its claims put it — derived, and nothing a word can declare"),
    EFFECTS("effects", "what it changes, and whether it insists on it"),
    PICKS("picks", ""),
    COMMENT("comment", ""),
    LISTING("rarity", ""),

    /** Last, and marked, because it is the one thing here that cannot be undone. */
    DELETE("delete", "", perilous = true),
}

/**
 * Which of the four effect slots a row belongs to.
 *
 * Two questions, asked separately: does the word **insist** or merely **request**, and does it do this
 * **always** or is it **drawn** from a pool per Age. A word may want any mix of the four.
 */
enum class Slot(val required: Boolean, val drawn: Boolean, val title: String, val about: String) {
    REQUIRED_ALWAYS(true, false, "required", "always applies, and overrides anything else"),
    REQUIRED_POOL(true, true, "required pool", "drawn per Age, then applies like the rest of required"),
    REQUESTED_ALWAYS(false, false, "requested", "applies only where the book said nothing"),
    REQUESTED_POOL(false, true, "requested pool", "drawn per Age, and still gives way to the book"),
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
        Part.ASPECTS -> aspectRows(candidate, word)
        Part.TEMPLATE -> templateRows(candidate)
        Part.EFFECTS -> effectRows(candidate, word)
        Part.PICKS -> pickRows(candidate, word)
        Part.COMMENT -> commentRows(candidate)
        Part.LISTING -> listingRows(candidate)
        Part.DELETE -> deleteRows(candidate)
    }

    /** What the section says about itself — some of it depends on the word, so it is not on the enum. */
    fun aboutOf(part: Part, candidate: Candidate): String = when (part) {
        Part.PICKS -> whatTagsDoHere(candidate.tier)
        Part.COMMENT -> "why this word exists, for whoever reads it next"
        Part.LISTING -> "how hard it is to find, and what it takes to write"
        Part.DELETE -> "removing this word for good"
        else -> part.about
    }

    /**
     * What a tag actually does, which depends on how specific the word is: an evocative word leans the
     * draw and can rule nothing out, where the other two keep only what is tagged well enough.
     */
    private fun whatTagsDoHere(tier: Tier): String = when (tier) {
        Tier.EVOCATIVE -> "leans the Age toward things carrying these tags — it cannot rule anything out"
        else -> "keeps only things tagged ${tier.threshold} or better; negative weights push away"
    }

    /** Whether this section holds a list you add to and delete from, which decides what `a` and `d` mean. */
    fun isAList(part: Part) = part in setOf(Part.EFFECTS, Part.PICKS)

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
    private fun aspectRows(candidate: Candidate, word: Word?): List<Row> {
        val reaches = word?.aspects.orEmpty().sortedBy { it.ordinal }
        if (reaches.isEmpty()) {
            return listOf(
                Row(
                    handle = "none",
                    shown = listOf(Ink("nothing yet", Palette.warned)),
                    note = "a word reaches wherever its effects and queries point; it has none that do",
                ),
            )
        }
        return reaches.map { aspect ->
            Row(
                handle = aspect.page,
                shown = listOf(
                    Ink(aspect.page.padEnd(22), Palette.value),
                    Ink("${aspect.holds.name.lowercase()}${if (aspect.open) ", open" else ""}", Palette.faint),
                ),
                note = whyItReaches(aspect, candidate),
            )
        }
    }

    /** Which of the word's own claims put it here — the answer to "why is this on the list". */
    private fun whyItReaches(aspect: Aspect, candidate: Candidate): String {
        val because = buildList {
            if (candidate.queries.containsKey(aspect)) add("it asks tags of ${aspect.page}")
            if (candidate.requests.queries.containsKey(aspect)) add("it offers tags to ${aspect.page}")
            if (candidate.weights.containsKey(aspect)) add("it weighs a preset in ${aspect.page}")
            val parameters = candidate.sets.keys + candidate.pool.keys + candidate.requests.sets.keys +
                candidate.requests.pool.keys
            val here = parameters.filter { aspect.ownsParameterNamed(it.substringAfterLast('.')) }
            if (here.isNotEmpty()) add("it turns ${here.sorted().joinToString(" ")}")
            candidate.meansExactly[aspect]?.let { add("it means $it outright") }
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
        for (slot in Slot.entries) {
            val parameters = candidate.holding(slot)
            val leaning = if (slot.required) emptyMap() else candidate.requests.queries
            if (parameters.isEmpty() && (slot.drawn || leaning.isEmpty())) continue
            add(headingFor(slot, candidate))
            for ((parameter, value) in parameters.entries.sortedBy { it.key }) {
                add(
                    Row(
                        handle = "${slot.name}/$parameter",
                        shown = listOf(
                            Ink("    "),
                            Ink(parameter.padEnd(22), Palette.parameter),
                            Ink(value, Palette.value),
                        ),
                        note = parameterNote(parameter, value, word),
                    ),
                )
            }
        }
        candidate.mints?.let { pattern ->
            add(Row("heading/mints", listOf(Ink("makes", Palette.heading)), "a new member, out of a pattern the game already has"))
            add(
                Row(
                    handle = "mints",
                    shown = listOf(
                        Ink("    "),
                        Ink(pattern.padEnd(22), Palette.parameter),
                        Ink(if (candidate.mintsSomethingThatFlows) "holds a fluid" else "holds a block", Palette.value),
                    ),
                    note = "the substance comes from the clause it is written in — `ink springs`",
                ),
            )
        }
        add(Row("+", listOf(Ink("+ add an effect", Palette.faint))))
        // **A pool is a group and a count, so it is built rather than assembled.** Adding facets one at a
        // time through `add an effect` meant choosing the pool slot again for every one and then finding
        // the heading to set the draw — four walks for what is one decision.
        add(Row("+pool", listOf(Ink("+ add a pool, drawn per Age", Palette.faint))))
    }

    private fun headingFor(slot: Slot, candidate: Candidate): Row {
        val pool = candidate.holding(slot)
        val draws = if (slot.required) candidate.draws else candidate.requests.draws
        val counted = if (slot.drawn) "  ${Glyph.BULLET} $draws of ${pool.size} drawn" else ""
        return Row(
            handle = if (slot.drawn) "draws/${slot.name}" else "heading/${slot.name}",
            shown = listOf(Ink(slot.title, Palette.heading), Ink(counted, Palette.faint)),
            note = slot.about,
        )
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
        if (candidate.meansExactly.isNotEmpty()) {
            add(
                Row(
                    "heading/ours",
                    listOf(Ink("meant outright", Palette.heading)),
                    "the one preset this word means in a part of the world, claimed without searching",
                ),
            )
            for ((aspect, key) in candidate.meansExactly.entries.sortedBy { it.key.ordinal }) {
                add(
                    Row(
                        "means/${aspect.page}",
                        listOf(Ink("    "), Ink(key, Palette.value), Ink("  in ${aspect.page}", Palette.faint)),
                        whatItMeans(aspect, key),
                    ),
                )
            }
        }
        if (candidate.weights.isNotEmpty()) {
            add(
                Row(
                    "heading/named",
                    listOf(Ink("by name", Palette.heading)),
                    "beats the tags, and reaches things the tags never described",
                ),
            )
            addAll(weightRows(candidate))
        }
        val tags = queryRows(candidate, word)
        if (tags.isNotEmpty()) add(Row("heading/tagged", listOf(Ink("by tag", Palette.heading))))
        addAll(tags)
        add(Row("+", listOf(Ink("+ add a tag, a weight or a name", Palette.faint))))
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
    fun alreadyMeantBy(aspect: Aspect, key: String): String? = corpus.vocabulary.words.distinct()
        .firstOrNull { it.meaningIn(aspect)?.key == key }
        ?.name

    /** Everything this mod wrote that a word may mean outright — see [Word.meansExactly]. */
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

    private fun queryRows(candidate: Candidate, word: Word?): List<Row> {
        val flat = candidate.everywhere.entries.sortedByDescending { it.value }.map { (tag, weight) ->
            Row(tag, tagInk(tag, weight, ""), tagNote(tag, word))
        }
        val keyed = candidate.queries.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, weights) ->
            weights.entries.sortedByDescending { it.value }.map { (tag, weight) ->
                Row("${aspect.page}/$tag", tagInk(tag, weight, aspect.page), tagNote(tag, word, aspect))
            }
        }
        val leaning = candidate.requests.queries.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, tags) ->
            tags.entries.sortedByDescending { it.value }.map { (tag, weight) ->
                Row(
                    handle = "requested/${aspect.page}/$tag",
                    shown = tagInk(tag, weight, aspect.page) + Ink("  requested", Palette.faint),
                    note = leanNote(aspect, tag),
                )
            }
        }
        return flat + keyed + leaning
    }

    /**
     * The tag a picks row is about, where it is about one.
     *
     * Built from the same three sources [queryRows] draws from rather than by unpicking the handle: a
     * preset named `requested` would otherwise be read as a leaning tag.
     */
    fun tagOn(candidate: Candidate, handle: String): String? {
        if (handle in candidate.everywhere) return handle
        val keyed = candidate.queries.entries.flatMap { (aspect, tags) ->
            tags.keys.map { "${aspect.page}/$it" to it }
        }
        val leaning = candidate.requests.queries.entries.flatMap { (aspect, tags) ->
            tags.keys.map { "requested/${aspect.page}/$it" to it }
        }
        return (keyed + leaning).firstOrNull { it.first == handle }?.second
    }

    private fun tagInk(tag: String, weight: Double, only: String): List<Ink> = listOf(
        Ink("    "),
        Ink("$TAG_MARK$tag".padEnd(18), Palette.tag),
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
    private fun leanNote(aspect: Aspect, tag: String): String {
        val candidates = corpus.vocabulary.candidatesFor(aspect)
        val carrying = candidates.filter { tag in corpus.vocabulary.tagsOf(it) }
        if (carrying.isEmpty()) return "nothing in ${aspect.page} carries it, so the lean falls on nothing"
        return "${carrying.size} of ${candidates.size}: ${carrying.joinToString(" ") { it.key }}"
    }

    // -- the rest ------------------------------------------------------------------------------------

    private fun weightRows(candidate: Candidate) =
        candidate.weights.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, byPreset) ->
            byPreset.entries.sortedByDescending { it.value }.map { (preset, weight) ->
                Row(
                    handle = "weight/${aspect.page}/$preset",
                    shown = listOf(
                        Ink("    "),
                        Ink(aspect.page.padEnd(12), Palette.faint),
                        Ink(preset.padEnd(34), Palette.value),
                        Ink("%+.2f".format(weight), Palette.value),
                    ),
                )
            }
        }

    private fun field(name: String, value: String?) = listOf(
        Ink(name.padEnd(12), Palette.faint),
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

    private fun listingRows(candidate: Candidate): List<Row> {
        val listing = WordFile.listingFor(candidate.listingKey)
        // A registry entry's ink is a tag on the entry, not a name in `art/ink/` — that is what lets
        // another mod's ore be worth the good ink without anybody editing our files. A page minted from
        // one of our own designs has no entry to tag and is listed by name like the rest.
        val ink = if (candidate.inkedByTag) {
            corpus.registryOf(candidate.id)?.let { WordFile.inkTagOn(candidate.id.toString(), it) }
        } else {
            listing.ink
        }
        return listOf(
            Row("rarity", field("rarity", listing.rarity)),
            Row(
                handle = "ink",
                shown = field("required ink quality", ink),
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

    private companion object {
        const val BAR_WIDTH = 10
        const val COMMENT_PREVIEW = 12
        const val VALUES_SHOWN = 6
        const val VALUE_LABEL = 14
    }
}

/** What one effect slot holds on this word. */
fun Candidate.holding(slot: Slot): Map<String, String> = when (slot) {
    Slot.REQUIRED_ALWAYS -> sets
    Slot.REQUIRED_POOL -> pool
    Slot.REQUESTED_ALWAYS -> requests.sets
    Slot.REQUESTED_POOL -> requests.pool
}

/**
 * This word with [parameter] set to [value] in [slot].
 *
 * **A first facet brings a draw with it.** `pool` with `draws` still at zero is a pool the Age never
 * takes, which the file cannot show and `Verdict` has to refuse; starting the count at one means the
 * refusal is unreachable rather than merely rare, and a writer who wants more says so on the heading.
 */
fun Candidate.putting(slot: Slot, parameter: String, value: String): Candidate = when (slot) {
    Slot.REQUIRED_ALWAYS -> copy(sets = sets + (parameter to value))
    Slot.REQUIRED_POOL -> copy(pool = pool + (parameter to value), draws = draws.coerceAtLeast(1))
    Slot.REQUESTED_ALWAYS -> copy(requests = requests.copy(sets = requests.sets + (parameter to value)))
    Slot.REQUESTED_POOL -> copy(
        requests = requests.copy(
            pool = requests.pool + (parameter to value),
            draws = requests.draws.coerceAtLeast(1),
        ),
    )
}

/**
 * This word without [parameter] in [slot].
 *
 * **The last facet out takes the draw with it.** A count left standing over an empty pool draws from
 * nothing, and the heading it is edited on is gone by then — so the only way back would be the file.
 */
fun Candidate.without(slot: Slot, parameter: String): Candidate = when (slot) {
    Slot.REQUIRED_ALWAYS -> copy(sets = sets - parameter)
    Slot.REQUIRED_POOL -> (pool - parameter).let { left -> copy(pool = left, draws = drawsFor(left, draws)) }
    Slot.REQUESTED_ALWAYS -> copy(requests = requests.copy(sets = requests.sets - parameter))
    Slot.REQUESTED_POOL -> (requests.pool - parameter).let { left ->
        copy(requests = requests.copy(pool = left, draws = drawsFor(left, requests.draws)))
    }
}

/** A count no larger than the pool it draws from, and none at all where there is nothing left. */
private fun drawsFor(pool: Map<String, String>, standing: Int) = standing.coerceAtMost(pool.size)
