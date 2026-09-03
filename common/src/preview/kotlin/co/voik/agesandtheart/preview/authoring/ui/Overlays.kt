package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
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
     * Something to draw above the list, given whatever the cursor is on and **how wide the pane it lands
     * in turned out to be.**
     *
     * A function rather than lines, because what it shows depends on the row: a numeric axis wants the
     * highlighted value shaded on it, and that changes as the cursor moves. The width is passed rather
     * than captured because only the frame knows it — a chart built with a guess at it was cut off on the
     * right of a pane with room to spare.
     */
    val chart: ((Option?, Int) -> List<Line>)? = null,
    /**
     * What `-` and `=` do to the row under the cursor — for a list you **set** rather than pick from.
     *
     * Several leans are usually wanted at once and each is only a number, so walking out to a prompt and
     * back per member was the whole of the work. The caller re-opens the list with the same filter and
     * index, which is the same thing a re-sort already does.
     */
    val onStep: ((Option, Double) -> Unit)? = null,
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
         * A drawn value between the row and its note — a [Gauge], where the row carries one.
         *
         * Styled runs rather than text, which is the whole of why it is not part of [note]: a lean's sign
         * is what changes what it does, and a bar that grows red one way and green the other says that
         * without anybody reading the figure. It is also what makes a list you *set* look like one.
         */
        val gauge: Line? = null,
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

/**
 * **A ranged parameter's value, set on the axis itself** rather than described in words first.
 *
 * A range used to be chosen twice: once from a list naming the five shapes a value can take, and again in
 * a prompt where the number was typed as text — with the axis, its bands and its landmarks on the screen
 * before last. What a writer means by a temperature is a stretch of that axis, so the stretch is what they
 * move, and the shape falls out of it: two ends is a band, one is a floor or a ceiling.
 *
 * [nudge] and [spread] cannot be drawn on the axis — a nudge moves whatever the band turned out to be and
 * a spread widens it, so neither has a place of its own to stand. They are rows under it, stepped the same
 * way every other number in the tool is.
 *
 * **A value is one setting**, so the rows are exclusive: what the cursor is on is what the parameter gets.
 */
