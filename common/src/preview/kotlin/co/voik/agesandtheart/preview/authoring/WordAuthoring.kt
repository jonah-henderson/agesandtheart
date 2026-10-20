package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.preview.authoring.ui.Canvas
import co.voik.agesandtheart.preview.authoring.ui.Leaving
import co.voik.agesandtheart.preview.authoring.ui.Menu
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
    val wantsTheWordEditor = asked.auditing || asked.rewriting || asked.word != null
    if (wantsTheWordEditor) {
        terminal.println(TextColors.yellow(WORD_EDITOR_IS_BEING_REBUILT))
        return
    }
    onScreen(terminal) { canvas -> Menu(terminal, canvas, corpus).run() }
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

private const val WORD_EDITOR_IS_BEING_REBUILT =
    "The word editor is being rebuilt for meanings (notes/meanings-plan.md phase 6). The tags, the rules and " +
        "the age workshop are on the menu: run the script with no word."

private val USAGE = """
    |Scrivener — a vocabulary editor for Ages and the Art.
    |
    |  scripts/scrivener.sh                   the tags, the rules and the age workshop
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
