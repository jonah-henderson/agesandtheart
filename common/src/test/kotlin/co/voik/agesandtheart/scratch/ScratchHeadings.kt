package co.voik.agesandtheart.scratch

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

/** Scratch: never commit. */
@Tags(NEEDS_REGISTRIES)
class ScratchHeadings : FunSpec({
    test("headings") {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val vocabulary = ShippedCorpus.vocabulary
        val charged = vocabulary.word("charged")!!
        val out = StringBuilder()
        vocabulary.usage.takersOf(charged).forEach { out.appendLine("taker ${it.word.id} ${it.part} derived=${it.word.id in vocabulary.words.derived}") }
        for (name in listOf("iron_golem", "creeper")) {
            val word = vocabulary.word(name)!!
            for (sense in word.senses) {
                out.appendLine("$name sense part=${sense.speaksFor} means=${sense.means}")
                for (offer in charged.senses.flatMap { it.offers }) if (vocabulary.usage.takes(sense, offer)) out.appendLine("  takes ${offer.concept} ${offer.audience}")
            }
        }
        File(root, "build/claude-scratch/headings-out.txt").writeText(out.toString())
    }
})