class Band(
    override val title: String,
    val parameter: Parameter,
    /** How the words already written say it — pickable, since "the same band as `arid`" is a thing to mean. */
    val presets: List<Picker.Option>,
    said: String,
    val onDone: (String) -> Unit,
) : Overlay {

    enum class Row(val title: String, val about: String) {
        BAND("band", "the stretch of the axis this word asks for"),
        NUDGE("nudge", "more than it would have been; nudges add up"),
        SPREAD("spread", "wider, or narrower, about the middle of the band"),
    }

    /** The low end of the band, or null where it has none and the value is a ceiling. */
    var least: Double? = null
        private set

    /** The high end, or null where the value is a floor. */
    var most: Double? = null
        private set

    var nudge: Double = 0.0
        private set

    var spread: Double = 0.0
        private set

    /** Which of [Row] the cursor is on, then on into [presets]. */
    var index: Int = 0
        private set

    /** Which end of the band the keys move. */
    var onTheHighEnd: Boolean = false
        private set

    var filter: String = ""
        private set

    init {
        when (val standing = Setting.read(said)) {
            is Setting.Fixed -> { least = standing.span.least; most = standing.span.most }
            is Setting.Bound -> { least = standing.least; most = standing.most }
            is Setting.Shift -> { nudge = standing.by; index = Row.NUDGE.ordinal }
            is Setting.Spread -> { spread = standing.by; index = Row.SPREAD.ordinal }
            null -> { least = Span.NATURAL_LEAST; most = Span.NATURAL_MOST }
        }
    }

    /** The rows the filter leaves — the three shapes always, and whatever of [presets] matches. */
    val shown: List<Picker.Option>
        get() = Row.entries.map { Picker.Option(it.name, it.title, it.about) } +
            presets.filter { it.label.contains(filter, ignoreCase = true) }
                .mapIndexed { at, option -> if (at == 0) option.copy(startsGroup = true) else option }

    /** Whether the cursor is down among the words already written, where typing searches. */
    val onAPreset: Boolean get() = index >= Row.entries.size

    val row: Row? get() = Row.entries.getOrNull(index)

    val focusedPreset: Picker.Option? get() = shown.getOrNull(index).takeIf { onAPreset }

    /** What the row under the cursor would write, or an empty string where it would write nothing. */
    val spelled: String
        get() = when {
            onAPreset -> focusedPreset?.value.orEmpty()
            row == Row.NUDGE -> if (nudge == 0.0) "" else Setting.Shift(nudge).spelled()
            row == Row.SPREAD -> if (spread == 0.0) "" else Setting.Spread(spread).spelled()
            least != null && most != null -> Span(minOf(least!!, most!!), maxOf(least!!, most!!)).spelled()
            least != null -> Setting.Bound(least = least).spelled()
            most != null -> Setting.Bound(most = most).spelled()
            else -> ""
        }

    /**
     * **What the chart shades: whatever the cursor is on.**
     *
     * The band while you are moving it; the actual value of a word already written while you are reading
     * one; and for a nudge or a spread what it would do to [ILLUSTRATION], since neither has a band of its
     * own and on the whole axis neither moves anything.
     */
    val drawn: String
        get() = when {
            onAPreset -> focusedPreset?.value.orEmpty()
            row == Row.NUDGE -> Setting.Shift(nudge).spelled()
            row == Row.SPREAD -> Setting.Spread(spread).spelled()
            else -> band
        }

    /** The band as it stands, whatever row the cursor is on — what the `band` row itself always says. */
    val band: String
        get() = when {
            least != null && most != null -> Span(minOf(least!!, most!!), maxOf(least!!, most!!)).spelled()
            least != null -> Setting.Bound(least = least).spelled()
            most != null -> Setting.Bound(most = most).spelled()
            else -> ""
        }

    /** The band [drawn] is applied to — a real one only where what is drawn works on one. */
    val drawnFrom: Span get() = if (illustrated) ILLUSTRATION else Span.NATURAL

    /** Whether what is drawn is an example rather than the value, which the chart colours apart and says. */
    val illustrated: Boolean get() = !onAPreset && (row == Row.NUDGE || row == Row.SPREAD)

    /** The end the keys are moving, for the chart to mark. */
    val handle: Double? get() = if (row != Row.BAND) null else if (onTheHighEnd) most else least

    fun move(by: Int) {
        val size = shown.size
        if (size == 0) return
        index = ((index + by) % size + size) % size
    }

    fun turn() { onTheHighEnd = !onTheHighEnd }

    /** One step of whatever the cursor is on — an end of the band, or one of the two scalars. */
    fun step(by: Double) {
        when (row) {
            Row.NUDGE -> nudge = (nudge + by).coerceIn(Span.NATURAL_LEAST, Span.NATURAL_MOST)
            Row.SPREAD -> spread = (spread + by).coerceIn(Span.NATURAL_LEAST, Span.NATURAL_MOST)
            Row.BAND -> {
                val standing = handle ?: (if (onTheHighEnd) Span.NATURAL_MOST else Span.NATURAL_LEAST)
                val moved = (standing + by).coerceIn(Span.NATURAL_LEAST, Span.NATURAL_MOST)
                if (onTheHighEnd) most = moved else least = moved
            }
            null -> Unit
        }
    }

    /**
     * Take an end off, or put it back — a band with one end is a floor or a ceiling.
     *
     * Never both: a value with neither end says nothing, and the way to say nothing is to leave without
     * taking anything.
     */
    fun dropTheEnd(high: Boolean) {
        if (high) {
            most = if (most == null) Span.NATURAL_MOST else if (least == null) return else null
        } else {
            least = if (least == null) Span.NATURAL_LEAST else if (most == null) return else null
        }
    }

    fun type(character: String) {
        filter += character
        index = Row.entries.size
    }

    fun backspace() {
        filter = filter.dropLast(1)
        index = index.coerceAtMost(shown.size - 1)
    }

    companion object {
        /**
         * The band a nudge and a spread are shown working on.
         *
         * The middle half of the axis, because it is the one band both of them visibly change: a nudge
         * slides it and a spread opens it, where the whole axis has nowhere to slide to and nothing left
         * to open into.
         */
        val ILLUSTRATION = Span(-0.25, 0.25)
    }
}
