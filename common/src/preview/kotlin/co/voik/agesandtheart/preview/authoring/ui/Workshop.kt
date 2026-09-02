package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.preview.authoring.AgeDraft
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.GameClient
import co.voik.agesandtheart.preview.authoring.PreviewServer
import co.voik.agesandtheart.preview.authoring.Seeding
import co.voik.agesandtheart.preview.authoring.Suggestions
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Writing a book, a page at a time, with the Art saying what could come next.
 *
 * **Typing, not a form.** A book *is* a sentence, and a screen of drop-downs would hide the one thing
 * worth learning - that modifiers lead and the page they modify closes the clause. So the row is written
 * left to right and what is offered comes from [Suggestions], which asks the parser rather than knowing
 * the grammar.
 *
 * Three things are on screen at once and that is the whole design: the row as laid, what could follow it,
 * and what the Age comes out as. The last is what makes this a workshop rather than a text field - the
 * cost and the flaws move as each page goes down, so a book that is drifting says so while it is being
 * written rather than afterwards.
 */
class Workshop(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
) {

    private var laid: List<String> = emptyList()

    /**
     * Where in the row the next page goes - **0 is before everything, `laid.size` is after it.**
     *
     * A book is written left to right and then fixed in the middle, which a row you can only add to the
     * end of cannot do without unlaying everything after the mistake. Left and right walk it, so what is
     * offered is what could follow the pages *before* the caret.
     */
    private var caret: Int = 0

    private var name: String = ""
    private var seeding: Seeding = Seeding.SETTLED
    private var seed: Long = 0L
    private var saved: Boolean = true
    private var message: String = ""

    private val suggesting = Suggestions(corpus.vocabulary)

    /** The server this session opened - held across rewrites, so a change is seconds and not a boot. */
    private var server: PreviewServer? = null
    private var client: Process? = null

    /** Offers by the row that produced them, so walking back through the row is instant. */
    private val remembered = mutableMapOf<List<String>, Suggestions.Offers>()

    private var offers: Suggestions.Offers = Suggestions.Offers(emptyList(), emptyList())
    private var closers: List<String> = emptyList()

    /**
     * Whether to show the pages that would parse and then do nothing.
     *
     * Off by default and not hidden: the count is on screen, so a list that looks short says why rather
     * than leaving somebody wondering where a word went.
     */
    private var showingInert = false

    /** The suggestions, as a table - so they carry headings and are read the way every other list is. */
    private var table: Table = emptyTable()

    /** Every page that ends a clause - what the row is coloured by, and it never changes while running. */
    private val closingPages: Set<String> by lazy {
        (Aspect.entries.mapNotNull { it.page } +
            corpus.vocabulary.grammarWords.map { it.name }
                .filter { Grammar.isABook(corpus.vocabulary, listOf(it)) }).toSet()
    }

    private fun draft() = AgeDraft(name, laid, seeding, seed)

    /** The seed this book would be written at right now - one place, so nothing on screen can disagree. */
    private fun seedNow(): Long = draft().seedNow()

    /** What is being typed. The table owns it, so the row and the filter cannot say different things. */
    private val typed: String get() = table.filter

    private val focused: Suggestions.Offer? get() = table.focused?.let { row -> listed().firstOrNull { it.page == row.key } }

    private fun listed(): List<Suggestions.Offer> = if (showingInert) offers.all else offers.bearing

    // -- the drafts ------------------------------------------------------------------------------------

    /**
     * The drafts already written, and a way into a new one.
     *
     * **`^o` opens one straight into the game from here**, without going through the writing screen: once
     * a book is written the thing you keep doing is looking at it again, and making that two screens deep
     * would be making the common case the awkward one. The server is this object's, so opening two drafts
     * in turn reuses it.
     */
    fun browse() {
        val rowsOf = { AgeDraft.all().map(::draftRow) }
        val drafts = Table(
            title = "the age workshop",
            columns = listOf(
                Table.Column("age", NAME_WIDTH),
                Table.Column("seed", SEEDING_WIDTH),
                Table.Column("book", BOOK_WIDTH),
            ),
            rows = rowsOf(),
            whenEmpty = "no books written yet — press `a` to start one",
        )
        try {
            walkTheDrafts(drafts, rowsOf)
        } finally {
            closeTheGame()
        }
    }

    private fun draftRow(draft: AgeDraft) = Table.Row(
        key = draft.name,
        cells = listOf(draft.name, draft.seeding.title, draft.sentence),
        tone = if (suggesting.isASentence(draft.pages)) null else Palette.warned,
    )

    private fun walkTheDrafts(drafts: Table, rowsOf: () -> List<Table.Row>) {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    tableLines(
                        drafts,
                        canvas,
                        listOf(
                            Line(
                                "  " + (server?.let { "${Glyph.FILLED} a game is up on localhost:${it.port}" }
                                    ?: "books you are writing ${Glyph.BULLET} kept in ${AgeDraft.directory.path}"),
                                if (server == null) Palette.faint else Palette.settled,
                            ),
                            hints(
                                "a" to "new book",
                                "enter" to "write it",
                                "^o" to "open in minecraft",
                                "d" to "delete",
                                "←" to "back",
                            ),
                        ),
                    ),
                )
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                val standing = drafts.focused?.key?.let(AgeDraft::read)
                when {
                    key.ctrl && key.key == "q" -> return
                    key.ctrl && key.key == "o" -> {
                        standing?.let { open(it) }
                        drafts.withRows(rowsOf())
                    }
                    key.key == "Escape" -> if (drafts.isFiltered) drafts.clearFilter() else return
                    key.key == "ArrowLeft" -> if (drafts.column == 0) return else drafts.across(-1)
                    key.key == "ArrowRight" -> drafts.across(1)
                    key.key == "ArrowUp" -> drafts.move(-1)
                    key.key == "ArrowDown" -> drafts.move(1)
                    key.key == "Home" -> drafts.home()
                    key.key == "End" -> drafts.end()
                    key.key == "PageUp" -> drafts.page(-1)
                    key.key == "PageDown" -> drafts.page(1)
                    key.key == "Tab" -> drafts.sortByTheColumnInHand()
                    key.key == "Backspace" -> drafts.backspace()
                    key.key == "a" && drafts.filter.isEmpty() -> {
                        write(null)
                        drafts.withRows(rowsOf())
                    }
                    key.key == "d" && drafts.filter.isEmpty() && standing != null -> {
                        if (sure("Delete '${standing.name}'?", "the book, not any Age already written from it")) {
                            AgeDraft.delete(standing.name)
                        }
                        drafts.withRows(rowsOf())
                    }
                    key.key == "Enter" -> {
                        standing?.let(::write)
                        drafts.withRows(rowsOf())
                    }
                    key.key.length == 1 && !key.ctrl && !key.alt -> drafts.type(key.key)
                }
            }
        }
    }

    /** One draft loaded and opened in the game, without the writing screen in between. */
    private fun open(draft: AgeDraft) {
        take(draft)
        openInMinecraft()
    }

    private fun write(draft: AgeDraft?) {
        take(draft ?: AgeDraft("", emptyList()))
        refresh()
        loop()
    }

    private fun take(draft: AgeDraft) {
        laid = draft.pages
        caret = draft.pages.size
        name = draft.name
        seeding = draft.seeding
        seed = draft.seed
        saved = true
    }

    // -- writing ---------------------------------------------------------------------------------------

    private fun loop() {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(lines())
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                message = ""
                when {
                    key.ctrl && key.key == "q" -> return
                    key.ctrl && key.key == "s" -> save()
                    key.ctrl && key.key == "o" -> openInMinecraft()
                    key.ctrl && key.key == "g" -> sendEveryoneIn()
                    key.ctrl && key.key == "x" -> stopTheGame()
                    key.ctrl && key.key == "r" -> askForTheSeed()
                    key.ctrl && key.key == "n" -> askForTheName()
                    key.ctrl && key.key == "i" -> {
                        showingInert = !showingInert
                        rebuildTheTable(typed)
                    }
                    // **Escape is the way out**, since left and right walk the row now.
                    key.key == "Escape" -> if (typed.isNotEmpty()) table.clearFilter() else return
                    key.key == "ArrowLeft" -> stepThroughTheRow(-1)
                    key.key == "ArrowRight" -> stepThroughTheRow(1)
                    key.key == "Home" -> stepTo(0)
                    key.key == "End" -> stepTo(laid.size)
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Enter" || key.key == "Tab" || key.key == " " -> lay()
                    key.key == "Backspace" -> back()
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    /** Along the row a page at a time. What is typed is dropped: it belonged where the caret was. */
    private fun stepThroughTheRow(by: Int) = stepTo(caret + by)

    private fun stepTo(where: Int) {
        val wanted = where.coerceIn(0, laid.size)
        if (wanted == caret) return
        caret = wanted
        refresh()
    }

    /**
     * The page under the cursor laid down at the caret - or **what was typed, where nothing was offered.**
     *
     * [Suggestions] is sound and not complete: it does not find a page needing three more before the row
     * could close again. Free typing is the way past that. A page nobody knows is still refused, because
     * that is not writing ahead, it is a typo.
     */
    private fun lay() {
        val page = exactlyTyped()?.page ?: focused?.page ?: typed.trim().ifEmpty { return }
        val known = corpus.vocabulary.word(page) != null || corpus.vocabulary.grammarWord(page) != null
        if (!known) {
            message = "no word called '$page'"
            return
        }
        laid = laid.take(caret) + page + laid.drop(caret)
        caret++
        saved = false
        refresh()
    }

    /**
     * The page whose name is **exactly** what was typed, where there is one.
     *
     * Typing a short word that is also the start of a hundred others put the cursor on the first of the
     * hundred: `and` sits under `andesite` and every landform beginning with it, so laying the
     * conjunction meant typing three letters and then scrolling past a screenful of rock. An exact match
     * is not an ambiguous one, so it wins.
     */
    private fun exactlyTyped(): Suggestions.Offer? {
        val said = typed.trim()
        if (said.isEmpty()) return null
        return listed().firstOrNull { it.page.equals(said, ignoreCase = true) }
    }

    /** A character back, or - with nothing typed - the page before the caret off the row. */
    private fun back() {
        if (typed.isNotEmpty()) {
            table.backspace()
            return
        }
        if (caret == 0) return
        laid = laid.take(caret - 1) + laid.drop(caret)
        caret--
        saved = false
        refresh()
    }

    private fun refresh() {
        val before = laid.take(caret)
        offers = remembered[before] ?: canvas.whileBusy("Asking what could come next") {
            suggesting.after(before)
        }.also { remembered[before] = it }
        closers = suggesting.closersAfter(before)
        rebuildTheTable("")
    }

    /** What the table was built for, so a resized terminal rebuilds it rather than keeping old widths. */
    private var builtFor: Int = 0

    /**
     * The columns, **measured off the terminal rather than guessed.**
     *
     * `what it does` was a fixed sixty characters, which truncated the interesting half of every row on a
     * wide screen for no reason at all. What is left after the page and the marker is what it gets, and
     * the arithmetic is `rowLine`'s own: four columns of indent, then ` │ ` between each pair.
     */
    private fun columnsFor(width: Int): List<Table.Column> = listOf(
        Table.Column("page", PAGE_WIDTH),
        Table.Column("specificity", TIER_WIDTH, order = Tier.entries.map { it.key }),
        Table.Column("targets", TARGETS_WIDTH),
        Table.Column("what it does", (width - COLUMNS_SPENT).coerceAtLeast(MINIMUM_SAYS)),
    )

    /** The narrowest the list can be drawn and still say something in every column. */
    private val listPaneLeast: Int get() = COLUMNS_SPENT + MINIMUM_SAYS

    /**
     * Whether the screen can carry the book beside the list rather than above it.
     *
     * **Above it was the mistake.** The detail under the caret grows and shrinks with whatever page the
     * cursor is on, so a list beneath it walked up and down the screen while it was being read. Beside
     * it, the detail can take all the room it wants and the list never moves.
     */
    private val roomForTwoPanes: Boolean get() = canvas.width >= BOOK_PANE_LEAST + Frame.GUTTER + listPaneLeast

    /** How wide the book pane is: a share of the screen, never so wide that the list is squeezed. */
    private val bookPaneWidth: Int
        get() = (canvas.width * BOOK_PANE_SHARE / SHARE_OF)
            .coerceIn(BOOK_PANE_LEAST, canvas.width - Frame.GUTTER - listPaneLeast)

    private val listPaneWidth: Int
        get() = if (roomForTwoPanes) canvas.width - bookPaneWidth - Frame.GUTTER else canvas.width

    private fun emptyTable() = Table(
        title = "",
        columns = columnsFor(listPaneWidth),
        rows = emptyList(),
        whenEmpty = "nothing the Art can read follows this",
    )

    private fun rebuildTheTable(filter: String) {
        builtFor = listPaneWidth
        table = Table(
            title = "",
            columns = columnsFor(listPaneWidth),
            rows = listed().map { offer ->
                Table.Row(
                    key = offer.page,
                    cells = listOf(offer.page, offer.tier, offer.targets, offer.says),
                    tone = when {
                        offer.closes -> Palette.chosen
                        offer.authored -> Palette.value
                        else -> null
                    },
                )
            },
            filter = filter,
            whenEmpty = table.whenEmpty,
        )
    }

    private fun resolution(): Resolution? {
        if (!suggesting.isASentence(laid)) return null
        val read = Grammar.read(corpus.vocabulary, laid) ?: return null
        return runCatching { Resolver.resolve(corpus.vocabulary, read, seedNow()) }.getOrNull()
    }

    // -- the frame -------------------------------------------------------------------------------------

    private fun lines(): List<Line> {
        // A resize changes what `what it does` has room for, and the table holds its widths.
        if (listPaneWidth != builtFor) rebuildTheTable(typed)
        val head = buildList {
            add(
                Line("  the age workshop  ", Palette.heading) + Line(name.ifEmpty { "untitled" }, Palette.value) +
                    Line(if (saved) "" else " *", Palette.warned) +
                    Line("   seed ${seedNow()} ${Glyph.BULLET} ${seeding.title}", Palette.faint) +
                    Line(server?.let { "   ${Glyph.FILLED} localhost:${it.port}" }.orEmpty(), Palette.settled),
            )
            add(Frame.rule(canvas.width))
        }
        val tail = buildList {
            add(Frame.rule(canvas.width))
            add(countLine())
            if (message.isNotEmpty()) add(Line("  $message", Palette.warned))
            val theGame = if (server == null) {
                arrayOf("^o" to "open in minecraft")
            } else {
                arrayOf("^o" to "rewrite", "^g" to "send me in", "^x" to "stop")
            }
            add(hints(*theGame, "^s" to "save", "^n" to "name", "^r" to "seed", "esc" to "back"))
        }
        // Counted rather than guessed: the chrome grows a line whenever the book gains a flaw, and a
        // constant for it went stale every time this frame was touched.
        val room = (canvas.height - head.size - tail.size).coerceAtLeast(1)
        val middle = if (roomForTwoPanes) besidePanes(room) else stackedPanes(room)
        return head + middle + tail
    }

    /**
     * The book on the left and what could follow it on the right, each keeping its own column.
     *
     * The book pane is allowed to run past the room and is cut with a marker rather than being allowed to
     * push the list: whatever it has to say, the row under the cursor stays where it was.
     */
    private fun besidePanes(room: Int): List<Line> {
        val list = listOf(headerLine(table)) + offerLines(room - 1)
        return Frame.beside(exactly(bookPane(bookPaneWidth), room), bookPaneWidth, list, listPaneWidth)
    }

    /**
     * One above the other, for a screen too narrow to carry both.
     *
     * **The book keeps a fixed height here**, which is the whole of what this had to learn from the two
     * panes: the detail grows and shrinks with whatever page the cursor is on, so a book that took only
     * the room it needed walked the list up and down the screen underneath it. It is padded as well as
     * cut, because a short book leaving the gap is what stops the list moving at all.
     */
    private fun stackedPanes(room: Int): List<Line> {
        // The rule and the header the list wears, and one offer row, which is the least worth drawing.
        val spentOnTheList = LIST_CHROME + 1
        val tall = STACKED_BOOK_ROWS.coerceIn(1, (room - spentOnTheList).coerceAtLeast(1))
        val book = exactly(bookPane(canvas.width), tall) +
            listOf(Frame.rule(canvas.width), headerLine(table))
        return book + offerLines((room - book.size).coerceAtLeast(1))
    }

    /**
     * [lines] made exactly [rows] tall — cut with a marker where it overruns, padded where it falls short.
     *
     * Both halves matter and for the same reason: what is under the caret is the one thing on this screen
     * whose height nobody chose, so it is given a height rather than allowed to take one.
     */
    private fun exactly(lines: List<Line>, rows: Int): List<Line> = when {
        lines.size == rows -> lines
        lines.size > rows -> lines.take(rows - 1) +
            Line("  ${Glyph.ELIDED} ${lines.size - rows + 1} more", Palette.faint)
        else -> lines + List(rows - lines.size) { Line.BLANK }
    }

    /**
     * The book so far, what it reads as, and what the page under the cursor would do to it — in that
     * order, because that is the order the questions are asked in.
     */
    private fun bookPane(width: Int): List<Line> = buildList {
        addAll(rowLines(width))
        add(Line.BLANK)
        // The readout and the flaws are one long sentence each, so they wrap under their own label.
        addAll(readingLines().flatMap { it.wrapped(width, READING_HANGING) })
        add(Line.BLANK)
        add(Frame.rule(width, "laying"))
        // Already wrapped, and to its own hanging indents — a second pass would break it under the wrong one.
        addAll(whatWouldBeLaid(width))
    }

    /**
     * What laying the highlighted page would actually do, **at whatever length it takes.**
     *
     * The column has to end somewhere and the interesting half of a word is usually past it — a query of
     * four tags and two parameters does not fit anywhere sensible. This is the one page it matters for: the
     * one about to go down.
     */
    private fun whatWouldBeLaid(width: Int): List<Line> {
        // What enter would lay, which is the exact match ahead of wherever the cursor happens to sit.
        val offer = exactlyTyped() ?: focused ?: return listOf(Line("  nothing to lay", Palette.faint))
        // **The page, then everything about it on its own line.** Beside the list there is height to
        // spend and no width to waste, and the targets are the half a writer actually reads here — the
        // column beside them cuts at two aspect pages and a word reaching nine says so nowhere else.
        val head = Line("  ") + Line(offer.page, if (offer.closes) Palette.chosen else Palette.value) +
            Line(if (offer.tier.isEmpty()) "" else "  ${offer.tier}", Palette.parameter) +
            Line(if (offer.closes) "  closes the clause" else "", Palette.chosen) +
            Line(if (offer.authored || offer.tier.isEmpty()) "" else "  auto-generated", Palette.faint)
        val aimedAt = if (offer.targets.isEmpty()) emptyList() else {
            (Line("    ") + Line("targets  ", Palette.faint) + Line(offer.targets, Palette.tag))
                .wrapped(width, "             ")
        }
        val said = claimLines("required ", offer.required, Palette.value, width) +
            claimLines("requested", offer.requested, Palette.nudged, width)
        return buildList {
            add(head)
            addAll(aimedAt)
            if (said.isEmpty()) {
                addAll((Line("    ") + Line(offer.says, Palette.faint)).wrapped(width, "    "))
                return@buildList
            }
            addAll(said.take(DETAIL_LINES))
            if (said.size > DETAIL_LINES) {
                add(Line("    ") + Line("${Glyph.ELIDED} and ${said.size - DETAIL_LINES} more", Palette.faint))
            }
            // **Why a block's targets and the clause line disagree**, said where the question is asked.
            if (offer.isMaterial) {
                addAll(
                    (Line("    ") + Line("a block", Palette.parameter) +
                        Line(" — so it can also be aimed at anything made of something", Palette.faint))
                        .wrapped(width, "    "),
                )
            }
        }
    }

    /**
     * One half of what a page claims — **one line each, and a pool kept together.**
     *
     * The label carries the required/requested distinction rather than the wording, because the two are
     * spelled identically: `colour=red` demanded and `colour=red` offered are the same six characters and
     * completely different promises.
     *
     * A pool is indented under the count that draws from it, so what an Age chooses *between* reads as a
     * group rather than as three more settings on the same list.
     */
    private fun claimLines(
        label: String,
        claims: List<Suggestions.Claim>,
        tone: TextStyle,
        width: Int,
    ): List<Line> {
        if (claims.isEmpty()) return emptyList()
        val gutter = " ".repeat(label.length + 2)
        val room = (width - INDENT - gutter.length).coerceAtLeast(MINIMUM_SAYS)
        return buildList {
            for ((at, claim) in claims.withIndex()) {
                for ((line, said) in wrapped(claim.said, room).withIndex()) {
                    val head = if (at == 0 && line == 0) "$label  " else gutter
                    add(Line("    ") + Line(head, tone) + Line(said, Palette.faint))
                }
                for (member in claim.drawnFrom) {
                    add(
                        Line("    ") + Line(gutter, tone) + Line("${Glyph.BAR} ", Palette.rule) +
                            Line(member, Palette.value),
                    )
                }
            }
        }
    }

    /** [said] broken on spaces to fit [width], because a long query is one line of nothing legible. */
    private fun wrapped(said: String, width: Int): List<String> {
        if (said.length <= width) return listOf(said)
        val lines = mutableListOf<String>()
        var standing = StringBuilder()
        for (word in said.split(' ')) {
            if (standing.isNotEmpty() && standing.length + 1 + word.length > width) {
                lines += standing.toString()
                standing = StringBuilder()
            }
            if (standing.isNotEmpty()) standing.append(' ')
            standing.append(word)
        }
        if (standing.isNotEmpty()) lines += standing.toString()
        return lines
    }

    /**
     * The row as written, with the caret where the next page goes.
     *
     * Everything after the caret is dimmed: it is still in the book and it is not what the suggestions
     * are about, and showing the two alike made it look as though the list had stopped making sense.
     */
    private fun rowLines(width: Int): List<Line> {
        var line = Line("  ")
        for (page in laid.take(caret)) {
            line += Line("$page ", if (page in closingPages) Palette.chosen else Palette.value)
        }
        line += Line(typed, Palette.heading) + Line(CURSOR, Palette.focused)
        for (page in laid.drop(caret)) {
            line += Line(" $page", Palette.faint)
        }
        // **Wrapped rather than cut.** A book is written until it is finished and the caret is usually at
        // the end of it, so a row sized to the pane would hide the very thing being typed.
        return line.wrapped(width, "  ")
    }

    private fun offerLines(room: Int): List<Line> {
        val shown = table.shown
        table.window = room
        if (shown.isEmpty()) {
            return listOf(
                Line(
                    "    " + if (table.rows.isEmpty()) table.whenEmpty else "nothing here starts '$typed'",
                    Palette.warned,
                ),
            )
        }
        val first = (table.index - room / 2).coerceIn(0, (shown.size - room).coerceAtLeast(0))
        return shown.drop(first).take(room).mapIndexed { offset, row ->
            rowLine(table, row, here = first + offset == table.index)
        }
    }

    /** What is on the list, and what is being kept off it. */
    private fun countLine(): Line {
        val hidden = offers.inert.size
        return Line("  ${listed().size} pages", Palette.faint) +
            when {
                hidden == 0 -> Line("")
                showingInert -> Line("  ${Glyph.BULLET} $hidden of them would parse and do nothing  ${Glyph.BULLET} ", Palette.warned) + Line("[^i]", Palette.key) + Line(" hide", Palette.faint)
                else -> Line("  ${Glyph.BULLET} $hidden hidden that would do nothing here  ${Glyph.BULLET} ", Palette.faint) + Line("[^i]", Palette.key) + Line(" show", Palette.faint)
            }
    }

    /**
     * What the clause being written is about — **and whether one is being written at all.**
     *
     * Listing every aiming page at a clause boundary is true and misleading: a row that has just closed a
     * clause can be followed by anything, so a line saying "can be aimed at" the whole world reads as
     * though something were already being aimed. Nothing is, until a page goes down.
     */
    private fun aimLine(): Line {
        val before = laid.take(caret)
        val label = Line("  clause           ", Palette.faint)
        val opened = before.isNotEmpty() && !suggesting.isASentence(before)
        return label + when {
            opened && closers.isEmpty() ->
                Line("cannot be closed — nothing that follows would finish it", Palette.refused)
            !opened -> Line("not started — the next page opens one", Palette.faint)
            // The nucleus is about the whole Age rather than a part of it, so nothing is aimed there
            // either; what it wants is the `age` page, and that is worth saying outright.
            suggesting.aimsStillOpen(before) == null ->
                Line("the whole Age, and it ends with ", Palette.faint) + Line("age", Palette.chosen)
            closers.size == 1 -> Line("aimed at ", Palette.faint) + Line(closers.single(), Palette.chosen)
            else -> Line("could still be aimed at ", Palette.faint) +
                Line(closers.joinToString(" "), Palette.chosen)
        }
    }

    /**
     * What the book says and what it costs, live.
     *
     * **The aiming pages still open are the guidance a writer actually needs**: which part of the world a
     * clause is about is settled by the page that *ends* it, so until one is laid nothing else on screen
     * says what the modifiers already written are being said about.
     */
    private fun readingLines(): List<Line> = buildList {
        add(aimLine())
        val read = if (laid.isEmpty()) null else Grammar.read(corpus.vocabulary, laid)
        val resolved = resolution()
        add(
            Line("  reads as         ", Palette.faint) +
                when {
                    laid.isEmpty() -> Line("nothing yet", Palette.faint)
                    resolved == null || read == null ->
                        Line("not a book yet - it wants the `age` page and a close", Palette.warned)
                    else -> Line(Readout.of(read), Palette.value)
                },
        )
        if (resolved != null) {
            add(
                Line("  costs            ", Palette.faint) +
                    Line("${resolved.cost} ink", Palette.value) +
                    Line(
                        "   ${resolved.instability}",
                        if (resolved.instability.isCoherent) Palette.settled else Palette.warned,
                    ),
            )
            for (flaw in resolved.instability.flaws.take(FLAWS_SHOWN)) {
                add(Line("    ${Glyph.WARN} ", Palette.warned) + Line(flaw.describe(), Palette.faint))
            }
        }
    }

    // -- saving ----------------------------------------------------------------------------------------

    private fun save() {
        if (name.isEmpty()) {
            askForTheName()
            if (name.isEmpty()) return
        }
        runCatching { AgeDraft.write(draft()) }.fold(
            onSuccess = { saved = true; message = "saved to ${it.path}" },
            onFailure = { message = it.message.orEmpty() },
        )
    }

    /**
     * What the Age is called - **typed however you like, and written down as an id.**
     *
     * A name becomes a `namespace:path`, and a resource path is lower case with no spaces in it. That is
     * Minecraft's rule rather than ours, and refusing a capital letter over it made the tool look
     * arbitrary; so anything is accepted and [AgeDraft.asAName] settles it, with what it will become
     * shown while it is being typed.
     */
    private fun askForTheName() {
        val said = ask(
            "What is this Age called?",
            "anything you like — it is written down as an id",
            name,
        ) { typedName ->
            if (AgeDraft.asAName(typedName).isEmpty()) "it needs some letters or digits in it" else null
        } ?: return
        name = AgeDraft.asAName(said)
        saved = false
    }

    private fun askForTheSeed() {
        val chosen = choose(
            "Where does the seed come from?",
            Seeding.entries.map { entry ->
                Triple(
                    entry.name,
                    entry.title + if (entry == seeding) "  ${Glyph.TICK}" else "",
                    entry.about + when (entry) {
                        Seeding.SETTLED -> "  ${Glyph.BULLET} ${AgeDraft.settledSeed(name)}"
                        Seeding.CHOSEN, Seeding.DRAWN -> ""
                    },
                )
            },
        ) ?: return
        val wanted = Seeding.valueOf(chosen)
        if (wanted == Seeding.CHOSEN) {
            val said = ask("What seed?", "the same book at the same seed makes the same Age", seed.toString()) {
                if (it.toLongOrNull() == null) "a whole number" else null
            } ?: return
            seed = said.toLong()
        }
        if (wanted == Seeding.DRAWN) seed = draft().rolled().seed
        seeding = wanted
        saved = false
        message = "seed ${seedNow()} ${Glyph.BULLET} ${wanted.title}"
    }

    // -- the game --------------------------------------------------------------------------------------

    /**
     * The book, in a running game - **server and client both.**
     *
     * The server is kept between rewrites, which is the whole value: booting one is most of a minute and
     * writing an Age into one already up is a second. So the loop is change a page, press the key, and
     * walk back out into it. The client is started once and joins straight away, because handing somebody
     * an address to type is not opening an Age.
     */
    private fun openInMinecraft() {
        if (resolution() == null) {
            message = "not a book yet - it wants the `age` page and a close"
            return
        }
        if (name.isEmpty()) {
            askForTheName()
            if (name.isEmpty()) return
        }
        // Rolled here rather than when the mode was picked, because "a new one each time" means each time
        // it opens. Done before the reading is drawn again, so the screen never shows a seed that is not
        // the one written.
        if (seeding == Seeding.DRAWN) seed = draft().rolled().seed
        canvas.lending {
            // **Before anything else**, because a word written since the last preview is not in the pack
            // the game reads until it is put there — and an Age written with a word the game has never
            // heard of comes out as though the page were blank.
            PreviewServer.stageTheCorpus(::say)
            val reusing = server != null
            val standing = server ?: runCatching { PreviewServer.boot(::say) }.getOrElse { failure ->
                say("could not start a server: ${failure.message}")
                waitForEnter()
                return@lending
            }
            server = standing
            // A server already up read its corpus at boot; this is what makes it read the staged one.
            if (reusing) {
                say("re-reading the corpus")
                say(standing.rereadTheCorpus())
            }
            say("writing '$name' at seed ${seedNow()}")
            say(standing.write(draft()))
            // **Started again if it has gone.** Quitting the game and pressing the key again is the
            // obvious way to look at a rewrite, and a client that is merely *remembered* rather than
            // checked leaves that doing the server half of the job in silence.
            if (client?.isAlive != true) {
                client = GameClient.start("localhost:${standing.port}", ::say)
            }
            say("")
            if (client == null) say("Connect to  localhost:${standing.port}  and you will be sent to '$name'.")
            awaitAPlayer(standing)
        }
    }

    /**
     * Waits for the client to arrive, and sends whoever is there into the Age.
     *
     * Here rather than on a thread because the screen is blocked on a keystroke and a player arriving is
     * not one. Giving up is not a failure: `^g` does the same thing later and the Age is already written.
     */
    private fun awaitAPlayer(standing: PreviewServer) {
        val deadline = System.nanoTime() + WAIT_SECONDS * NANOS_A_SECOND
        while (System.nanoTime() < deadline) {
            val sent = runCatching { standing.sendEveryoneTo(name) }.getOrDefault(0)
            if (sent > 0) {
                say("sent $sent player(s) to '$name' - opped, and in creative")
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        say("nobody has joined yet. Press ^g once you are in and I will send you across.")
        waitForEnter()
    }

    private fun sendEveryoneIn() {
        val standing = server ?: run { message = "nothing running - ^o first"; return }
        val sent = runCatching { standing.sendEveryoneTo(name) }.getOrDefault(0)
        message = if (sent > 0) "sent $sent player(s) to '$name'" else "nobody is connected"
    }

    private fun stopTheGame() {
        if (server == null && client == null) {
            message = "nothing running"
            return
        }
        canvas.lending { say("closing the game and removing its world"); closeTheGame() }
        message = "stopped"
    }

    /** However this screen is left, the game goes with it. */
    private fun closeTheGame() {
        client?.let(GameClient::stop)
        client = null
        server?.close()
        server = null
    }

    private fun say(said: String) = terminal.println(said)

    private fun waitForEnter() {
        terminal.println("")
        terminal.print("Press enter to go back. ")
        readlnOrNull()
    }

    // -- dialogues -------------------------------------------------------------------------------------

    private fun ask(title: String, hint: String, standing: String, complaint: (String) -> String?): String? {
        var said = standing
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val wrong = complaint(said)
                val willBe = AgeDraft.asAName(said)
                canvas.show(
                    listOf(
                        Line("  $title", Palette.heading),
                        Line("  $hint", Palette.faint),
                        Line.BLANK,
                        Line("  ${Glyph.FOCUS} ", Palette.focused) + Line(said, Palette.value) +
                            Line(CURSOR, Palette.faint),
                        Line.BLANK,
                        Line(
                            if (wrong != null || willBe == said) "" else "  written down as  $willBe",
                            Palette.faint,
                        ),
                        Line("  ${wrong.orEmpty()}", Palette.refused),
                        Frame.rule(canvas.width),
                        hints("enter" to "accept", "escape" to "leave it alone"),
                    ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return null
                    key.key == "Escape" -> return null
                    key.key == "Enter" -> if (wrong == null) return said
                    key.key == "Backspace" -> said = said.dropLast(1)
                    key.key.length == 1 && !key.ctrl && !key.alt -> said += key.key
                }
            }
        }
    }

    /** One of a few, picked - small enough that it needs no filtering and no scrolling. */
    private fun choose(title: String, options: List<Triple<String, String, String>>): String? {
        var at = 0
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    listOf(Line("  $title", Palette.heading), Line.BLANK) +
                        options.mapIndexed { index, (_, label, about) ->
                            val here = index == at
                            Line(if (here) "  ${Glyph.FOCUS} " else "    ", Palette.focused) +
                                Line(label.padEnd(OPTION_WIDTH), if (here) Palette.value else Palette.faint) +
                                Line(about, Palette.faint)
                        } +
                        listOf(
                            Line.BLANK,
                            Frame.rule(canvas.width),
                            hints("↑↓" to "move", "enter" to "choose", "escape" to "leave it"),
                        ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return null
                    key.key == "Escape" || key.key == "ArrowLeft" -> return null
                    key.key == "ArrowUp" -> at = (at - 1 + options.size) % options.size
                    key.key == "ArrowDown" -> at = (at + 1) % options.size
                    key.key == "Enter" || key.key == "ArrowRight" -> return options[at].first
                }
            }
        }
    }

    /** Yes or no, defaulting to no - for the one thing on this screen that cannot be undone. */
    private fun sure(title: String, about: String): Boolean {
        var yes = false
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    listOf(
                        Line("  ${Glyph.WARN} $title", Palette.refused),
                        Line("  $about", Palette.faint),
                        Line.BLANK,
                        Line("  ${if (yes) Glyph.FOCUS else " "} ", Palette.focused) +
                            Line("delete it", if (yes) Palette.refused else Palette.faint),
                        Line("  ${if (yes) " " else Glyph.FOCUS} ", Palette.focused) +
                            Line("keep it", if (yes) Palette.faint else Palette.value),
                        Line.BLANK,
                        Frame.rule(canvas.width),
                        hints("↑↓" to "move", "enter" to "do it", "escape" to "keep it"),
                    ),
                )
                val key = scope.readKey() ?: return false
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return false
                    key.key == "Escape" || key.key == "ArrowLeft" -> return false
                    key.key == "ArrowUp" || key.key == "ArrowDown" -> yes = !yes
                    key.key == "Enter" -> return yes
                }
            }
        }
    }

    private companion object {
        const val PAGE_WIDTH = 26

        /** `restrictive` is the longest of the three. */
        const val TIER_WIDTH = 12

        /**
         * Enough for two aspect pages before the cut.
         *
         * A word reaching six of them will not fit whatever this is, and `cell` slides a match into view,
         * so searching `structures` still shows why the row is on the list.
         */
        const val TARGETS_WIDTH = 22

        /** The four columns `rowLine` indents every row by. */
        const val INDENT = 4

        /** Everything a list row spends before `what it does` takes what is left. */
        const val COLUMNS_SPENT = INDENT + PAGE_WIDTH + Frame.GUTTER + TIER_WIDTH + Frame.GUTTER +
            TARGETS_WIDTH + Frame.GUTTER

        /**
         * The narrowest the book pane is worth drawing beside the list — enough for a labelled line to
         * wrap twice rather than become a column of single words. Below this the two go back to stacked.
         */
        const val BOOK_PANE_LEAST = 34

        /** What share of the screen the book takes where there is more than the least to share out. */
        const val BOOK_PANE_SHARE = 2
        const val SHARE_OF = 5

        /** Where a wrapped label's continuation starts, so `reads as …` runs under itself, not the label. */
        const val READING_HANGING = "                   "

        /**
         * How tall the book is drawn where it sits above the list rather than beside it.
         *
         * Enough for the row, the reading and the first few lines of the detail. It shrinks on a short
         * terminal so at least one offer is always visible — a list with no rows in it is worse than a
         * detail with a `… 4 more` on the end.
         */
        const val STACKED_BOOK_ROWS = 10

        /** The rule and the header the list carries above its rows. */
        const val LIST_CHROME = 2

        const val FLAWS_SHOWN = 3

        /** What `what it does` keeps on a terminal too narrow to give it anything. */
        const val MINIMUM_SAYS = 20

        /**
         * How much of the highlighted page's claims is worth showing before it crowds the list out.
         *
         * The frame gives the list whatever is left, so an unbounded block would shrink it to a line —
         * and a word with eight facets is one to open in the word screen, not to read here.
         */
        const val DETAIL_LINES = 8
        const val CURSOR = "▉"
        const val OPTION_WIDTH = 24
        const val NAME_WIDTH = 22
        const val SEEDING_WIDTH = 20
        const val BOOK_WIDTH = 70

        const val WAIT_SECONDS = 180L
        const val NANOS_A_SECOND = 1_000_000_000L
        const val POLL_MILLIS = 1500L
    }
}
