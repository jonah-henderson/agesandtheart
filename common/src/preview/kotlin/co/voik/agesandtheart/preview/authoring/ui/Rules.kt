package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.DerivationRules
import co.voik.agesandtheart.preview.authoring.DerivationRules.Rule
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The derivation rules, and **what each one actually catches** — read only, on purpose.
 *
 * `"#minecraft:is_forest": {"wooded": 1.0}` cannot be improved on by a form: the file already says the
 * whole rule in one line. What the file cannot say is its consequence, and the recurring fault the tag
 * pass keeps finding (`notes/the-tag-layer.md` §7 step 4) is a rule that was *nearly* the fact it stood
 * for — which is only ever visible in the list of things it caught.
 *
 * So this shows the list and offers no write path. One rule moves dozens of presets, which is a different
 * kind of editing from nudging one weight and should not be reachable by accident from a screen that
 * nudges weights.
 */
class Rules(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
) {

    private val rules: List<Rule> by lazy { DerivationRules.of(corpus) }

    fun run() {
        val table = Table(
            title = "${rules.size} tagging rules",
            columns = listOf(
                Table.Column("aspect", ASPECT_WIDTH),
                Table.Column("reads", KEY_WIDTH),
                Table.Column("from", FROM_WIDTH),
                Table.Column("and calls it", GRANTS_WIDTH),
            ),
            rows = rules.map(::ruleRow),
        )
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    tableLines(
                        table,
                        canvas,
                        listOf(
                            Line(
                                "  the rules that read the world's own tags and facts into ours " +
                                    "${Glyph.BULLET} art/derivation/",
                                Palette.faint,
                            ),
                            hints(
                                "enter" to "what it catches",
                                "tab" to "sort",
                                "←" to "back",
                                "" to "type to search",
                                "" to table.filter,
                            ),
                        ),
                    ),
                )
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return
                    key.key == "ArrowLeft" -> if (table.column == 0) return else table.across(-1)
                    key.key == "ArrowRight" -> table.across(1)
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Tab" -> table.sortByTheColumnInHand()
                    key.key == "Backspace" -> table.backspace()
                    key.key == "Enter" ->
                        rules.firstOrNull { it.id == table.focused?.key }?.let(::showWhatItCatches)
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    private fun ruleRow(rule: Rule) = Table.Row(
        key = rule.id,
        cells = listOf(
            rule.aspect.page,
            rule.key,
            if (rule.byTag) "a tag it carries" else "a fact it states",
            rule.grants.entries.joinToString(" ") { (tag, weight) -> "$TAG_MARK$tag %.1f".format(weight) },
        ),
        tone = if (rule.byTag && corpus.snapshot == null) Palette.faint else null,
    )

    /**
     * Exactly what this rule tags, **by running the derivation with nothing else in it.**
     *
     * The real code rather than a reimplementation of it, which matters here more than anywhere: two
     * rules granting the same tag at the same weight cannot be told apart by reading the merged answer,
     * and a lens that guessed would be wrong precisely where a rule overlaps another.
     */
    private fun showWhatItCatches(rule: Rule) {
        val caught = canvas.whileBusy("Running the rule") { DerivationRules.catches(rule) }
        val lines = buildList {
            add(
                Line("reads ", Palette.faint) + Line(rule.key, Palette.value) +
                    Line(" and calls it ", Palette.faint) +
                    Line(rule.grants.entries.joinToString(" ") { (tag, weight) -> "$TAG_MARK$tag %.1f".format(weight) }, Palette.tag),
            )
            add(Line.BLANK)
            if (caught.isEmpty()) {
                add(Line("it catches nothing here", Palette.warned))
                if (rule.byTag) {
                    add(Line.BLANK)
                    add(Line("Registry tags are bound by a running game and are absent offline, so a", Palette.faint))
                    add(Line("rule that reads one matches nothing in this corpus. That is the harness", Palette.faint))
                    add(Line("rather than the rule — load minecraft data to see what it really takes.", Palette.faint))
                } else {
                    add(Line.BLANK)
                    add(Line("This one reads a stated fact, which is knowable offline — so catching", Palette.faint))
                    add(Line("nothing means nothing in ${rule.aspect.page} answers to '${rule.key}'.", Palette.faint))
                }
            } else {
                add(Line("${caught.size} things in ${rule.aspect.page}", Palette.heading))
                addAll(caught.take(SHOWN).map { Line("  $it", Palette.value) })
                if (caught.size > SHOWN) add(Line("  ${Glyph.ELIDED} and ${caught.size - SHOWN} more", Palette.faint))
            }
        }
        read(Reader("what '${rule.key}' catches", lines))
    }

    /** The reading wrapped to the screen, and the reader told how many rows that came to. */
    private fun wrappedFor(reader: Reader, window: Int): List<Line> {
        val whole = reader.lines.flatMap { it.wrapped(canvas.width - INDENT, "      ") }
        reader.rows = whole.size
        return whole.drop(reader.offset).take(window)
    }

    private fun read(reader: Reader) {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val window = (canvas.height - CHROME).coerceAtLeast(1)
                canvas.show(
                    listOf(Line("  ${reader.title}", Palette.heading), Line.BLANK) +
                        wrappedFor(reader, window).map { Line("  ") + it } +
                        listOf(Frame.rule(canvas.width), hints("↑↓" to "scroll", "←" to "back")),
                )
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" || key.key == "ArrowLeft" || key.key == "Enter" -> return
                    key.key == "ArrowUp" -> reader.scroll(-1, window)
                    key.key == "ArrowDown" -> reader.scroll(1, window)
                    key.key == "PageUp" -> reader.scroll(-window, window)
                    key.key == "PageDown" -> reader.scroll(window, window)
                }
            }
        }
    }

    private companion object {
        /** The two columns every reading is indented by. */
        const val INDENT = 2

        const val ASPECT_WIDTH = 12
        const val KEY_WIDTH = 34
        const val FROM_WIDTH = 16
        const val GRANTS_WIDTH = 42
        const val SHOWN = 200
        const val CHROME = 4
    }
}
