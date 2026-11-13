package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.MinecraftRegistries
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
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** The mood words we ship, and every word we ship read before `age` (`notes/the-word-rework.md`, step 6's last batch). */
@Tags(NEEDS_REGISTRIES)
class MoodsCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<60L

    fun drawn(row: String, seed: Long = 0L): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed)
    }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> = drawn(row, seed).landed.flatMap { it.effects }
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits, and every word of the old format has one in the new") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
        fun names(directory: String) = MinecraftRegistries.resourceRoot().resolve("data/agesandtheart/art/$directory").listDirectoryEntries()
            .filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet()
        val unwritten = names("word") - names("words")
        check(unwritten.isEmpty()) { "old words with no new one: $unwritten" }
    }

    test("every word we write reads before `age` — landing something, dangling, or a part's page saying nothing — and never fails") {
        val written = ShippedWords.written.map { it.name }.filter { words.word(it)?.defaultSense?.behaviour == null }
        for (name in written) {
            val read = drawn("$name age")
            val isAPartsPage = Aspect.byPage(name) != null
            val readSomehow = read.landed.isNotEmpty() || read.template != null || read.dangling.any { it.written == name } || isAPartsPage
            check(readSomehow) { "$name age neither landed nor dangled: $read" }
        }
    }

    test("`frozen` is cold always, and now and then a halo: an ice rainbow, pale as frozen's colours are") {
        check(seeds.all { set("frozen age", Aspect.CLIMATE, "temperature", it) == listOf("-0.95..-0.45") }) { "frozen age was not cold" }
        val halos = seeds.count { set("frozen age", Aspect.RAINBOW, "rain", it).isNotEmpty() }
        check(halos in 8..35) { "frozen age hung a halo at $halos seeds of ${seeds.count()}" }
        val colours = seeds.flatMap { set("frozen age", Aspect.RAINBOW, "colour", it) }.toSet()
        check(colours.isNotEmpty() && colours.all { it in setOf("white", "grey", "blue") }) { "the halo was coloured $colours" }
    }

    test("`polar` sets the sun on a polar path a quarter of the time from the whole Age, and always said of the sun") {
        check(set("polar sun age", Aspect.SUN, "path") == listOf("polar")) { "polar sun was not polar" }
        val fromTheAge = seeds.count { set("polar age", Aspect.SUN, "path", it).isNotEmpty() }
        check(fromTheAge in 5..30) { "polar age set the sun's path at $fromTheAge seeds of ${seeds.count()}" }
    }

    test("`stormy sky` thunders and lowers grey cloud; `prismatic` turns colours; `fog` is a phenomenon") {
        check(set("stormy sky age", Aspect.WEATHER, "thunder") == listOf("0.6..1.0")) { "stormy sky did not thunder" }
        check(set("stormy sky age", Aspect.CLOUD, "colour") == listOf("grey")) { "stormy sky's clouds were not grey" }
        check(set("prismatic sky age", Aspect.SKY, "colours_cycling") == listOf("true")) { "prismatic sky did not turn" }
        check(Effect.Chooses(Aspect.PHENOMENA, "fog") in drawn("fog age").landed.flatMap { it.effects }) { "fog brought no fog" }
    }
})
