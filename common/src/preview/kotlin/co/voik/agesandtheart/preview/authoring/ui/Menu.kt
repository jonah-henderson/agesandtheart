package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Gaps
import co.voik.agesandtheart.preview.authoring.ServerSnapshot
import co.voik.agesandtheart.preview.authoring.Verdict
import co.voik.agesandtheart.preview.authoring.WordFile
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Where the tool starts when nobody said what to do — **everything it can do, as a list.**
 *
 * The editor used to be the whole program, so running it dropped you into writing a new word and the
 * other three jobs were flags you had to know about. They are the same jobs; this is where they are
 * visible. The flags still work, and a flag does its one thing and leaves.
 */
class Menu(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private var corpus: Corpus,
) {

    /**
     * Everything the tool can do, in four groups.
     *
     * [startsGroup] is a blank line before the entry and nothing else — no headings, because the four
     * groups are obvious once they are apart and a label on each would be four more things to read. What
     * they separate is the *subject*: the words, the world they describe, writing an Age out of them, and
     * the tool itself.
     */
    private enum class Errand(val title: String, val about: String, val startsGroup: Boolean = false) {
        WRITE("write a new word", ""),
        OPEN("open a word", ""),
        DERIVED("auto-generated words", "derived from the base game and any installed mods"),
        AUDIT("audit words", ""),
        GAPS("missing words", ""),

        RULES("tagging rules", "", startsGroup = true),
        TAGS("the world's tags", ""),

        WORKSHOP("the age workshop", "", startsGroup = true),

        // The one name that does not explain itself: it boots a Minecraft server and reads what only a
        // running game knows — the tags on blocks, biomes and mobs, and anything a modpack adds.
        REFRESH("load minecraft data", "boots a server and reads its tags, blocks, biomes and mobs", startsGroup = true),
        HELP("help", ""),
        LEAVE("leave", ""),
    }

    fun run() {
        while (true) {
            val errand = chooseAnErrand() ?: return
            when (errand) {
                Errand.WRITE -> edit(Candidate.blank(""))
                Errand.OPEN -> openOne()
                Errand.DERIVED -> openADerivedOne()
                Errand.AUDIT -> auditAndOpen()
                Errand.GAPS -> fillAGap()
                Errand.TAGS -> Tags(terminal, canvas, corpus).run()
                Errand.RULES -> Rules(terminal, canvas, corpus).run()
                Errand.WORKSHOP -> Workshop(terminal, canvas, corpus).browse()
                Errand.REFRESH -> refresh()
                Errand.HELP -> Help(terminal, canvas, corpus).run()
                Errand.LEAVE -> return
            }
        }
    }

    /**
     * A word, opened — and **the corpus read again afterwards**.
     *
     * It is loaded once at startup and every screen answers from it, so an edit made through the tool
     * leaves it describing a pack that no longer exists: deleting `sanguine` left `red` still reported as
     * duplicating it, because the word it duplicated was still in memory.
     */
    private fun edit(candidate: Candidate) {
        val changed = Editor(terminal, canvas, corpus, candidate, canLeave = true).run()
        // Only where the words themselves moved. A rarity or an ink is read off the files by whatever
        // shows it, so paying a second to read the whole pack again would buy nothing.
        if (changed) corpus = canvas.whileBusy("Reading the corpus again") { Corpus.load() }
    }

    /** A word this pack authored: what it is, how hard it is to find, and what it does. */
    private fun openOne() {
        val rowsOf = {
            val listings = WordFile.everyListing()
            WordFile.authoredNames().map { authoredRow(it, listings[it]) }
        }
        val table = Table(
            title = "${WordFile.authoredNames().size} authored words",
            columns = listOf(
                Table.Column("word", NAME_WIDTH),
                Table.Column("specificity", TIER_WIDTH, TIER, Tier.entries.map { it.key }),
                Table.Column("rarity", RARITY_WIDTH, RARITY, WordFile.rarityBuckets()),
                Table.Column("ink", INK_WIDTH, INK, WordFile.inkTiers()),
                Table.Column("effects", EFFECTS_WIDTH),
            ),
            rows = canvas.whileBusy(work = rowsOf),
        )
        // **Looped here rather than inside [walk]**, so the same table comes back after a word is closed:
        // the filter, the cursor and the sort are where you left them, and only leaving for the menu
        // starts again.
        while (true) {
            val chosen = walk(table, rowsOf) ?: return
            WordFile.read(chosen).fold(
                onSuccess = ::edit,
                onFailure = { failure ->
                    read(Reader("'$chosen' would not read", listOf(Line(failure.message.orEmpty(), Palette.refused))))
                },
            )
            table.withRows(canvas.whileBusy(work = rowsOf))
        }
    }

    private fun authoredRow(name: String, listing: WordFile.Listing?): Table.Row {
        val word = corpus.vocabulary.word(name)
        return Table.Row(
            key = name,
            cells = listOf(
                name,
                word?.tier?.key.orEmpty(),
                listing?.rarity.orEmpty(),
                listing?.ink.orEmpty(),
                word?.let(::summaryOf).orEmpty(),
            ),
        )
    }

    /** What a word does, in one line — enough to recognise it without opening it. */
    private fun summaryOf(word: Word): String {
        val said = buildList {
            word.chooses.values.forEach { add("chooses $it") }
            word.template?.let { add("template $it") }
            word.mints?.let { add("mints $it") }
            addAll(word.canSet.map { (parameter, value) -> "$parameter=$value" })
            if (word.wanted.isNotEmpty()) add(word.wanted.joinToString(" ") { "$TAG_MARK$it" })
        }
        return said.joinToString(", ").ifEmpty { "nothing" }
    }

    /**
     * A word the game gave us — every block, biome, placed feature, mob and structure set has one.
     *
     * **Built when it is asked for**, not with the rest of the screen: there are about sixteen hundred of
     * them and each row costs two file reads, which is not worth paying on every visit to the menu.
     *
     * There is nothing to change but the rarity and the ink, and both are columns here.
     */
    private fun openADerivedOne() {
        val derived = corpus.vocabulary.derivedWords
        val rowsOf = {
            val listings = WordFile.everyListing()
            val inks = INK_TAG_DIRECTORIES.associateWith(WordFile::everyInkTag)
            derived.map { derivedRow(it, listings, inks) }
        }
        val table = Table(
            title = "${derived.size} auto-generated words",
            columns = listOf(
                Table.Column("word", NAME_WIDTH),
                // Read-only here: an auto-generated word is exact because it names one thing exactly, and
                // there is no file in which to say otherwise.
                Table.Column("specificity", TIER_WIDTH, order = Tier.entries.map { it.key }),
                Table.Column("rarity", RARITY_WIDTH, RARITY, WordFile.rarityBuckets()),
                Table.Column("ink", INK_WIDTH, INK, WordFile.inkTiers()),
                Table.Column("from", EFFECTS_WIDTH),
            ),
            rows = canvas.whileBusy(work = rowsOf),
        )
        while (true) {
            val chosen = walk(table, rowsOf) ?: return
            derived.firstOrNull { it.name == chosen }?.let { edit(Candidate.of(it)) }
            table.withRows(canvas.whileBusy(work = rowsOf))
        }
    }

    private fun derivedRow(
        word: Word,
        listings: Map<String, WordFile.Listing>,
        inks: Map<String, Map<String, String>>,
    ): Table.Row {
        val id = word.id.toString()
        val rarity = listings[id]?.rarity
        val ink = inks.values.firstNotNullOfOrNull { it[id] }
        return Table.Row(
            key = word.name,
            cells = listOf(word.name, word.tier.key, rarity.orEmpty(), ink.orEmpty(), word.id.toString()),
            tone = if (rarity == null && ink == null) null else Palette.settled,
        )
    }

    /**
     * The rarity or the ink of whichever row the cursor is on, moved one step round.
     *
     * Both lists are keyed the same way — an authored word by its name, an auto-generated one by its full
     * id, which is what reaches it and what takes it out of the anonymous mass.
     */
    private fun cycle(row: Table.Row, kind: String, at: Int, by: Int) {
        val word = corpus.vocabulary.word(row.key) ?: return
        val derived = corpus.vocabulary.isDerived(word)
        val key = if (derived) word.id.toString() else row.key
        val standing = row.cells.getOrElse(at) { "" }.ifEmpty { null }
        when (kind) {
            RARITY -> WordFile.list("rarity", key, cycled(listOf(null) + WordFile.rarityBuckets(), standing, by))
            INK -> reink(word, key, derived, cycled(listOf(null) + WordFile.inkTiers(), standing, by))
            TIER -> retier(row.key, standing, by)
        }
    }

    private fun reink(word: Word, key: String, derived: Boolean, wanted: String?) {
        val where = if (derived) corpus.registryOf(word.id) else null
        if (derived && where == null) return
        if (where == null) WordFile.list("ink", key, wanted) else WordFile.inkTagFor(key, where, wanted)
    }

    /**
     * How specific a word is, changed in place — **which rewrites the word file**, unlike rarity and ink.
     *
     * It is still only a value on a line, and the audit will say if the new tier leaves the word making a
     * claim nothing can answer. Better that than making somebody open a word to change one field.
     */
    private fun retier(name: String, standing: String?, by: Int) {
        val candidate = WordFile.read(name).getOrNull() ?: return
        val wanted = cycled(Tier.entries.map { it.key }, standing, by) ?: return
        val tier = Tier.entries.firstOrNull { it.key == wanted } ?: return
        runCatching { WordFile.write(candidate.copy(tier = tier)) }
    }

    /**
     * What the Art cannot yet say, and a word begun for whatever you pick.
     *
     * **Worked out from the words there are**, never from a list kept here: a tag stops being missing the
     * moment something asks for it, and a parameter the moment something turns it. So the screen empties
     * itself as the corpus fills, with nothing to remember to cross off.
     */
    private fun fillAGap() {
        val rowsOf = { canvas.whileBusy("Looking for what nothing reaches") { Gaps.of(corpus) }.map(::gapRow) }
        val table = Table(
            title = "what nothing reaches yet",
            columns = listOf(
                Table.Column("kind", GAP_KIND_WIDTH),
                Table.Column("what", NAME_WIDTH),
                Table.Column("where", RARITY_WIDTH + INK_WIDTH),
                Table.Column("", EFFECTS_WIDTH),
            ),
            rows = rowsOf(),
            // An empty list here is the good outcome, and it should read like one.
            whenEmpty = "nothing in the world is out of reach \u2014 every tag, parameter and value has a word",
        )
        while (true) {
            val chosen = walk(table, rowsOf) ?: return
            val gap = Gaps.of(corpus).firstOrNull { it.key == chosen } ?: continue
            edit(Gaps.wordFor(gap, corpus))
            table.withRows(canvas.whileBusy(work = rowsOf))
        }
    }

    private fun gapRow(gap: Gaps.Gap) = Table.Row(
        key = gap.key,
        cells = listOf(
            gap.kind.title,
            if (gap.parameter.isEmpty()) gap.what else "${gap.parameter}=${gap.what}",
            gap.where.joinToString(" ") { it.page },
            gap.said,
        ),
        tone = if (gap.kind == Gaps.Kind.TAG) Palette.warned else null,
    )

    /**
     * The audit, as a list you can act on. Choosing a word opens it, which is the point of reading the
     * audit at all — it used to be a wall of text you then had to leave to do anything about.
     */
    private fun auditAndOpen() {
        var byName = false
        var filter = ""
        while (true) {
            val judged = canvas.whileBusy("Auditing ${WordFile.authoredNames().size} words") { audited(byName) }
            if (judged.isEmpty()) {
                read(Reader("Audit", listOf(Line("Nothing to answer for.", Palette.settled))))
                return
            }
            val picker = Picker(
                title = "${judged.size} words with something to answer for",
                options = judged.map(::rowFor),
                filter = filter,
            ) {}
            val sorting = if (byName) "by name" else "worst first"
            var resorted = false
            val chosen = choose(
                picker,
                "$sorting ${Glyph.BULLET} tab to re-sort",
                onTab = { byName = !byName; resorted = true },
            )
            // Both tab and backing out end the chooser, so the flag is what tells them apart.
            filter = picker.filter
            when {
                chosen != null -> WordFile.read(chosen).onSuccess(::edit)
                !resorted -> return
            }
        }
    }

    /**
     * How bad a word is, at a glance: **an icon for the worst thing wrong with it, and a colour for how
     * much is wrong.** Two signals rather than one, because a single error and four nudges are different
     * problems and a list sorted worst-first should not make you count to see which is which.
     */
    private fun rowFor(judged: Pair<String, List<Verdict.Finding>>): Picker.Option {
        val (name, found) = judged
        val worst = found.minOf { it.standing.ordinal }
        return Picker.Option(
            value = name,
            label = name,
            note = found.joinToString("  ${Glyph.BULLET} ") { it.says },
            mark = when (Verdict.Standing.entries[worst]) {
                Verdict.Standing.ERROR -> Glyph.CROSS
                Verdict.Standing.WARNED -> Glyph.WARN
                else -> Glyph.BULLET
            },
            tone = when {
                found.size >= MANY -> Palette.refused
                found.size >= SOME -> Palette.warned
                else -> Palette.value
            },
        )
    }

    /** Every authored word with something against it — worst first, or by name. */
    private fun audited(byName: Boolean): List<Pair<String, List<Verdict.Finding>>> {
        val judged = WordFile.authoredNames().map { name ->
            val candidate = WordFile.read(name).getOrNull()
            name to candidate?.let { Verdict.on(it, corpus) }.orEmpty()
                .filterNot { it.standing == Verdict.Standing.NOTED }
        }
        val worth = judged.filter { (_, found) -> found.isNotEmpty() }
        if (byName) return worth.sortedBy { (name, _) -> name }
        return worth.sortedWith(
            compareByDescending<Pair<String, List<Verdict.Finding>>> { (_, found) ->
                found.count { it.standing == Verdict.Standing.ERROR }
            }.thenByDescending { (_, found) -> found.count { it.standing == Verdict.Standing.WARNED } }
                .thenByDescending { (_, found) -> found.size },
        )
    }

    /**
     * A server, asked. **The screen is lent back for it**: this is minutes of a game booting and the
     * progress belongs in the scrollback, where it can be read after the fact if it goes wrong.
     */
    private fun refresh() {
        canvas.lending {
            terminal.println("Loading Minecraft data. This boots a server, so give it a minute.")
            runCatching {
                ServerSnapshot.refresh(
                    attach = null,
                    serverOnlyTags = corpus.vocabulary.tagsOnlyAServerGrants,
                ) { said -> terminal.println("  $said") }
            }.fold(
                onSuccess = { it.write(); terminal.println("Wrote ${it.snapshotPath()}") },
                onFailure = { terminal.println("Nothing loaded: ${it.message}") },
            )
            terminal.println("")
            terminal.print("Press enter to go back. ")
            readlnOrNull()
        }
    }

    private fun chooseAnErrand(): Errand? {
        while (true) {
            val options = Errand.entries.map {
                Picker.Option(it.name, it.title, it.about, startsGroup = it.startsGroup)
            }
            val chosen = choose(
                Picker("The word forge", options) {},
                standing(),
                canLeave = false,
                helpOn = true,
            ) ?: return null
            // Help is asked for from the menu and comes back to it, rather than being an errand you leave
            // by. Its own raw mode is why it cannot simply be opened from inside the chooser.
            if (chosen == WANTS_HELP) Help(terminal, canvas, corpus).run() else return Errand.valueOf(chosen)
        }
    }

    private fun standing(): String {
        val snapshot = corpus.snapshot?.provenance ?: "no minecraft data imported"
        return "${corpus.vocabulary.words.size} words ${Glyph.BULLET} " +
            "${WordFile.authoredNames().size} authored ${Glyph.BULLET} $snapshot"
    }

    /**
     * A table, walked. Null where the reader backed out; otherwise the key of the row they chose.
     *
     * **Left and right move between columns, up and down move within one.** On the name column that means
     * changing row; on a rarity or an ink column it means cycling the value under the cursor, which is
     * written straight away and the rows rebuilt from disk — so what is on screen is what is in the files.
     *
     * [rowsOf] rather than a list, for that rebuild.
     */
    private fun walk(table: Table, rowsOf: () -> List<Table.Row>): String? {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(tableLines(table))
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                val row = table.focused
                val kind = table.columns[table.column].kind

                /** The active column's value, one step on. */
                fun bump(by: Int) {
                    if (kind.isEmpty() || row == null) return
                    cycle(row, kind, table.column, by)
                    table.withRows(canvas.whileBusy(work = rowsOf))
                }

                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return null
                    // Escape gives back what was typed before it gives up the screen — losing a filter
                    // is cheap, losing your place in sixteen hundred rows is not.
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return null
                    key.key == "ArrowLeft" -> if (table.column == 0) return null else table.across(-1)
                    key.key == "ArrowRight" -> table.across(1)
                    // **Up and down always move.** They cycled a value where the cursor sat on one, which
                    // reads well written down and trips you up constantly: the same key moved you on one
                    // column and edited on the next.
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Tab" -> table.sortByTheColumnInHand()
                    key.key == "Backspace" -> table.backspace()
                    // On a value column enter steps it on; on the name it opens the word.
                    key.key == "Enter" -> if (kind.isEmpty()) return row?.key ?: continue else bump(1)
                    key.key == "=" -> bump(1)
                    key.key == "-" -> bump(-1)
                    // The function keys reach a column without moving to it.
                    key.key in HOTKEYS && row != null -> {
                        val wanted = HOTKEYS.getValue(key.key)
                        val at = table.columns.indexOfFirst { it.kind == wanted }
                        if (at >= 0) {
                            cycle(row, wanted, at, 1)
                            table.withRows(canvas.whileBusy(work = rowsOf))
                        }
                    }
                    // Everything else types. `-` and `=` are spent above, which costs nothing: no word
                    // in the corpus has either in its name.
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    private fun tableLines(table: Table): List<Line> = tableLines(
        table,
        canvas,
        listOf(
            hints(
                "\u2191\u2193" to "move",
                "\u2190\u2192" to "column",
                "home/end" to "first, last",
                "pgup/pgdn" to "a page",
                "tab" to "sort",
            ),
            if (table.columns[table.column].cycles) {
                hints(
                    "- =" to "change",
                    "enter" to "change",
                    "F1" to "rarity", "F2" to "ink", "F3" to "specificity",
                    "" to "type to search", "" to table.filter,
                )
            } else {
                hints(
                    "enter" to "open",
                    "F1" to "rarity", "F2" to "ink", "F3" to "specificity",
                    "" to "type to search", "" to table.filter,
                )
            },
        ),
    )

    /**
     * One of a list, chosen. Null where the reader backed out.
     *
     * [canLeave] is false at the top of the tool, where there is nothing behind to go back to: left
     * backs out of a screen, and backing out of the last one should not close the program.
     */
    private fun choose(
        picker: Picker,
        subtitle: String = "",
        canLeave: Boolean = true,
        /**
         * Whether `?` opens the help rather than narrowing the list.
         *
         * True only where typing is not the point. On a list of six errands `?` can only have been a
         * request for help; on a list of a hundred words it is a character somebody is searching with.
         */
        helpOn: Boolean = false,
        /** Tab, where the list has another order to offer. The caller rebuilds and calls again. */
        onTab: (() -> Unit)? = null,
    ): String? {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(pickerLines(picker, subtitle))
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return null
                    key.key == "Tab" && onTab != null -> { onTab(); return null }
                    key.key == "Escape" ->
                        if (picker.filter.isNotEmpty()) picker.clearFilter() else if (canLeave) return null
                    key.key == "ArrowLeft" -> if (canLeave) return null
                    key.key == "Enter" || key.key == "ArrowRight" -> return picker.focused?.value ?: continue
                    key.key == "?" && helpOn -> return WANTS_HELP
                    key.key == "ArrowUp" -> picker.move(-1)
                    key.key == "ArrowDown" -> picker.move(1)
                    key.key == "Backspace" -> picker.backspace()
                    key.key.length == 1 && !key.ctrl && !key.alt -> picker.type(key.key)
                }
            }
        }
    }

    private fun read(reader: Reader) {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val window = (canvas.height - CHROME).coerceAtLeast(1)
                canvas.show(readerLines(reader, window))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" || key.key == "ArrowLeft" || key.key == "Enter" -> return
                    key.key == "ArrowUp" -> reader.scroll(-1, window)
                    key.key == "ArrowDown" -> reader.scroll(1, window)
                }
            }
        }
    }

    private fun pickerLines(picker: Picker, subtitle: String): List<Line> = buildList {
        add(Line("  ${picker.title}", Palette.heading))
        if (subtitle.isNotEmpty()) add(Line("  $subtitle", Palette.faint))
        add(Line.BLANK)
        val shown = picker.shown
        val grouped = picker.filter.isEmpty()
        // The separators take room too, or a grouped list would run off the bottom of the frame.
        val spacers = if (grouped) shown.count { it.startsGroup } else 0
        val room = (canvas.height - CHROME - 2 - spacers).coerceAtLeast(1)
        val first = (picker.index - room / 2).coerceIn(0, (shown.size - room).coerceAtLeast(0))
        for ((offset, option) in shown.drop(first).take(room).withIndex()) {
            val here = first + offset == picker.index
            val name = option.tone ?: if (here) Palette.value else Palette.faint
            val mark = if (option.mark.isEmpty()) "" else "${option.mark} "
            if (option.startsGroup && grouped && offset > 0) add(Line.BLANK)
            add(
                Line(if (here) "  ${Glyph.FOCUS} " else "    ", Palette.focused) +
                    Line(mark, option.tone ?: Palette.faint) +
                    Line(highlighted(option.label.padEnd((LABEL - mark.length).coerceAtLeast(1)), name, picker.filter)) +
                    Line(option.note, Palette.faint),
            )
        }
        if (shown.isEmpty()) add(Line("    nothing matches '${picker.filter}'", Palette.warned))
        add(Line.BLANK)
        add(Frame.rule(canvas.width))
        add(
            hints(
                "↑↓" to "move",
                "→" to "choose",
                "←" to "back",
                "" to "type to search",
                "" to picker.filter,
            ),
        )
    }

    private fun readerLines(reader: Reader, window: Int): List<Line> = buildList {
        add(Line("  ${reader.title}", Palette.heading))
        add(Line.BLANK)
        val whole = reader.lines.flatMap { (Line("  ") + it).wrapped(canvas.width, READER_HANGING) }
        reader.rows = whole.size
        addAll(whole.drop(reader.offset).take(window))
        add(Frame.rule(canvas.width))
        add(hints("↑↓" to "scroll", "←" to "back"))
    }

    private companion object {
        const val LABEL = 26

        /** Where a wrapped reading's continuation lines start, so a break reads as one. */
        const val READER_HANGING = "      "

        /** What a chooser answers with when `?` was asked rather than a row chosen. */
        const val WANTS_HELP = "\u0000help"
        const val NAME_WIDTH = 24
        const val RARITY_WIDTH = 9
        const val INK_WIDTH = 11
        const val EFFECTS_WIDTH = 60
        const val TIER_WIDTH = 12
        const val GAP_KIND_WIDTH = 7
        const val RARITY = "rarity"
        const val INK = "ink"
        const val TIER = "tier"

        /** Where an ink tag can live — one per registry a derived word may name. */
        val INK_TAG_DIRECTORIES = listOf("block", "worldgen/biome", "worldgen/structure_set")

        /** Which column each function key reaches, by kind rather than by position. */
        val HOTKEYS = mapOf("F1" to RARITY, "F2" to INK, "F3" to TIER)

        /** Where a word stops being ordinarily untidy and starts being worth looking at. */
        const val SOME = 2
        const val MANY = 4

        /** Title, blank, rule and the key line — what a list is not allowed to use. */
        const val CHROME = 4
    }
}
