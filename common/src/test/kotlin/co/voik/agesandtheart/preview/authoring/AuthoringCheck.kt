package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.preview.authoring.ui.Handle
import co.voik.agesandtheart.preview.authoring.ui.Part
import co.voik.agesandtheart.preview.authoring.ui.Parts
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The word-authoring tool's own checks — the pure half of it, which is all of it that matters. The editor is a
 * terminal and is not asserted on; what is, is that the tool cannot lie about a word.
 */
@Tags(NEEDS_REGISTRIES)
class AuthoringCheck : FunSpec({

    val names by lazy { WordFile.authoredNames() }

    /** A file the tool writes back unchanged, so opening a word and saving it moves nothing. */
    test("every authored word is written back exactly as its file holds it") {
        val moved = names.filter { name ->
            val read = WordFile.read(name).getOrThrow()
            WordFile.textOf(read) != WordFile.fileFor(name).readText()
        }
        check(moved.isEmpty()) {
            "these would change on being opened and saved, so `--rewrite` has not been run since they were edited: $moved"
        }
    }

    test("an effect added where one of its kind stands merges into it, as the file holds them") {
        val keepsDry = Effect.Restricts(Aspect.TERRAIN, mapOf("dry" to 0.3))
        val keepsBarren = Effect.Restricts(Aspect.TERRAIN, mapOf("barren" to 0.5))
        val meaning = Meaning(listOf(keepsDry)).withEffect(keepsBarren)
        check(meaning.effects == listOf(Effect.Restricts(Aspect.TERRAIN, mapOf("dry" to 0.3, "barren" to 0.5)))) { "$meaning" }
        check(meaning.withoutEntry(0, "dry").effects == listOf(keepsBarren)) { "taking one tag out took the other" }
        check(meaning.withoutEntry(0, "dry").withoutEntry(0, "barren").effects.isEmpty()) { "an emptied effect was left standing" }
    }

    test("a meaning made certain gives up its chances") {
        val offered = Meaning(listOf(Effect.Abundance(2.0)), chanceToApply = 0.4, unaimedChanceToApply = 0.1)
        val certain = offered.mustAlways(true)
        check(certain.mustAlwaysApply && certain.chanceToApply == Meaning.CERTAIN && certain.unaimedChanceToApply == null) { "$certain" }
        check(offered.withChance(1.7).chanceToApply == Meaning.CERTAIN) { "a chance past one was kept" }
    }

    /** Every row of every section of every authored word, and each entry pointing at an effect the word has. */
    test("every authored word lays out into rows that name what it has") {
        val parts = Parts(Corpus(ShippedCorpus.vocabulary, snapshot = null))
        for (name in names) {
            val candidate = WordFile.read(name).getOrThrow()
            for (part in Part.entries.filter { it != Part.LISTING && it != Part.NAME }) {
                val rows = parts.rowsOf(part, candidate, candidate.asWord())
                for (entry in rows.map { it.handle }.filterIsInstance<Handle.Entry>()) {
                    val effect = candidate.meanings.getOrNull(entry.meaning)?.effects?.getOrNull(entry.effect)
                    check(effect != null) { "'$name' has a row for meaning ${entry.meaning} effect ${entry.effect}, which it lacks" }
                }
            }
        }
    }

    test("what the corpus cannot say is worked out, and a word begun for each is one the tool can read") {
        val corpus = Corpus(ShippedCorpus.vocabulary, snapshot = null)
        for (gap in Gaps.of(corpus)) {
            val begun = Gaps.wordFor(gap, corpus)
            check(begun.meanings.single().effects.isNotEmpty()) { "the word begun for ${gap.key} says nothing" }
        }
    }

    /** A page that would land nothing, take nothing and bind nothing where it is laid is set aside, not offered. */
    test("the workshop sets aside a page that would do nothing where it is laid") {
        val suggesting = Suggestions(ShippedCorpus.vocabulary)
        val afterAge = suggesting.after(listOf("age"))
        check(afterAge.inert.any { it.page == "teeming" }) { "a quantifier with nothing to count was offered after `age`" }
        check(afterAge.bearing.any { it.page == "frozen" }) { "'frozen' was set aside after `age`" }
        val afterFrozen = suggesting.after(listOf("frozen"))
        check(afterFrozen.bearing.first { it.page == "climate" }.closes) { "the climate page does not take a waiting 'frozen'" }
    }

    test("what the tool reads is the word the game loads") {
        val differing = names.filter { name ->
            val read = WordFile.read(name).getOrThrow().asWord()
            read != ShippedCorpus.vocabulary.word(name)
        }
        check(differing.isEmpty()) { "the tool reads these otherwise than the game does: $differing" }
    }
})
