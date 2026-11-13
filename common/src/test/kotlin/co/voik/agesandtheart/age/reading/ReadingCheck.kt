package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/** How a row of pages reads in the new model (`notes/the-word-rework.md`), held against the fixture words' hard cases. */
@Tags(NEEDS_REGISTRIES)
class ReadingCheck : FunSpec({

    fun read(row: String): Reading = Reader.read(FixtureWords.words, row.split(" "), FixtureWords.membership, seed = 0L)

    fun Reading.page(word: String): PageRead = pages.first { it.written == word }
    fun Reading.fateOf(word: String): Fate = page(word).fate
    fun Reading.phrase(head: String): Phrase? = phrases.firstOrNull { pages[it.head].written == head }

    /** Every effect [word]'s offers became, wherever they landed. */
    fun Reading.effectsOf(word: String): List<Effect> =
        placements.filter { pages[it.from].written == word || it.carrying.any { at -> pages[at].written == word } }.flatMap { it.effects }

    test("the last word is the head: `red oak trees` are trees of oak with red leaves, and the oak passes on") {
        val reading = read("red oak trees age")
        check(reading.fateOf("oak") == Fate.PassedOn(2)) { "oak was ${reading.fateOf("oak")}" }
        val trees = reading.phrase("trees") ?: error("trees closed no phrase")
        check(Effect.Chooses(Aspect.FEATURES, "minecraft:oak") in trees.placements.flatMap { it.effects }) { "the trees are not oak: $trees" }
        check(trees.own.none { it is Effect.Restricts }) { "the trees still name every woody tree beside the oak: ${trees.own}" }
        check(Effect.Sets(ParameterKey("colour", Aspect.LEAVES), "red") in reading.effectsOf("red")) { "red did not reach the leaves" }
    }

    test("two single things side by side are two things: `oak landmass` is an oak and a landmass") {
        val reading = read("oak landmass age")
        check(reading.fateOf("oak") == Fate.Closed && reading.fateOf("landmass") == Fate.Closed) {
            "oak was ${reading.fateOf("oak")}, landmass ${reading.fateOf("landmass")}"
        }
    }

    test("a formation takes any member: `zombie herds` are zombies in herds, and `herds of warden` wardens") {
        val zombies = read("zombie herds age").phrase("herds") ?: error("herds closed no phrase")
        check(Effect.Chooses(Aspect.SPAWNS, "minecraft:zombie") in zombies.placements.flatMap { it.effects }) { "the herds are not zombies" }
        check(zombies.own.none { it is Effect.Restricts } && Effect.Abundance(2.0) in zombies.own) { "herds kept the grazers or lost its own: ${zombies.own}" }
        val wardens = read("teeming herds of warden age")
        val herds = wardens.phrase("herds") ?: error("herds closed no phrase")
        check(Effect.Chooses(Aspect.SPAWNS, "minecraft:warden") in herds.placements.flatMap { it.effects }) { "the herds are not of wardens" }
        check(Effect.Abundance(4.0) in wardens.effectsOf("teeming")) { "teeming did not reach the herds" }
    }

    test("a group refuses what it does not hold, and the refused page dangles: `sheep fliers`") {
        val reading = read("sheep fliers age")
        check(reading.fateOf("sheep") == Fate.Dangles) { "sheep was ${reading.fateOf("sheep")}" }
        check(reading.fateOf("fliers") == Fate.Closed)
    }

    test("a landform word is an adjective: `cratered age` is both the land and the craters, `cratered landmass` the land") {
        val ofTheAge = read("cratered age").effectsOf("cratered")
        check(Effect.Chooses(Aspect.TERRAIN, "cratered") in ofTheAge && Effect.Admits(Aspect.FEATURES, setOf("agesandtheart:craters")) in ofTheAge) {
            "cratered age became $ofTheAge"
        }
        check(read("cratered landmass age").effectsOf("cratered") == listOf(Effect.Chooses(Aspect.TERRAIN, "cratered")))
        check(read("age cratered").fateOf("cratered") == Fate.Dangles) { "a landform word with nothing after it was read" }
    }

    test("an offer for a part passes things to reach an `age`, and dangles where none follows") {
        val stormy = read("stormy birch age")
        check(Effect.Sets(ParameterKey("frequency", Aspect.WEATHER), "0.5..1.0") in stormy.effectsOf("stormy")) { "the storm did not reach the sky" }
        check(stormy.fateOf("birch") == Fate.Closed)
        check(read("beautiful age stormy birch").fateOf("stormy") == Fate.Dangles) { "a storm after the last `age` was read" }
        check(Effect.Sets(ParameterKey("absent", Aspect.MOON), "true") in read("moonless zombie age").effectsOf("moonless"))
        check(Effect.Template("nether") in read("nether trees age").effectsOf("nether")) { "the nether did not pass the trees" }
    }

    test("only a page the thing took nothing of passes it: `bare oak age` is leafless oak, and the clouds stay") {
        val effects = read("bare oak age").effectsOf("bare")
        check(effects == listOf(Effect.Sets(ParameterKey("bare", Aspect.FEATURES), "true"))) { "bare oak became $effects" }
    }

    test("what a describing word folds in travels inside its offer: `north rising sun` rises in the north") {
        val reading = read("north rising sun age")
        val sun = reading.phrase("sun") ?: error("the sun closed no phrase")
        val effects = sun.placements.flatMap { it.effects }
        check(Effect.Sets(ParameterKey("rises", Aspect.SUN), "true") in effects) { "the sun does not rise: $effects" }
        check(Effect.Sets(ParameterKey("rising_from", Aspect.SUN), "north") in effects) { "the sun does not rise in the north: $effects" }
        check(reading.fateOf("north") == Fate.Placed)
    }

    test("a pace beside turning colours is the colours' pace, and after them the sun's own") {
        val coloursFaster = read("swift prismatic sun age").effectsOf("swift")
        check(coloursFaster == listOf(Effect.Sets(ParameterKey("colour_cycle_pace", Aspect.SUN), "0.7..1.0"))) { "swift prismatic sun: $coloursFaster" }
        val sunFaster = read("prismatic swift sun age").effectsOf("swift")
        check(sunFaster == listOf(Effect.Sets(ParameterKey("pace", Aspect.SUN), "0.7..1.0"))) { "prismatic swift sun: $sunFaster" }
    }

    test("`no` and `only` put a polarity on the next thing; before a block `no` bans it, and with nothing after it binds nothing") {
        check(read("no villages age").phrase("villages")?.polarity == Polarity.EXCEPT)
        check(read("only villages age").phrase("villages")?.polarity == Polarity.ONLY)
        val bans = read("no snow and powder_snow age").handedOut.flatMap { it.effects }
        check(Effect.Bans("minecraft:snow") in bans && Effect.Bans("minecraft:powder_snow") in bans) { "no snow and powder_snow banned $bans" }
        check(read("no age").fateOf("no") == Fate.Dangles) { "`no` before the Age bound something" }
    }

    test("a negated thing is struck as described where the description picks out kinds; a dial dangles") {
        val reading = read("no red trees age")
        val trees = reading.phrase("trees") ?: error("trees closed no phrase")
        check(trees.polarity == Polarity.EXCEPT) { "`no` did not reach the trees past red" }
        check(trees.placements.map { it.offer.concept.path } == listOf("lean")) { "the struck trees kept more than red's lean: ${trees.placements}" }
        check(reading.fateOf("red") == Fate.Placed) { "red was ${reading.fateOf("red")}" }
        val big = read("no colossal trees age")
        check(big.fateOf("colossal") == Fate.Dangles) { "a size narrowed a strike: ${big.fateOf("colossal")}" }
        check(big.phrase("trees")?.polarity == Polarity.EXCEPT)
    }

    test("`and` joins like with like, the joined sharing what reaches them, and dangles between unlike words") {
        val reading = read("colossal trees and villages age")
        val trees = reading.phrase("trees") ?: error("trees closed no phrase")
        val villages = reading.phrase("villages") ?: error("villages closed no phrase")
        check(trees.joined != null && trees.joined == villages.joined) { "trees and villages were not joined" }
        check(trees.placements.any { it.offer.concept.path == "size" } && villages.placements.any { it.offer.concept.path == "size" }) {
            "colossal did not reach both"
        }
        check(read("red and villages age").fateOf("and") == Fate.Dangles) { "`and` joined a colour to a thing" }
    }

    test("`in` sites the thing before it, and `link in` sets where a link arrives") {
        check(read("beautiful plants in jungle age").phrase("plants")?.sitedIn == Identifier.parse("minecraft:jungle"))
        check(read("link in jungle age").arrival?.anchor == LinkAnchor(Aspect.BIOMES, setOf("minecraft:jungle")))
        check(read("everywhere age").fateOf("everywhere") == Fate.Dangles) { "`everywhere` with nothing before it bound something" }
    }

    test("the neighbours pick a sense: `clay beds` are beds of clay, `beds piles` piles of beds") {
        val clay = read("clay beds age")
        check(clay.page("beds").sense == 0 && Effect.MadeOf("minecraft:clay") in clay.effectsOf("clay")) { "clay beds read beds as ${clay.page("beds").sense}" }
        val piles = read("beds piles age")
        check(piles.page("beds").sense == 1 && Effect.MadeOf("minecraft:red_bed") in piles.effectsOf("beds")) { "beds piles read beds as ${piles.page("beds").sense}" }
        check(read("beds age").page("beds").sense == 1) { "beds alone was not read as its default" }
    }

    test("a filter refuses a value: whole blocks for a built thing, so `torch villages` dangles the torch") {
        check(read("torch villages age").fateOf("torch") == Fate.Dangles)
        check(Effect.MadeOf("minecraft:gold_block") in read("gold_block arches age").effectsOf("gold_block"))
    }

    test("two values of one concept both reach the thing, for the draw to settle; what nothing takes dangles") {
        val sky = read("red blue sky age").phrase("sky") ?: error("the sky closed no phrase")
        check(sky.placements.count { it.offer.concept.path == "colour" } == 2) { "red and blue did not both reach the sky" }
        check(read("colossal sea age").fateOf("colossal") == Fate.Dangles) { "a size the sea cannot take was placed" }
        val foreboding = read("foreboding sunless age").effectsOf("foreboding")
        check(Effect.Sets(ParameterKey("colour", Aspect.MOON), "dark_red") in foreboding && Effect.Sets(ParameterKey("colour", Aspect.SKY), "grey") in foreboding) {
            "foreboding's shares did not reach the moon and the sky: $foreboding"
        }
        check(read("foreboding zombie").fateOf("foreboding") == Fate.Dangles) { "an offer for the moon with no `age` after it was placed" }
    }
})
