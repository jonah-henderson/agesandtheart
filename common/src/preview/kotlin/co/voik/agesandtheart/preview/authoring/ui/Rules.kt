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
                    key.ctrl && key.key == "q" -> return
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return
                    key.key == "ArrowLeft" -> return
                    key.key == "ArrowRight" || key.key == "Enter" ->
                        table.focused?.key?.let(::openTheRules)
                    key.ctrl && key.key == "l" -> if (refreshed()) table.withRows(
                        canvas.whileBusy("Reading the rules") { filling.keys.sorted().map(::tagRow) },
                    )
                    else -> table.tookTheKey(key)
                }
            }
        }
    }

    /**
     * The rules behind one tag, **three panes: where, which, and what it took.**
     *
     * A reading was a document and the fan-in makes that unreadable — `grown` has thirty-one rules across
     * two aspects, each with a list of members underneath, so finding the one about biomes meant scrolling
     * past twenty about features. The aspect is the first cut because it is the one a reader already has
     * in mind.
     *
     * **All three are tables**, so all three scroll, page, sort and filter without this screen knowing
     * how — the members of `#minecraft:logs` are seventy and a pane that could only be read to the bottom
     * of the screen was the third thing here to hide its own answer. Left and right walk a table's columns
     * and then step to the pane beside it, which is one pair of keys for both and no order to remember.
     */
    private fun openTheRules(tag: String) {
        val here = filling[tag].orEmpty()
        val aspects = here.map { it.aspect }.distinct().sortedBy { it.page }
        if (aspects.isEmpty()) return
        val listed = Table(
            title = "",
            columns = listOf(Table.Column("aspect", ASPECT_LEAST, grows = true)),
            rows = aspects.map { Table.Row(key = it.page, cells = listOf(it.page)) },
        )
        var pane = Pane.ASPECTS
        var rules = tableOf(here, aspects[0])
        var showingAspect = aspects[0].page
        var members: Table? = null
        var showingRule: String? = null

        /** The panes to the right follow the cursor to their left, and only when it has moved. */
        fun follow() {
            val aspect = listed.focused?.key
            if (aspect != null && aspect != showingAspect) {
                showingAspect = aspect
                rules = tableOf(here, aspects.first { it.page == aspect })
            }
            val rule = rules.focused?.key
            if (rule != showingRule) {
                showingRule = rule
                members = here.firstOrNull { it.id == rule }?.let(::membersTable)
            }
        }
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                follow()
                var widths = widthsFor(rules, pane)
                if (widths.members == 0 && pane == Pane.MEMBERS) {
                    pane = Pane.RULES
                    widths = widthsFor(rules, pane)
                }
                canvas.show(detailLines(tag, listed, rules, members, pane, widths))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                val focused = when (pane) {
                    Pane.ASPECTS -> listed
                    Pane.RULES -> rules
                    Pane.MEMBERS -> members
                }

                /** A pane over, the table there taking its cursor to whichever edge was stepped through. */
                fun stepTo(next: Pane, by: Int) {
                    pane = next
                    val landed = if (next == Pane.RULES) rules else if (next == Pane.MEMBERS) members else listed
                    landed?.toColumn(if (by > 0) 0 else landed.columns.lastIndex)
                }

                /** Across the focused table's columns first, and on to the pane beside it once spent. */
                fun step(by: Int): Boolean {
                    if (focused?.acrossOrOff(by) == true) return true
                    val next = when {
                        by > 0 && pane == Pane.ASPECTS -> Pane.RULES
                        by > 0 && pane == Pane.RULES && members != null -> Pane.MEMBERS
                        by < 0 && pane == Pane.MEMBERS -> Pane.RULES
                        by < 0 && pane == Pane.RULES -> Pane.ASPECTS
                        else -> return false
                    }
                    stepTo(next, by)
                    return true
                }
                when {
                    key.ctrl && key.key == "q" -> return
                    key.key == "Escape" -> when {
                        focused?.isFiltered == true -> focused.clearFilter()
                        !step(-1) -> return
                    }
                    key.key == "ArrowLeft" -> if (!step(-1)) return
                    key.key == "ArrowRight" -> step(1)
                    key.key == "Enter" -> when (pane) {
                        Pane.ASPECTS -> stepTo(Pane.RULES, 1)
                        Pane.RULES -> if (members != null) stepTo(Pane.MEMBERS, 1)
                        Pane.MEMBERS -> Unit
                    }
                    else -> focused?.tookTheKey(key)
                }
            }
        }
    }

    /**
     * One aspect's rules, as the table every other list on these screens is.
     *
     * **The test column is there only where an aspect has one to state**, since most rules read a tag or
     * a type whose name the predicate already carries — a column repeating it in other words is a column
     * of noise, and one left standing empty is worse.
     */
    private fun tableOf(here: List<Rule>, aspect: Aspect): Table {
        val rules = here.filter { it.aspect == aspect }
        val tested = rules.any { testOf(it) != null }
        return Table(
            title = "",
            columns = listOf(Table.Column("predicate", PREDICATE_LEAST)) +
                (if (tested) listOf(Table.Column("test", TEST_LEAST)) else emptyList()) +
                listOf(Table.Column("strength", STRENGTH_WIDTH), Table.Column("members", COUNT_WIDTH)),
            rows = rules.map { rule ->
                val caught = DerivationRules.catches(rule, corpus)
                Table.Row(
                    key = rule.id,
                    cells = listOf(saidOf(rule)) +
                        (if (tested) listOf(testOf(rule).orEmpty()) else emptyList()) +
                        listOf(
                            "%.1f".format(rule.fills.values.max()),
                            if (caught.members.isEmpty()) "" else caught.members.size.toString(),
                        ),
                    tone = if (caught.members.isEmpty()) Palette.warned else null,
                )
            },
            whenEmpty = "no rule here fills it",
        )
    }

    /** Which of the three lists the keys are going to. */
    private enum class Pane { ASPECTS, RULES, MEMBERS }

    /** What each pane is drawn at; zero members means the pane is not drawn at all. */
    private data class Widths(val rules: Int, val members: Int)

    /**
     * The width divided between the panes — **the rules take what they measure and the members get the
     * rest**, since a column told to grow would spend the whole pane on a predicate.
     *
     * Nothing is drawn on the right until the cursor has reached a rule: while the aspect is still being
     * chosen there is no row for a member list to be the answer to, and a pane standing there anyway is a
     * third list with no visible reason to be showing what it shows.
     */
    private fun widthsFor(rules: Table, pane: Pane): Widths {
        val beside = canvas.width - ASPECTS_PANE - Frame.GUTTER
        if (pane == Pane.ASPECTS) return Widths(beside, 0)
        val forRules = (rules.wanted + CURSOR_COLUMN).coerceIn(MINIMUM_ROOM, beside)
        val left = beside - forRules - Frame.SEPARATION
        return if (left < MEMBERS_LEAST) Widths(beside, 0) else Widths(forRules, left)
    }

    private fun detailLines(
        tag: String,
        listed: Table,
        rules: Table,
        members: Table?,
        pane: Pane,
        widths: Widths,
    ): List<Line> {
        val room = (canvas.height - CHROME).coerceAtLeast(1)
        listed.room = ASPECTS_PANE - CURSOR_COLUMN
        rules.room = (widths.rules - CURSOR_COLUMN).coerceAtLeast(MINIMUM_ROOM)
        members?.room = (widths.members - CURSOR_COLUMN).coerceAtLeast(MINIMUM_ROOM)
        val body = Frame.beside(
            paneLines(listed, room, focused = pane == Pane.ASPECTS),
            ASPECTS_PANE,
            paneLines(rules, room, focused = pane == Pane.RULES),
            widths.rules,
        )
        // **Walled rather than ruled**, so the members read as a second list and not a fifth column.
        val whole = if (members == null || widths.members == 0) {
            body
        } else {
            Frame.apart(
                body,
                ASPECTS_PANE + Frame.GUTTER + widths.rules,
                paneLines(members, room, focused = pane == Pane.MEMBERS),
                widths.members,
            )
        }
        val focused = when (pane) {
            Pane.ASPECTS -> listed
            Pane.RULES -> rules
            Pane.MEMBERS -> members
        }
        return listOf(
            Line("  what fills ", Palette.heading) + Line("${Word.TAG_MARK}$tag", Palette.tag),
            Frame.rule(canvas.width),
        ) + whole.take(room) + listOf(
            Frame.rule(canvas.width),
            hints(
                "↑↓" to when (pane) {
                    Pane.ASPECTS -> "aspect"
                    Pane.RULES -> "rule"
                    Pane.MEMBERS -> "member"
                },
                "←→" to if (pane == Pane.ASPECTS) "its rules" else "column, then pane",
                "tab" to "sort",
                "esc" to "back",
                searching(focused?.filter.orEmpty()),
            ),
        )
    }

    /**
     * What the rule under the cursor took, as a table of its own.
     *
     * A list rather than a pane of wrapped text because seventy members is longer than any terminal, and
     * a table is the one thing here that already knows how to be read a page at a time.
     *
     * **A rule a server caught nothing with is not a rule nobody asked.** Eight of the hundred and
     * forty-two are genuinely empty against vanilla, and reading "needs snapshot" off a snapshot that
     * holds the answer sends the reader on an errand they have already run.
     */
    private fun membersTable(rule: Rule): Table {
        val caught = DerivationRules.catches(rule, corpus)
        val heading = when {
            caught.members.isEmpty() -> "members"
            caught.fromAServer -> "${caught.members.size} members, from a server"
            else -> "${caught.members.size} members"
        }
        return Table(
            title = "",
            columns = listOf(Table.Column(heading, MEMBERS_LEAST - CURSOR_COLUMN, grows = true)),
            rows = caught.members.map { Table.Row(key = it, cells = listOf(it)) },
            whenEmpty = if (caught.fromAServer) "empty" else "needs snapshot",
        )
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
                "^l" to "sync data from server",
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
            runCatching { corpus.withFreshSnapshot { far = it } }
        }
        corpus = taken.getOrNull() ?: return false
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
     * The condition [saidOf] is prose for, **where stating it exactly says something the prose cannot.**
     *
     * `is scalding` is a number a reader would otherwise go to the source for. A rule that reads a tag or
     * a feature type has no such second answer — its key *is* the mechanic, and "the feature it places"
     * beside `places a tree` is the row saying itself twice. The bands come from [DerivedTags] rather
     * than being written out here, so the words and the numbers cannot drift apart.
     */
    private fun testOf(rule: Rule): String? = if (rule.byTag) null else DerivedTags.BANDS[rule.key]

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

        /** The aspects down the left; the two panes beside them divide what is left. */
        const val ASPECTS_PANE = 14
        const val ASPECT_LEAST = 10

        /** Below this the third pane cannot say its own heading, so it is not drawn. */
        const val MEMBERS_LEAST = 30

        /** The rules table's own columns; every one of them measures its own contents. */
        const val PREDICATE_LEAST = 24
        const val TEST_LEAST = 4
        const val STRENGTH_WIDTH = 8

        /** How wide the refresh bar is drawn. */
        const val BAR = 24

        const val TAG_WIDTH = 16
        const val COUNT_WIDTH = 7
        const val WHERE_WIDTH = 20
    }
}
