package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What the Art cannot yet say.
 *
 * **Worked out from the words there are**, so a gap closes itself the moment something fills it and there
 * is no list here to remember to cross off. These checks are about that property rather than about the
 * particular gaps, which are content and move as the corpus does.
 */
@Tags(NEEDS_REGISTRIES)
class GapsCheck : FunSpec({

    val corpus by lazy { Corpus.load() }

    /** Nothing reported as missing is something the corpus already reaches. */
    test("a tag something asks for is not missing") {
        val asked = corpus.vocabulary.words.flatMap { it.wanted + it.unwanted + it.offeredTags }.toSet()
        val wrong = Gaps.of(corpus).filter { it.kind == Gaps.Kind.TAG && it.what in asked }
        check(wrong.isEmpty()) { "reported as missing but already asked for: ${wrong.map { it.what }}" }
    }

    /** And the same for a parameter: something setting it means it is not a gap. */
    test("a parameter something turns is not missing") {
        val turned = corpus.vocabulary.words
            .flatMap { word -> word.canSet.keys.map { it.substringAfterLast('.') } }
            .toSet()
        val wrong = Gaps.of(corpus).filter { it.kind == Gaps.Kind.PARAMETER && it.what in turned }
        check(wrong.isEmpty()) { "reported as missing but already set: ${wrong.map { it.what }}" }
    }

    /**
     * **The word a gap becomes is one the tool would accept.**
     *
     * A suggestion you have to correct beats an empty screen, but only if it starts valid — a prefill
     * that errors on arrival is worse than nothing.
     */
    test("the word suggested for a gap has no errors but its name") {
        for (gap in Gaps.of(corpus)) {
            val suggested = Gaps.wordFor(gap, corpus)
            val refused = Verdict.refusals(Verdict.on(suggested, corpus))
                // The name is a guess and may collide with a word already there, which the writer fixes.
                .filterNot { it.says.contains("already") || it.says.contains("aspect") }
            check(refused.isEmpty()) {
                "the word for ${gap.key} starts wrong: ${refused.joinToString { it.says }}"
            }
        }
    }
})
