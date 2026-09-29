package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.word.generation.GenerationGrammar
import co.voik.agesandtheart.age.word.generation.GrammarNotation
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import java.io.File

/**
 * Converts the Art's generation grammars between the form they are **authored** in and the form they
 * **ship** in — `src/main/generation/<name>.gen` against the pack's `art/generation/<name>.json`.
 *
 * `./gradlew :common:grammars` writes the JSON from the `.gen` files, which is the everyday direction;
 * `--args=import` goes the other way, which is how the three shipped grammars were moved across in the
 * first place and how a grammar edited by hand in JSON can be brought back.
 *
 * **Not hung off `processResources`, deliberately.** The generated JSON lands in `src/main/resources`,
 * which `processResources` reads, and a task writing into another task's input directory is an undeclared
 * dependency. It is a task you run when you edit a grammar, and `GrammarSourceCheck` fails the build if you
 * forget.
 */
fun main(arguments: Array<String>) {
    val importing = arguments.firstOrNull() == IMPORT
    if (importing) importGrammars() else exportGrammars()
}

/** `.gen` to `.json`, the everyday direction. */
private fun exportGrammars() {
    val sources = SOURCES.listFiles { file -> file.name.endsWith(GEN_SUFFIX) }?.sortedBy { it.name }
    if (sources.isNullOrEmpty()) error("No .gen grammars in ${SOURCES.path} — nothing to export")

    PACK.mkdirs()
    for (source in sources) {
        val name = source.name.removeSuffix(GEN_SUFFIX)
        val problems = mutableListOf<String>()
        val grammar = GrammarNotation.read(name, source.readText(), problems)
            ?: error("${problems.size} problem(s) in ${source.path}:\n  ${problems.joinToString("\n  ")}")
        // Structure only: whether a terminal is a *word* needs the corpus, and half of that needs a server.
        // `Vocabulary.load` asks the full question when a game loads, and `VocabularyCheck` fails over it.
        val structural = GenerationGrammar.problemsWith(grammar) { true }
        if (structural.isNotEmpty()) {
            error("${source.path} does not hang together:\n  ${structural.joinToString("\n  ")}")
        }
        val written = File(PACK, "$name$JSON_SUFFIX")
        written.writeText(jsonOf(grammar, name))
        println("  ${source.path} -> ${written.path}")
    }
    println("Wrote ${sources.size} grammar(s). `GrammarSourceCheck` holds the two in step.")
}

/** `.json` back to `.gen`, for the one-time move across and for a grammar edited in the wrong file. */
private fun importGrammars() {
    val packed = PACK.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }?.sortedBy { it.name }
    if (packed.isNullOrEmpty()) error("No .json grammars in ${PACK.path} — nothing to import")

    SOURCES.mkdirs()
    for (file in packed) {
        val name = file.name.removeSuffix(JSON_SUFFIX)
        val written = File(SOURCES, "$name$GEN_SUFFIX")
        written.writeText(GrammarNotation.write(grammarIn(file, name)))
        println("  ${file.path} -> ${written.path}")
    }
    println("Wrote ${packed.size} grammar(s). Read them, then run without --args=import to write the JSON back.")
}

/** The grammar [file] holds, however it complains about it — this is a developer tool, so it may throw. */
private fun grammarIn(file: File, name: String): GenerationGrammar =
    GenerationGrammar.codec(name)
        .parse(JsonOps.INSTANCE, JsonParser.parseString(file.readText()))
        .getOrThrow { complaint -> IllegalStateException("${file.path} would not read: $complaint") }

/**
 * [grammar] as the pack carries it, under a line saying where it came from.
 *
 * The marker is load-bearing rather than decoration: the JSON is generated, an edit to it is lost the next
 * time anyone runs the converter, and the file itself is the only place a person who opened it will look.
 *
 * **An alternative to the line, rather than Gson's pretty-printing.** Expanded, one `produce` array spans
 * five lines and changing a single word in a `.gen` lands as a dozen lines of JSON diff for ever after —
 * where a line apiece keeps the diff the size of the edit. It is also how these files were written by hand
 * before they were generated.
 */
private fun jsonOf(grammar: GenerationGrammar, name: String): String {
    val encoded = GenerationGrammar.codec(name)
        .encodeStart(JsonOps.INSTANCE, grammar)
        .getOrThrow { complaint -> IllegalStateException("$name would not write: $complaint") }
        .asJsonObject
    // HTML escaping would spell a `'` or `=` in a terminal as a `\u` escape, which parses and cannot be read.
    val gson = GsonBuilder().disableHtmlEscaping().create()
    // Laid out field by field below, so a field added to the codec would be dropped in silence. Rather than
    // trust that nobody will, say so — and print it the dull way, which is wordy but never wrong.
    if (encoded.keySet() != FIELDS) {
        return GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(encoded) + "\n"
    }

    val rules = encoded.getAsJsonObject("rules")
    val said = mutableListOf(
        "{",
        """  "_comment": "Generated from src/main/generation/$name$GEN_SUFFIX — edit that, not this.",""",
        """  "start": ${gson.toJson(encoded.get("start"))},""",
        """  "terminals": ${gson.toJson(encoded.get("terminals"))},""",
        """  "rules": {""",
    )
    for ((index, rule) in rules.entrySet().withIndex()) {
        val alternatives = rule.value.asJsonArray.map { gson.toJson(it) }
        said += """    ${gson.toJson(rule.key)}: ["""
        for ((position, alternative) in alternatives.withIndex()) {
            said += "      $alternative${if (position < alternatives.size - 1) "," else ""}"
        }
        said += "    ]${if (index < rules.size() - 1) "," else ""}"
    }
    said += "  }"
    said += "}"
    return said.joinToString("\n", postfix = "\n")
}

/** What [GenerationGrammar]'s codec writes, and what [jsonOf] knows how to lay out one line at a time. */
private val FIELDS = setOf("start", "terminals", "rules")

/** Run from the `common` module, which is where Gradle puts a `JavaExec`'s working directory. */
private val SOURCES = File("src/main/generation")
private val PACK = File("src/main/resources/data/agesandtheart/art/generation")

private const val IMPORT = "import"
private const val GEN_SUFFIX = ".gen"
private const val JSON_SUFFIX = ".json"
