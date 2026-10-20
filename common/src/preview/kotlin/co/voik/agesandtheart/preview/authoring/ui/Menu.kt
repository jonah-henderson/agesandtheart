package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.language.DerivedWords
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.preview.authoring.Corpus
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
     * Everything the tool can do, in groups. The words are not among them while the word editor is rebuilt for
     * meanings (`notes/meanings-plan.md` phase 6).
     *
     * [startsGroup] is a blank line before the entry and nothing else — no headings, because the four
     * groups are obvious once they are apart and a label on each would be four more things to read. What
     * they separate is the *subject*: the words, the world they describe, writing an Age out of them, and
     * the tool itself.
     */
    private enum class Errand(val title: String, val about: String, val startsGroup: Boolean = false) {
        TAGS("tag editor", ""),
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

        /** Either summary column's floor — the two divide what the named columns left. */
        const val HALF_A_SUMMARY = 26
        const val FIRMNESS_WIDTH = 9

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
