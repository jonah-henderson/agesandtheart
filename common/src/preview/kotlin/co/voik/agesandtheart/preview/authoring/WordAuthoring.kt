package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.preview.authoring.ui.Canvas
import co.voik.agesandtheart.preview.authoring.ui.Editor
import co.voik.agesandtheart.preview.authoring.ui.Leaving
import co.voik.agesandtheart.preview.authoring.ui.Menu
import co.voik.agesandtheart.preview.authoring.ui.style
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Scrivener — **a vocabulary editor for Ages and the Art**, run from `scripts/scrivener.sh`.
 *
 * It answers offline. `Vocabulary.load` with vanilla's worldgen registries is the corpus
 * `VocabularyCheck` reads, so what a candidate reaches, what it costs, what its query keeps and what it
 * contradicts are all computed from the real code with no server anywhere. The one thing offline cannot
 * know is a tag only a bound registry grants, and `--refresh` remembers that in a snapshot rather than
 * making a server a condition of writing a word.
 *
 * **Not a `JavaExec` task**, because a full-screen editor needs a TTY and Gradle gives none: it owns
 * stdin and strips the control characters a redraw is made of. The build writes the launch command down
 * and the script starts the JVM.
 */
fun main(arguments: Array<String>) {
    val asked = Arguments.of(arguments)
    if (asked.wantsHelp) {
        println(USAGE)
        return
    }
    // **The corpus is stood up before the terminal is made.** Standing it up redirects System.out and
    // `Corpus.load` puts it back, so a `Terminal` built first would hold the redirected stream and write
    // every frame into the log.
    println("reading the corpus…")
    val corpus = if (asked.refreshing) null else Corpus.load()
    val terminal = Terminal()
    if (asked.refreshing) {
        refresh(terminal, asked.attach)
        return
    }
    requireNotNull(corpus)
    terminal.println(
        TextColors.gray(
            "${corpus.vocabulary.words.size} words, ${corpus.vocabulary.carriedTags.size} tags" +
                (corpus.snapshot?.let { ", ${it.provenance}" } ?: ", no minecraft data imported"),
        ),
    )
    when {
        asked.auditing -> audit(terminal, corpus)
        asked.rewriting -> rewrite(terminal)
        // **A named word goes straight in and a bare run offers the menu.** A flag is somebody who already
        // knows what they want; the menu is where the tool says what it can do at all.
        asked.word != null -> onScreen(terminal) { canvas -> open(terminal, canvas, corpus, asked.word) }
        else -> onScreen(terminal) { canvas -> Menu(terminal, canvas, corpus).run() }
    }
}

/** What the command line said, read the dull way — there are five options and none of them nest. */
private data class Arguments(
    val word: String? = null,
    val auditing: Boolean = false,
    val rewriting: Boolean = false,
    val refreshing: Boolean = false,
    val attach: String? = null,
    val wantsHelp: Boolean = false,
) {
    companion object {
        fun of(arguments: Array<String>): Arguments {
            var read = Arguments()
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index]
                read = when {
                    argument == "--audit" -> read.copy(auditing = true)
                    argument == "--rewrite" -> read.copy(rewriting = true)
                    argument == "--refresh" -> read.copy(refreshing = true)
                    argument == "--attach" -> read.copy(attach = arguments.getOrNull(++index))
                    argument == "--help" || argument == "-h" -> read.copy(wantsHelp = true)
                    argument.startsWith("-") -> read.copy(wantsHelp = true)
                    else -> read.copy(word = argument)
                }
                index++
            }
            return read
        }
    }
}

/**
 * One word, opened or begun.
 *
 * A word that will not read is said rather than started from blank: a file that has drifted out of the
 * schema is a thing to look at, and silently replacing it with an empty form would be how it got lost.
 */
private fun open(terminal: Terminal, canvas: Canvas, corpus: Corpus, name: String) {
    val candidate = if (WordFile.exists(name)) {
        WordFile.read(name).getOrElse { failure ->
            terminal.println(TextColors.brightRed("'$name' would not read: ${failure.message}"))
            return
        }
    } else {
        Candidate.blank(name)
    }
    Editor(terminal, canvas, corpus, candidate, canLeave = false).run()
}

/**
 * The full-screen half of the tool, with the terminal given back however it ends.
 *
 * [Leaving] is `^C`, thrown from whichever screen saw it so it unwinds through all of them at once. By
 * the time it arrives here every `finally` on the way has run — the preview server stopped, its world
 * removed — and there is nothing left to say about it.
 */
private fun onScreen(terminal: Terminal, work: (Canvas) -> Unit) = Canvas(terminal).use { canvas ->
    try {
        work(canvas)
    } catch (leaving: Leaving) {
        Unit
    }
}

/**
 * **The corpus, worst first** — 127 authored words against what they actually do, which is the audit the
 * plan's §7 calls the bulk of the work this tool serves.
 *
 * Ranked by what is wrong rather than alphabetically, because the point is to find the words nobody has
 * looked at since they were written.
 */
