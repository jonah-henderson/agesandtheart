package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.language.DerivedWords
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Gaps
import co.voik.agesandtheart.preview.authoring.Verdict
import co.voik.agesandtheart.preview.authoring.ServerSnapshot
import co.voik.agesandtheart.preview.authoring.WordFile
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey

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
     * Everything the tool can do, in groups.
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

        TAGS("tag editor", "", startsGroup = true),
        RULES("tagging rules", ""),

        WORKSHOP("age workshop", "", startsGroup = true),

        // The one name that does not explain itself: it boots a Minecraft server and reads what only a
        // running game knows — the tags on blocks, biomes and mobs, and anything a modpack adds.
        REFRESH("sync data from server", "boots a server and reads its tags, blocks, biomes and mobs", startsGroup = true),
        HELP("help", ""),
        LEAVE("leave", ""),
    }

    fun run() {
        while (true) {
            val errand = chooseAnErrand() ?: return
            when (errand) {
                Errand.WRITE -> edit(Candidate(name = ""))
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

    /** Whether the list has nothing to show and what was typed could be a word's name. */
    private fun offeringToWrite(table: Table, mayWriteOne: Boolean) =
        mayWriteOne && table.shown.isEmpty() && table.isFiltered && WordFile.couldBeAName(table.filter)

    /**
     * A word, opened — and **the corpus read again afterwards** where the words themselves moved, since every screen
     * answers from the one loaded at startup.
     */
    private fun edit(candidate: Candidate) {
        val changed = Editor(terminal, canvas, corpus, candidate, canLeave = true).run()
        if (changed) corpus = canvas.whileBusy("Reading the corpus again") { Corpus.load() }
    }

    /** A word this pack authored: what it is, how hard it is to find, and what it means. */
    private fun openOne() {
        val rowsOf = {
            val listings = WordFile.everyListing()
            val keptOut = WordFile.authoredKeptOutOfLoot()
            WordFile.authoredNames().map { authoredRow(it, listings[it], isKeptOutOfLoot = it in keptOut) }
        }
        val table = Table(
            title = "${WordFile.authoredNames().size} authored words",
            columns = listOf(
                Table.Column("word", NAME_WIDTH),
                Table.Column("ink", PRICE_WIDTH),
                Table.Column("rarity", RARITY_WIDTH, RARITY, WordFile.rarityChoices()),
                Table.Column("quality", INK_WIDTH, INK, WordFile.inkTiers()),
                Table.Column("means", EFFECTS_WIDTH),
            ),
            rows = canvas.whileBusy(work = rowsOf),
            whenNothingMatches = { typed ->
                if (WordFile.couldBeAName(typed)) "nothing matches '$typed' — enter to write it" else "nothing matches '$typed'"
            },
        )
        while (true) {
            val chosen = walk(table, rowsOf, mayWriteOne = true) ?: return
            if (chosen.isNew) {
                edit(Candidate(name = chosen.key))
            } else {
                WordFile.read(chosen.key).fold(
                    onSuccess = ::edit,
                    onFailure = { failure ->
                        Dialogs.read(terminal, canvas, Reader("'${chosen.key}' would not read", listOf(Line(failure.message.orEmpty(), Palette.refused))))
                    },
                )
            }
            table.withRows(canvas.whileBusy(work = rowsOf))
        }
    }

    private fun authoredRow(name: String, listing: WordFile.Listing?, isKeptOutOfLoot: Boolean): Table.Row {
        val word = corpus.vocabulary.word(name)
        return Table.Row(
            key = name,
            cells = listOf(
                name,
                word?.price?.toString().orEmpty(),
                if (isKeptOutOfLoot) WordFile.NO_LOOT else listing?.rarity.orEmpty(),
                listing?.ink.orEmpty(),
                word?.let(::meansIn).orEmpty(),
            ),
        )
    }

    /** What a word means, in one line: each kind of effect it has, and where. */
    private fun meansIn(word: Word): String = word.effects.joinToString(", ") { effect ->
        when (effect) {
            is Effect.Sets -> "${effect.parameter.spelled}=${effect.value}"
            is Effect.Bends -> "bends ${effect.parameter.spelled}"
            is Effect.Chooses -> "chooses ${effect.member}"
            is Effect.Admits -> "adds ${effect.members.joinToString(" ")}"
            is Effect.Excludes -> "strikes ${effect.members.joinToString(" ")}"
            is Effect.Restricts -> "keeps ${effect.bars.keys.joinToString(" ") { "$TAG_MARK$it" }} in ${effect.aspect.page}"
            is Effect.Leans -> "leans ${effect.aspect.page}"
            is Effect.LeansEverywhere -> "leans everything"
            is Effect.Mints -> "mints ${effect.pattern}"
            is Effect.Template -> "begins from ${effect.name}"
            is Effect.Abundance -> "${Rung.spelled(effect.rung)} times as many"
            is Effect.MadeOf -> "made of ${effect.substance}"
        }
    }

    /** A word the game gave us — every block, biome, placed feature, mob and structure set has one. */
    private fun openADerivedOne() {
        val derived = corpus.vocabulary.derivedWords
        val rowsOf = {
            val listings = WordFile.everyListing()
            val inkTags = WordFile.everyInkTag()
            val lootFence = WordFile.everyIdTagged(CannotAppearInLoot.TAG_NAME)
            val wordless = WordFile.everyIdTagged(DerivedWords.DOES_NOT_HAVE_A_WORD)
            derived.map { derivedRow(it, listings, inkTags, lootFence, wordless) }
        }
        val table = Table(
            title = "${derived.size} auto-generated words",
            columns = listOf(
                Table.Column("word", NAME_WIDTH),
                Table.Column("kind", KIND_WIDTH),
                Table.Column("ink", PRICE_WIDTH),
                Table.Column("rarity", RARITY_WIDTH, RARITY, WordFile.rarityChoices()),
                Table.Column("quality", INK_WIDTH, INK, WordFile.inkTiers()),
                Table.Column("in game", IN_GAME_WIDTH, IN_GAME, listOf(WordFile.NO_WORD)),
                Table.Column("from", EFFECTS_WIDTH),
            ),
            rows = canvas.whileBusy(work = rowsOf),
        )
        while (true) {
            val chosen = walk(table, rowsOf) ?: return
            derived.firstOrNull { it.name == chosen.key }?.let { edit(Candidate.of(it, corpus.vocabulary)) }
            table.withRows(canvas.whileBusy(work = rowsOf))
        }
    }

    /** What an auto-generated word names: the registry it is an entry of, or the aspect whose preset it means. */
    private fun kindOf(word: Word): String {
        word.referentRegistries.firstOrNull()?.let { return registryCalled(it) }
        val aspect = word.effects.filterIsInstance<Effect.Chooses>().firstOrNull()?.aspect ?: return ""
        return aspect.presetsAreEntriesOf?.let(::registryCalled) ?: "${aspect.page} preset"
    }

    private fun registryCalled(registry: ResourceKey<out Registry<*>>): String = when (registry) {
        Registries.PLACED_FEATURE -> "feature"
        Registries.ENTITY_TYPE -> "mob"
        Registries.STRUCTURE_SET -> "structure"
        else -> registry.identifier().path.substringAfterLast('/').replace('_', ' ')
    }

    private fun derivedRow(
        word: Word,
        listings: Map<String, WordFile.Listing>,
        inkTags: Map<String, Map<String, String>>,
        lootFence: Map<String, Set<String>>,
        wordless: Map<String, Set<String>>,
    ): Table.Row {
        val page = Candidate.of(word, corpus.vocabulary)
        fun isIn(tagged: Map<String, Set<String>>) = page.tagDirectory?.let { word.id.toString() in tagged[it].orEmpty() } == true
        val isKeptOutOfLoot = isIn(lootFence)
        val hasNoWord = isIn(wordless)
        val rarity = if (isKeptOutOfLoot) WordFile.NO_LOOT else listings[page.listingKey]?.rarity
        val ink = page.tagDirectory?.let { inkTags[it]?.get(word.id.toString()) } ?: listings[word.name]?.ink
        return Table.Row(
            key = word.name,
            cells = listOf(
                word.name, kindOf(word), word.price.toString(), rarity.orEmpty(), ink.orEmpty(),
                if (hasNoWord) WordFile.NO_WORD else "", word.id.toString(),
            ),
            tone = when {
                hasNoWord -> Palette.faint
                rarity == null && ink == null -> null
                else -> Palette.settled
            },
        )
    }

    /** The rarity, the ink or the word's absence of whichever row the cursor is on, moved one step round. */
    private fun cycle(row: Table.Row, kind: String, at: Int, by: Int) {
        val word = corpus.vocabulary.word(row.key) ?: return
        val candidate = if (corpus.vocabulary.isDerived(word)) Candidate.of(word, corpus.vocabulary) else WordFile.read(row.key).getOrNull() ?: return
        val standing = row.cells.getOrElse(at) { "" }.ifEmpty { null }
        when (kind) {
            RARITY -> WordFile.setRarity(candidate, cycled(listOf(null) + WordFile.rarityChoices(), standing, by))
            INK -> WordFile.setInk(candidate, cycled(listOf(null) + WordFile.inkTiers(), standing, by))
            IN_GAME -> WordFile.setHasNoWord(candidate, hasNoWord = standing == null)
        }
    }

    /** What the Art cannot yet say, and a word begun for whatever you pick. */
    private fun fillAGap() {
        val rowsOf = { canvas.whileBusy("Looking for what nothing reaches") { Gaps.of(corpus) }.map(::gapRow) }
        val table = Table(
            title = "things that can't be expressed with the current corpus",
            columns = listOf(
                Table.Column("kind", GAP_KIND_WIDTH),
                Table.Column("what", NAME_WIDTH),
                Table.Column("where", RARITY_WIDTH + INK_WIDTH),
                Table.Column("", EFFECTS_WIDTH),
            ),
            rows = rowsOf(),
            whenEmpty = "nothing in the world is out of reach — every tag, parameter and value has a word",
        )
        while (true) {
            val chosen = walk(table, rowsOf) ?: return
            val gap = Gaps.of(corpus).firstOrNull { it.key == chosen.key } ?: continue
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

    /** The audit, as a list you can act on: choosing a word opens it. */
    private fun auditAndOpen() {
        var byName = false
        var filter = ""
        while (true) {
            val judged = canvas.whileBusy("Auditing ${WordFile.authoredNames().size} words") { audited(byName) }
            if (judged.isEmpty()) {
                Dialogs.read(terminal, canvas, Reader("Audit", listOf(Line("No word needs attention.", Palette.settled))))
                return
            }
            val picker = Picker(title = "${judged.size} words need attention", options = judged.map(::rowFor), filter = filter) {}
            val sorting = if (byName) "by name" else "worst first"
            var resorted = false
            val chosen = choose(picker, "$sorting ${Glyph.BULLET} tab to re-sort", onTab = { byName = !byName; resorted = true })
            filter = picker.filter
            when {
                chosen != null -> WordFile.read(chosen).onSuccess(::edit)
                !resorted -> return
            }
        }
    }

    /** How bad a word is at a glance: a mark for the worst thing wrong with it, a colour for how much is. */
    private fun rowFor(judged: Pair<String, List<Verdict.Finding>>): Picker.Option {
        val (name, found) = judged
        val worst = found.minOf { it.standing.ordinal }
        return Picker.Option(
            value = name,
            label = name,
            note = found.joinToString("  ${Glyph.BULLET} ") { it.says },
            mark = Verdict.Standing.entries[worst].mark,
            tone = when {
                found.size >= MANY -> Palette.refused
                found.size >= SOME -> Palette.warned
                else -> Palette.value
            },
        )
    }

    private fun audited(byName: Boolean): List<Pair<String, List<Verdict.Finding>>> {
        val judged = WordFile.authoredNames().map { name ->
            val candidate = WordFile.read(name).getOrNull()
            name to candidate?.let { Verdict.on(it, corpus.vocabulary, quarrels = false) }.orEmpty()
                .filterNot { it.standing == Verdict.Standing.NOTED }
        }
        val worth = judged.filter { (_, found) -> found.isNotEmpty() }
        return if (byName) worth.sortedBy { (name, _) -> name } else worth.sortedWith(Verdict.WORST_FIRST)
    }

    /** What walking a list came back with: a row, or a name to write that no row had. */
    private data class Chosen(val key: String, val isNew: Boolean = false)

    /**
     * A table, walked: left and right move between columns, up and down within one. On a rarity or an ink column up
     * and down cycle the value under the cursor, written straight away and the rows rebuilt from disk.
     */
    private fun walk(table: Table, rowsOf: () -> List<Table.Row>, mayWriteOne: Boolean = false): Chosen? {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(tableLines(table))
                val key = scope.readKey()
                if (key.ctrl && key.key == "c") throw Leaving()
                val row = table.focused
                val kind = table.columns[table.column].kind

                fun bump(by: Int) {
                    if (kind.isEmpty() || row == null) return
                    cycle(row, kind, table.column, by)
                    table.withRows(canvas.whileBusy(work = rowsOf))
                }

                when {
                    key.ctrl && key.key == "q" -> return null
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return null
                    key.key == "ArrowLeft" -> if (table.column == 0) return null else table.across(-1)
                    key.key == "ArrowRight" -> table.across(1)
                    key.key == "Enter" -> when {
                        offeringToWrite(table, mayWriteOne) -> return Chosen(table.filter, isNew = true)
                        kind.isEmpty() -> return Chosen(row?.key ?: continue)
                        else -> bump(1)
                    }
                    key.key == "=" -> bump(1)
                    key.key == "-" -> bump(-1)
                    key.key in HOTKEYS && row != null -> {
                        val wanted = HOTKEYS.getValue(key.key)
                        val at = table.columns.indexOfFirst { it.kind == wanted }
                        if (at >= 0) {
                            cycle(row, wanted, at, 1)
                            table.withRows(canvas.whileBusy(work = rowsOf))
                        }
                    }
                    else -> table.tookTheKey(key)
                }
            }
        }
    }

    private fun tableLines(table: Table): List<Line> = tableLines(
        table,
        canvas,
        listOf(
            hints("↑↓" to "move", "←→" to "column", "home/end" to "first, last", "pgup/pgdn" to "a page", "tab" to "sort"),
            if (table.columns[table.column].cycles) {
                hints("- =" to "change", "enter" to "change", "F1" to "rarity", "F2" to "ink", "F3" to "no word", searching(table.filter))
            } else {
                hints("enter" to "open", "F1" to "rarity", "F2" to "ink", "F3" to "no word", searching(table.filter))
            },
        ),
    )

    /**
     * A server, asked. **The screen is lent back for it**: this is minutes of a game booting and the
     * progress belongs in the scrollback, where it can be read after the fact if it goes wrong.
     */
    private fun refresh() {
        canvas.lending {
            terminal.println("Loading Minecraft data. This boots a server, so give it a minute.")
            runCatching {
                corpus.withFreshSnapshot { far ->
                    terminal.println("  " + if (far.total <= 0) far.what else "${far.done}/${far.total} ${far.what}")
                }
            }.fold(
                onSuccess = { refreshed ->
                    corpus = refreshed
                    terminal.println("Wrote ${ServerSnapshot.FILE.path}")
                },
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
                Picker("Scrivener", options) {},
                subtitle = "a vocabulary editor for Ages and the Art",
                standing = standing(),
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
     * One of a list, chosen. Null where the reader backed out.
     *
     * [canLeave] is false at the top of the tool, where there is nothing behind to go back to: left
     * backs out of a screen, and backing out of the last one should not close the program.
     */
    private fun choose(
        picker: Picker,
        subtitle: String = "",
        /** What is loaded right now, under the subtitle — the corpus's size and where its data came from. */
        standing: String = "",
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
                canvas.show(pickerLines(picker, subtitle, standing))
                val key = scope.readKey()
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return null
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

    private fun pickerLines(picker: Picker, subtitle: String, standing: String = ""): List<Line> = buildList {
        add(Line("  ${picker.title}", Palette.heading))
        val under = listOf(subtitle, standing).filter { it.isNotEmpty() }
        under.forEach { add(Line("  $it", Palette.faint)) }
        add(Line.BLANK)
        val shown = picker.shown
        val grouped = picker.filter.isEmpty()
        // The separators take room too, or a grouped list would run off the bottom of the frame.
        val spacers = if (grouped) shown.count { it.startsGroup } else 0
        val room = (canvas.height - CHROME - 1 - under.size - spacers).coerceAtLeast(1)
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
                searching(picker.filter),
            ),
        )
    }

    private companion object {
        const val LABEL = 26

        /** What a chooser answers with when `?` was asked rather than a row chosen. */
        const val WANTS_HELP = "\u0000help"
        const val NAME_WIDTH = 24

        /** Room for the longest kind, `underground preset`. */
        const val KIND_WIDTH = 19
        const val RARITY_WIDTH = 9
        const val INK_WIDTH = 11
        const val EFFECTS_WIDTH = 60
        const val PRICE_WIDTH = 5

        const val TAG_MARK = "#"

        const val GAP_KIND_WIDTH = 7
        const val RARITY = "rarity"
        const val INK = "ink"
        const val IN_GAME = "in game"
        const val IN_GAME_WIDTH = 9

        /** Which column each function key reaches, by kind rather than by position. */
        val HOTKEYS = mapOf("F1" to RARITY, "F2" to INK, "F3" to IN_GAME)

        /** Where a word stops being ordinarily untidy and starts being worth looking at. */
        const val SOME = 2
        const val MANY = 4

        /** Title, blank, rule and the key line — what a list is not allowed to use. */
        const val CHROME = 4
    }
}
