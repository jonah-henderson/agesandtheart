package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.Pairings
import co.voik.agesandtheart.preview.authoring.ui.Canvas
import co.voik.agesandtheart.preview.authoring.ui.Editor
import co.voik.agesandtheart.preview.authoring.ui.style
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
    when {
        asked.auditing -> audit(terminal, corpus)
        asked.pairingsOf != null -> pairings(terminal, corpus, asked.pairingsOf)
        asked.sentence != null -> for (sentence in asked.sentence.split(SENTENCES_APART).map(String::trim)) {
            terminal.println(TextColors.cyan("» $sentence"))
            terminal.println(
                corpus.vocabulary.read(sentence.split(Regex("\\s+")), seed = 1L)?.readout ?: "no 'age' page, so no book",
            )
        }
        asked.rewriting -> rewrite(terminal)
        asked.inTheNewModel != null -> onScreen(terminal) { canvas -> openInTheNewModel(terminal, canvas, corpus, asked.inTheNewModel) }
        // A named word goes straight in, and a bare run offers the menu.
        asked.word != null -> onScreen(terminal) { canvas -> open(terminal, canvas, corpus, asked.word) }
        else -> onScreen(terminal) { canvas -> Menu(terminal, canvas, corpus).run() }
    }
}

/** What the command line said, read the dull way — there are five options and none of them nest. */
private data class Arguments(
    val word: String? = null,
    /** A word to open in the editor over the new word model, until it replaces the old one. */
    val inTheNewModel: String? = null,
    val auditing: Boolean = false,
    val pairingsOf: String? = null,
    val sentence: String? = null,
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
                    argument == "--pairings" -> read.copy(pairingsOf = arguments.getOrNull(++index))
                    argument == "--read" -> read.copy(sentence = arguments.getOrNull(++index))
                    argument == "--rewrite" -> read.copy(rewriting = true)
                    argument == "--new" -> read.copy(inTheNewModel = arguments.getOrNull(++index))
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

/** One word, opened or begun — a word that will not read is said rather than started from blank. */
private fun open(terminal: Terminal, canvas: Canvas, corpus: Corpus, name: String) {
    val authored = WordFile.exists(name)
    val derived = corpus.vocabulary.word(name)?.takeIf { !authored }
    val candidate = when {
        authored -> WordFile.read(name).getOrElse { failure ->
            terminal.println(TextColors.brightRed("'$name' would not read: ${failure.message}"))
            return
        }
        derived != null -> Candidate.of(derived, corpus.vocabulary)
        else -> Candidate(name = name)
    }
    Editor(terminal, canvas, corpus, candidate, canLeave = false).run()
}

/** One word of the new model, opened or begun — a word that will not read is said rather than started from blank. */
private fun openInTheNewModel(terminal: Terminal, canvas: Canvas, corpus: Corpus, name: String) {
    val files = co.voik.agesandtheart.preview.authoring.words.WordFile
    if (!files.couldBeAName(name)) return terminal.println(TextColors.brightRed("'$name' cannot be a word's name"))
    val candidate = if (files.exists(name)) {
        files.read(name).getOrElse { failure -> return terminal.println(TextColors.brightRed("'$name' would not read: ${failure.message}")) }
    } else {
        co.voik.agesandtheart.preview.authoring.words.Candidate.blank(name.location())
    }
    val words = co.voik.agesandtheart.preview.authoring.words.WordCorpus(corpus.vocabulary, corpus.snapshot)
    co.voik.agesandtheart.preview.authoring.words.ui.WordEditor(terminal, canvas, words, candidate, canLeave = false).run()
}

/** Every authored word with something against it, worst first, printed rather than shown. */
private fun audit(terminal: Terminal, corpus: Corpus) {
    val judged = WordFile.authoredNames().map { name ->
        val findings = WordFile.read(name).fold(
            onSuccess = { Verdict.on(it, corpus.vocabulary, quarrels = false) },
            onFailure = { listOf(Verdict.Finding(Verdict.Standing.REFUSED, "'$name' will not read: ${it.message}")) },
        )
        name to findings
    }.sortedWith(Verdict.WORST_FIRST)
    terminal.println(TextStyles.bold("\nThe corpus, worst first\n"))
    for ((name, findings) in judged) {
        val worth = findings.filterNot { it.standing == Verdict.Standing.NOTED }
        if (worth.isEmpty()) continue
        val counts = Verdict.Standing.entries.filter { it != Verdict.Standing.NOTED }.mapNotNull { standing ->
            worth.count { it.standing == standing }.takeIf { it > 0 }?.let { standing.style("$it ${standing.name.lowercase()}") }
        }
        terminal.println("  ${name.padEnd(NAME_COLUMN)}${counts.joinToString("  ")}")
        worth.forEach { terminal.println(TextColors.gray("      ${it.says}")) }
    }
    val clean = judged.count { (_, findings) -> findings.all { it.standing == Verdict.Standing.NOTED } }
    terminal.println(TextColors.gray("\n$clean of ${judged.size} words have nothing against them."))
    terminal.println(TextColors.gray("Open one with: scripts/scrivener.sh <name>\n"))
}

private const val NAME_COLUMN = 24

/**
 * What becomes of each of [name]'s offers, and what it takes, written to [Pairings.reportFor]: every taker, what it
 * makes, and whether that holds; and the offers nothing takes.
 */
private fun pairings(terminal: Terminal, corpus: Corpus, name: String) {
    val files = co.voik.agesandtheart.preview.authoring.words.WordFile
    val words = co.voik.agesandtheart.preview.authoring.words.WordCorpus(corpus.vocabulary, corpus.snapshot)
    val written = if (files.exists(name)) files.read(name).getOrNull()?.word else null
    val word = written ?: words.words.word(name)
    if (word == null) {
        terminal.println(TextColors.brightRed("'$name' is no word"))
        return
    }
    terminal.println(TextColors.gray("reading what takes each of '$name''s offers…"))
    val table = Pairings(words, standIn = written).of(word)
    val file = Pairings.reportFor(name)
    file.parentFile.mkdirs()
    file.writeText(Pairings.report(table))
    val takers = table.offers.sumOf { it.takers.size }
    terminal.println(
        TextColors.green(
            "${table.offers.size} offers taken ${takers} ways; ${table.hollow.size} hollow, ${table.untaken.size} taken by nothing, " +
                "${table.unoffered.size} acceptances offered nothing — written to ${file.path}",
        ),
    )
}

/** Every authored word put back in the layout the tool writes, so opening and saving one moves nothing else. */
private fun rewrite(terminal: Terminal) {
    val files = co.voik.agesandtheart.preview.authoring.words.WordFile
    val rewritten = files.authoredNames().filter { name ->
        val read = files.read(name).getOrElse { failure ->
            terminal.println(TextColors.brightRed("  $name: ${failure.message}"))
            return@filter false
        }
        val changes = files.textOf(read) != files.fileFor(name).readText()
        if (changes) files.write(read)
        changes
    }
    terminal.println(TextColors.green("rewrote ${rewritten.size} word(s)${rewritten.joinToString(" ", ": ").takeIf { rewritten.isNotEmpty() }.orEmpty()}"))
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
    |  scripts/scrivener.sh                   the menu: words, tags, rules and the age workshop
    |  scripts/scrivener.sh <word>            open a word, or begin one
    |  scripts/scrivener.sh --audit           every authored word, worst first
    |  scripts/scrivener.sh --read "<sentence>"  what a book of that sentence is read as; `|` between several
    |  scripts/scrivener.sh --pairings <word> what takes each of a word's offers, what it makes there,
    |                                         and whether that holds; and what offers to what it takes
    |  scripts/scrivener.sh --rewrite         put every word file back in the layout the tool writes
    |  scripts/scrivener.sh --new <word>      open a word in the editor over the new word model
    |  scripts/scrivener.sh --refresh         ask a server what only it knows, and remember it
    |  scripts/scrivener.sh --refresh --attach host:port:password
    |
    |It works offline. The corpus, every tag table and vanilla's own biomes, features and structure sets
    |are read straight off the source tree, so what a word reaches, costs, keeps and contradicts is exact
    |without a server. What offline cannot know is a tag only a bound registry grants — `ore` is the one —
    |and `--refresh` remembers that rather than making a server a condition of writing a word.
    |
    |Read notes/the-world-model.md §3 before authoring: it is what a word is.
""".trimMargin()

/** What `--read` takes between several sentences. */
private const val SENTENCES_APART = "|"