private fun audit(terminal: Terminal, corpus: Corpus) {
    val judged = WordFile.authoredNames().mapNotNull { name ->
        val candidate = WordFile.read(name).getOrNull() ?: return@mapNotNull name to unreadable(name)
        name to Verdict.on(candidate, corpus)
    }.sortedWith(Verdict.WORST_FIRST)
    terminal.println(TextStyles.bold("\nThe corpus, worst first\n"))
    for ((name, findings) in judged) {
        val refused = findings.count { it.standing == Verdict.Standing.ERROR }
        val warned = findings.count { it.standing == Verdict.Standing.WARNED }
        val nudged = findings.count { it.standing == Verdict.Standing.NUDGED }
        if (refused + warned + nudged == 0) continue
        val marks = listOfNotNull(
            refused.takeIf { it > 0 }?.let { Verdict.Standing.ERROR.style("$it refused") },
            warned.takeIf { it > 0 }?.let { Verdict.Standing.WARNED.style("$it warned") },
            nudged.takeIf { it > 0 }?.let { Verdict.Standing.NUDGED.style("$it nudged") },
        )
        terminal.println("  ${name.padEnd(24)}${marks.joinToString("  ")}")
        findings.filterNot { it.standing == Verdict.Standing.NOTED }.forEach {
            terminal.println(TextColors.gray("      ${it.says}"))
        }
    }
    val clean = judged.count { (_, findings) -> findings.none { it.standing != Verdict.Standing.NOTED } }
    terminal.println(TextColors.gray("\n$clean of ${judged.size} words have nothing against them."))
    terminal.println(TextColors.gray("Open one with: scripts/scrivener.sh <name>\n"))
}

private fun unreadable(name: String) = listOf(
    Verdict.Finding(Verdict.Standing.ERROR, "'$name' will not read at all", heldBy = "Vocabulary.load"),
)

/**
 * Every authored word read and written back unchanged — **the corpus in the layout the tool writes.**
 *
 * Run by hand rather than wired into anything, with `AuthoringCheck`'s round trip standing behind it: the
 * check insists a rewrite changes nothing, so this is only ever needed after the layout itself moves, and
 * a run that changes a file is a run whose diff somebody should read. Same bargain as `:common:grammars`.
 */
private fun rewrite(terminal: Terminal) {
    val moved = WordFile.authoredNames().filter { name ->
        val candidate = WordFile.read(name).getOrElse { failure ->
            terminal.println(TextColors.brightRed("  $name would not read: ${failure.message}"))
            return@filter false
        }
        val before = WordFile.fileFor(name).readText()
        val after = WordFile.textOf(candidate)
        if (before == after) return@filter false
        WordFile.write(candidate)
        true
    }
    terminal.println(
        TextColors.gray("rewrote ${moved.size} of ${WordFile.authoredNames().size}: ${moved.joinToString(" ")}"),
    )
}

/** A server asked what it carries, and the answer written down for every later run to read offline. */
private fun refresh(terminal: Terminal, attach: String?) {
    val corpus = Corpus.load()
    val serverOnly = corpus.vocabulary.tagsOnlyAServerGrants
    terminal.println(TextColors.gray("${serverOnly.size} of them: ${serverOnly.sorted().joinToString(" ")}"))
    val taken = runCatching {
        ServerSnapshot.refresh(attach, serverOnly) { said -> terminal.println(TextColors.gray("  $said")) }
    }
    taken.fold(
        onSuccess = { snapshot ->
            snapshot.write()
            terminal.println(
                TextColors.green(
                    "wrote ${ServerSnapshot.FILE.path} — ${snapshot.words} words on ${snapshot.loader}",
                ),
            )
        },
        onFailure = { failure -> terminal.println(TextColors.brightRed("no snapshot: ${failure.message}")) },
    )
}

private val USAGE = """
    |Scrivener — a vocabulary editor for Ages and the Art.
    |
    |  scripts/scrivener.sh                   begin a new word
    |  scripts/scrivener.sh <name>            open an authored one
    |  scripts/scrivener.sh --audit           every authored word, worst first
    |  scripts/scrivener.sh --rewrite         every word back in the layout this writes (see AuthoringCheck)
    |  scripts/scrivener.sh --refresh         ask a server what only it knows, and remember it
    |  scripts/scrivener.sh --refresh --attach host:port:password
    |
    |It works offline. The corpus, every tag table and vanilla's own biomes, features and structure sets
    |are read straight off the source tree, so what a word reaches, costs, keeps and contradicts is exact
    |without a server. What offline cannot know is a tag only a bound registry grants — `ore` is the one —
    |and `--refresh` remembers that rather than making a server a condition of writing a word.
    |
    |Read notes/vocabulary-pass-plan.md §5 before authoring; it is what this was built from.
""".trimMargin()
