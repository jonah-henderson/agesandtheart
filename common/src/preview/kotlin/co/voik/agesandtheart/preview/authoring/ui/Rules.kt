package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.DerivedTags
import co.voik.agesandtheart.age.word.DerivationRules.Rule
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.DerivationRules
import co.voik.agesandtheart.preview.authoring.ServerSnapshot
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * **How each of our tags fills itself** — read only, and the tag is the unit.
 *
 * It listed the 142 rules flat, one row each, with the aspect in the first column and the rule split
 * across three more: a bare key, the *kind* of key, and "and calls it", which reads as renaming. The
 * subject of that sentence — a member of the world — appeared in no column at all, so "it" referred to
 * nothing and the row could not be read.
 *
 * The tag is the unit because that is the question: **what fills `wooded`?** Fan-in is wildly uneven —
 * `grown` has thirty-one rules behind it and `monumental` has one — so 36 tags is a shape a reader can
 * hold where 142 rules is not, and it puts this screen on the same unit as the tag editor.
 *
 * **Read only, and it says so at the top.** One rule moves dozens of members at once, which is a
 * different kind of editing from nudging one weight and should not be reachable by accident from a screen
 * that nudges weights (`notes/the-tag-layer.md` §5).
 */
class Rules(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private var corpus: Corpus,
) {

    private val rules: List<Rule> by lazy { DerivationRules.of(corpus) }

    /** The rules that fill each tag, strongest fan-in first — what the list is built from. */
    private val filling: Map<String, List<Rule>> by lazy {
        rules.flatMap { rule -> rule.fills.keys.map { it to rule } }
            .groupBy({ it.first }, { it.second })
    }

    fun run() {
        val table = Table(
            title = "${filling.size} tags filled automatically",
            columns = listOf(
                Table.Column("tag", TAG_WIDTH),
                Table.Column("rules", COUNT_WIDTH),
                Table.Column("members", COUNT_WIDTH),
                Table.Column("from", WHERE_WIDTH, grows = true),
            ),
            rows = canvas.whileBusy("Reading the rules") { filling.keys.sorted().map(::tagRow) },
        )
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(lines(table))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return
                    key.key == "ArrowLeft" -> return
                    key.key == "ArrowRight" || key.key == "Enter" ->
                        table.focused?.key?.let(::openTheRules)
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Tab" -> table.sortByTheColumnInHand()
                    key.key == "Backspace" -> table.backspace()
                    key.ctrl && key.key == "l" -> if (refreshed()) table.withRows(
                        canvas.whileBusy("Reading the rules") { filling.keys.sorted().map(::tagRow) },
                    )
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    /**
     * The rules behind one tag, **on a screen of its own rather than in a pane beside the list.**
     *
     * `grown` has thirty-one of them and each carries a wrapped list of what it caught, so the pane that
     * suits the word editor's one-line notes is a column of cut-off sentences here. What is being read is
     * a document, and a document wants the width.
     */
    private fun openTheRules(tag: String) {
        val reader = Reader("what fills ${Word.TAG_MARK}$tag", canvas.whileBusy("Running the rules") {
            rulesFilling(tag)
        })
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val window = (canvas.height - CHROME).coerceAtLeast(1)
                val whole = reader.lines.flatMap { it.wrapped(canvas.width - INDENT, "      ") }
                reader.rows = whole.size
                canvas.show(
                    listOf(Line("  ${reader.title}", Palette.heading), Line.BLANK) +
                        whole.drop(reader.offset).take(window).map { Line("  ") + it } +
                        listOf(
                            Frame.rule(canvas.width),
                            hints("↑↓" to "scroll", "pgup/pgdn" to "a page", "←" to "back"),
                        ),
                )
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" || key.key == "ArrowLeft" -> return
                    key.key == "ArrowUp" -> reader.scroll(-1, window)
                    key.key == "ArrowDown" -> reader.scroll(1, window)
                    key.key == "PageUp" -> reader.scroll(-window, window)
                    key.key == "PageDown" -> reader.scroll(window, window)
                }
            }
        }
    }

    private fun tagRow(tag: String): Table.Row {
        val here = filling.getValue(tag)
        val caught = here.sumOf { DerivationRules.catches(it, corpus).members.size }
        return Table.Row(
            key = tag,
            cells = listOf(
                "${Word.TAG_MARK}$tag",
                here.size.toString(),
                if (caught == 0) "" else caught.toString(),
                here.map { it.aspect.page }.distinct().sorted().joinToString(" "),
            ),
            tone = if (caught == 0) Palette.warned else null,
        )
    }

    private fun lines(table: Table): List<Line> = tableLines(
        table,
        canvas,
        listOf(
            Line(
                "  read only ${Glyph.BULLET} how the tags fill themselves from the game's own tags and " +
                    "facts ${Glyph.BULLET} art/derivation/",
                Palette.faint,
            ),
            hints(
                "enter" to "the rules that fill it",
                "tab" to "sort",
                "^l" to "load minecraft data",
                "←" to "back",
                searching(table.filter),
            ),
        ),
    )

    /**
     * The rules that fill one tag, each said as **a sentence about a member** — and what each caught.
     *
     * Three mechanisms wear three verbs on purpose. A feature carries no tags at all in vanilla, so a
     * `by_tag` rule there reads the tags of the *blocks the feature places*, weighted by what share of
     * them carry it; saying "carries" of that would be false of the largest group of rules there is.
     */
    private fun rulesFilling(tag: String): List<Line> = buildList {
        val here = filling[tag].orEmpty()
        add(Line("what fills ", Palette.faint) + Line("${Word.TAG_MARK}$tag", Palette.tag))
        add(Line.BLANK)
        for (rule in here) {
            val caught = DerivationRules.catches(rule, corpus)
            add(
                Line(rule.aspect.page.padEnd(ASPECT_WIDTH), Palette.aspect) +
                    Line(saidOf(rule), Palette.value) +
                    Line(mechanicOf(rule)?.let { "  ($it)" }.orEmpty(), Palette.faint) +
                    Line("  %.1f".format(rule.fills.getValue(tag)), Palette.faint),
            )
            add(
                Line("  ".padEnd(ASPECT_WIDTH)) + when {
                    caught.members.isEmpty() -> Line("catches nothing — no snapshot to ask", Palette.warned)
                    caught.fromAServer -> Line("${caught.members.size} members, from a server", Palette.nudged)
                    else -> Line("${caught.members.size} members", Palette.settled)
                },
            )
            if (caught.members.isNotEmpty()) {
                val shown = caught.members.take(SHOWN).joinToString("  ")
                val more = (caught.members.size - SHOWN).takeIf { it > 0 }
                    ?.let { "  ${Glyph.ELIDED} and $it more" }.orEmpty()
                addAll(Line("  ".padEnd(ASPECT_WIDTH) + shown + more, Palette.faint).wrapped(PANE_TEXT, "    "))
            }
            add(Line.BLANK)
        }
    }

    /**
     * One rule as a predicate about a member: `places blocks tagged #minecraft:logs`, `is a monster`.
     *
     * The old screen had a column saying whether the key was "a tag it carries" or "a fact it states",
     * which is a fact about the rule's shape rather than about the world — and wrong for every feature
     * rule, features carrying no tags of their own.
     */
    /**
     * A snapshot taken now, in the background, **without leaving the screen that needed it.**
     *
     * The rules that read a registry tag catch nothing until a game has bound one, so this is the one
     * screen where the answer to "why is this empty" is a two-minute errand. Menu-and-back is a long way
     * to go for it.
     */
    private fun refreshed(): Boolean {
        var far = ServerSnapshot.Progress(0, 0, "starting")
        val taken = canvas.whileBusy(saying = { bar(far) }) {
            runCatching {
                ServerSnapshot.refresh(
                    attach = null,
                    serverOnlyTags = corpus.vocabulary.tagsOnlyAServerGrants,
                ) { far = it }
            }
        }
        taken.onFailure { return false }
        taken.getOrNull()?.write()
        corpus = Corpus(corpus.vocabulary, ServerSnapshot.read())
        return true
    }

    /** The errand and how far through it, drawn — a bar only once there is a total to be a share of. */
    private fun bar(far: ServerSnapshot.Progress): String {
        if (far.total <= 0) return far.what
        val full = (far.share * BAR).toInt().coerceIn(0, BAR)
        return Glyph.FULL.repeat(full) + Glyph.EMPTY.repeat(BAR - full) +
            "  ${far.done}/${far.total}  ${far.what}"
    }

    /**
     * What the sentence beside it actually tests, where a reader could not work it out.
     *
     * `is scalding` is a threshold and `spawns as monster` is a field on the entity type; saying which
     * costs a parenthesis and saves a trip to the source. The bands come from [DerivedTags] itself rather
     * than being written out here, so the words and the numbers cannot drift apart.
     */
    private fun mechanicOf(rule: Rule): String? = when {
        rule.byTag && rule.aspect == Aspect.FEATURES -> "share of the blocks it places"
        rule.byTag -> null
        rule.aspect == Aspect.FEATURES -> "the feature it places"
        rule.aspect == Aspect.SPAWNS -> "MobCategory"
        else -> DerivedTags.BANDS[rule.key]
    }

    private fun saidOf(rule: Rule): String = when {
        rule.aspect == Aspect.FEATURES && rule.byTag -> "places blocks tagged ${rule.key}"
        rule.aspect == Aspect.FEATURES -> "places a ${rule.key}"
        rule.byTag -> "is tagged ${rule.key}"
        rule.aspect == Aspect.SPAWNS -> "spawns as ${rule.key}"
        else -> "is ${rule.key}"
    }

    private companion object {
        /** The two columns a reading is indented by, and the chrome around one. */
        const val INDENT = 2
        const val CHROME = 4

        /** How wide the refresh bar is drawn. */
        const val BAR = 24

        const val TAG_WIDTH = 16
        const val COUNT_WIDTH = 7
        const val WHERE_WIDTH = 20
        const val ASPECT_WIDTH = 11

        /** How wide the list stays when a tag is open beside it. */
        const val RULES_PANE = 56

        /** Below this the pane cannot say anything, so the list keeps the whole screen. */
        const val NARROWEST = 40

        /** What a wrapped member list is laid to, inside the pane. */
        const val PANE_TEXT = RULES_PANE - 4

        const val SHOWN = 60
    }
}
