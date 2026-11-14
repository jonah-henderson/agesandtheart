package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.words.ShippedWords
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What the workshop offers to lay next, asked of the reader: what would take what waits, what bears, and what is set aside. */
@Tags(NEEDS_REGISTRIES)
class SuggestionsCheck : FunSpec({

    val suggesting by lazy { Suggestions(ShippedWords.corpus) }

    test("a page that would take nothing and land nothing where it is laid is set aside, and a describing word is not") {
        val atTheStart = suggesting.after(emptyList())
        check(atTheStart.inert.any { it.page == "sky" }) { "the sky's page, with nothing before it to take, was offered" }
        check(atTheStart.bearing.any { it.page == "frozen" }) { "'frozen' was set aside" }
        check(atTheStart.bearing.any { it.page == "rising" }) { "'rising', which waits for a sun, was set aside" }
        check(atTheStart.bearing.any { it.page == "no" }) { "the grammar was set aside" }
    }

    test("what takes the page waiting closes it, and the parts it could still be taken by are said") {
        val afterFrozen = suggesting.after(listOf("frozen"))
        check(afterFrozen.bearing.first { it.page == "climate" }.closes) { "the climate page does not take a waiting 'frozen'" }
        check(Aspect.CLIMATE in suggesting.aimsStillOpen(listOf("frozen")).orEmpty()) { "'frozen' could not be aimed at the climate" }
        val afterRising = suggesting.after(listOf("north", "rising"))
        check(afterRising.bearing.first { it.page == "sun" }.closes) { "the sun does not take a waiting 'north rising'" }
        check(afterRising.bearing.none { it.page == "climate" && it.closes }) { "the climate took a rising" }
    }

    test("a row reads as laid once every page lands, and what a page reaches is every part a thing of takes it on") {
        check(!suggesting.isASentence(listOf("north", "rising"))) { "a rising with nothing to rise read as laid" }
        check(suggesting.isASentence(listOf("north", "rising", "sun", "age"))) { "'north rising sun age' did not read as laid" }
        val frozen = suggesting.after(emptyList()).all.first { it.page == "frozen" }
        check("climate" in frozen.targets.split(' ') && "sky" in frozen.targets.split(' ')) { "frozen reaches ${frozen.targets}" }
    }
})
