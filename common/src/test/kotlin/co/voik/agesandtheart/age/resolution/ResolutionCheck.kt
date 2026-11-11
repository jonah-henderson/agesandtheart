package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.age.language.ShippedLexicon
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The resolver on landings: what fills, steers, weighs and mints, and what is charged (`the-world-model.md` §5). */
@Tags(NEEDS_REGISTRIES)
class ResolutionCheck : FunSpec({

    fun Resolution.spelled() = CompositionSpelling.spell(CompositionSpelling.Written(composition, template, instability))

    fun Resolution.option(aspect: Aspect, parameter: String, member: Int = 0) =
        composition.optionsFor(aspect, member).allSpelled(parameter)

    fun Resolution.incoherentPairs() =
        instability.flaws.filter { it.register == Register.INCOHERENT }.map { it.words.toSet() }.toSet()

    test("a member struck by name is struck and asked for nothing besides") {
        for (row in listOf("age no dripstone_caves", "age no zombie", "age no villages")) {
            val spelled = ShippedLexicon.resolved(row).composition.toString()
            check("[except]" in spelled && "except,amount" !in spelled) { "'$row' came back as $spelled" }
        }
    }

    test("a strike spares what it marks with a bang, and asks for nothing it leaves standing") {
        val strikesTheMonstersButTheUndead = Landing(
            listOf(Effect.Excludes(Aspect.SPAWNS, setOf("#hostile", "!#undead"))),
            Place(Aspect.SPAWNS),
            word = "by_hand",
            page = 0,
            clause = 0,
            aimed = true,
            chance = 1.0,
        )
        val book = Book(listOf(strikesTheMonstersButTheUndead), possible = listOf(strikesTheMonstersButTheUndead), template = null, cost = 1, pagesLaid = 1)
        val spelled = Resolver.resolve(ShippedLexicon.world, book, seed = 0L).composition.toString()
        check("minecraft:creeper[except]" in spelled) { "a creeper was not struck: $spelled" }
        check("minecraft:zombie[except]" !in spelled) { "a zombie was struck though the undead were spared: $spelled" }
        check("caps" !in spelled) { "a strike raised the caps of what it left: $spelled" }
    }

    test("a landing built by hand is resolved: a choice seats its landform, a setting steers it") {
        val chosen = Landing(
            listOf(Effect.Chooses(Aspect.TERRAIN, "pillared"), Effect.Sets(ParameterKey("size"), "1.0..1.0")),
            Place(Aspect.TERRAIN),
            word = "by_hand",
            page = 0,
            clause = 0,
            aimed = true,
            chance = 1.0,
        )
        val book = Book(listOf(chosen), possible = listOf(chosen), template = null, cost = 4, pagesLaid = 1)
        val world = Resolver.resolve(ShippedLexicon.world, book, seed = 0L)
        check(world.composition.presets.any { it.aspect == Aspect.TERRAIN && it.key == "pillared" }) { world.spelled() }
        check(world.option(Aspect.TERRAIN, "size").mapNotNull(Span::read) == listOf(Span(1.0, 1.0))) { world.spelled() }
        check(world.instability.isCoherent && world.cost == 4) { "$world" }
    }

    /** A catalogue's member is there only where chosen, so describing one still chooses it. */
    test("a described landform or underground is still the one chosen") {
        val world = resolved("age large fissured small gentle")
        check(world.composition.presets.any { it.aspect == Aspect.UNDERGROUND && it.key == "fissured" }) { world.spelled() }
        check(world.composition.presets.any { it.aspect == Aspect.TERRAIN && it.key == "gentle" }) { world.spelled() }
        check(world.option(Aspect.UNDERGROUND, "size").mapNotNull(Span::read) == listOf(Span(0.5, 0.5))) { world.spelled() }
    }

    test("a cold climate is cold, and an Age nobody described is the ordinary one") {
        val cold = resolved("age frozen climate")
        val temperature = cold.option(Aspect.CLIMATE, "temperature").mapNotNull(Span::read)
        check(temperature.isNotEmpty() && temperature.all { it.most <= -0.45 }) { cold.spelled() }
        check(resolved("age").instability.isCoherent) { "an Age nobody described is at odds with itself" }
    }

    test("two words disagreeing where they land are charged, the same at every seed, and only once") {
        val charged = (1L..5L).map { seed -> resolved("frozen scorching age", seed).incoherentPairs() }
        check(charged.all { it == setOf(setOf("frozen", "scorching")) }) { "frozen against scorching charged $charged" }
    }

    test("weather under a roof is charged, whether a word or the rock made the roof") {
        for (weather in listOf("tempest", "deluge", "blizzard", "rainbow")) {
            check(resolved("age $weather").incoherentPairs().isEmpty()) { "$weather under an open sky was charged" }
            val written = resolved("age lost $weather").incoherentPairs()
            check(setOf("lost", weather) in written) { "$weather under a written roof charged $written" }
            val rock = resolved("age subterranean $weather").incoherentPairs()
            check(setOf("subterranean", weather) in rock) { "$weather under roofing rock charged $rock" }
            val divided = resolved("age subterranean and gentle landmass $weather").incoherentPairs()
            check(setOf("subterranean", weather) !in divided) { "$weather over rock that roofs only part of the Age was charged" }
        }
        check(resolved("age sunless sun tempest").incoherentPairs().isEmpty()) { "a tempest in an Age with no sun was charged" }
    }

    test("words that never share a place never clash, and `and` waives what they would") {
        check(resolved("age frozen climate scorching sun").incoherentPairs().isEmpty()) { "a cold climate under a hot sun clashed" }
        check(resolved("age small landmass colossal rainbow").incoherentPairs().isEmpty()) { "a small land and a colossal bow clashed" }
        val asked = resolved("age frozen and scorching climate")
        check(asked.incoherentPairs().isEmpty()) { "a climate divided on purpose was charged: ${asked.instability}" }
    }

    /** Laid bare, `beautiful` offers a blue sky; an aimed `red sky` overrules it, and nothing is charged for that. */
    test("an offer laid bare yields to a page aimed there") {
        val overruled = resolved("beautiful age red sky")
        check(overruled.incoherentPairs().isEmpty()) { "a bare offer was charged against an aimed page: ${overruled.instability}" }
        check(overruled.option(Aspect.SKY, "colour").toList() == listOf("red")) { overruled.spelled() }
    }

    test("a minting is made of what its clause laid, at the size its clause asked") {
        val obelisks = resolved("age colossal gold_block obelisks")
        val placed = obelisks.option(Aspect.FEATURES, Features.PLACES.name)
        check(placed.any { "obelisks" in it && "gold_block" in it }) { obelisks.spelled() }
        check(obelisks.instability.isCoherent) { "${obelisks.instability}" }
        val spring = resolved("age gold_block springs")
        check(spring.incoherentPairs() == setOf(setOf("gold_block", "springs"))) { "a spring of gold: ${spring.instability}" }
    }

    test("two mintings of one pattern are two things, and what each clause says is said of its own") {
        val lakes = resolved("age teeming deep obsidian lakes teeming shallow lava lakes")
        check(lakes.instability.isCoherent) { "${lakes.instability}" }
        val placed = lakes.option(Aspect.FEATURES, Features.PLACES.name).filter { "lake" in it }
        check(placed.any { "obsidian" in it } && placed.any { "lava" in it }) { lakes.spelled() }
        check(resolved("age deep lakes shallow lakes").incoherentPairs().isEmpty()) { "two lakes of water apart were charged" }
        check(resolved("age deep shallow lakes").incoherentPairs() == setOf(setOf("deep", "shallow"))) { "one lake deep and shallow went uncharged" }
    }

    test("a quantifier counts the stars, and each sun described is a body") {
        val stars = resolved("age teeming stars")
        check(stars.option(Aspect.STARS, SkyBodies.STARS.name).isNotEmpty()) { stars.spelled() }
        check(resolved("age red sun colossal sun").composition.membersIn(Aspect.SUN) == 2) { "two suns described, not two suns" }
    }

    test("an amount said of the cave a book chose counts its tunnels, and the underground's own page takes none") {
        val few = resolved("age few tunnels")
        check(few.option(Aspect.UNDERGROUND, Underground.DENSITY.name).mapNotNull(Span::read) == listOf(Span(-0.5, -0.5))) { few.spelled() }
        val underground = resolved("age few underground")
        check(underground.composition == resolved("age underground").composition) { underground.spelled() }
    }

    test("motionless air stills the wind and the clouds, and roots no creature") {
        val still = resolved("age motionless air")
        check(still.option(Aspect.CLOUD, "still").toList() == listOf("true")) { still.spelled() }
        check(still.option(Aspect.AIR, "wind").mapNotNull(Span::read) == listOf(Span(-1.0, -1.0))) { still.spelled() }
        check(still.option(Aspect.SPAWNS, "still").isEmpty()) { still.spelled() }
    }

    test("open underground asks for larger caves, and a riddled landmass for more of them") {
        val open = resolved("age open underground")
        val sized = open.option(Aspect.UNDERGROUND, Terrain.SIZE.name).mapNotNull(Span::read).single()
        check(sized.least >= 0.3 && sized.most <= 0.8) { open.spelled() }
        val riddled = resolved("age riddled landmass")
        val denser = riddled.option(Aspect.UNDERGROUND, Underground.DENSITY.name).mapNotNull(Span::read).single()
        check(denser.least >= 0.5) { riddled.spelled() }
    }

    test("empty underground leaves the rock without monsters") {
        val empty = resolved("age empty underground")
        check(empty.option(Aspect.SPAWNS, Spawns.NONE_BELOW.name).toList() == listOf("true")) { empty.spelled() }
    }

    test("an empty landmass is the void, with nothing built and nothing beneath it") {
        val empty = resolved("age empty landmass")
        check(empty.composition.terrains == listOf(Terrain.VOID)) { empty.spelled() }
        val built = empty.composition.optionsFor(Aspect.STRUCTURES).skewOn(Structures.BUILT)
        check(built.emptied || "#built" in built.struck) { "an empty landmass still builds: ${empty.spelled()}" }
        check(empty.composition.hasNothingBeneathIt) { "the void had ground beneath it" }
    }

    test("a colour on the stars tints them, and on meteors burns them in it") {
        val stars = resolved("age red stars")
        val tint = SkyBodies.starTintOf(stars.composition.optionsFor(Aspect.STARS))
        check(tint != null && tint.red == 1.0f && tint.green < tint.red) { "red stars were tinted $tint: ${stars.spelled()}" }
        check(SkyBodies.starTintOf(resolved("age stars").composition.optionsFor(Aspect.STARS)) == null) { "plain stars were tinted" }
        val meteors = resolved("age red meteors").composition.optionsFor(Aspect.PHENOMENA)
        check(Phenomena.colourOf(meteors, Phenomenon.METEORS) == "red") { "red meteors burned ${Phenomena.colourOf(meteors, Phenomenon.METEORS)}" }
    }

    test("a colour aimed at a creature draws it in that colour, at the inhabitants all of them, and laid bare none") {
        fun coloursOf(row: String) = Spawns.coloursIn(resolved(row).composition.optionsFor(Aspect.SPAWNS))
        val zombie = coloursOf("age red zombie")
        check(zombie.byType.keys.map { it.toString() } == listOf("minecraft:zombie") && zombie.everyCreature == null) { "$zombie" }
        check(coloursOf("age red inhabitants").everyCreature != null) { "red inhabitants left the creatures their own colours" }
        check(coloursOf("age red").isNone) { "a red Age recoloured its creatures" }
        check(coloursOf("age black sheep").isNone) { "black sheep recoloured the sheep rather than its wool" }
    }

    test("nether and end are the realms' whole worlds, their rock where it is the head's, and the old names read as them") {
        fun worldOf(row: String) = resolved(row).let { it.template to it.composition.terrains }
        check(worldOf("age nether") == (AgeTemplate.INFERNAL to listOf(Terrain.NETHER))) { "nether was ${worldOf("age nether")}" }
        check(worldOf("age gentle nether") == (AgeTemplate.INFERNAL to listOf(Terrain.HILLS))) { "gentle nether was ${worldOf("age gentle nether")}" }
        check(worldOf("age nether landmass") == (AgeTemplate.OVERWORLD to listOf(Terrain.NETHER))) {
            "nether landmass was ${worldOf("age nether landmass")}"
        }
        check(worldOf("age end") == (AgeTemplate.DARK_VOID to listOf(Terrain.END))) { "end was ${worldOf("age end")}" }
        check(worldOf("age infernal") == worldOf("age nether")) { "the retired infernal read as ${worldOf("age infernal")}" }
        check(worldOf("age dark_void") == worldOf("age end")) { "the retired dark_void read as ${worldOf("age dark_void")}" }
    }

    test("what the reading charged and dropped reaches the instability, and the cost is every page's ink") {
        val moved = resolved("age colossal")
        check(moved.instability.flaws.any { it.register == Register.REHOMED }) { "colossal laid after the Age was not charged" }
        check(resolved("age xyzzy").dropped == listOf("xyzzy")) { "a page that is no word was not dropped" }
        val cost = resolved("age sunless").cost
        check(cost == 1 + 4) { "age and sunless cost $cost, where the age page is one and sunless four" }
    }
})
