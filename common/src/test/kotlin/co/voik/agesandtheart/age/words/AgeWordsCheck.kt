package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The words said of the Age as a whole — its template, its bodies, its strangeness, its emptiness — as we ship them. */
@Tags(NEEDS_REGISTRIES)
class AgeWordsCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }

    fun drawn(row: String): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, 0L)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L)
    }

    fun effects(row: String): List<Effect> = drawn(row).landed.flatMap { it.effects }

    fun set(row: String, part: Aspect, parameter: String): List<String> = effects(row)
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("`nether` begins the Age from the nether, passing the land it is said beside; `nether landmass` is only its land") {
        check(drawn("nether age").template == "infernal") { "nether age: ${drawn("nether age")}" }
        check(drawn("nether gentle landmass age").template == "infernal" || drawn("nether trees age").template == "infernal") { "nether did not pass to the Age" }
        val land = drawn("nether landmass age")
        check(Effect.Chooses(Aspect.TERRAIN, "nether") in land.landed.flatMap { it.effects } && land.template == null) { "nether landmass: $land" }
    }

    test("the bodies: `sunless` passes what it is said beside to reach the sun, `lightless` seals the Age and darkens it") {
        check(set("sunless sky age", Aspect.SUN, "absent") == listOf("true")) { "sunless sky kept its sun: ${effects("sunless sky age")}" }
        check(set("starless age", Aspect.STARS, "density") == listOf("-1.0..-1.0")) { "starless age kept its stars" }
        check(set("lightless age", Aspect.SKY, "sealed") == listOf("true") && set("lightless age", Aspect.SUN, "absent") == listOf("true")) {
            "lightless age: ${effects("lightless age")}"
        }
        check(set("lightless moon age", Aspect.MOON, "unlit") == listOf("true") && set("lightless age", Aspect.MOON, "unlit").isEmpty()) {
            "the moon stayed new only where said of it"
        }
    }

    test("`incongruous` sets things anywhere, its foreign ground said of the land alone; `jumbled` is the patchwork") {
        check(set("incongruous age", Aspect.FEATURES, "incongruous") == listOf("true")) { "incongruous age: ${effects("incongruous age")}" }
        check(set("incongruous age", Aspect.TERRAIN, "incongruous").isEmpty()) { "incongruous age read foreign ground" }
        check(set("incongruous landmass age", Aspect.TERRAIN, "incongruous") == listOf("true")) { "incongruous landmass read none" }
        check(set("jumbled age", Aspect.TERRAIN, "patchwork") == listOf("true")) { "jumbled age was not a patchwork" }
    }

    test("`deserted age` is nobody anywhere; `deserted villages` and `deserted structures` keep what is built, and empty it") {
        val age = effects("deserted age")
        check(age.any { it is Effect.Excludes && it.aspect == Aspect.STRUCTURES && "#inhabited" in it.members }) { "deserted age built homes: $age" }
        check(age.any { it is Effect.Excludes && it.aspect == Aspect.SPAWNS && "#mobs" in it.members }) { "deserted age has creatures: $age" }
        val villages = effects("deserted villages age")
        check(set("deserted villages age", Aspect.STRUCTURES, "abandoned") == listOf("true")) { "deserted villages: $villages" }
        check(villages.none { it is Effect.Excludes }) { "deserted villages struck something: $villages" }
        val structures = effects("deserted structures age")
        check(set("deserted structures age", Aspect.STRUCTURES, "abandoned") == listOf("true")) { "deserted structures: $structures" }
        check(structures.none { it is Effect.Excludes }) { "deserted structures struck something: $structures" }
        check(set("deserted age", Aspect.STRUCTURES, "abandoned") == listOf("true")) { "deserted age left what stands inhabited" }
    }

    test("`empty` is a sea of air, and an empty sky, underground or land only where it is said of them") {
        check(effects("empty age").any { it is Effect.Restricts && it.aspect == Aspect.SEA && "empty" in it.bars }) { "empty age: ${effects("empty age")}" }
        check(set("empty age", Aspect.CLOUD, "absent").isEmpty() && set("empty sky age", Aspect.CLOUD, "absent") == listOf("true")) {
            "the clouds went only where the sky was said empty: ${effects("empty sky age")}"
        }
    }
})
