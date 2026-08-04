package co.voik.agesandtheart.age.word.generation

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Whether the generation grammars the pack ships are still the ones somebody authored.
 *
 * Each `art/generation/<name>.json` is **generated** from `src/main/generation/<name>.gen` by
 * `./gradlew :common:grammars`,
 * and nothing in the build regenerates it — writing into `src/main/resources` from a task that
 * `processResources` reads is the undeclared dependency this build has been bitten by before. So the two can
 * drift, in both of the ways that matter: a `.gen` edited and not exported, and a `.json` edited directly by
 * somebody who did not know it was generated and whose work the next export would silently throw away.
 *
 * This is what stands behind running the converter by hand, so it has to fail *loudly enough to act on* —
 * every message here names the command to run.
 *
 * Needs no registries: a grammar is text either side, and whether a terminal is a real word is
 * `VocabularyCheck`'s question rather than this one's.
 */
class GrammarSourceCheck : FunSpec({

    /** Every authored grammar has been exported, and exported to what it currently says. */
    test("the shipped grammars are what their sources say") {
        val sources = authoredGrammars()
        check(sources.isNotEmpty()) { "no .gen grammars in ${SOURCES.path} — did the source directory move?" }
        for (source in sources) {
            val name = source.name.removeSuffix(GEN_SUFFIX)
            val problems = mutableListOf<String>()
            val authored = GrammarNotation.read(name, source.readText(), problems)
            check(problems.isEmpty()) { "${source.path} does not read:\n  ${problems.joinToString("\n  ")}" }

            val packed = File(PACK, "$name$JSON_SUFFIX")
            check(packed.isFile) { "${source.path} has never been exported — run `./gradlew :common:grammars`" }
            check(authored == grammarIn(packed, name)) {
                "${packed.path} is not what ${source.path} says. Whichever you edited, the `.gen` is the " +
                    "source: export it with `./gradlew :common:grammars`, or pull the JSON back first with " +
                    "`./gradlew :common:grammars --args=import`."
            }
        }
    }

    /**
     * And nothing is shipped that nobody authored. A grammar added straight to the pack would work in game
     * and be deleted by the next export, which is the failure that looks least like one at the time.
     */
    test("every shipped grammar has a source") {
        val authored = authoredGrammars().map { it.name.removeSuffix(GEN_SUFFIX) }.toSet()
        for (packed in packedGrammars()) {
            val name = packed.name.removeSuffix(JSON_SUFFIX)
            check(name in authored) {
                "${packed.path} has no ${name}$GEN_SUFFIX behind it. It is generated content, so write the " +
                    "grammar in ${SOURCES.path} — `./gradlew :common:grammars --args=import` moves this one over."
            }
        }
    }

    /**
     * And every shipped grammar survives the trip out and back, which is what makes `--args=import` safe to
     * reach for: the converter is the only way back from JSON, and a lossy one would quietly rewrite a
     * grammar rather than refuse to. `GrammarNotationCheck` asks this of a grammar built to be awkward; this
     * asks it of the ones actually in the pack.
     */
    test("every shipped grammar survives being rewritten") {
        for (source in authoredGrammars()) {
            val name = source.name.removeSuffix(GEN_SUFFIX)
            val problems = mutableListOf<String>()
            val authored = GrammarNotation.read(name, source.readText(), problems)
                ?: error("${source.path} does not read:\n  ${problems.joinToString("\n  ")}")

            val rewritten = GrammarNotation.read(name, GrammarNotation.write(authored), problems)
            check(problems.isEmpty()) { "$name did not survive being written:\n  ${problems.joinToString("\n  ")}" }
            check(rewritten == authored) { "$name came back as $rewritten, not $authored" }
        }
    }
})

private fun authoredGrammars(): List<File> =
    SOURCES.listFiles { file -> file.name.endsWith(GEN_SUFFIX) }.orEmpty().sortedBy { it.name }

private fun packedGrammars(): List<File> =
    PACK.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty().sortedBy { it.name }

private fun grammarIn(file: File, name: String): GenerationGrammar =
    GenerationGrammar.codec(name)
        .parse(JsonOps.INSTANCE, JsonParser.parseString(file.readText()))
        .getOrThrow { complaint -> IllegalStateException("${file.path} would not read: $complaint") }

// Relative to the `common` module, which is where Gradle runs a test from.
private val SOURCES = File("src/main/generation")
private val PACK = File("src/main/resources/data/agesandtheart/art/generation")

private const val GEN_SUFFIX = ".gen"
private const val JSON_SUFFIX = ".json"
