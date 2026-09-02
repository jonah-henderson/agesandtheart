package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Verdict
import co.voik.agesandtheart.preview.authoring.WordFile
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * The word editor — a frame that stays up while a word is changed, and a verdict under it that is never
 * more than one edit old.
 *
 * **Everything is recomputed from the candidate**, which is what makes it an editor rather than a
 * questionnaire: there is no order the sections have to be visited in, no answer that is locked once
 * given, and going back is the same operation as going forward. The candidate is immutable and an edit
 * pushes a new one, so undo is dropping the top of a list.
 */
class Editor(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
    opened: Candidate,
    /** Whether there is a screen behind this one — false when the word was named on the command line. */
    private val canLeave: Boolean,
) {

    private val parts = Parts(corpus)
    private val history = mutableListOf(opened)

    /**
     * The word as it last stood on disk, so *changed* means changed **since it was saved** rather than
     * since it was opened.
     *
     * Undo alone could not answer it: a word edited and put back the way it was has a history two deep
     * and nothing to save, and one saved half way through has a long history and nothing outstanding.
     */
    private var written: Candidate = opened

    /**
     * The name the word was opened under, where it had a file.
     *
     * Saving under a different one has to *move* the word rather than copy it: the old file, and the
     * rarity, ink and display entries that name it, all belong to the word rather than to the name.
     */
    private val openedAs: String? = opened.name.takeIf { WordFile.exists(it) && !opened.isDerived }
    private val rowOf = Part.entries.associateWith { 0 }.toMutableMap()
    private var part = Part.NAME

    /**
     * Which of the two columns the arrow keys belong to.
     *
     * **Up and down always move within a list, and left and right move between them.** A section list that
     * scrolled sideways and a row list that scrolled down asked the same two keys to mean two things
     * depending on where you had got to, which is the sort of thing you have to keep re-learning.
     */
    private var inside = false
    private var overlay: Overlay? = null
    private var message: String? = null
    /**
     * Whether anything reached the disk that the **corpus** would read differently.
     *
     * Saving, renaming and deleting change what words there are; setting a rarity or an ink does not, and
     * the lists that show those read the files directly. So this is what decides whether closing the word
     * is worth a second of reading the pack again.
     */
    private var changed = false
    private var quitting = false

    /** Backing out of the leftmost column leaves the word — to the menu, or to the shell. */
    private var leaving = false
    private var raw: RawModeScope? = null
    private var readerWindow = MINIMUM_BODY

    /** How many options the picker last had room for — what a page means to it. Set as it is drawn. */
    private var pickerWindow = MINIMUM_BODY

    private val candidate: Candidate get() = history.last()

    /** Whether there is anything to lose by leaving. */
    private val unsaved: Boolean get() = !candidate.isDerived && candidate != written

    /** The word as the game would load it, or null while it will not load at all. */
    private var word: Word? = null
    private var findings: List<Verdict.Finding> = emptyList()
    private var judged: Candidate? = null

    /** Runs until the writer leaves. True if a file was written. */
    fun run(): Boolean {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            raw = scope
            while (!quitting && !leaving) {
                judge()
                draw()
                // **`break`, not `continue`.** A closed input reads null for ever, and a loop that
                // carried on would spin a core redrawing a frame nobody is looking at.
                val key = scope.readKey() ?: break
                if (key.ctrl && key.key == "c") throw Leaving()
                message = null
                handle(key)
            }
        }
        raw = null
        return changed
    }

    // -- state ---------------------------------------------------------------------------------------

    /** The verdict, recomputed only when the word changed — moving a cursor must not cost a corpus sweep. */
    private fun judge() {
        if (judged == candidate) return
        word = candidate.asWord().getOrNull()
        findings = Verdict.on(candidate, corpus)
        judged = candidate
    }

    private fun edit(change: (Candidate) -> Candidate) {
        // A derived word has no file, so there is nothing here to change but its rarity and its ink —
        // both of which live away from the word and are written straight out rather than through history.
        if (candidate.isDerived) {
            message = "${candidate.name} comes from Minecraft — only its rarity and ink can be set"
            return
        }
        history += change(candidate)
    }

    private fun rows() = parts.rowsOf(part, candidate, word)

    private fun row(): Int = rowOf.getValue(part).coerceIn(0, (rows().size - 1).coerceAtLeast(0))

    // -- drawing -------------------------------------------------------------------------------------

    private fun draw() {
        val width = canvas.width
        canvas.show(
            buildList {
                add(header(width))
                add(Frame.rule(width))
                addAll(body(width, canvas.height))
                add(Frame.rule(width))
                addAll(strip(width))
                add(keys(width))
            },
        )
    }

    private fun header(width: Int): Line {
        val price = word?.price
        val reach = word?.aspects.orEmpty().sortedBy { it.ordinal }.joinToString(" ") { it.page }
        val left = Line("  ") +
            Line(candidate.name.ifBlank { "(unnamed)" }, Palette.heading) +
            Line("  ${candidate.tier.key}", Palette.faint) +
            Line(if (unsaved) "  ${Glyph.BULLET} unsaved" else "", Palette.warned)
        val right = Line(reach.ifEmpty { "reaches nothing" }, Palette.chosen) +
            Line(price?.let { "   ink $it" } ?: "   —", Palette.value) + Line("  ")
        return left.sized((width - right.width).coerceAtLeast(0)) + right
    }

    private fun body(width: Int, height: Int): List<Line> {
        val room = (height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val rest = width - SECTION_WIDTH - Frame.GUTTER
        val asked = overlay

        // **A question opens beside its answer where there is room.** Choosing a value while the word it
        // belongs to has scrolled away is guesswork, so the third pane keeps both in view.
        val roomForThree = asked != null && rest >= WORD_PANE_LEAST + QUESTION_LEAST + Frame.GUTTER
        if (asked != null && !roomForThree) return overlayLines(asked, width, room)

        // **The word pane is capped while a question is open, rather than keeping the leftovers.** Giving
        // it everything spare pushed the question against the right edge of a wide terminal, which is a
        // long way from the row it is about — the two want to be next to each other, not at either end.
        val wordWidth = if (roomForThree) {
            minOf(WORD_PANE_ASKED, rest - QUESTION_LEAST - Frame.GUTTER).coerceAtLeast(WORD_PANE_LEAST)
        } else {
            rest.coerceAtLeast(MINIMUM_WIDTH / 2)
        }

        // The section list is the navigation, so a short terminal must not cut the last parts off the
        // bottom and leave them looking unreachable.
        val sections = scrolled(sectionList(), part.ordinal, room)
        val body = Frame.beside(sections, SECTION_WIDTH, partLines(wordWidth, room), wordWidth).take(room)
        if (!roomForThree || asked == null) return body

        val questionWidth = (rest - wordWidth - Frame.GUTTER).coerceAtMost(QUESTION_MOST)
        val question = overlayLines(asked, questionWidth, room)
        return body.mapIndexed { at, line ->
            line + Line(" ${Glyph.BAR} ", Palette.rule) + (question.getOrNull(at) ?: Line.BLANK)
        }
    }

    private fun sectionList(): List<Line> = Part.entries.map { entry ->
        val here = entry == part
        val marker = if (here && !inside) "${Glyph.FOCUS} " else "  "
        val name = when {
            entry.perilous -> Palette.refused
            here -> Palette.focused
            else -> Palette.faint
        }
        val warned = if (entry.perilous) "${Glyph.WARN} " else ""
        Line(marker, Palette.focused) +
            Line(warned, Palette.refused) +
            Line(entry.title.padEnd(MARK_COLUMN - warned.length), name) +
            Line(filledness(entry), Palette.faint)
    }

    /** A glance at how much of each part has been said, so nothing is forgotten by not being visited. */
    private fun filledness(entry: Part): String = when (entry) {
        Part.NAME -> if (candidate.name.isBlank()) "" else Glyph.TICK
        Part.TIER -> Glyph.TICK
        Part.ASPECTS -> word?.aspects?.size?.takeIf { it > 0 }?.toString().orEmpty()
        Part.EFFECTS -> Slot.entries.sumOf { candidate.holding(it).size }
            .takeIf { it > 0 }?.toString().orEmpty()
        Part.PICKS -> (candidate.everywhere.size + candidate.queries.values.sumOf { it.size } +
            candidate.requests.queries.values.sumOf { it.size } +
            candidate.weights.values.sumOf { it.size } + candidate.meansExactly.size)
            .takeIf { it > 0 }?.toString().orEmpty()
        Part.TEMPLATE -> if (candidate.template == null) "" else Glyph.TICK
        Part.DELETE -> ""
        // No mark when it is empty: a word without a comment is not a fault.
        Part.COMMENT -> if (candidate.commentLines.isEmpty()) "" else Glyph.TICK
        Part.LISTING -> if (WordFile.listingFor(candidate.listingKey).rarity == null) Glyph.WARN else Glyph.TICK
    }

    private fun partLines(width: Int, room: Int): List<Line> {
        val shown = rows()
        val at = row()
        val about = parts.aboutOf(part, candidate)
        val head = if (about.isEmpty()) listOf(Line.BLANK) else listOf(Line(about, Palette.faint), Line.BLANK)
        val listed = shown.mapIndexed { index, entry ->
            val marker = if (index == at && inside) "${Glyph.FOCUS} " else "  "
            // The row keeps its label and gives up its value, which is the part being typed.
            val body = typing?.takeIf { it.handle == entry.handle }?.let { said ->
                entry.shown.dropLast(1) + Ink(said.text, Palette.value) + Ink(Glyph.FULL, Palette.chosen)
            } ?: entry.shown
            Line(marker, Palette.focused) + Line(body)
        }
        val note = shown.getOrNull(at)?.note.orEmpty()
        val tail = if (note.isEmpty()) {
            emptyList()
        } else {
            listOf(Line.BLANK) + note.lines().map { said ->
                // A heading is the line that is not indented, and it reads as one.
                Line("  $said", if (said.startsWith(" ")) Palette.faint else Palette.value)
            }
        }
        // **The list is served first, and the note gets what is left.** The note used to be laid out
        // whole and the list squeezed into whatever remained, so a parameter with six lines of help about it
        // took six rows off a section of thirty and the rest scrolled away under a paragraph nobody was
        // reading yet. A section shorter than the pane still shows entire, which is the common case.
        val forTheList = listed.size.coerceAtMost((room - head.size - NOTE_LEAST).coerceAtLeast(1))
        val forTheNote = (room - head.size - forTheList).coerceAtLeast(0)
        val window = scrolled(listed, at, forTheList)
        val padding = List((room - head.size - window.size - minOf(tail.size, forTheNote)).coerceAtLeast(0)) {
            Line.BLANK
        }
        return (head + window + padding + tail.take(forTheNote)).map { it.sized(width) }
    }

    /** A window onto a list too long for the pane, kept around the cursor rather than at the top. */
    private fun scrolled(lines: List<Line>, at: Int, room: Int): List<Line> {
        if (room <= 0) return emptyList()
        if (lines.size <= room) return lines
        val first = (at - room / 2).coerceIn(0, lines.size - room)
        return lines.subList(first, first + room)
    }

    /** How many rows fit in the pane at the moment — what a page means to [move]. */
    private fun rowsInView(): Int {
        val room = (canvas.height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val about = parts.aboutOf(part, candidate)
        val head = if (about.isEmpty()) 1 else 2
        return (room - head - NOTE_LEAST).coerceAtLeast(1)
    }

    private fun overlayLines(shown: Overlay, width: Int, room: Int): List<Line> = when (shown) {
        is Prompt -> promptLines(shown, width)
        is Picker -> pickerLines(shown, width, room)
        is Reader -> readerLines(shown, width, room)
    }

    private fun promptLines(prompt: Prompt, width: Int) = listOf(
        Line(prompt.title, Palette.heading),
        Line(prompt.hint, Palette.faint),
        Line.BLANK,
        Line("  > ", Palette.chosen) + Line(prompt.typed, Palette.value) + Line(Glyph.FULL, Palette.chosen),
        Line.BLANK,
        Line("  ${prompt.says.orEmpty()}", Palette.refused),
    ).map { it.sized(width) }

    private fun pickerLines(picker: Picker, width: Int, room: Int): List<Line> {
        val head = listOf(
            Line(picker.title, Palette.heading),
            hints("" to "type to search", "" to picker.filter),
        ) + picker.chart?.invoke(picker.focused).orEmpty() + Line.BLANK
        val listed = picker.shown.mapIndexed { index, option ->
            val here = index == picker.index
            Line(if (here) "${Glyph.FOCUS} " else "  ", Palette.focused) +
                Line(if (option.mark.isEmpty()) "" else "${option.mark} ", option.tone ?: Palette.faint) +
                Line(option.label.padEnd(PICKER_LABEL), option.tone ?: if (here) Palette.value else Palette.faint) +
                Line(if (picker.isMarked(option)) "${Glyph.TICK} " else "  ", Palette.settled) +
                Line(option.note, Palette.faint)
        }
        val empty = listOf(Line("  nothing matches", Palette.warned))
        val body = listed.ifEmpty { empty }
        pickerWindow = (room - head.size).coerceAtLeast(1)
        return (head + scrolled(body, picker.index, pickerWindow)).map { it.sized(width) }
    }

    private fun readerLines(reader: Reader, width: Int, room: Int): List<Line> {
        val head = listOf(Line(reader.title, Palette.heading), Line.BLANK)
        // Held, because scrolling has to clamp against the *drawn* window and only drawing knows it. A
        // constant here let the offset run past the end and scroll a long preview off the top.
        readerWindow = (room - head.size).coerceAtLeast(1)
        // Wrapped before the window is taken, so a long line costs the rows it really occupies and
        // scrolling counts what is on screen rather than what was handed in.
        val whole = reader.lines.flatMap { it.wrapped(width, READER_HANGING) }
        reader.rows = whole.size
        val shown = whole.drop(reader.offset).take(readerWindow)
        return (head + shown).map { it.sized(width) }
    }

    // -- the verdict strip ---------------------------------------------------------------------------

    private fun strip(width: Int): List<Line> {
        message?.let { return listOf(Line("  $it", Palette.settled)) + List(STRIP_LINES - 1) { Line.BLANK } }
        val ordered = findings.sortedBy { it.standing.ordinal }
        val counted = Verdict.Standing.entries
            .mapNotNull { standing ->
                val many = findings.count { it.standing == standing }
                if (many == 0) null else Ink("$many ${standing.name.lowercase()}  ", styleOf(standing))
            }
        val summary = if (counted.isEmpty()) {
            Line("  ${Glyph.TICK} nothing to answer for", Palette.settled)
        } else {
            Line("  ") + Line(counted)
        }
        val said = ordered.take(STRIP_LINES - 1).map { finding ->
            Line("  ${markerOf(finding.standing)} ", styleOf(finding.standing)) +
                Line(finding.says, Palette.value) +
                Line(finding.because?.let { " ${Glyph.BULLET} $it" }.orEmpty(), Palette.faint) +
                Line(finding.heldBy?.let { "  [$it]" }.orEmpty(), Palette.faint)
        }
        return (listOf(summary) + said + List(STRIP_LINES) { Line.BLANK }).take(STRIP_LINES)
            .map { it.sized(width) }
    }

    private fun styleOf(standing: Verdict.Standing) = when (standing) {
        Verdict.Standing.ERROR -> Palette.refused
        Verdict.Standing.WARNED -> Palette.warned
        Verdict.Standing.NUDGED -> Palette.nudged
        Verdict.Standing.NOTED -> Palette.noted
    }

    private fun markerOf(standing: Verdict.Standing) = when (standing) {
        Verdict.Standing.ERROR -> Glyph.CROSS
        Verdict.Standing.WARNED -> Glyph.WARN
        Verdict.Standing.NUDGED -> Glyph.BULLET
        Verdict.Standing.NOTED -> Glyph.BULLET
    }

    private fun keys(width: Int): Line = typing?.let { said ->
        val wrong = said.complaint(said.text)
        hints(
            "enter" to "accept", "↑↓" to "accept and move on", "esc" to "leave it alone",
            "" to wrong.orEmpty(),
        )
    } ?: when (overlay) {
        is Prompt -> hints("enter" to "accept", "esc" to "cancel")
        is Picker -> if ((overlay as Picker).marking) {
            val asked = overlay as Picker
            // The preview *is* the value where marks build one, and a count where they pick several.
            val standing = when {
                asked.marked.isEmpty() -> "nothing marked"
                asked.joinsWith != null -> asked.marked.joinToString(asked.joinsWith)
                else -> "${asked.marked.size} marked"
            }
            hints(
                "↑↓" to "move",
                "enter" to "mark",
                "→" to if (asked.marked.isEmpty()) "take this one" else "take the ${asked.marked.size} marked",
                "←" to "back",
                "" to standing,
            )
        } else {
            hints(
                "↑↓" to "move", "pgup/pgdn" to "a page", "home/end" to "ends",
                "→" to "pick", "^d" to "clear", "←" to "back",
            )
        }
        is Reader -> hints("↑↓" to "scroll", "pgup/pgdn" to "a page", "home/end" to "ends", "←" to "close")
        null -> if (inside) {
            hints(
                "↑↓" to "row", "pgup/pgdn" to "a page", "home/end" to "ends", "←" to "back",
                "enter" to "edit", "a" to "add", "d" to "delete", "tab" to "target",
                "?" to "help", "^p" to "preview", "^t" to "try", "^f" to "faults",
                "^z" to "undo", "^s" to "save",
            )
        } else {
            hints(
                "↑↓" to "part", "→" to "open it",
                if (canLeave) "←" to "back" else "" to "",
                "?" to "help", "^p" to "preview", "^t" to "try",
                "^f" to "faults", "^z" to "undo", "^s" to "write",
            )
        }
    }.sized(width)

    // -- keys ----------------------------------------------------------------------------------------

    private fun handle(key: KeyboardEvent) {
        typing?.let { return handleTyping(it, key) }
        overlay?.let { return handleOverlay(it, key) }
        when {
            key.ctrl && key.key == "q" -> if (unsaved) askBeforeLeaving(quits = true) else quitting = true
            key.ctrl && key.key == "c" -> quitting = true
            key.ctrl && key.key == "s" -> save()
            key.ctrl && key.key == "z" -> undo()
            key.ctrl && key.key == "p" -> overlay = Preview.carriers(candidate, word, corpus)
            key.ctrl && key.key == "f" -> overlay = Preview.faults(findings)
            key.ctrl && key.key == "t" -> askForASentence()
            // **Tab moves the inline help between a parameter's targets**, since the arrows already move the
            // cursor: `size` is a different parameter on a landform and on a sun, and only one of them can be
            // explained at a time.
            key.key == "Tab" && key.shift -> parts.helpAspect--
            key.key == "Tab" -> parts.helpAspect++
            key.key == "?" -> openTheHelp()
            key.key == "ArrowUp" -> if (inside) move(-1) else turnTo(previousPart())
            key.key == "ArrowDown" -> if (inside) move(1) else turnTo(nextPart())
            // **Right goes deeper.** Inside the picks, a tag row has somewhere deeper to go: what carries
            // it. Everywhere else right is already spent getting in, so this costs no key.
            key.key == "ArrowRight" -> if (inside) openTheTagLayer() else inside = true
            key.key == "ArrowLeft" -> if (inside) inside = false else askBeforeLeaving()
            key.key == "Enter" -> if (inside) act() else inside = true
            // **Escape is back, one step at a time**: out of the rows to the sections, then out of the
            // word. Nothing is lost by pressing it twice; a great deal can be by it leaving at once.
            key.key == "Escape" -> if (inside) inside = false else askBeforeLeaving()
            key.key == "PageUp" -> if (inside) move(-rowsInView()) else turnTo(previousPart())
            key.key == "PageDown" -> if (inside) move(rowsInView()) else turnTo(nextPart())
            key.key == "Home" -> if (inside) moveTo(0) else turnTo(Part.entries.first())
            key.key == "End" -> if (inside) moveTo(rows().size - 1) else turnTo(Part.entries.last())
            key.key == "a" && inside && parts.isAList(part) -> add()
            key.key == "d" && inside && parts.isAList(part) -> remove()
        }
    }

    private fun handleOverlay(shown: Overlay, key: KeyboardEvent) {
        when (shown) {
            is Prompt -> when {
                key.key == "Escape" -> overlay = null
                key.key == "Enter" -> if (shown.says == null) {
                    overlay = null
                    shown.onDone(shown.typed)
                }
                key.key == "Backspace" -> shown.backspace()
                key.key.length == 1 && !key.ctrl && !key.alt -> shown.type(key.key)
            }
            is Picker -> when {
                key.key == "Escape" || key.key == "ArrowLeft" -> overlay = null
                key.ctrl && key.key == "d" -> {
                    overlay = null
                    shown.onClear?.invoke()
                }
                // **Enter marks where several may be taken**, and leaves the overlay standing so the
                // next one can be marked too; whatever the caller does with the accepting row is what
                // closes it. Right takes one and has done, which is the common case a mark would slow.
                key.key == "Enter" -> shown.focused?.let { picked ->
                    if (!shown.marking) overlay = null
                    shown.onPick(picked)
                }
                key.key == "ArrowRight" -> shown.focused?.let { picked ->
                    overlay = null
                    (shown.onOnly ?: shown.onPick)(picked)
                }
                key.key == "ArrowUp" -> shown.move(-1)
                key.key == "ArrowDown" -> shown.move(1)
                // A list of every block in the game is not a list anybody arrows through.
                key.key == "PageUp" -> shown.page(-pickerWindow)
                key.key == "PageDown" -> shown.page(pickerWindow)
                key.key == "Home" -> shown.page(-shown.shown.size)
                key.key == "End" -> shown.page(shown.shown.size)
                key.key == "Backspace" -> shown.backspace()
                key.key.length == 1 && !key.ctrl && !key.alt -> shown.type(key.key)
            }
            is Reader -> when {
                // **`^q` is not `q`.** Leaving the tool from inside a reading was closing the reading and
                // needing to be said twice, because the plain key that closes it swallowed the modified one.
                key.ctrl && key.key == "q" -> { overlay = null; quitting = true }
                key.key == "Escape" || key.key == "Enter" || key.key == "ArrowLeft" ||
                    (key.key == "q" && !key.ctrl) -> overlay = null
                key.key == "ArrowUp" -> shown.scroll(-1, readerWindow)
                key.key == "ArrowDown" -> shown.scroll(1, readerWindow)
                key.key == "PageUp" -> shown.scroll(-readerWindow, readerWindow)
                key.key == "PageDown" -> shown.scroll(readerWindow, readerWindow)
                key.key == "Home" -> shown.scroll(-shown.lines.size, readerWindow)
                key.key == "End" -> shown.scroll(shown.lines.size, readerWindow)
            }
        }
    }

    /** Another section, with the inline help back on the first of whatever it finds there. */
    private fun turnTo(wanted: Part) {
        part = wanted
        parts.helpAspect = 0
    }

    /**
     * The tag layer, opened on whatever tag the cursor is on.
     *
     * The question a query row keeps raising is what the tag actually reaches, and answering it used to
     * mean leaving the word. The tables are files rather than corpus state, so nothing here goes stale
     * from an edit made in there except the reach, which the preview recomputes when it is next asked.
     */
    private fun openTheTagLayer() {
        val handle = rows().getOrNull(row())?.handle ?: return
        val tag = parts.tagOn(candidate, handle) ?: return
        Tags(terminal, canvas, corpus).open(tag)
    }

    private fun nextPart() = Part.entries[(part.ordinal + 1) % Part.entries.size]

    private fun previousPart() = Part.entries[(part.ordinal - 1 + Part.entries.size) % Part.entries.size]

    /**
     * Leaving, with whatever has not been written offered first.
     *
     * **Three answers rather than two.** "Are you sure" makes somebody who wanted to save do the work of
     * saying no, going back, saving and leaving again — so saving is one of the answers, and it is the
     * first one.
     */
    private fun askBeforeLeaving(quits: Boolean = false) {
        fun go() = if (quits) quitting = true else leaving = canLeave
        if (!unsaved || (!canLeave && !quits)) return go()
        val name = candidate.name.ifBlank { "this word" }
        overlay = Picker(
            title = "'$name' has changes that are not saved",
            options = listOf(
                Picker.Option(SAVE_AND_LEAVE, "save it and leave", "write the file, then go"),
                Picker.Option(LEAVE_ANYWAY, "leave without saving", "the changes are lost"),
                Picker.Option(STAY, "keep editing", "", startsGroup = true),
            ),
        ) { picked ->
            when (picked.value) {
                SAVE_AND_LEAVE -> {
                    save()
                    // A word with errors is refused, and leaving would throw away what was refused.
                    if (!unsaved) go()
                }
                LEAVE_ANYWAY -> go()
                else -> Unit
            }
        }
    }

    private fun handleTyping(said: Typing, key: KeyboardEvent) {
        when {
            key.ctrl && key.key == "c" -> { typing = null; quitting = true }
            key.key == "Escape" -> typing = null
            key.key == "Enter" -> settleTyping()
            // Moving off a row is a way of finishing with it, so what is typed goes in rather than away.
            key.key == "ArrowUp" -> { settleTyping(); move(-1) }
            key.key == "ArrowDown" -> { settleTyping(); move(1) }
            key.key == "Backspace" -> said.text = said.text.dropLast(1)
            key.key.length == 1 && !key.ctrl && !key.alt -> said.text += key.key
        }
    }

    private fun settleTyping() {
        val said = typing ?: return
        typing = null
        val wrong = said.complaint(said.text)
        if (wrong == null) said.commit(said.text) else message = wrong
    }

    /** Starts typing on the row the cursor is on, so the value is edited where it is shown. */
    private fun typeOn(
        handle: String,
        standing: String,
        complaint: (String) -> String? = { null },
        commit: (String) -> Unit,
    ) {
        typing = Typing(handle, standing, complaint, commit)
    }

    private fun move(by: Int) {
        val size = rows().size
        if (size == 0) return
        // A page runs to the end rather than round it: wrapping is what the arrows do, and a page key
        // that jumped from the bottom back to the top would be a page nobody could read.
        val wanted = if (kotlin.math.abs(by) > 1) (row() + by).coerceIn(0, size - 1) else row() + by
        rowOf[part] = ((wanted % size) + size) % size
        parts.helpAspect = 0
    }

    private fun moveTo(where: Int) {
        val size = rows().size
        if (size == 0) return
        rowOf[part] = where.coerceIn(0, size - 1)
        parts.helpAspect = 0
    }

    // -- what a row does -----------------------------------------------------------------------------

    private fun act() {
        val handle = rows().getOrNull(row())?.handle ?: return
        when (part) {
            Part.NAME -> if (rows().getOrNull(row())?.handle == "display") retitle() else renameTo()
            Part.TIER -> Tier.entries.firstOrNull { it.key == handle }?.let { tier -> edit { it.copy(tier = tier) } }
            Part.ASPECTS -> if (handle == "+") add() else Unit
            Part.TEMPLATE -> pickABaseDimension()
            Part.EFFECTS -> actOnAnEffect(handle)
            Part.PICKS -> actOnAPick(handle)
            Part.COMMENT -> openTheEditor()
            Part.LISTING -> relist(handle)
            Part.DELETE -> askAboutDeleting()
        }
    }

    /**
     * A row of the effects list. Handles say which of the four slots they are in, since one list holds
     * all of them: `REQUIRED_POOL/motes`, `draws/REQUIRED_POOL`, `heading/REQUESTED_ALWAYS`.
     */
    /**
     * A row of the picks list — the three ways of choosing a preset, told apart by their handle.
     *
     * `means/<aspect>` is the one meant outright there, `weight/<aspect>/<preset>` a weight by name, and
     * everything else a tag.
     */
    private fun actOnAPick(handle: String) {
        when {
            handle == "+" -> add()
            handle.startsWith("heading/") -> Unit
            handle == "names" -> pickOneOfOurs()
            handle.startsWith("weight/") -> retypePresetWeight(handle.removePrefix("weight/"))
            else -> retypeTagWeight(handle)
        }
    }

    private fun actOnAnEffect(handle: String) {
        val kind = handle.substringBefore('/')
        val rest = handle.substringAfter('/', "")
        when (kind) {
            "+" -> add()
            "+pool" -> buildAPool()
            "heading" -> Unit
            // **The pool's own menu**, where adding a facet and setting the count are the same size of
            // decision. Opening straight into the count made the count the price of looking at the pool.
            "draws" -> Slot.entries.firstOrNull { it.name == rest }?.let { slot ->
                building = slot
                keepBuilding(slot)
            }
            else -> Slot.entries.firstOrNull { it.name == kind }?.let { slot -> retypeParameter(listOf(rest), slot) }
        }
    }

    /**
     * A short value being typed **on its own row**, rather than in a question over the top of it.
     *
     * A name and a draw count are one word and one number, and a full-screen prompt to collect either was
     * a screen change for a keystroke — with the thing being named no longer on screen to look at while
     * you named it. Moving off the row commits what is there, the way a spreadsheet does, because that is
     * what somebody typing a number and then pressing down means by it.
     */
    private class Typing(
        val handle: String,
        var text: String,
        val complaint: (String) -> String?,
        val commit: (String) -> Unit,
    )

    private var typing: Typing? = null

    /**
     * The pool being built, so laying a facet comes back for the next one instead of closing.
     *
     * A field rather than a continuation threaded through five signatures: the parameter flow already has
     * four steps and each would have had to carry a callback it does nothing with.
     */
    private var building: Slot? = null

    /**
     * A pool, built in one pass — **the facets and then the count.**
     *
     * The count is what makes a pool a pool: without it the whole thing is more `sets`, and with it at
     * zero the whole thing does nothing. So it is asked for at the end rather than left on a heading row
     * for somebody to find, which is where `draws: 0` pools come from.
     */
    private fun buildAPool(into: Slot? = null) {
        if (into != null) {
            building = into
            // On the menu rather than straight into a facet: adding one is a choice like the others.
            return keepBuilding(into)
        }
        // Adding to a pool that exists is the same errand as starting one, so the row says which it is.
        val sides = listOf(Slot.REQUIRED_POOL, Slot.REQUESTED_POOL).map { slot ->
            val standing = candidate.holding(slot).size
            Picker.Option(
                value = slot.name,
                label = if (standing == 0) slot.title else "${slot.title} (add to its $standing)",
                note = slot.about,
            )
        }
        overlay = Picker("A pool of what?", sides) { picked -> buildAPool(Slot.valueOf(picked.value)) }
    }

    /** After a facet goes in: another, the count, or done. */
    private fun keepBuilding(into: Slot) {
        val pool = candidate.holding(into)
        val drawn = if (into.required) candidate.draws else candidate.requests.draws
        overlay = Picker(
            title = "${into.title} — ${pool.size} facet(s)",
            options = listOf(
                Picker.Option(
                    ANOTHER_FACET,
                    if (pool.isEmpty()) "add the first facet" else "add another facet",
                    pool.keys.sorted().joinToString(" "),
                ),
                Picker.Option(
                    HOW_MANY_DRAWN,
                    "how many are drawn",
                    when {
                        drawn <= 0 -> "none — a pool at zero never fires"
                        drawn >= pool.size -> "all of them, which is the same as `sets`"
                        else -> "$drawn of ${pool.size}"
                    },
                ),
                Picker.Option(DONE_BUILDING, "done", "", startsGroup = true),
            ),
        ) { picked ->
            when (picked.value) {
                ANOTHER_FACET -> pickAParameter(into)
                HOW_MANY_DRAWN -> retypeDraws(into)
                else -> building = null
            }
        }
    }

    private fun add() {
        when (part) {
            // Straight to the parameter: an effect is a value on a parameter, and the other thing that question
            // used to offer belongs — and now lives — in the picks.
            Part.EFFECTS -> pickAParameter(null)
            Part.PICKS -> pickAWayToChoose()
            else -> Unit
        }
    }

    private fun remove() {
        val handle = rows().getOrNull(row())?.handle ?: return
        if (handle == "+") return
        when (part) {
            Part.EFFECTS -> {
                val slot = Slot.entries.firstOrNull { it.name == handle.substringBefore('/') }
                if (slot != null) edit { it.without(slot, handle.substringAfter('/')) }
            }
            Part.PICKS -> when {
                handle.startsWith("heading/") -> Unit
                handle.startsWith("means/") -> forgetMeaning(handle.removePrefix("means/"))
                handle.startsWith("weight/") -> forgetWeight(handle.removePrefix("weight/"))
                else -> forgetTag(handle)
            }
            else -> Unit
        }
    }

    // -- the edits themselves ------------------------------------------------------------------------

    private fun renameTo() = typeOn(
        handle = "name",
        standing = candidate.name,
        complaint = { typed ->
            when {
                typed.isBlank() -> "a word needs a name"
                !typed.matches(Regex("[a-z0-9/._-]+")) -> "lower case, digits and _ - . / only"
                else -> null
            }
        },
    ) { typed -> edit { it.copy(name = typed) } }

    /**
     * Deleting the word, asked first.
     *
     * A word is a file and three entries elsewhere, so this is not something to be one keystroke away
     * from — and the question names what it is about rather than asking "are you sure".
     */
    private fun askAboutDeleting() {
        val had = openedAs
        if (candidate.isDerived || had == null) {
            message = "nothing to delete: this word has no file yet"
            return
        }
        val answers = listOf(
            Picker.Option("keep", "keep it", "nothing changes"),
            Picker.Option(
                value = "delete",
                label = "delete it",
                note = "the file, its rarity, its ink and what it is shown as. There is no undo.",
                mark = Glyph.WARN,
                tone = Palette.refused,
            ),
        )
        overlay = Picker("Delete '${candidate.name}' for good?", answers) { picked ->
            if (picked.value != "delete") return@Picker
            runCatching { WordFile.deleteWord(had) }
                .onSuccess { changed = true; leaving = true }
                .onFailure { message = "could not delete: ${it.message}" }
        }
    }

    /**
     * The help, which is its own screen.
     *
     * Raw mode is handed over for it, as it is for `$EDITOR`: it runs its own loop, and two of them
     * reading the same terminal would each get half the keys.
     */
    private fun openTheHelp() {
        raw?.close()
        Help(terminal, canvas, corpus).run()
        raw = terminal.enterRawMode(MouseTracking.Off)
    }

    /**
     * What a player reads, which is not what the file is called.
     *
     * It lives in the language file rather than in the word, because everything player-facing already
     * reads it from there and a name in a datapack could not be translated.
     */
    private fun retitle() {
        val id = candidate.listingKey
        typeOn("display", WordFile.displayOf(id).orEmpty()) { typed ->
            runCatching { WordFile.setDisplay(id, typed.ifBlank { null }) }
                .onSuccess { message = typed.ifBlank { null }?.let { "shown as $it" } ?: "back to the fallback" }
                .onFailure { message = "could not rename: ${it.message}" }
            judged = null
        }
    }

    private fun parameterNamesIn(aspect: Aspect): List<String> =
        (aspect.parameters.map { it.name } + corpus.vocabulary.candidatesFor(aspect).flatMap { preset ->
            preset.ownParameters.filter(preset::honours).map { it.name }
        }).distinct().sorted()

    /**
     * A parameter, offered by name across every aspect that owns one — because that is what a writer is
     * choosing. **A name shared by several aspects is the good case** and is shown as such: one `colour`
     * word paints eight aspects, and the note says which before it is chosen rather than after.
     */
    /** [into] null asks which slot, which is what the add-an-effect row wants. */
    private fun pickAParameter(into: Slot?) {
        val owners = Aspect.entries.flatMap { aspect -> parameterNamesIn(aspect).map { it to aspect } }
            .groupBy({ it.first }, { it.second })
        val options = owners.entries.sortedBy { it.key }.map { (parameter, aspects) ->
            val said = aspects.firstNotNullOfOrNull { aspect ->
                Verdict.parametersNamed(aspect, parameter, corpus).firstOrNull { it.help.isNotBlank() }?.help
            }.orEmpty()
            Picker.Option(
                value = parameter,
                label = parameter,
                note = listOfNotNull(said.ifEmpty { null }, aspects.sortedBy { it.ordinal }.joinToString(" ") { it.page })
                    .joinToString("  ${Glyph.BULLET}  "),
            )
        }
        overlay = Picker("Which value?", options) { picked ->
            val aspects = owners[picked.value].orEmpty()
            if (aspects.size > 1) qualify(picked.value, aspects, into) else certaintyFor(listOf(picked.value), into)
        }
    }

    /**
     * Which of Minecraft's dimensions the Age is built on.
     *
     * **A list, like naming**, and for the same reason: a base is one of three things `AgeTemplate` knows,
     * and typing a fourth would only fail later. A modded dimension could be one — what a base supplies is
     * a chunk generator and a dimension type, which any dimension has — but nothing reads one yet, so the
     * list says what actually works.
     */
    private fun pickABaseDimension() {
        val options = parts.baseDimensions().map { (key, said) ->
            Picker.Option(key, key, said, marked = key == candidate.template)
        }
        overlay = Picker(
            title = "Built on which dimension?",
            options = options,
            onClear = { edit { it.copy(template = null) } },
            onPick = { picked -> edit { it.copy(template = picked.value) } },
        )
    }

    /**
     * The things this mod wrote that a word may claim by name.
     *
     * **A list rather than a prompt**, which is the whole point of the change: it used to be free text, so
     * you could type anything and find out later that nothing answered to it. Naming is only ever about
     * our own pieces — an open aspect mints a preset from any id it is handed, and a derived word claims
     * its own registry entry without anybody writing that down.
     */
    private fun pickOneOfOurs() {
        val options = parts.oursToName().map { (aspect, key) ->
            Picker.Option(
                value = "${aspect.page}$MEANING_MARK$key",
                label = key,
                note = "${aspect.page}  ${Glyph.BULLET}  " +
                    corpus.vocabulary.candidatesFor(aspect).firstOrNull { it.key == key }
                        ?.let { corpus.vocabulary.tagsOf(it).keys.joinToString(" ") { tag -> "$TAG_MARK$tag" } }
                        .orEmpty(),
                marked = candidate.meansExactly[aspect] == key,
            )
        }
        overlay = Picker(
            title = "Mean which of ours outright?",
            options = options,
            onClear = { edit { it.copy(meansExactly = emptyMap()) } },
            onPick = { picked -> edit { it.copy(meansExactly = it.meansExactly + meaningIn(picked.value)) } },
        )
    }

    /** A picked option said back as the one entry it stands for — `landmass/alps` is the alps, there. */
    private fun meaningIn(picked: String): Pair<Aspect, String> {
        val page = picked.substringBefore(MEANING_MARK)
        val aspect = Aspect.entries.first { it.page == page }
        return aspect to picked.substringAfter(MEANING_MARK)
    }

    /** Drop what this word meant in one part of the world, leaving whatever it means elsewhere. */
    private fun forgetMeaning(page: String) =
        edit { it.copy(meansExactly = it.meansExactly.filterKeys { aspect -> aspect.page != page }) }

    /** The three ways a word chooses a preset, offered in the order the resolver reads them. */
    private fun pickAWayToChoose() {
        val ways = listOf(
            Picker.Option(
                "names",
                "one of ours",
                "a landform, sky or phenomenon this mod wrote; it answers before any tag",
            ),
            Picker.Option("weight", "one by name", "a weight on a named preset, which beats the tags"),
            Picker.Option("tag", "by tag", "anything carrying the tags you ask for"),
            // **Here rather than under the effects**, which is where it used to be: a leaned tag is a
            // query, it is written into `requests.queries`, and it shows up in this section. Offering it
            // from the other one meant adding an effect and watching the answer appear somewhere else.
            Picker.Option(
                "leaning",
                "offered by tag",
                "the same, and given up wherever the book already chose",
            ),
        )
        overlay = Picker("Choose a preset how?", ways) { picked ->
            when (picked.value) {
                "names" -> pickOneOfOurs()
                "weight" -> pickAnAspectToWeighIn()
                "leaning" -> pickAnAspectToLean()
                else -> pickATag()
            }
        }
    }

    /** An offered query names its aspect, because a flat one would lean everywhere the word reaches. */
    private fun pickAnAspectToLean() {
        val options = Aspect.entries.filter { it.holds == Holds.CATALOGUE }.sortedBy { it.ordinal }
            .map { aspect ->
                Picker.Option(
                    aspect.page,
                    aspect.page,
                    "${corpus.vocabulary.askableIn(aspect).size} to choose between",
                )
            }
        overlay = Picker("Lean which aspect?", options) { picked ->
            Aspect.entries.firstOrNull { it.page == picked.value }?.let(::pickATagToLeanOn)
        }
    }

    private fun pickATagToLeanOn(aspect: Aspect) {
        val options = corpus.vocabulary.candidatesFor(aspect)
            .flatMap { corpus.vocabulary.tagsOf(it).keys }
            .distinct()
            .sorted()
            .map { tag ->
                val carriers = corpus.vocabulary.candidatesFor(aspect)
                    .filter { tag in corpus.vocabulary.tagsOf(it) }
                Picker.Option(tag, tag, carriers.joinToString(" ") { it.key })
            }
        overlay = Picker("Lean ${aspect.page} toward which tag?", options) { picked ->
            retypeOfferedWeight("${aspect.page}/${picked.value}")
        }
    }

    private fun retypeOfferedWeight(handle: String) {
        val aspect = Aspect.entries.firstOrNull { it.page == handle.substringBefore('/') } ?: return
        val tag = handle.substringAfter('/')
        overlay = Prompt(
            title = "How far does it lean ${aspect.page} toward '$tag'?",
            hint = "positive pulls, negative pushes away; it only picks between what is left",
            typed = candidate.requests.queries[aspect]?.get(tag)?.toString() ?: "1.0",
            complaint = { typed -> if (typed.toDoubleOrNull() == null) "a number between -1 and 1" else null },
            onDone = { typed ->
                edit { at ->
                    val leaning = at.requests.queries[aspect].orEmpty() + (tag to typed.toDouble())
                    at.copy(requests = at.requests.copy(queries = at.requests.queries + (aspect to leaning)))
                }
            },
        )
    }

    private fun forgetOffered(handle: String) {
        val aspect = Aspect.entries.firstOrNull { it.page == handle.substringBefore('/') } ?: return
        val tag = handle.substringAfter('/')
        edit { at ->
            val left = at.requests.queries[aspect].orEmpty() - tag
            val queries = if (left.isEmpty()) at.requests.queries - aspect else at.requests.queries + (aspect to left)
            at.copy(requests = at.requests.copy(queries = queries))
        }
    }

    /**
     * Whether the effect is demanded or offered, asked where the caller did not already say.
     *
     * **The two pools are not here**, though they are two of the four slots. A pool is a group and a
     * count, and reaching one through the same list as an ordinary effect gave two ways to build the same
     * thing — one of which quietly left the count at zero, where the pool never fires. `add a pool` is
     * the way in, and it asks for the count as part of the job.
     */
    private fun certaintyFor(parameters: List<String>, into: Slot?) {
        if (into != null) return typeValueFor(parameters, into)
        val slots = Slot.entries.filterNot { it.drawn }.map { Picker.Option(it.name, it.title, it.about) }
        overlay = Picker("Demanded, or offered?", slots) { picked ->
            typeValueFor(parameters, Slot.valueOf(picked.value))
        }
    }

    /**
     * Which aspect's parameter is meant, where several own one by that name.
     *
     * **Several can be marked at once**, because that is what a writer is usually doing: one `colour` is
     * owned by eight aspects and a word about a red sky wants three or four of them, which used to mean
     * walking the whole flow once per aspect and keeping track of which were done. Enter marks, right
     * takes one alone, and the row at the bottom takes everything marked.
     */
    private fun qualify(parameter: String, aspects: List<Aspect>, into: Slot?) {
        val ordered = aspects.sortedBy { it.ordinal }
        val everywhere = Picker.Option(
            value = EVERY_ASPECT,
            label = "all of them",
            note = ordered.joinToString(" ") { it.page } + " ${Glyph.BULLET} usually what you want",
        )
        val each = ordered.mapIndexed { at, aspect ->
            Picker.Option(
                value = aspect.page,
                label = aspect.page,
                note = "${corpus.vocabulary.askableIn(aspect).size} to choose between",
                startsGroup = at == 0,
            )
        }
        val take = Picker.Option(
            value = TAKE_THE_MARKED,
            label = "use what is marked",
            note = "enter marks a row above ${Glyph.BULLET} this takes them all",
            startsGroup = true,
        )
        var standing: Picker? = null
        standing = Picker(
            title = "${aspects.size} aspects have a parameter called '$parameter'. Which?",
            options = listOf(everywhere) + each + take,
            marking = true,
            // **Right takes the marks where there are any**, and the row under the cursor where there
            // are none. Ignoring marks made it a way to lose them by pressing the key that means "go".
            onOnly = { picked ->
                val marked = standing?.marked.orEmpty()
                when {
                    marked.isNotEmpty() -> certaintyFor(marked.map { qualified(parameter, it) }, into)
                    picked.value == TAKE_THE_MARKED -> Unit
                    picked.value == EVERY_ASPECT -> certaintyFor(listOf(parameter), into)
                    else -> certaintyFor(listOf(qualified(parameter, picked.value)), into)
                }
            },
        ) { picked ->
            val marked = standing?.marked.orEmpty()
            when {
                picked.value == EVERY_ASPECT -> { overlay = null; certaintyFor(listOf(parameter), into) }
                picked.value != TAKE_THE_MARKED -> standing?.mark(picked.value)
                marked.isEmpty() -> Unit
                else -> {
                    overlay = null
                    certaintyFor(marked.map { qualified(parameter, it) }, into)
                }
            }
        }
        overlay = standing
    }

    /** `sun.colour` — or the bare parameter, where the row stood for every aspect that owns one. */
    private fun qualified(parameter: String, page: String) =
        if (page == EVERY_ASPECT || page == TAKE_THE_MARKED) parameter else "$page.$parameter"

    /**
     * What to set the parameter to.
     *
     * **A closed parameter's values are marked rather than typed.** A parameter takes alternatives as
     * `red|blue|green` and an Age draws one per world, which meant spelling three options exactly and
     * hoping — with the list of what they could be one screen back. Marking builds the same string and
     * previews it while it is built. A band is still typed, because a band is a number and not a choice.
     */
    private fun typeValueFor(parameters: List<String>, into: Slot) {
        val bare = parameters.first().substringAfterLast('.')
        // The aspect the parameter was qualified to, where it was — else whichever owns a parameter by that name.
        val on = Aspect.entries.firstOrNull { it.page == parameters.first().substringBefore('.', "") }
            ?: Aspect.entries.firstOrNull { it.ownsParameterNamed(bare) }
        val parameter = on?.let { Verdict.parametersNamed(it, bare, corpus).firstOrNull() }
        val shapes = parameter?.let { parts.optionsFor(it, on) }.orEmpty()
        if (shapes.isEmpty()) return retypeParameter(parameters, into, starting = "")
        // **A band is the one thing still typed.** Its shapes are templates to edit rather than answers,
        // where every other parameter's values are the answers themselves and can simply be marked.
        if (parameter?.holds == Holds.RANGE) {
            overlay = Picker(
                title = "What kind of value?",
                options = shapes,
                // The scale, with whatever the cursor is on shaded across the ground it would claim.
                chart = { focused -> Axis.chart(parameter, focused?.value, canvas.width - PANE_MARGIN) },
            ) { picked -> retypeParameter(parameters, into, starting = picked.value) }
            return
        }
        val take = Picker.Option(
            value = TAKE_THE_MARKED,
            label = "use what is marked",
            note = "several become alternatives, and an Age draws one",
            startsGroup = true,
        )
        // The way out for a value the corpus does not list — a block from a mod that is not installed,
        // or a spelling the picker cannot know about.
        val byHand = Picker.Option(TYPE_IT, "type it myself", "for anything not on this list")
        var standing: Picker? = null
        standing = Picker(
            title = "Set '$bare' to what?",
            options = shapes + take + byHand,
            marking = true,
            onOnly = { picked -> settleValue(parameters, into, picked, standing?.marked.orEmpty()) },
            joinsWith = ALTERNATIVELY,
        ) { picked ->
            val marked = standing?.marked.orEmpty()
            when {
                picked.value == TYPE_IT -> retypeParameter(parameters, into, starting = "")
                picked.value != TAKE_THE_MARKED -> standing?.mark(picked.value)
                marked.isEmpty() -> Unit
                else -> setParameters(parameters, into, marked.joinToString(ALTERNATIVELY))
            }
        }
        overlay = standing
    }

    /**
     * A value **chosen rather than typed**, written straight in.
     *
     * The picker used to hand what was marked to a prompt with it already filled in, which asked somebody
     * to confirm a string they had just built out of a list — every value it could hold was one of the
     * rows they had picked from. A band is different and still goes through the prompt: a shape like
     * `0.5..1.0` is a template to edit, not an answer.
     */
    private fun settleValue(parameters: List<String>, into: Slot, picked: Picker.Option, marked: Set<String>) {
        when {
            marked.isNotEmpty() -> setParameters(parameters, into, marked.joinToString(ALTERNATIVELY))
            picked.value == TAKE_THE_MARKED -> Unit
            picked.value == TYPE_IT -> retypeParameter(parameters, into, starting = "")
            else -> setParameters(parameters, into, picked.value)
        }
    }

    private fun setParameters(parameters: List<String>, into: Slot, value: String) {
        overlay = null
        edit { at -> parameters.fold(at) { word, parameter -> word.putting(into, parameter, value) } }
        building?.let(::keepBuilding)
    }

    /** One value, written to every parameter asked for — they were chosen together and they mean one thing. */
    private fun retypeParameter(parameters: List<String>, into: Slot, starting: String? = null) {
        if (parameters.isEmpty()) return
        val bare = parameters.first().substringAfterLast('.')
        val said = Aspect.entries.firstNotNullOfOrNull { aspect ->
            Verdict.parametersNamed(aspect, bare, corpus).firstOrNull { it.help.isNotBlank() }?.help
        }.orEmpty()
        overlay = Prompt(
            title = if (parameters.size == 1) "Set '${parameters.single()}' to what?" else "Set ${parameters.size} parameters to what?",
            hint = listOfNotNull(
                parameters.takeIf { it.size > 1 }?.joinToString(" "),
                said.ifEmpty { null },
                "one value, or several separated by | to draw one per Age",
            ).joinToString("  ${Glyph.BULLET}  "),
            typed = starting ?: candidate.holding(into)[parameters.first()].orEmpty(),
            complaint = { typed -> if (typed.isBlank()) "needs a value" else null },
            onDone = { typed ->
                edit { at -> parameters.fold(at) { word, parameter -> word.putting(into, parameter, typed) } }
                building?.let(::keepBuilding)
            },
        )
    }

    /** The count, typed on the pool's own heading — a number is not worth a screen of its own. */
    private fun retypeDraws(of: Slot) {
        val standing = if (of == Slot.REQUIRED_POOL) candidate.draws else candidate.requests.draws
        overlay = null
        building = null
        turnTo(Part.EFFECTS)
        inside = true
        rows().indexOfFirst { it.handle == "draws/${of.name}" }.takeIf { it >= 0 }?.let { rowOf[Part.EFFECTS] = it }
        typeOn(
            handle = "draws/${of.name}",
            standing = standing.toString(),
            complaint = { typed -> if (typed.toIntOrNull()?.let { it >= 0 } == true) null else "a whole number, 0 or more" },
        ) { typed ->
            val many = typed.toInt()
            edit { at ->
                if (of == Slot.REQUIRED_POOL) at.copy(draws = many)
                else at.copy(requests = at.requests.copy(draws = many))
            }
        }
    }

    /**
     * A tag to want or to push away, **listed with what it actually carries** — which is the fact a query
     * is otherwise written blind to, and the reason eight carried tags are asked by nobody.
     */
    /**
     * **Where the tags are asked, before which tags.**
     *
     * A query has to name the part of the world it is about: a word cannot declare its targets any more,
     * and a query keyed nowhere would reach nowhere. An evocative word may say `all` instead, which is
     * what one has always meant — it is written on the Age rather than on a part of it.
     */
    private fun pickATag() {
        val everywhere = if (candidate.tier.narrows) {
            emptyList()
        } else {
            listOf(
                Picker.Option(
                    Word.EVERYWHERE,
                    "the whole Age",
                    "an evocative word leans everything and rules nothing out",
                ),
            )
        }
        val options = everywhere + Aspect.entries.sortedBy { it.ordinal }.map { aspect ->
            Picker.Option(
                value = aspect.page,
                label = aspect.page,
                note = "${corpus.vocabulary.askableIn(aspect).size} to choose between",
                startsGroup = everywhere.isNotEmpty() && aspect == Aspect.entries.first(),
            )
        }
        overlay = Picker("Ask it of which part of the world?", options) { picked ->
            pickATagIn(picked.value)
        }
    }

    private fun pickATagIn(page: String) {
        val carried = corpus.vocabulary.carriedTags.sorted()
        val onlyAServerGrants = corpus.vocabulary.tagsOnlyAServerGrants.sorted()
        val standing = if (page == Word.EVERYWHERE) {
            candidate.everywhere.keys
        } else {
            Aspect.entries.firstOrNull { it.page == page }?.let { candidate.queries[it]?.keys }.orEmpty()
        }
        val options = (carried + onlyAServerGrants).map { tag ->
            Picker.Option(value = tag, label = tag, note = carriedNote(tag), marked = tag in standing)
        }
        overlay = Picker("Which tag?", options) { picked -> retypeTagWeight("$page/${picked.value}") }
    }

    private fun carriedNote(tag: String): String {
        val here = Aspect.entries.mapNotNull { aspect ->
            val many = corpus.vocabulary.candidatesFor(aspect).count { tag in corpus.vocabulary.tagsOf(it) }
            if (many == 0) null else "${aspect.page} $many"
        }
        val asked = corpus.vocabulary.authoredWords.count { tag in it.wanted || tag in it.unwanted }
        val carried = here.joinToString(" ").ifEmpty { "carried by nothing offline" }
        return "$carried ${Glyph.BULLET} asked by $asked word(s)"
    }

    private fun retypeTagWeight(handle: String) {
        val aspect = handle.substringBefore('/').takeIf { it != handle }
            ?.let { page -> Aspect.entries.firstOrNull { it.page == page } }
        val tag = handle.substringAfterLast('/')
        val existing = aspect?.let { candidate.queries[it]?.get(tag) } ?: candidate.everywhere[tag]
        overlay = Prompt(
            title = "How much does it want '$tag'?",
            hint = "1.0 wants it outright, -0.8 pushes it away",
            typed = existing?.toString() ?: "1.0",
            complaint = { typed -> if (typed.toDoubleOrNull() == null) "a number between -1 and 1" else null },
            onDone = { typed ->
                val weight = typed.toDouble()
                edit { at ->
                    if (aspect == null) {
                        at.copy(everywhere = at.everywhere + (tag to weight))
                    } else {
                        at.copy(queries = at.queries + (aspect to (at.queries[aspect].orEmpty() + (tag to weight))))
                    }
                }
            },
        )
    }

    private fun forgetTag(handle: String) {
        val page = handle.substringBefore('/').takeIf { it != handle }
        val tag = handle.substringAfterLast('/')
        val aspect = page?.let { named -> Aspect.entries.firstOrNull { it.page == named } }
        edit { at ->
            if (aspect == null) {
                at.copy(everywhere = at.everywhere - tag)
            } else {
                val left = at.queries[aspect].orEmpty() - tag
                at.copy(queries = if (left.isEmpty()) at.queries - aspect else at.queries + (aspect to left))
            }
        }
    }

    private fun pickAnAspectToWeighIn() {
        val options = Aspect.entries.sortedBy { it.ordinal }.map { aspect ->
            Picker.Option(aspect.page, aspect.page, "${corpus.vocabulary.candidatesFor(aspect).size} to choose between")
        }
        overlay = Picker("Weigh a preset in which aspect?", options) { picked ->
            Aspect.entries.firstOrNull { it.page == picked.value }?.let(::pickAPreset)
        }
    }

    private fun pickAPreset(aspect: Aspect) {
        val options = corpus.vocabulary.candidatesFor(aspect).map { preset ->
            Picker.Option(
                value = preset.key,
                label = preset.key,
                note = corpus.vocabulary.tagsOf(preset).entries.sortedByDescending { it.value }
                    .joinToString(" ") { "${it.key} ${"%.1f".format(it.value)}" },
            )
        }
        overlay = Picker("Which ${aspect.page}?", options) { picked ->
            retypePresetWeight("${aspect.page}/${picked.value}")
        }
    }

    private fun retypePresetWeight(handle: String) {
        val aspect = Aspect.entries.firstOrNull { it.page == handle.substringBefore('/') } ?: return
        val preset = handle.substringAfter('/')
        overlay = Prompt(
            title = "How much does it want '$preset'?",
            hint = "a weight by name beats the tags",
            typed = candidate.weights[aspect]?.get(preset)?.toString() ?: "1.0",
            complaint = { typed -> if (typed.toDoubleOrNull() == null) "a number between -1 and 1" else null },
            onDone = { typed ->
                edit { at ->
                    at.copy(weights = at.weights + (aspect to (at.weights[aspect].orEmpty() + (preset to typed.toDouble()))))
                }
            },
        )
    }

    private fun forgetWeight(handle: String) {
        val aspect = Aspect.entries.firstOrNull { it.page == handle.substringBefore('/') } ?: return
        val preset = handle.substringAfter('/')
        edit { at ->
            val left = at.weights[aspect].orEmpty() - preset
            at.copy(weights = if (left.isEmpty()) at.weights - aspect else at.weights + (aspect to left))
        }
    }

    private fun retypeField(handle: String) {
        if (handle == "flows") {
            edit { it.copy(mintsSomethingThatFlows = !it.mintsSomethingThatFlows) }
            return
        }
        overlay = Prompt(
            title = "Set '$handle' to what?",
            hint = "blank for none",
            typed = candidate.mints.orEmpty(),
            complaint = { null },
            onDone = { typed ->
                val said = typed.ifBlank { null }
                edit { at -> at.copy(mints = said) }
            },
        )
    }

    private fun relist(handle: String) {
        if (handle == "ink") return reink()
        val standing = WordFile.listingFor(candidate.listingKey).rarity
        val standings = WordFile.rarityStandings()
        val total = standings.values.sumOf { (weight, _) -> weight }
        val options = WordFile.rarityBuckets().map { bucket ->
            val (weight, listed) = standings[bucket] ?: (0.0 to 0)
            val share = if (total <= 0) "" else "%.0f%% of pages".format(weight / total * 100)
            Picker.Option(
                value = bucket,
                label = bucket,
                note = "$share ${Glyph.BULLET} $listed words" + if (bucket == standing) "  ${Glyph.BULLET} current" else "",
            )
        }
        overlay = Picker(
            title = "Which rarity?",
            options = options,
            onPick = { picked -> writeRarity(picked.value) },
            onClear = { writeRarity(null) },
        )
    }

    private fun writeRarity(bucket: String?) {
        // A derived word is named by its full id, which is how `WordRarity` finds it and how it stops
        // being drawn from the anonymous derived mass.
        WordFile.list("rarity", candidate.listingKey, bucket)
        message = bucket?.let { "rarity set to $it" } ?: "rarity cleared"
        judged = null
    }

    /**
     * Which ink it demands — **through a tag for a derived word and a name list for an authored one.**
     *
     * The two halves answer through different channels on purpose: a derived word is a registry entry, so
     * tagging the entry is what lets another mod's ore be worth the good ink without touching our files.
     */
    private fun reink() {
        val where = if (candidate.isDerived) corpus.registryOf(candidate.id) else null
        if (candidate.isDerived && where == null) {
            message = "nothing in the game has the id ${candidate.id}, so it cannot be tagged"
            return
        }
        val standing = if (where != null) {
            WordFile.inkTagOn(candidate.id.toString(), where)
        } else {
            WordFile.listingFor(candidate.listingKey).ink
        }
        val options = WordFile.inkTiers().map {
            Picker.Option(it, it, if (it == standing) "current" else "")
        }
        overlay = Picker(
            title = "Which ink quality?",
            options = options,
            onPick = { picked -> writeInk(where, picked.value) },
            onClear = { writeInk(where, null) },
        )
    }

    private fun writeInk(where: String?, tier: String?) {
        if (where == null) {
            WordFile.list("ink", candidate.name, tier)
        } else {
            WordFile.inkTagFor(candidate.id.toString(), where, tier)
        }
        message = tier?.let { "ink set to $it" } ?: "ink cleared"
        judged = null
    }

    /**
     * The reasoning, in `$EDITOR`.
     *
     * Raw mode is given up for the duration and taken back afterwards — a full-screen editor inside a
     * full-screen editor cannot share a terminal, and a 15-line argument about why a word exists wants
     * a real one rather than a line at a time.
     */
    private fun openTheEditor() {
        val editor = System.getenv("EDITOR") ?: System.getenv("VISUAL") ?: DEFAULT_EDITOR
        val scratch = File.createTempFile("word-comment-", ".md")
        scratch.writeText(candidate.commentLines.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
        val ran = runCatching {
            raw?.close()
            canvas.lending { ProcessBuilder(editor, scratch.absolutePath).inheritIO().start().waitFor() }
        }
        raw = terminal.enterRawMode(MouseTracking.Off)
        if (ran.getOrNull() == 0) {
            edit { it.commenting(scratch.readLines()) }
        } else {
            message = "could not run $editor; set \$EDITOR"
        }
        scratch.delete()
    }

    // -- the errands ---------------------------------------------------------------------------------

    private fun undo() {
        if (history.size <= 1) {
            message = "nothing to undo"
            return
        }
        history.removeAt(history.lastIndex)
    }

    private fun save() {
        if (candidate.isDerived) {
            message = "${candidate.name} has no file — its rarity and ink are already saved"
            return
        }
        val refused = Verdict.refusals(findings)
        if (refused.isNotEmpty()) {
            message = "not saved: ${refused.size} error(s). ^f to see them."
            return
        }
        val file = runCatching {
            openedAs?.takeIf { it != candidate.name }?.let { WordFile.renameWord(it, candidate.name) }
            WordFile.write(candidate)
        }
        message = file.fold(
            onSuccess = { "saved ${it.path}" },
            onFailure = { "could not save: ${it.message}" },
        )
        changed = changed || file.isSuccess
        if (file.isSuccess) written = candidate
    }

    /**
     * A sentence read and resolved **with this word in the corpus** — the answer to "does it do what its
     * name promises", which no table gives.
     */
    private fun askForASentence() {
        overlay = Prompt(
            title = "Try a book",
            hint = "words in order, `age` first: '${candidate.name} age blue sun'",
            // A book whose first clause closes on the Age, because a row that is not a book is quietly
            // filled in by `Repair` and what comes back is a world nobody wrote.
            typed = "${candidate.name} age",
            complaint = { typed -> if (typed.isBlank()) "type something" else null },
            onDone = { typed -> overlay = Preview.resolved(candidate, typed, corpus) },
        )
    }

    private companion object {
        /** What separates the part of the world from the preset in a picked meaning — `landmass/alps`. */
        const val MEANING_MARK = '/'

        /**
         * Where a section's status mark sits — **one column past the longest name there is.**
         *
         * It was a hardcoded eleven, which `specificity` filled exactly and `base dimension` overran, so
         * one mark touched its name and another had nowhere to go at all. Measured off [Part] instead, so
         * renaming a section or adding one moves the column with it.
         */
        val MARK_COLUMN: Int =
            Part.entries.maxOf { it.title.length + if (it.perilous) WARNING_ROOM else 0 } + 1

        /** The cursor in front of a name, the names themselves, and room for a mark after them. */
        val SECTION_WIDTH: Int = CURSOR_ROOM + MARK_COLUMN + MARK_ROOM

        /** `▸ ` before a section name. */
        const val CURSOR_ROOM = 2

        /** `! ` before a perilous one. */
        const val WARNING_ROOM = 2

        /** A tick, or a count — the effects section shows how many there are. */
        const val MARK_ROOM = 2

        /** What the word pane keeps while a question is open, so the question sits beside it. */
        const val WORD_PANE_ASKED = 52
        const val WORD_PANE_LEAST = 30

        /** What a question needs to open beside the word rather than over it, and what it may grow to. */
        const val QUESTION_LEAST = 46
        const val QUESTION_MOST = 92
        const val MINIMUM_WIDTH = 60
        /** The row that opens the prompt, for a value no list could hold. */
        const val TYPE_IT = "\u0000typed"

        const val ANOTHER_FACET = "\u0000another"
        const val HOW_MANY_DRAWN = "\u0000draws"
        const val DONE_BUILDING = "\u0000done"

        const val SAVE_AND_LEAVE = "\u0000save"
        const val LEAVE_ANYWAY = "\u0000leave"
        const val STAY = "\u0000stay"

        /** The row standing for every aspect that owns the parameter, rather than for one of them. */
        const val EVERY_ASPECT = "\u0000all"

        /** The row that takes whatever has been marked. */
        const val TAKE_THE_MARKED = "\u0000marked"

        /** What separates a parameter's alternatives, of which an Age draws one. */
        const val ALTERNATIVELY = "|"

        /** Where a wrapped reading's continuation lines start, so a break reads as one. */
        const val READER_HANGING = "      "

        const val MINIMUM_BODY = 8

        /**
         * How much of the pane the note under a section keeps when the section is longer than the pane.
         *
         * Enough for a parameter's first line and its options, and no more: what a reader is doing while a
         * section is scrolling is finding a row, and the help about the row they are on can wait for
         * them to stop.
         */
        const val NOTE_LEAST = 4

        /** Roughly what the question pane leaves after the sections and the word beside it. */
        const val PANE_MARGIN = 56
        const val STRIP_LINES = 4
        const val PICKER_LABEL = 30
        const val DEFAULT_EDITOR = "vi"

        /** Header, two rules, the strip and the key line — what the body is not allowed to use. */
        const val CHROME_LINES = 4 + STRIP_LINES
    }
}
