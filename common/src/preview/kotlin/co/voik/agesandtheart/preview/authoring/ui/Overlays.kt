package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.rendering.TextStyle

/**
 * What is on top of the editor — a question being answered, rather than a second screen.
 *
 * Overlays are drawn in the editor's own frame rather than through Mordant's blocking prompts. A prompt
 * that takes the terminal over would tear the frame down and put it back for every value typed, and what
 * a writer is doing here is looking at the verdict *while* they change something.
 */
sealed interface Overlay {
    val title: String
}

/** One line of text, with a live complaint where what is typed will not do. */
class Prompt(
    override val title: String,
    val hint: String,
    typed: String,
    val complaint: (String) -> String?,
    val onDone: (String) -> Unit,
) : Overlay {
    var typed: String = typed
        private set

    fun type(character: String) { typed += character }

    fun backspace() { typed = typed.dropLast(1) }

    val says: String? get() = complaint(typed)
}

/** One of a list, narrowed by typing. Arrow keys move; what is typed filters rather than selects. */
class Picker(
    override val title: String,
    private val options: List<Option>,
    /** What `^d` does, where the thing being picked can also be *unset* — a rarity, an ink quality. */
    val onClear: (() -> Unit)? = null,
    /** Kept across a re-sort or an edit, so neither throws away what was typed or where you were. */
    filter: String = "",
    index: Int = 0,
    /**
     * Whether `enter` **marks** a row instead of taking it, so several can be taken at once.
     *
     * One `colour` parameter is owned by eight aspects and a writer usually wants three of them; picking one
     * at a time meant walking the whole flow three times over and remembering which were already done.
     */
    val marking: Boolean = false,
    /** What `→` does while [marking] — take this one alone and never mind what is marked. */
    val onOnly: ((Option) -> Unit)? = null,
    /**
     * What the marked values are joined with, where marking several **builds one value** rather than
     * choosing several things.
     *
     * A parameter takes alternatives as `red|blue|green` and an Age draws one, which had to be typed by hand
     * and spelled exactly. Set this and the marks are previewed as the value they will make.
     */
    val joinsWith: String? = null,
    /**
     * Something to draw above the list, given whatever the cursor is on.
     *
     * A function rather than lines, because what it shows depends on the row: a numeric axis wants the
     * highlighted value shaded on it, and that changes as the cursor moves.
     */
    val chart: ((Option?) -> List<Line>)? = null,
    val onPick: (Option) -> Unit,
) : Overlay {

    private val marks = linkedSetOf<String>()

    /** What has been marked so far, in the order it was marked. */
    val marked: Set<String> get() = marks

    fun mark(value: String) {
        if (!marks.remove(value)) marks.add(value)
    }

    /** Whether to draw a tick — what the caller said outright, or what has been marked since. */
    fun isMarked(option: Option): Boolean = option.marked || option.value in marks

    /**
     * One row. [note] is what makes a pick informed — how many things carry a tag, what an axis takes.
     * [mark] and [tone] carry severity where a list has any, so it reads at a glance.
     */
    data class Option(
        val value: String,
        val label: String = value,
        val note: String = "",
        val marked: Boolean = false,
        val mark: String = "",
        val tone: TextStyle? = null,
        /**
         * A blank line before this row, and nothing else.
         *
         * A separator rather than a row, so nothing here has to be skipped when moving and the index is
         * still an index into the options. Suppressed while the list is being narrowed, where the groups
         * are no longer what is on screen.
         */
        val startsGroup: Boolean = false,
    )

    var filter: String = filter
        private set

    var index: Int = index.coerceAtLeast(0)
        private set

    val shown: List<Option> get() = options.filter { it.label.contains(filter, ignoreCase = true) }

    val focused: Option? get() = shown.getOrNull(index)

    fun type(character: String) {
        filter += character
        index = 0
    }

    fun backspace() {
        filter = filter.dropLast(1)
        index = 0
    }

    fun clearFilter() {
        filter = ""
        index = 0
    }

    fun move(by: Int) {
        val size = shown.size
        if (size == 0) return
        index = ((index + by) % size + size) % size
    }

    /** A page on, or to an end — **without wrapping**, which is what tells it apart from [move]. */
    fun page(by: Int) {
        val size = shown.size
        if (size == 0) return
        index = (index + by).coerceIn(0, size - 1)
    }
}

/** Something to read — a preview, a resolved sentence, the help. Scrolls, and answers nothing. */
class Reader(override val title: String, val lines: List<Line>) : Overlay {
    var offset: Int = 0
        private set

    /**
     * How many rows the last frame actually drew.
     *
     * **Not [lines].size**, because a line too long for the pane is wrapped into several and scrolling
     * counts rows on screen. Clamping against the unwrapped count stopped short of the end by however
     * much the wrapping had added — which on a spelled-out composition is most of it.
     */
    var rows: Int = lines.size

    fun scroll(by: Int, window: Int) {
        val last = (rows - window).coerceAtLeast(0)
        offset = (offset + by).coerceIn(0, last)
    }
}
