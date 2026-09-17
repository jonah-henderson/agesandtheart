package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.word.Claims
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Facets
import co.voik.agesandtheart.age.word.Tier
import com.github.ajalt.mordant.rendering.TextStyle
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.aspectNamedBy
import co.voik.agesandtheart.age.word.landsOn
import co.voik.agesandtheart.age.word.parameterIn
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Insistence
import co.voik.agesandtheart.preview.authoring.holding
import co.voik.agesandtheart.preview.authoring.leaning
import co.voik.agesandtheart.preview.authoring.poolsOn
import co.voik.agesandtheart.preview.authoring.putting
import co.voik.agesandtheart.preview.authoring.settingOn
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
    /**
     * **What a word costs, and how precisely it speaks** — five numbers, of which the three the Art names
     * are a filling-in rather than a category.
     *
     * It was `specificity` and offered the three alone, which made a name a claim nothing could hold an
     * author to: a word called `evocative` that chooses four members outright is precise, and was priced
     * as though it were vague. The name is derived from the numbers now, so it can only ever be true.
     */
    TIER("cost", ""),
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

    /**
     * Whether the cost section's numbers have been opened to editing.
     *
     * **A state of the screen, not of the word.** A tier *is* its five numbers, so one still matching
     * `restrictive` is restrictive whatever was picked, and writing `custom` beside restrictive's own
     * numbers into a file would be a label the file could not honour. Taking a named tier closes it again.
     */
    private var costWasOpened = false

    /** Whether this word says its cost in numbers rather than by name. */
    fun statingItsOwnCost(candidate: Candidate) = costWasOpened || candidate.tier.key == Tier.CUSTOM

    /** What the `custom` row does, and what taking one of the three undoes. */
    fun openTheCost(open: Boolean) { costWasOpened = open }

    fun rowsOf(part: Part, candidate: Candidate, word: Word?, width: Int): List<Row> = when (part) {
        Part.NAME -> nameRows(candidate)
        Part.TIER -> tierRows(candidate, width)
        Part.TEMPLATE -> templateRows(candidate)
        Part.PROPERTIES -> effectRows(candidate, word)
        Part.POPULATIONS -> pickRows(candidate, word)
        Part.REVIEW -> reviewRows(candidate, word, width)
        Part.COMMENT -> commentRows(candidate)
        Part.LISTING -> listingRows(candidate)
        Part.SAVE, Part.SAVE_AND_LEAVE -> doingRows(part, candidate)
        Part.DELETE -> deleteRows(candidate)
    }

    /** Whether this section holds a list you add to and delete from, which decides what `a` and `d` mean. */
    fun isAList(part: Part) = part in setOf(Part.PROPERTIES, Part.POPULATIONS)

    /**
     * **What the row under the cursor can do** — the contextual half of the key line.
     *
     * Here rather than in the editor because what a row *is* is decided here: the editor reads a handle to
     * act on it and would be reading it a second time to say so, which is two places for one answer and a
     * key line that quietly stops matching what a key does.
     *
     * It also replaces the hints that used to sit in a row's own note. A note is for what a thing means;
     * which keys work on it belongs where every other key is listed.
     */
    fun keysFor(part: Part, row: Row?, candidate: Candidate): List<Pair<String, String>> {
        val handle = row?.handle.orEmpty()
        val kind = handle.substringBefore('/')
        return when (part) {
            Part.PROPERTIES -> when {
                kind.startsWith("+") -> listOf("enter" to "do it")
                kind == "group" -> listOf(
                    "enter" to "add a setting to this group",
                    "d" to "remove the whole group",
                )
                kind == "draws" -> listOf("enter" to "how many are drawn", "d" to "remove the pool")
                kind == "pool" -> listOf(
                    "enter" to "set it",
                    "a" to "add to this one's group",
                    "d" to "remove it",
                )
                kind == "mints" -> listOf("enter" to "change the pattern", "d" to "make nothing")
                kind == "flows" -> listOf("enter" to "block or fluid")
                kind == "unstated" -> listOf("enter" to "change the pool", "d" to "say nothing")
                handle.isEmpty() -> emptyList()
                else -> listOf("enter" to "set it", "a" to "add another", "d" to "remove it")
            }
            Part.POPULATIONS -> when {
                kind.startsWith("+") -> listOf("enter" to "do it")
                kind == "restricts" || kind == "biases" -> listOf(
                    "enter" to "type the weight",
                    "- =" to "step it",
                    "d" to "remove it",
                )
                kind == "chooses" || kind == "admits" || kind == "excludes" ->
                    listOf("enter" to "change it", "d" to "remove it")
                handle.isEmpty() -> emptyList()
                else -> listOf("a" to "add", "d" to "remove")
            }
            Part.TIER -> when {
                kind == "named" -> listOf("enter" to "take these numbers")
                kind == "cost" && statingItsOwnCost(candidate) ->
                    listOf("enter" to "type it", "- =" to "step it")
                kind == "cost" -> listOf("" to "take `custom` above to change these")
                else -> emptyList()
            }
            Part.NAME -> listOf("enter" to "rename it")
            Part.LISTING -> listOf("enter" to "choose one")
            Part.TEMPLATE -> listOf("enter" to "choose one")
            Part.COMMENT -> listOf("enter" to "open \$EDITOR")
            Part.REVIEW -> emptyList()
            Part.SAVE, Part.SAVE_AND_LEAVE -> listOf("enter" to "write the file")
            Part.DELETE -> listOf("enter" to "delete the word")
        }
    }

    /** Whether the cursor may rest on this row at all — a heading names what is under it and does nothing. */
    fun isAHeading(row: Row) = row.handle.startsWith("heading/")

    /** A line of air between two groups. A heading, so the cursor passes over it rather than into it. */
    private fun spacer(named: String) = Row("heading/space/$named", emptyList())

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

    /**
     * **The three the Art names, then a fourth that is the numbers themselves, then the numbers.**
     *
     * A name is a filling-in rather than a category: taking one sets all five at once, and the row that is
     * filled in is whichever the numbers match. `custom` is what opens them to editing, and a word already
     * stating its own arrives with them open — the numbers are shown either way, because what `exact`
     * actually means is worth being able to read without having to change anything to see it.
     */
    private fun tierRows(candidate: Candidate, width: Int): List<Row> = buildList {
        val own = statingItsOwnCost(candidate)
        val named = Tier.NAMED.map { (named, tier) ->
            Celled(
                "named/$named",
                listOf(
                    Ink(named, if (!own && candidate.tier == tier) Palette.value else Palette.faint),
                    Ink("ink ${tier.cost}", Palette.faint),
                    Ink(whatATierMeans(named), Palette.faint),
                ),
                mark = !own && candidate.tier == tier,
            )
        } + Celled(
            "named/${Tier.CUSTOM}",
            listOf(
                Ink(Tier.CUSTOM, if (own) Palette.value else Palette.faint),
                Ink("", Palette.faint),
                Ink("", Palette.faint),
            ),
            mark = own,
        )
        addAll(laidInColumns(named, TIER_CHOICES, width, marked = true))
        add(Row("heading/space/numbers", emptyList()))
        addAll(costRows(candidate, own, width))
    }

    private fun whatATierMeans(named: String) = when (named) {
        "evocative" -> "steers and biases but imposes no hard constraints"
        "restrictive" -> "narrows available options to a specific set, like hot temperatures"
        "exact" -> "sets a parameter precisely"
        else -> ""
    }

    /**
     * The five numbers a tier is — **shown always, reachable only where the word states its own.**
     *
     * Grey and unfocusable under a named tier, which is the whole of how a reader tells the two apart: the
     * numbers are there to be read, and reading `exact`'s threshold is what tells you what `exact` means.
     */
    private fun costRows(candidate: Candidate, own: Boolean, width: Int): List<Row> {
        val tier = candidate.tier
        // **No note under the pane**: the third column already says what each number does, and a footer
        // repeating it in other words is the same sentence twice with the cursor between them.
        //
        // **Under a named tier the cursor passes over them**, the `heading/` handle being what makes a row
        // unreachable. They are there to be read — reading `exact`'s threshold is what tells you what
        // `exact` means — and a row that stops the cursor without taking an edit reads as one that broke.
        fun row(field: String, said: String, about: String) = Celled(
            handle = if (own) "cost/$field" else "heading/cost/$field",
            cells = listOf(
                Ink(field, if (own) Palette.parameter else Palette.faint),
                Ink(said, if (own) Palette.value else Palette.faint),
                Ink(about, Palette.faint),
            ),
        )
        // **`restricts` on screen, `narrows` in the code**, since `Word.restricts` is already the tag query
        // and one word for two things is worse in the file than a second word for one thing is on a list.
        val restricting = if (tier.narrows) {
            "parameter values and population members that conflict will not be selected"
        } else {
            "parameter values and population members that conflict will be unlikely, but still selectable"
        }
        // **The multiplier last, and the sum under it.** The worked example is the longest thing on this
        // page and it belongs to the row above it, which reads as a crowd when there are three more rows
        // beneath. `base ink cost` says what it is, so it is left to.
        val rows = listOf(
            row("base ink cost", "${tier.cost}", ""),
            row("restricts", yesOrNo(tier.narrows), restricting),
            row(
                "tag match threshold",
                "%.2f".format(tier.threshold),
                "how well a tag must align to be considered matching",
            ),
            row(
                "instability cost",
                "${tier.weight}",
                "how many instability points are penalised when this word is used in a contradiction",
            ),
            row(
                "versatility multiplier",
                "%.2f".format(tier.versatilityMultiplier),
                "scales the versatility cost by this amount",
            ),
        )
        val laid = laidInColumns(rows, TIER_NUMBERS, width).toMutableList()
        // Anchored on the row rather than on its position, so moving one does not silently move the sum.
        val multiplier = laid.indexOfFirst { it.handle == "cost/versatility multiplier" }
        laid.add(multiplier + 1, Row("heading/sum", listOf(Ink("        ${inkSpelledOut(candidate)}", Palette.faint))))
        return laid
    }

    /**
     * The ink this word costs, worked through — **live, and in the arithmetic's own signs.**
     *
     * A multiplier is a number whose effect nobody should have to compute, least of all while choosing it.
     * The floor is said only where it bites, since `× 0.00 = 2` reads as a mistake until something
     * explains it.
     */
    private fun inkSpelledOut(candidate: Candidate): String {
        val word = candidate.asWord().getOrNull() ?: return ""
        val tier = word.tier
        val reach = word.aspects.size.coerceAtLeast(1)
        val floored = reach * tier.versatilityMultiplier < 1.0
        val sum = "${tier.cost} base ink × $reach aspect(s) × %.2f versatility = ${word.price} ink"
            .format(tier.versatilityMultiplier)
        return if (floored) "$sum  ${Glyph.BULLET} never below the base cost" else sum
    }

    private fun yesOrNo(said: Boolean) = if (said) "yes" else "no"

    /**
     * A parameter key as a row shows it — **the part of the world first**, where the key names one.
     *
     * `size` is a landform's, a sun's and a vein's; the qualifier is what tells them apart and it was
     * being stripped for display, so three different parameters read as the same row.
     */
    private fun saidAsAParameter(spelled: String): String {
        val named = aspectNamedBy(spelled) ?: return spelled
        return "${named.page} ${parameterIn(spelled)}"
    }

    /** A row before its columns are measured — what every list here with columns is built from. */
    private data class Celled(
        val handle: String,
        val cells: List<Ink>,
        val note: String = "",
        val mark: Boolean = false,
    )

    /**
     * [rows] laid in columns measured across them, rather than padded to a width somebody guessed.
     *
     * The same arithmetic the review page uses ([Columns]); it is here because the cost section has two
     * groups of rows whose columns have nothing to do with each other and each wants its own measurement.
     */
    private fun laidInColumns(
        rows: List<Celled>,
        columns: List<Columns.Column>,
        width: Int,
        marked: Boolean = false,
    ): List<Row> {
        val lead = if (marked) MARKER_ROOM else REVIEW_INDENT
        val widths = Columns.widths(
            rows.map { row -> row.cells.map { it.text } },
            columns,
            (width - lead).coerceAtLeast(MINIMUM_ROOM),
            gap = REVIEW_GAP,
        )
        val gap = Line(" ".repeat(REVIEW_GAP))
        return rows.map { row ->
            val marker = when {
                !marked -> Ink(" ".repeat(REVIEW_INDENT))
                row.mark -> Ink("${Glyph.FILLED} ", Palette.chosen)
                else -> Ink("${Glyph.HOLLOW} ", Palette.chosen)
            }
            Row(row.handle, listOf(marker) + Columns.laid(row.cells, widths, gap).inks, row.note)
        }
    }

    // -- review --------------------------------------------------------------------------------------

    /**
     * One review line before it knows how wide its columns are.
     *
     * Measured rather than declared, which is what the page was missing: its columns were sixteen and
     * thirty-four characters whatever a terminal had, so a span was cut in the middle on every one of
     * them while the aside beside it ran off the end.
     */
    private sealed interface Told {
        val handle: String

        /** A line that owns the width: a heading, a blank, a line of the comment, the aspects it reaches. */
        data class Whole(override val handle: String, val shown: List<Ink>) : Told

        /** A line in the page's columns: what it is about, what it says, and the aside after. */
        data class Columned(override val handle: String, val cells: List<Ink>) : Told
    }

    /**
     * **The whole word on one page**, read-only, in the order somebody checks it: what it costs, what it
     * sets, what it does to each set of members, where all of that lands, and why it exists.
     *
     * **Empty sections are absent rather than stated** — what a word has not said is already on the
     * section list to its left as a blank mark, and the page is worth having only if scanning it is quick.
     */
    private fun reviewRows(candidate: Candidate, word: Word?, width: Int): List<Row> {
        if (word == null) {
            return listOf(Row("none", listOf(Ink("this word will not load", Palette.refused))))
        }
        val listing = WordFile.listingFor(candidate.listingKey)
        // **A section at a time.** One measurement for the whole page makes every value column as wide as
        // the longest thing any section puts there, so a temperature of `0.5..1.0` sits alone in the width
        // of a tag query with its aside pushed off the end.
        val sections = listOf(
            under("cost", costTold(candidate, word, listing)),
            under("properties", propertiesTold(candidate)),
            under("populations", populationsTold(candidate)),
            under("aspects", aspectsTold(word, width)),
            under("comment", candidate.commentLines.map { said(it) }),
        )
        if (sections.all { it.isEmpty() }) {
            return listOf(Row("none", listOf(Ink("nothing said yet", Palette.faint))))
        }
        return sections.flatMap { laidOut(it, width) }
    }

    /** One section's three columns measured against each other, and its rows drawn into them. */
    private fun laidOut(told: List<Told>, width: Int): List<Row> {
        val columned = told.filterIsInstance<Told.Columned>()
        val widths = Columns.widths(
            columned.map { row -> row.cells.map { it.text } },
            REVIEW_COLUMNS,
            (width - REVIEW_INDENT).coerceAtLeast(MINIMUM_ROOM),
            gap = REVIEW_GAP,
        )
        val gap = Line(" ".repeat(REVIEW_GAP))
        return told.map { row ->
            when (row) {
                is Told.Whole -> Row(row.handle, row.shown)
                is Told.Columned -> Row(
                    row.handle,
                    listOf(Ink(" ".repeat(REVIEW_INDENT))) + Columns.laid(row.cells, widths, gap).inks,
                )
            }
        }
    }

    /**
     * A heading and its rows, or nothing at all where there are none.
     *
     * The blank line before it carries a heading's handle so the cursor passes over it, which is the same
     * rule that keeps it off the heading itself.
     */
    private fun under(title: String, rows: List<Told>): List<Told> =
        if (rows.isEmpty()) emptyList() else listOf(
            blank(title),
            Told.Whole("heading/$title", listOf(Ink(title, Palette.heading))),
        ) + rows

    /** A line that spaces two things apart, handled as a heading so the cursor passes over it. */
    private fun blank(named: String) = Told.Whole("heading/space/$named", emptyList())

    /** A group inside a section — the required half of the properties, or one pool of them. */
    private fun grouped(title: String, rows: List<Told>): List<Told> =
        grouped(title, listOf(Ink(title, Palette.chosen)), rows)

    /** The same, where the heading has something in it worth colouring apart from the rest. */
    private fun grouped(named: String, shown: List<Ink>, rows: List<Told>): List<Told> =
        if (rows.isEmpty()) emptyList() else listOf(
            Told.Whole("heading/group/$named", listOf(Ink("  ")) + shown),
        ) + rows

    /**
     * A pool's heading: how many facets an Age takes of it, and what of.
     *
     * **The subject where the facets share one** — `sun.cast`, `sun.colour` and `sun.size` are the sun,
     * and naming it says more than counting them does. Where they share nothing there is no subject to
     * name and the count out of the whole is the honest answer instead.
     */
    private fun poolHeading(insistence: Insistence, at: Int, pool: Facets): List<Ink> {
        val subject = Aspect.byPage(pool.said)
        val head = Ink(
            "${insistence.title} ${poolNamed(at)} ${Glyph.BULLET} draws ${pool.draws} ",
            Palette.chosen,
        )
        return if (subject == null) {
            listOf(head, Ink("of ${pool.offers.size}", Palette.chosen))
        } else {
            listOf(head, Ink("from ", Palette.chosen), Ink(subject.page, Palette.aspect))
        }
    }

    private fun told(handle: String, label: String, value: String, after: String = "", tone: TextStyle = Palette.value) =
        Told.Columned(handle, listOf(Ink(label, Palette.faint), Ink(value, tone), Ink(after, Palette.faint)))

    private fun said(line: String) = Told.Whole("said", listOf(Ink("    "), Ink(line, Palette.faint)))

    private fun costTold(candidate: Candidate, word: Word, listing: WordFile.Listing) = buildList {
        val reach = if (word.versatility > 1.0) {
            "${word.tier.cost} × %.2f for reaching ${word.aspects.size} part(s) of the world"
                .format(word.versatility)
        } else {
            "${word.tier.cost} flat, whatever it reaches"
        }
        add(told("cost/ink", "ink", "${word.price}", "${word.tier.key} ${Glyph.BULLET} $reach"))
        listing.rarity?.let { add(told("cost/rarity", "rarity", it, "how hard it is to find")) }
        WordFile.inkOf(candidate)?.let { add(told("cost/quality", "ink quality", it, "what it takes to write")) }
        candidate.template?.let {
            add(told("cost/base", "base dimension", dimensionCalled(it), "the world a book starts from"))
        }
    }

    /**
     * Every part of the world the word reaches, in one line that wraps.
     *
     * **Read, never set, and read as names alone.** It used to say what the word holds and does in each,
     * which is what the two sections above it now are — said claim by claim rather than summarised into an
     * aside. Last, because it is the least of what a reader came here for.
     */
    private fun aspectsTold(word: Word, width: Int): List<Told> {
        val reached = word.aspects.sortedBy { it.ordinal }.joinToString(", ") { it.page }
        if (reached.isEmpty()) return emptyList()
        val indent = " ".repeat(REVIEW_INDENT)
        return Line(indent + reached, Palette.value).wrapped(width, hanging = indent)
            .map { Told.Whole("reaches", it.inks) }
    }

    /**
     * What the word sets, **grouped by how hard it insists and then by which pool it draws from**.
     *
     * A pool is several claims of which an Age takes some, so squeezing one onto a line meant reading its
     * count, its size and every facet it offers as one run-on aside. Its facets are claims like any other
     * and belong in the same columns, under a heading that says what the draw is.
     */
    private fun propertiesTold(candidate: Candidate): List<Told> {
        val groups = Insistence.entries.flatMap { insistence ->
            val always = grouped(
                insistence.title,
                candidate.settingOn(insistence).entries.sortedBy { it.key }.map { (parameter, value) ->
                    told("set/${insistence.name}/$parameter", saidAsAParameter(parameter), value, wherever(parameter))
                },
            )
            val pools = candidate.poolsOn(insistence).mapIndexed { at, pool ->
                grouped(
                    "${insistence.name}/pool/$at",
                    poolHeading(insistence, at, pool),
                    pool.offers.flatMapIndexed { which, offer ->
                        val together = if (offer.size > 1) " ${Glyph.BULLET} with the rest of group ${which + 1}" else ""
                        offer.entries.sortedBy { it.key }.map { (parameter, value) ->
                            told(
                                "pool/${insistence.name}/$at/$parameter",
                                saidAsAParameter(parameter),
                                value,
                                wherever(parameter) + together,
                            )
                        }
                    },
                )
            }
            listOf(always) + pools
        }.filter { it.isNotEmpty() }
        // A blank between the groups and none before the first, which the section heading already carries.
        return groups.reduceOrNull { standing, next -> standing + blank("group") + next }.orEmpty()
    }

    /**
     * What a lean of [weight] does, said.
     *
     * **Never `requires` or `disallows`, and nothing stronger at the far end either.** Those verbs are
     * exactly what `keep only` and `remove` do, and both are rows of their own on this page; a lean cannot
     * take anything out or put anything in however far it goes, so a word for the maximum would be
     * promising a difference in kind where there is only one of degree. The bar beside it says how far.
     */
    private fun leanSaid(weight: Double): String = when {
        weight > 0.0 -> "favours"
        weight < 0.0 -> "discourages"
        else -> "leans"
    }

    /** The parts of the world a parameter key lands in — the aside a property row carries. */
    private fun wherever(spelled: String): String =
        Aspect.entries.filter { landsOn(spelled, it) }.joinToString(" ") { it.page }

    /** One line per step a claim about a set takes, under the part of the world it lands in. */
    private fun populationsTold(candidate: Candidate): List<Told> {
        val everywhere = candidate.leansEverywhere.entries.sortedByDescending { it.value }
            .map { (named, weight) ->
                told("lean/all/$named", Word.EVERYWHERE, "${leanSaid(weight)} $named", "%+.2f".format(weight))
            }
        val keyed = Aspect.entries.sortedBy { it.ordinal }.flatMap { aspect ->
            val page = aspect.page
            buildList {
                candidate.chooses[aspect]?.let {
                    add(told("chooses/$page", page, "chooses $it", "nothing is searched for"))
                }
                candidate.admits[aspect]?.sorted()?.forEach {
                    add(told("admits/$page/$it", page, "adds $it", "into the pool"))
                }
                candidate.restricts[aspect]?.entries?.sortedByDescending { it.value }?.forEach { (tag, weight) ->
                    add(told("restricts/$page/$tag", page, "keeps only $TAG_MARK$tag", "%+.2f".format(weight)))
                }
                candidate.excludes[aspect]?.sorted()?.forEach {
                    add(told("excludes/$page/$it", page, "removes $it", "out of the pool"))
                }
                candidate.biases[aspect]?.entries?.sortedByDescending { it.value }?.forEach { (named, weight) ->
                    add(told("biases/$page/$named", page, "${leanSaid(weight)} $named", "%+.2f".format(weight)))
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
            if (isNotEmpty()) add(spacer(insistence.name))
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
                // **Pinned above what is in it**, and both ways in are rows. `a` on a facet joins its
                // offer, which is the quick way once you know it — and nothing here should be reachable
                // only by knowing it.
                add(Row("+in/${insistence.name}/$at", listOf(Ink("      + add a setting", Palette.faint))))
                add(
                    Row(
                        handle = "+group/${insistence.name}/$at",
                        shown = listOf(Ink("      + add a group", Palette.faint)),
                        note = "several settings the Age takes whole or not at all",
                    ),
                )
                addAll(offerRows(insistence, at, pool, word))
            }
        }
        add(Row("heading/mints", listOf(Ink("makes", Palette.heading)), "a new member, out of a pattern the game already has"))
        val pattern = candidate.mints
        if (pattern == null) {
            add(
                Row(
                    handle = "+mints",
                    shown = listOf(Ink("    + make something out of a pattern", Palette.faint)),
                    note = "`ink springs` is vanilla's spring running with ours — the pattern is the page",
                ),
            )
        } else {
            add(
                Row(
                    handle = "mints",
                    shown = listOf(
                        Ink("    "),
                        Ink("pattern".padEnd(PARAMETER_COLUMN), Palette.parameter),
                        Ink(pattern, Palette.value),
                    ),
                    note = "the substance comes from the clause it is written in — `ink springs`",
                ),
            )
            add(
                Row(
                    handle = "flows",
                    shown = listOf(
                        Ink("    "),
                        Ink("made of".padEnd(PARAMETER_COLUMN), Palette.parameter),
                        Ink(if (candidate.mintsSomethingThatFlows) "a fluid" else "a block", Palette.value),
                    ),
                    note = "a spring runs with a fluid and a solid holds none",
                ),
            )
            add(
                Row(
                    handle = if (candidate.unstated == null) "+unstated" else "unstated",
                    shown = candidate.unstated?.let { fallback ->
                        listOf(
                            Ink("    "),
                            Ink("when nobody says".padEnd(PARAMETER_COLUMN), Palette.parameter),
                            Ink(fallback, Palette.value),
                        )
                    } ?: listOf(Ink("    + say what it is made of when nobody does", Palette.faint)),
                    note = "a tag here is a pool the Age draws one from",
                ),
            )
        }
    }

    /**
     * Every pattern a word could mint from — **vanilla's offline, and the pack's own from a snapshot.**
     *
     * A placed feature is datapack content, so `agesandtheart:obelisks` exists nowhere until a server has
     * loaded its packs and only [ServerSnapshot.placedFeatures] can name one. Vanilla's are in the built-in
     * registries and need nobody.
     */
    fun patterns(): List<String> {
        val vanillas = MinecraftRegistries.worldgen.lookupOrThrow(Registries.PLACED_FEATURE)
            .listElementIds().map { it.identifier().toString() }.toList()
        return (vanillas + corpus.snapshot?.placedFeatures.orEmpty()).distinct().sorted()
    }

    /**
     * What a pattern may be made of when nobody says — every block, and every block **tag**, which is a
     * pool the Age draws one from.
     *
     * The tags need a snapshot for the same reason the patterns do: nothing binds one without a server.
     */
    fun substances(): List<Pair<String, String>> {
        val pools = corpus.snapshot?.blockTags.orEmpty().entries.sortedBy { it.key }
            .map { (tag, carriers) -> "$TAG_MARK$tag" to "a pool of $carriers" }
        val blocks = BuiltInRegistries.BLOCK.keySet().map { it.toString() }.sorted().map { it to "one block" }
        return pools + blocks
    }

    /**
     * A pool's offers, in order — **a group under a heading saying it is one.**
     *
     * The heading is the same word the two rows that build one use, so what `add a group` made is what
     * the list then calls it. A group of one is what every facet used to be and is drawn as one row with
     * nothing said about it.
     */
    private fun offerRows(insistence: Insistence, at: Int, pool: Facets, word: Word?): List<Row> =
        pool.offers.flatMapIndexed { which, offer ->
            val grouped = offer.size > 1
            // **The heading is a row, not a heading.** A group is a thing you can delete whole, and the
            // only place that means anything is the line naming it — a cursor that skipped past it left
            // `d` deleting settings one at a time with no way to say "not this idea at all".
            val head = if (!grouped) emptyList() else listOf(
                Row(
                    handle = "group/${insistence.name}/$at/$which",
                    shown = listOf(Ink("      group ${which + 1}", Palette.tag)),
                    note = "these are drawn together or not at all, and count as one thing drawn",
                ),
            )
            val settings = offer.entries.sortedBy { it.key }.map { (parameter, value) ->
                facetRow(
                    "pool/${insistence.name}/$at/$parameter",
                    parameter,
                    value,
                    word,
                    deeper = true,
                    grouped = grouped,
                )
            }
            // **On the group, not on the pool.** What a group takes is a question about that group, and
            // the row under it is where a reader already is when they think to ask.
            val joining = if (!grouped) emptyList() else listOf(
                Row(
                    handle = "+into/${insistence.name}/$at/$which",
                    shown = listOf(Ink("        + add to this group", Palette.faint)),
                    note = "drawn with the rest of it or not at all",
                ),
            )
            head + settings + joining
        }

    private fun facetRow(
        handle: String,
        parameter: String,
        value: String,
        word: Word?,
        deeper: Boolean = false,
        grouped: Boolean = false,
    ) =
        Row(
            handle = handle,
            shown = listOf(
                Ink(if (grouped) "        " else if (deeper) "      " else "    "),
                // **The part of the world first**, where the key names one. `size` alone is a landform's
                // and a sun's and a vein's, and which of them a row is about is the first thing to know.
                Ink(
                    saidAsAParameter(parameter).padEnd(PARAMETER_COLUMN - if (grouped) 4 else if (deeper) 2 else 0),
                    Palette.parameter,
                ),
                Ink(value, Palette.value),
            ),
            note = parameterNote(parameter, value, word),
        )

    /**
     * A pool's own heading — which one it is, and how much of itself an Age takes.
     *
     * **Numbered rather than named after what is in it.** `Facets.said` reads the subject off the facets,
     * which is right where they share one and a run-on list of every parameter where they do not — and a
     * label that grows as a pool does is a label you stop reading.
     */
    private fun drawsRow(insistence: Insistence, at: Int, pool: Facets): Row = Row(
        handle = "draws/${insistence.name}/$at",
        shown = listOf(
            Ink("    "),
            Ink(poolNamed(at).padEnd(PARAMETER_COLUMN), Palette.tag),
            Ink("${pool.draws} of ${pool.offers.size} drawn per Age", Palette.faint),
        ),
        note = drawsNote(pool),
    )

    /** What the count actually comes to, said back — the whole point of allowing a range to be written. */
    private fun drawsNote(pool: Facets): String {
        val options = pool.draws.options
        val counted = options.distinct().sorted().joinToString(" or ") { many ->
            val chances = options.count { it == many }
            if (chances == 1) "$many" else "$many (${chances} in ${options.size})"
        }
        return "takes $counted of ${pool.offers.size}\n" +
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
        ) + wordsSaying(parameter)
        // **A material takes a block, so it offers the blocks.** It used to offer `unchanged` and the
        // words "or any registry id", which is a list of one and an instruction to go and find the rest —
        // with eleven hundred of them a keystroke away in the corpus this screen already holds.
        parameter.material -> parameter.options.sorted().map { Picker.Option(it, it, "leave the preset's own") } +
            blocksFor(parameter)
        // **A population takes a registry id, so it offers the registry.** `grown`, `built`, `grows` and
        // `lives` are the biomes, structure sets, features and creatures an Age holds, and each used to
        // offer `unchanged`, `nothing`, and the words "or any registry id" — the id being the whole of
        // what a writer came to say, and the only thing not on the list.
        parameter.open -> parameter.options.sorted().map { Picker.Option(it, it, "") } + membersOf(on, parameter)
        // **Alphabetical on screen, declared in the model.** A parameter's first option is its default and
        // the order is what says so, which is a fact about the data and no help at all to somebody looking
        // for `snow` among fourteen motes. Which one is the default is said in words instead.
        else -> {
            val default = parameter.options.firstOrNull()
            parameter.options.sorted().map { option ->
                val said = parameter.optionHelp[option].orEmpty()
                val note = listOfNotNull(
                    said.ifEmpty { null },
                    if (option == default) "the default, so asking for it says nothing" else null,
                ).joinToString("  ${Glyph.BULLET}  ")
                Picker.Option(option, option, note)
            }
        }
    }

    /**
     * How the words already written say this axis — the corpus as its own set of landmarks.
     *
     * Public because the band screen offers them beside the axis rather than among the shapes a value can
     * take: "the same band as `arid`" is a thing a writer means, and it is a different errand from moving
     * an end.
     */
    fun wordsSaying(parameter: Parameter): List<Picker.Option> =
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

    /**
     * Every block a material parameter could take, out of the corpus rather than a registry.
     *
     * `holdsYouUp` narrows it to what can be a world: `Materials.makesAWorld` is what keeps a sign from
     * being the rock an Age is built of, and offering one here would be offering a value the corpus
     * refuses two screens later.
     */
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
            if (isNotEmpty()) add(spacer(step.name))
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
        return "never read: '$chosen' settles ${aspect.page}, and nothing after that is asked"
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

    /**
     * Every lean the word makes, **strongest first and across the aspects rather than within each.**
     *
     * A lean is a number, so the question a reader has of the list is which way it leans hardest — and
     * grouped by aspect the answer was somewhere down the third group. Each row says where it lands, so
     * nothing is lost by not gathering them.
     */
    private fun leaningRows(candidate: Candidate, word: Word?): List<Row> {
        val everywhere = candidate.leansEverywhere.entries.map { (named, weight) ->
            weight to Row(
                "biases/${Word.EVERYWHERE}/$named",
                leanInk(named, weight, Word.EVERYWHERE),
                leanNote(null, named),
            )
        }
        val keyed = candidate.biases.entries.flatMap { (aspect, by) ->
            val settled = settledNote(Step.BIAS, candidate, aspect)
            by.entries.map { (named, weight) ->
                weight to Row(
                    "biases/${aspect.page}/$named",
                    leanInk(named, weight, aspect.page),
                    settled ?: leanNote(aspect, named),
                )
            }
        }
        return (everywhere + keyed).sortedByDescending { (weight, _) -> weight }.map { (_, row) -> row }
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
    ) + Gauge.signed(weight, BAR_WIDTH).inks + listOf(
        Ink(" %+.2f".format(weight), Palette.value),
        Ink("  in $where", Palette.faint),
    )

    private fun addedNote(aspect: Aspect, key: String): String =
        if (aspect.presetFor(key) == null) "nothing in ${aspect.page} is called that"
        else "curation left it out of the pool; this puts it in for this Age"

    private fun struckNote(aspect: Aspect, key: String): String = when {
        key.startsWith(TAG_MARK) -> "everything in ${aspect.page} carrying $key is removed"
        aspect.presetFor(key) == null -> "nothing in ${aspect.page} is called that"
        else -> "removed from the pool, however it got in"
    }

    /**
     * What the named preset turns out to be.
     *
     * **The authored list, not the closed aspects.** They are not the same question: `phenomena` accepts
     * ids it has never heard of and every value it has is still ours, since nothing in vanilla is a
     * tempest. What makes a name sayable is that we wrote the thing.
     */
    private fun whatItMeans(aspect: Aspect, key: String): String = when {
        aspect.ownsPresetNamed(key) -> "a design of ours, in ${aspect.page}"
        aspect.presetsAreEntriesOf != null -> "an entry of ${aspect.page}'s own registry"
        else -> "nothing in ${aspect.page} is called that"
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
                Ink(
                    candidate.template?.let(::dimensionCalled) ?: UNSET,
                    if (candidate.template == null) Palette.faint else Palette.value,
                ),
            ),
        ),
    )

    /**
     * The base dimensions a word may choose, and the first row: none at all.
     *
     * **Unset is not the overworld**, though it generates the same world. Nearly every word says nothing
     * about which dimension an Age is built on, and a word that has taken one has no way back to saying
     * nothing without a row that says nothing.
     */
    fun baseDimensions(): List<Pair<String, String>> = listOf(
        UNSET to "this word does not say which dimension the Age is built on",
        "overworld" to "Minecraft's overworld: its rock, its biomes, its sky.",
        "infernal" to "sealed overhead, lit by nothing, a sea of lava.",
        "dark_void" to "islands in a void, and its own sky.",
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
    ) + Gauge.signed(weight, BAR_WIDTH).inks + listOf(
        Ink(" %+.2f".format(weight), Palette.value),
        Ink(if (only.isEmpty()) "" else "  in $only only", Palette.faint),
    )

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

    private fun listingRows(candidate: Candidate): List<Row> {
        val listing = WordFile.listingFor(candidate.listingKey)
        val ink = WordFile.inkOf(candidate)
        val labels = listOf("rarity", "required ink quality")
        val wide = labels.maxOf { it.length } + LABEL_GUTTER
        return listOf(
            Row("rarity", field(labels[0], listing.rarity, wide)),
            Row(
                handle = "ink",
                shown = field(labels[1], ink, wide),
                note = if (candidate.inkTagDirectory != null) "written as a tag on ${candidate.id}" else "",
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
        /**
         * What to call a base dimension here — **the game's name for it, not the Art's.**
         *
         * `infernal` and `dark_void` are what a player reads on a page; the key is what the recipe stores,
         * and between the two there is nobody who benefits from the tool pretending they are not the
         * nether and the end.
         */
        fun dimensionCalled(key: String): String = when (key) {
            "infernal" -> "infernal (the nether)"
            "dark_void" -> "dark void (the end)"
            else -> key
        }

        /** What a pool is called: which one it is, since what is in it is on the rows underneath. */
        fun poolNamed(at: Int) = "pool ${at + 1}"

        /** The row that says a word does not choose a base dimension. */
        const val UNSET = "unset"

        /** How wide a weight's bar is here — the same [Gauge] the list you set it on wears. */
        const val BAR_WIDTH = 13

        /** As far as a lean goes — what enter alone sets one to on the list that steps them. */
        const val A_WHOLE_LEAN = 1.0
        const val COMMENT_PREVIEW = 12
        const val VALUES_SHOWN = 6
        const val VALUE_LABEL = 14

        /** Where a facet's value starts, so every row in the section lines up on it. */
        const val PARAMETER_COLUMN = 22

        /**
         * The three columns of the review page: what a line is about, what it says, and the aside after.
         *
         * Widths are measured off the page rather than declared ([Columns]); only the last takes room
         * nobody else wanted, since it is the one holding a sentence rather than a name.
         */
        val REVIEW_COLUMNS = listOf(Columns.Column(), Columns.Column(), Columns.Column(grows = true))

        /** What every review line is indented by, and the gap between its columns. */
        const val REVIEW_INDENT = 4
        const val REVIEW_GAP = 2

        /**
         * The cost section's two groups of columns, each measured against its own rows: a name, its ink
         * and what it means; then a field, its value and what it does.
         */
        val TIER_CHOICES = listOf(Columns.Column(), Columns.Column(), Columns.Column(grows = true))
        val TIER_NUMBERS = listOf(Columns.Column(), Columns.Column(), Columns.Column(grows = true))

        /** The `● ` a chosen row wears, which every row in that group is indented by. */
        const val MARKER_ROOM = 2

        /** The gap between a label and the value it labels, wherever the two share a row. */
        const val LABEL_GUTTER = 2

        /** Where the value starts on a populations row, past the word saying what it does to the draw. */
        const val KIND_COLUMN = 10

        /** How many carriers a lean's note names before it stops. */
        const val CARRIERS_SHOWN = 6
    }
}
