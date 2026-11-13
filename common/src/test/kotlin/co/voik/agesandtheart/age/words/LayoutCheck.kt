package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** How thickly, how far apart and how alike things stand, in the words we ship (`notes/the-word-rework.md`, step 6). */
@Tags(NEEDS_REGISTRIES)
class LayoutCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<60L

    fun effects(row: String, seed: Long = 0L): List<Effect> {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed).landed.flatMap { it.effects }
    }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> =
        effects(row, seed).mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("`dense` thickens what grows, sets things close, covers the sky and doubles the creatures; `sparse` mirrors it") {
        val dense = effects("dense age")
        check(set("dense age", Aspect.FEATURES, "thickness") == listOf("0.4..0.8")) { "dense age: $dense" }
        check(set("dense age", Aspect.STRUCTURES, "spacing") == listOf("-1.0..-0.4")) { "dense age did not set what is built close" }
        check(set("dense clouds age", Aspect.CLOUD, "cover") == listOf("0.4..0.8")) { "dense clouds did not cover more of the sky" }
        check(Effect.Abundance(2.0) in effects("dense inhabitants age")) { "dense inhabitants were not twice as many" }
        check(set("sparse stars age", Aspect.STARS, "density") == listOf("-0.8..-0.4")) { "sparse stars were not sparse" }
    }

    test("the land's spacing bends rather than settles: `dense scattered landmass` blends the two, clashing only over scattered things") {
        val reading = Reader.read(words, "dense scattered landmass age".split(" "), membership, 0L)
        val drawn = Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L)
        check(drawn.flaws.none { "spacing" in it.tags }) { "the two spacings clashed: ${drawn.flaws}" }
        val spacings = drawn.landed.flatMap { it.effects }.filterIsInstance<Effect.Bends>().filter { it.parameter.name == "spacing" }
        check(spacings.size == 2) { "the land's spacing was not bent by both: $spacings" }
    }

    test("`scattered clouds` are less of the sky in small bits; `scattered landmass` leans to scattered landforms") {
        check(set("scattered clouds age", Aspect.CLOUD, "cover") == listOf("-0.5..-0.2") && set("scattered clouds age", Aspect.CLOUD, "clumping") == listOf("-1.0..-0.6"))
        check(effects("scattered landmass age").any { it is Effect.Restricts && "scattered" in it.bars }) { "scattered landmass leans to nothing scattered" }
    }

    test("`open` lowers the land, not the clouds, and its sea and its caves are said of them alone") {
        check(set("open landmass age", Aspect.TERRAIN, "relief") == listOf("-0.8..-0.2")) { "open landmass: ${effects("open landmass age")}" }
        check(seeds.none { set("open age", Aspect.CLOUD, "ceiling", it).contains("-0.8..-0.2") }) { "open age lowered the clouds" }
        check(seeds.none { set("open age", Aspect.SEA, "depth", it).isNotEmpty() }) { "open age deepened the sea" }
        check(seeds.any { seed -> Effect.Chooses(Aspect.CARVERS, "caves") in effects("open underground age", seed) }) { "open underground never opened caves" }
    }

    test("`gridded` lays the patterned land and the formations out in rows; said of the whole Age, it dangles") {
        check(set("gridded landmass age", Aspect.TERRAIN, "arrangement") == listOf("grid")) { "gridded landmass: ${effects("gridded landmass age")}" }
        val reading = Reader.read(words, listOf("gridded", "age"), membership, 0L)
        check(reading.handedOut.isEmpty()) { "the Age handed out a grid: ${reading.handedOut}" }
        check(Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L).dangling.single().written == "gridded") {
            "gridded age did not dangle"
        }
    }

    test("`uniform` and `varied`, `unbroken`, `stepped` and `subdued`") {
        check(set("uniform features age", Aspect.FEATURES, "uniform") == listOf("true")) { "uniform features were not alike" }
        check(set("varied inhabitants age", Aspect.SPAWNS, "variety") == listOf("0.4..1.0")) { "varied inhabitants did not vary" }
        check(set("unbroken clouds age", Aspect.CLOUD, "cover") == listOf("0.85..1.0")) { "unbroken clouds were not overcast" }
        check(effects("unbroken rock age").any { it is Effect.Restricts && it.aspect == Aspect.CARVERS && "solid" in it.bars }) { "unbroken rock was not solid" }
        check(set("stepped landmass age", Aspect.TERRAIN, "terracing") == listOf("0.6..1.0")) { "stepped landmass was not terraced" }
        check(set("subdued age", Aspect.TERRAIN, "relief") == listOf("-1.0..-0.4")) { "subdued age did not lower the land" }
    }
})
