package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The screen the tool draws on — **one frame at a time, and only where it changed.**
 *
 * The first version cleared the whole screen and reprinted it on every keystroke, which flickered badly:
 * between the clear and the print there is a real moment where the terminal has nothing to show, and at a
 * frame per keypress that reads as a blink. Nothing about the drawing was slow; the blank was the problem.
 *
 * So this keeps the last frame and writes only the rows that differ, each ended with an erase-to-end
 * rather than a clear. A cursor move and a short write leave no moment where the screen is empty.
 *
 * **Raw escapes rather than Mordant's cursor**, deliberately: absolute positioning is the one thing this
 * needs and every terminal that can run a full-screen program has it, where routing it through a widget
 * layer would buy a capability negotiation for a sequence that is never in doubt.
 */
class Canvas(private val terminal: Terminal) : AutoCloseable {

    /** Whether to colour at all — a run piped to a file should not carry escapes nothing will read. */
    val colourful: Boolean = terminal.terminalInfo.ansiLevel != AnsiLevel.NONE

    private var shown: List<String> = emptyList()
    private var lastSize: Pair<Int, Int> = 0 to 0

    /**
     * The usable width, **a column short of the terminal**: a line filled to the last one wraps, the
     * terminal moves down on its own, and every rule would draw a blank line under itself.
     */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /**
     * The terminal given back if the JVM is killed rather than closed.
     *
     * A full-screen program that dies on the alternate screen leaves a shell with no cursor and no echo,
     * which looks like the terminal has hung. `close()` covers every ordinary path; this covers a signal.
     */
    private val restoreTheTerminal = Thread { write(SHOW_CURSOR + ALTERNATE_SCREEN_OFF) }

    init {
        // The alternate screen, so the tool has the terminal to itself and gives back what was there
        // before — a scrollback full of half-drawn frames is the other thing a full-screen program leaves.
        write(ALTERNATE_SCREEN_ON + HIDE_CURSOR)
        Runtime.getRuntime().addShutdownHook(restoreTheTerminal)
        measure()
    }

    private fun measure() {
        val size = terminal.updateSize()
        width = (size.width - 1).coerceAtLeast(MINIMUM_WIDTH)
        height = size.height.coerceAtLeast(MINIMUM_HEIGHT)
        if (lastSize == size.width to size.height) return
        lastSize = size.width to size.height
        // A resize invalidates every remembered row, so the next frame is written whole.
        shown = emptyList()
        write(CLEAR_SCREEN)
    }

    /** [lines] on the screen, writing only what moved. */
    fun show(lines: List<Line>) {
        measure()
        val frame = lines.map { it.sized(width).trimmed().rendered(colourful) }
        val said = StringBuilder()
        for (row in 0..<maxOf(frame.size, shown.size)) {
            val now = frame.getOrNull(row)
            if (now == shown.getOrNull(row)) continue
            said.append(at(row)).append(now.orEmpty()).append(ERASE_TO_END_OF_LINE)
        }
        shown = frame
        if (said.isEmpty()) return
        // One write for the whole frame: several would let the terminal paint a half-finished screen.
        write(said.toString())
    }

    /**
     * [work] on a thread, with the cursor arrow spinning until it finishes.
     *
     * The intensive things here are intensive for honest reasons — the audit runs every rule over every
     * authored word, and the auto-generated table is sixteen hundred rows — and a screen that simply
     * stops for two seconds reads as a hang.
     *
     * **In place of the cursor rather than on a page of its own.** A loading page had to clear the screen
     * to arrive and clear it again to leave, which flashed on everything quick enough not to need it. The
     * arrow is already where you are looking, and one character changing is not a flash.
     *
     * Where nothing is on screen to decorate — the very first frame — the work simply runs.
     */
    fun <T> whileBusy(said: String? = null, work: () -> T): T {
        // A named errand says so on the top line; an unnamed one spins where the cursor already is.
        val row = if (said == null) shown.indexOfFirst { it.contains(Glyph.FOCUS) } else 0
        val restore = shown.getOrNull(row)
        var answer: Result<T>? = null
        val worker = Thread({ answer = runCatching(work) }, "word-forge")
        worker.isDaemon = true
        worker.start()
        var frame = 0
        while (worker.isAlive) {
            // Slept first, so anything quicker than a single frame never draws at all.
            Thread.sleep(SPINNER_MILLIS)
            if (!worker.isAlive) break
            if (row >= 0) {
                val spun = SPINNER[frame % SPINNER.size]
                // The rendered line still holds the arrow as a plain character, styling and all around
                // it, so swapping it keeps the row looking exactly as it did.
                val line = said?.let { "  $spun $it" } ?: restore.orEmpty().replace(Glyph.FOCUS, spun)
                write(at(row) + line + ERASE_TO_END_OF_LINE)
            }
            frame++
        }
        worker.join()
        if (row >= 0 && frame > 0) write(at(row) + restore.orEmpty() + ERASE_TO_END_OF_LINE)
        return requireNotNull(answer).getOrThrow()
    }

    override fun close() {
        // Removing a hook *during* shutdown throws, which is exactly when this runs on the way out.
        runCatching { Runtime.getRuntime().removeShutdownHook(restoreTheTerminal) }
        write(SHOW_CURSOR + ALTERNATE_SCREEN_OFF)
    }

    /**
     * Gives the terminal back for as long as [work] runs — for `$EDITOR`, which cannot share a screen with
     * a full-screen program. The frame is forgotten, so the next one is written whole.
     */
    fun <T> lending(work: () -> T): T {
        write(SHOW_CURSOR + ALTERNATE_SCREEN_OFF)
        try {
            return work()
        } finally {
            write(ALTERNATE_SCREEN_ON + HIDE_CURSOR + CLEAR_SCREEN)
            shown = emptyList()
        }
    }

    private fun at(row: Int) = "\u001B[${row + 1};1H"

    private fun write(said: String) {
        print(said)
        System.out.flush()
    }

    private companion object {
        const val MINIMUM_WIDTH = 60
        const val MINIMUM_HEIGHT = 16
        const val ALTERNATE_SCREEN_ON = "\u001B[?1049h"
        const val ALTERNATE_SCREEN_OFF = "\u001B[?1049l"
        const val HIDE_CURSOR = "\u001B[?25l"
        const val SHOW_CURSOR = "\u001B[?25h"
        const val CLEAR_SCREEN = "\u001B[2J"
        const val ERASE_TO_END_OF_LINE = "\u001B[K"

        const val SPINNER_MILLIS = 90L
        val SPINNER = listOf("\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F")
    }
}
