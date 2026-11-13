package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The points of the compass, `rising` and `setting`, as we ship them (`notes/the-word-rework.md`, step 6). */
@Tags(NEEDS_REGISTRIES)
class DirectionsCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }

    fun drawn(row: String): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, 0L)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L)
    }

    fun set(row: String, part: Aspect, parameter: String): List<String> = drawn(row).landed.flatMap { it.effects }
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("a point of the compass is a bearing to the land and a quarter to the wind") {
        check(set("north landmass age", Aspect.TERRAIN, "bearing") == listOf("-1.0..-1.0")) { "north landmass: ${drawn("north landmass age").landed}" }
        check(set("northeast landmass age", Aspect.TERRAIN, "bearing") == listOf("0.5..0.5")) { "northeast landmass ran wrong" }
        check(set("west age", Aspect.AIR, "from") == listOf("west") && set("west age", Aspect.TERRAIN, "bearing") == listOf("0.0..0.0")) {
            "west age: ${drawn("west age").landed}"
        }
    }

    test("before `rising` or `setting`, a point says where a sun or a moon comes up or goes down, and both are placed") {
        val both = drawn("north rising east setting sun age")
        check(set("north rising east setting sun age", Aspect.SUN, "rising") == listOf("north")) { "the sun did not rise in the north: ${both.landed}" }
        check(set("north rising east setting sun age", Aspect.SUN, "setting") == listOf("east")) { "the sun did not set in the east: ${both.landed}" }
        check(both.dangling.isEmpty()) { "a word of north rising east setting sun dangled: ${both.dangling}" }
        check(set("west setting moon age", Aspect.MOON, "setting") == listOf("west")) { "the moon did not set in the west" }
    }

    test("`rising sea` climbs; `rising sun` says nothing and charges nothing; `rising age` has nothing to rise") {
        check(set("rising sea age", Aspect.SEA, "climbing") == listOf("true")) { "rising sea did not climb" }
        val sun = drawn("rising sun age")
        check(sun.dangling.isEmpty() && sun.flaws.isEmpty()) { "rising sun was charged: ${sun.flaws}" }
        check(drawn("rising age").dangling.any { it.written == "rising" }) { "rising age placed its climbing" }
    }
})
