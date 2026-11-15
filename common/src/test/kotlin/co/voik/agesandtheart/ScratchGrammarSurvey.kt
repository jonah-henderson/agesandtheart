package co.voik.agesandtheart

import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.words.ShippedWords
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path

// Scratch for step 8's analysis: how the found-book grammars read under the new model. Never committed.
@Tags(NEEDS_REGISTRIES)
class ScratchGrammarSurvey : FunSpec({
    test("survey") {
        val words = ShippedWords.with()
        val writing = Writing(words, ShippedCorpus.vocabulary, WorldMembership(ShippedCorpus.vocabulary)) { false }
        val lines = mutableListOf<String>()
        for (kind in listOf("basic_book", "advanced_book", "unstable_book")) {
            val grammar = ShippedCorpus.vocabulary.generation.grammar(kind) ?: continue
            var clean = 0; var dangling = 0; var clashing = 0; var unknown = 0
            val examples = mutableListOf<String>()
            for (seed in 0L..<300L) {
                val pages = grammar.expand(kotlin.random.Random(seed))
                val written = writing.written(pages, seed)
                val unread = written.reading.pages.count { it.fate == co.voik.agesandtheart.age.reading.Fate.Unknown }
                val dangles = written.drawn.dangling.size
                val clashes = written.drawn.flaws.count { it.register == co.voik.agesandtheart.age.Register.INCOHERENT }
                if (unread > 0) unknown++
                if (dangles > 0) dangling++
                if (clashes > 0) clashing++
                if (unread == 0 && dangles == 0 && clashes == 0) clean++
                else if (examples.size < 25) examples += "  ${pages.joinToString(" ")}  ->  " +
                    listOfNotNull("unread ${written.reading.pages.filter { it.fate == co.voik.agesandtheart.age.reading.Fate.Unknown }.map { it.written }}".takeIf { unread > 0 },
                        "dangles ${written.drawn.dangling.map { it.written }}".takeIf { dangles > 0 },
                        "clash ${written.drawn.flaws.filter { it.register == co.voik.agesandtheart.age.Register.INCOHERENT }.map { it.words }}".takeIf { clashes > 0 }).joinToString("; ")
            }
            lines += "$kind: clean $clean/300, with unread $unknown, dangling $dangling, clashing $clashing"
            lines += examples
        }
        Files.write(Path.of("../build/claude-scratch/word-rework/grammar-survey.txt"), lines)
    }
})
