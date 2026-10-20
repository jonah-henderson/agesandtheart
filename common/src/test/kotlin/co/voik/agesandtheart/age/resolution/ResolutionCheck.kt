package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.language.ShippedLexicon
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The resolver on landings: what fills, steers, weighs and mints, and what is charged (`notes/meanings-plan.md` §3). */
@Tags(NEEDS_REGISTRIES)
class ResolutionCheck : FunSpec({

    fun Resolution.spelled() = CompositionSpelling.spell(CompositionSpelling.Written(composition, template, instability))

    fun Resolution.option(aspect: Aspect, parameter: String, member: Int = 0) =
        composition.optionsFor(aspect, member).allSpelled(parameter)

    fun Resolution.incoherentPairs() =
        instability.flaws.filter { it.register == Register.INCOHERENT }.map { it.words.toSet() }.toSet()

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

    test("words that never share a place never clash, and `and` waives what they would") {
        check(resolved("age frozen climate scorching sun").incoherentPairs().isEmpty()) { "a cold climate under a hot sun clashed" }
        check(resolved("age small landmass colossal rainbow").incoherentPairs().isEmpty()) { "a small land and a colossal bow clashed" }
        val asked = resolved("age frozen and scorching climate")
        check(asked.incoherentPairs().isEmpty()) { "a climate divided on purpose was charged: ${asked.instability}" }
    }

    test("a minting is made of what its clause laid, at the size its clause asked") {
        val obelisks = resolved("age colossal gold_block obelisks")
        val placed = obelisks.option(Aspect.FEATURES, Features.PLACES.name)
        check(placed.any { "obelisks" in it && "gold_block" in it }) { obelisks.spelled() }
        check(obelisks.instability.isCoherent) { "${obelisks.instability}" }
        val spring = resolved("age gold_block springs")
        check(spring.incoherentPairs() == setOf(setOf("gold_block", "springs"))) { "a spring of gold: ${spring.instability}" }
    }

    test("a quantifier counts the stars, and each sun described is a body") {
        val stars = resolved("age teeming stars")
        check(stars.option(Aspect.STARS, SkyBodies.STARS.name).isNotEmpty()) { stars.spelled() }
        check(resolved("age red sun colossal sun").composition.membersIn(Aspect.SUN) == 2) { "two suns described, not two suns" }
    }

    test("what the reading charged and dropped reaches the instability, and the cost is every page's ink") {
        val moved = resolved("age colossal")
        check(moved.instability.flaws.any { it.register == Register.REHOMED }) { "colossal laid after the Age was not charged" }
        check(resolved("age xyzzy").dropped == listOf("xyzzy")) { "a page that is no word was not dropped" }
        val cost = resolved("age sunless").cost
        check(cost == 1 + 4) { "age and sunless cost $cost, where the age page is one and sunless four" }
    }
})
