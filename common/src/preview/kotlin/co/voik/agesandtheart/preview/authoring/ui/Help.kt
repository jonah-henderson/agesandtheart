package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.preview.authoring.Corpus
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The help, laid out like the word screen: pages down the left, the page itself on the right.
 *
 * A reference is the sort of thing that grows, so it is a list of pages rather than one long scroll —
 * adding a third is adding an entry to [Page].
 */
class Help(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
) {

    private enum class Page(val title: String) {
        CONTROLS("controls"),
        PARAMETERS("aspects and parameters"),
    }

    private var page = Page.CONTROLS
    private var offset = 0

    /**
     * Which column the arrows belong to — the same two-level rule the word screen has, because a reader
     * arriving here should not have to learn a second one.
     */
    private var inside = false

    fun run() {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val window = (canvas.height - CHROME).coerceAtLeast(1)
                canvas.show(lines(window))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                val last = (contentOf(page).size - window).coerceAtLeast(0)
                fun scroll(by: Int) { offset = (offset + by).coerceIn(0, last) }
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "?" -> return
                    key.key == "ArrowLeft" || key.key == "Escape" -> if (inside) inside = false else return
                    key.key == "ArrowRight" || key.key == "Enter" -> inside = true
                    // Up and down move the list you are in; the page keys always move the page, since
                    // there is never a reason to page through six titles.
                    key.key == "ArrowUp" -> if (inside) scroll(-1) else turn(-1)
                    key.key == "ArrowDown" -> if (inside) scroll(1) else turn(1)
                    key.key == "PageUp" -> scroll(-window)
                    key.key == "PageDown" -> scroll(window)
                    key.key == "Home" -> offset = 0
                    key.key == "End" -> offset = last
                    key.key == "Tab" -> turn(1)
                }
            }
        }
    }

    private fun turn(by: Int) {
        page = Page.entries[(page.ordinal + by).mod(Page.entries.size)]
        offset = 0
    }

    private fun lines(window: Int): List<Line> = buildList {
        add(Line("  help", Palette.heading))
        add(Frame.rule(canvas.width))
        val pages = Page.entries.map { entry ->
            val here = entry == page
            Line(if (here && !inside) "  ${Glyph.FOCUS} " else "    ", Palette.focused) +
                Line(entry.title, if (here) Palette.focused else Palette.faint)
        }
        val shown = contentOf(page).drop(offset).take(window)
        addAll(Frame.apart(pages, pageListWidth, shown, canvas.width - pageListWidth - Frame.SEPARATION))
        add(Frame.rule(canvas.width))
        add(
            if (inside) {
                hints("↑↓" to "scroll", "pgup/pgdn" to "a page", "home/end" to "ends", "←" to "the page list")
            } else {
                hints("↑↓" to "page", "→" to "read it", "pgup/pgdn" to "scroll", "←" to "back")
            },
        )
    }

    /**
     * As wide as the longest page name, plus the cursor in front of it.
     *
     * It was a constant, which meant adding a page longer than twenty characters would quietly cut it in
     * half — and the page list is the one thing on this screen a reader has to be able to read.
     */
    private val pageListWidth: Int
        get() = Page.entries.maxOf { it.title.length } + CURSOR_ROOM

    private fun contentOf(page: Page): List<Line> = when (page) {
        Page.CONTROLS -> CONTROLS
        Page.PARAMETERS -> parameters()
    }

    /**
     * Every parameter in the game and what it is — **including the ones the word in hand does not touch.**
     *
     * The help beside a row only covers parameters the word already reaches, which is no use when you are
     * trying to find out whether some other aspect has the thing you want.
     */
    private fun parameters(): List<Line> = Aspect.entries.sortedBy { it.ordinal }.flatMap { aspect ->
        val here = parametersOf(aspect)
        if (here.isEmpty()) return@flatMap emptyList()
        listOf(Line(aspect.page, Palette.heading)) + here.flatMap { parameter ->
            listOf(
                Line("  ") + Line(parameter.name.padEnd(PARAMETER_WIDTH), Palette.parameter) +
                    Line(parameter.help, Palette.faint),
            ) + parameter.optionHelp.entries.map { (option, said) ->
                Line("      ") + Line(option.padEnd(VALUE_WIDTH), Palette.value) + Line(said, Palette.faint)
            }
        } + Line.BLANK
    }

    /** What an aspect actually turns: its own parameters, and whatever its presets honour. */
    private fun parametersOf(aspect: Aspect) =
        (aspect.parameters + corpus.vocabulary.candidatesFor(aspect).flatMap { preset ->
            preset.ownParameters.filter(preset::honours)
        }).distinctBy { it.name }.sortedBy { it.name }

    private companion object {
        /** The `  ▸ ` in front of a page name, and a space after it. */
        const val CURSOR_ROOM = 5

        /** How wide the key column of the reference is — every row lines up on it. */
        const val KEY_WIDTH = 15

        /**
         * One row of the key reference.
         *
         * **No brackets here**, unlike the hint rows along the bottom of a screen: a column of keys is
         * already unmistakably a column of keys, and bracketing every one of forty rows would be reading
         * the same punctuation forty times. The colour is what carries it, and it is the same colour.
         */
        private fun keyRow(key: String, does: String): Line =
            Line("  ") + Line(key.padEnd(KEY_WIDTH), Palette.key) + Line(does, Palette.faint)
        const val PARAMETER_WIDTH = 16
        const val VALUE_WIDTH = 16

        /** Title, two rules and the key line. */
        const val CHROME = 4

        val CONTROLS: List<Line> = listOf(
            Line("  The two rows along the bottom are the keys: what the row under the", Palette.faint),
            Line("  cursor answers to, then how to move about and what the tool does.", Palette.faint),
            Line.BLANK,
            Line("Moving", Palette.heading),
            keyRow("↑ ↓", "move within a list"),
            keyRow("→ ←", "in and out of a list; ← backs out of a screen"),
            keyRow("enter", "edit what the cursor is on"),
            keyRow("", "a short value — a name, a count — is typed on its own row;"),
            keyRow("", "enter accepts it, and moving off the row accepts it too"),
            keyRow("a  /  d", "add a row, or delete one"),
            keyRow("", "on a setting inside a pool, the new one joins that setting's group"),
            keyRow("", "on a group's own line, d takes the whole group"),
            keyRow("- =", "step a weight under the cursor, in tenths"),
            keyRow("", "on a list of things to lean, and on the row afterwards"),
            keyRow("tab", "which target the inline help is about"),
            keyRow("pgup pgdn", "a page of a long section, or a page of a list"),
            keyRow("home end", "the first and last row of a section"),
            Line("", Palette.faint),
            Line("  Where a list can take several at once — the aspects a parameter belongs to, the", Palette.faint),
            Line("  values it may draw between — enter marks a row and the row at the bottom", Palette.faint),
            Line("  takes everything marked. Right goes: the marks if there are any, otherwise the", Palette.faint),
            Line("  row under the cursor. What is marked is previewed on the key line, so", Palette.faint),
            Line("  `red|blue` is built rather than spelled.", Palette.faint),
            Line.BLANK,
            Line("Setting a value on an axis", Palette.heading),
            keyRow("- =", "move the end of the band the chart has lit"),
            keyRow("tab", "the other end"),
            keyRow("_ +", "take the bottom or the top off, leaving a floor or a ceiling"),
            keyRow("", "and again to put it back"),
            keyRow("↑↓", "the band, a nudge, a spread, or how a word already written says it"),
            keyRow("type", "search what the words already written say — down that list only"),
            Line.BLANK,
            Line("Checking a word", Palette.heading),
            keyRow("^p", "what it matches, aspect by aspect"),
            keyRow("^t", "try a book that uses it"),
            keyRow("^f", "everything wrong with it"),
            keyRow("\u2192", "on a tag, what in the world carries it"),
            Line.BLANK,
            Line("Saving", Palette.heading),
            keyRow("^z", "undo"),
            keyRow("^s", "save, unless there are errors"),
            keyRow("^q", "leave"),
            keyRow("delete", "the last section, and the one thing with no undo"),
            Line.BLANK,
            Line("In the word lists", Palette.heading),
            keyRow("← →", "between columns"),
            keyRow("- =", "step the value under the cursor down or up"),
            keyRow("enter", "open the word, or step a value where you are on one"),
            keyRow("F1 F2 F3", "rarity, ink, specificity"),
            keyRow("tab", "sort by the column you are on; again reverses"),
            keyRow("home end", "first and last row"),
            keyRow("pgup pgdn", "a page, or to the ends where it all fits"),
            keyRow("anything else", "types, to search the list"),
            Line.BLANK,
            Line("The age workshop", Palette.heading),
            keyRow("type", "narrows what could come next"),
            keyRow("\u2191\u2193", "move through the suggestions"),
            keyRow("enter / space", "lay the page under the cursor"),
            keyRow("backspace", "a letter back, or the last page off the row"),
            keyRow("^o", "open it in minecraft (^o again rewrites it)"),
            keyRow("^g", "send whoever is connected into the Age"),
            keyRow("^x", "stop the server and throw its world away"),
            keyRow("^s ^n ^r", "save  \u2022  name  \u2022  where the seed comes from"),
            Line("", Palette.faint),
            Line("  What is offered comes from the parser, so a page on the list is one the", Palette.faint),
            Line("  Art can really read next. A page needing three more before the clause", Palette.faint),
            Line("  could close is not found - type it out and lay it anyway.", Palette.faint),
            Line("", Palette.faint),
            Line("  A clause is modifiers, then the page it is about. 'can be aimed at'", Palette.faint),
            Line("  says which of those closes are still open to you.", Palette.faint),
            Line.BLANK,
            Line("The tag editor", Palette.heading),
            Line("  A tag is a set, and the things in it are its members. So you empty a set,", Palette.faint),
            Line("  add a member to one, take one out — rather than sticking a label on things.", Palette.faint),
            Line("", Palette.faint),
            keyRow("enter", "what is in the set \u2014 and on `+`, name a new one"),
            keyRow("", "a search that finds no set offers to make one of what you typed"),
            keyRow("- =", "how strongly the member under the cursor belongs, in tenths"),
            keyRow("^o", "back to the weight a rule gave it \u2014 where a rule gave one"),
            keyRow("tab", "group the members by where they came from, or sort by name"),
            keyRow("^d", "drop a member a rule put in, or restore it"),
            keyRow("^r", "rename the set \u2014 tables, words and antonyms together"),
            keyRow("^w", "which words ask for it"),
            keyRow("^x", "delete the set \u2014 the same three, and no undo"),
            Line("", Palette.faint),
            Line("  A membership says where it came from. Authored is a line in art/preset_tags", Palette.faint),
            Line("  standing alone; overridden is one standing over a rule, so taking it off", Palette.faint),
            Line("  lets the rule's weight back; derived is a rule with no line, so changing it", Palette.faint),
            Line("  writes the first one. Taking a member a rule put in back out is a drop,", Palette.faint),
            Line("  not a deletion, and the last column is what the rule had said.", Palette.faint),
            Line.BLANK,
            Line("Reading the rules", Palette.heading),
            Line("  A tag fills itself from what the game already states, and the rules are how.", Palette.faint),
            Line("  The list is one row per tag; enter opens the rules behind it, each said as a", Palette.faint),
            Line("  sentence about a member, with what it caught underneath.", Palette.faint),
            Line("", Palette.faint),
            Line("  Read-only, deliberately. One rule moves dozens of members, which is a", Palette.faint),
            Line("  different kind of editing from nudging one weight and should not be", Palette.faint),
            Line("  reachable by accident from a screen that nudges weights.", Palette.faint),
            Line("", Palette.faint),
            keyRow("enter", "the rules that fill the tag under the cursor"),
            keyRow("", "then: aspects on the left, their rules beside them, and what the"),
            keyRow("", "rule under the cursor took on the right"),
            keyRow("^l", "sync data from server without leaving the screen"),
            Line("", Palette.faint),
            Line("  A rule reading a registry tag catches nothing without a running game, so", Palette.faint),
            Line("  loading minecraft data is what fills those in. It asks a server what every", Palette.faint),
            Line("  rule catches and remembers the answer.", Palette.faint),
            Line.BLANK,
            Line("Minecraft data", Palette.heading),
            Line("  The tool works offline. Load minecraft data from the menu to import", Palette.faint),
            Line("  the tags and content that only a running server knows.", Palette.faint),
            Line("", Palette.faint),
            Line("  What was imported says which world it came from and when. That matters:", Palette.faint),
            Line("  a vanilla server and a modpack answer differently about the same tag,", Palette.faint),
            Line("  and only the world it was read out of tells them apart.", Palette.faint),
        )
    }
}
