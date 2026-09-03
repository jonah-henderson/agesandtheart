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
    /**
     * The rules behind one tag, **three panes: where, which, and what it took.**
     *
     * A reading was a document and the fan-in makes that unreadable — `grown` has thirty-one rules across
     * two aspects, each with a wrapped list of members underneath, so finding the one about biomes meant
     * scrolling past twenty about features. The aspect is the first cut because it is the one a reader
     * already has in mind, and the rules under it are a table like every other list here.
     *
     * The third pane **follows the cursor rather than taking it**: what a rule caught is the answer to
     * the row you are on, not a place to go.
     */
    private fun openTheRules(tag: String) {
        val here = filling[tag].orEmpty()
        val aspects = here.map { it.aspect }.distinct().sortedBy { it.page }
        if (aspects.isEmpty()) return
        var at = 0
        var inside = false
        var rules = tableOf(here, aspects[at])
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(detailLines(tag, aspects, at, inside, rules))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()

                /** Whichever list has the cursor, moved — and the rules rebuilt where the aspect moved. */
                fun move(by: Int) {
                    if (inside) return rules.move(by)
                    at = (at + by).coerceIn(0, aspects.lastIndex)
                    rules = tableOf(here, aspects[at])
                }
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" -> if (inside) inside = false else return
                    key.key == "ArrowLeft" -> if (inside) inside = false else return
                    key.key == "ArrowRight" || key.key == "Enter" -> inside = true
                    key.key == "ArrowUp" -> move(-1)
                    key.key == "ArrowDown" -> move(1)
                    key.key == "Home" -> if (inside) rules.home() else { at = 0; rules = tableOf(here, aspects[0]) }
                    key.key == "End" -> if (inside) rules.end() else {
                        at = aspects.lastIndex
                        rules = tableOf(here, aspects[at])
                    }
                    key.key == "PageUp" -> if (inside) rules.page(-1) else move(-1)
                    key.key == "PageDown" -> if (inside) rules.page(1) else move(1)
                }
            }
        }
    }

    /** One aspect's rules, as the table every other list on these screens is. */
    private fun tableOf(here: List<Rule>, aspect: Aspect): Table = Table(
        title = "",
        columns = listOf(
            Table.Column("predicate", PREDICATE_LEAST, grows = true),
            Table.Column("strength", STRENGTH_WIDTH),
            Table.Column("members", COUNT_WIDTH),
        ),
        rows = here.filter { it.aspect == aspect }.map { rule ->
            val caught = DerivationRules.catches(rule, corpus)
            Table.Row(
                key = rule.id,
                cells = listOf(
                    saidOf(rule) + mechanicOf(rule)?.let { "  ($it)" }.orEmpty(),
                    "%.1f".format(rule.fills.values.max()),
                    if (caught.members.isEmpty()) "" else caught.members.size.toString(),
                ),
                tone = if (caught.members.isEmpty()) Palette.warned else null,
            )
        },
        whenEmpty = "no rule here fills it",
    )

    private fun detailLines(
        tag: String,
        aspects: List<Aspect>,
        at: Int,
        inside: Boolean,
        rules: Table,
    ): List<Line> {
        val room = (canvas.height - CHROME).coerceAtLeast(1)
        val caught = rules.focused?.let { row -> filling[tag].orEmpty().firstOrNull { it.id == row.key } }
            ?.let { DerivationRules.catches(it, corpus) }
        val forMembers = if (caught == null || canvas.width < WIDE) 0 else MEMBERS_PANE
        val forRules = canvas.width - ASPECTS_PANE - Frame.GUTTER -
            if (forMembers == 0) 0 else forMembers + Frame.GUTTER
        rules.room = (forRules - CURSOR_COLUMN).coerceAtLeast(MINIMUM_ROOM)
        rules.window = room - 1

        val listed = aspects.mapIndexed { where, aspect ->
            val focused = where == at && !inside
            Line(if (focused) "${Glyph.FOCUS} " else "  ", Palette.focused) +
                Line(aspect.page, if (where == at) Palette.aspect else Palette.faint)
        }
        val shown = rules.shown
        val first = (rules.index - room / 2).coerceIn(0, (shown.size - room + 1).coerceAtLeast(0))
        val table = listOf(headerLine(rules)) + shown.drop(first).take(room - 1).mapIndexed { offset, row ->
            rowLine(rules, row, here = inside && first + offset == rules.index)
        }
        val body = Frame.beside(listed, ASPECTS_PANE, table, forRules)
        val whole = if (forMembers == 0) body else {
            val members = membersPane(requireNotNull(caught), forMembers)
            body.mapIndexed { line, drawn ->
                drawn + Line(" ${Glyph.BAR} ", Palette.rule) + (members.getOrNull(line) ?: Line.BLANK)
            }
        }
        return listOf(
            Line("  what fills ", Palette.heading) + Line("${Word.TAG_MARK}$tag", Palette.tag),
            Frame.rule(canvas.width),
        ) + whole.take(room) + listOf(
            Frame.rule(canvas.width),
            hints(
                "↑↓" to if (inside) "rule" else "aspect",
                if (inside) "←" to "aspects" else "→" to "its rules",
                "esc" to "back",
            ),
        )
    }

    /** What the rule under the cursor took, listed — the answer to the row rather than a place to go. */
    private fun membersPane(caught: DerivationRules.Caught, width: Int): List<Line> = buildList {
        add(
            when {
                caught.members.isEmpty() -> Line("nothing — no snapshot to ask", Palette.warned)
                caught.fromAServer -> Line("${caught.members.size} members, from a server", Palette.nudged)
                else -> Line("${caught.members.size} members", Palette.settled)
            },
        )
        addAll(caught.members.map { Line(cell(it, width), Palette.faint) })
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
        /** Title, two rules, the key line, and a row the terminal keeps. */
        const val CHROME = 5

        /** The aspects down the left, and what a rule's members get on the right. */
        const val ASPECTS_PANE = 14
        const val MEMBERS_PANE = 30

        /** Below this the third pane cannot say a member's name, so it is not drawn. */
        const val WIDE = 96

        /** The rules table's own columns; the predicate takes whatever is left. */
        const val PREDICATE_LEAST = 24
        const val STRENGTH_WIDTH = 8

        /** How wide the refresh bar is drawn. */
        const val BAR = 24

        const val TAG_WIDTH = 16
        const val COUNT_WIDTH = 7
        const val WHERE_WIDTH = 20
        const val ASPECT_WIDTH = 11

    }
}
