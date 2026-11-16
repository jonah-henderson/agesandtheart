package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Spending
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/** That each way an Age's instability can call an end down reads from its file and judges as it says. */
class EndConditionCheck : FunSpec({

    fun flaws(count: Int, register: Register = Register.INCOHERENT) =
        (1..count).map { Flaw(register, listOf("word $it", "other $it"), null, emptyList()) }

    fun ageOf(flaws: List<Flaw>, spreadBought: Int = 0, pages: Int = 20, seed: Long = 1L): Unravelling {
        val steps = if (spreadBought > 0) mapOf(Manifestation.COLLAPSE to mapOf("spread" to spreadBought)) else emptyMap()
        val spending = Spending(steps, mapOf(Manifestation.COLLAPSE to mapOf("spread" to 3)))
        return Unravelling(Instability(flaws, pages), spending, seed)
    }

    fun read(json: String): EndCondition = EndCondition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()

    val kind = CataclysmKind.ASTEROID

    test("reach holds only once the dial is bought to the share asked") {
        val condition = read("""{"type": "reach", "manifestation": "collapse", "dial": "spread", "at_least": 1.0}""")
        check(condition == EndCondition.Reach(Manifestation.COLLAPSE, "spread", 1.0)) { "read as $condition" }
        check(!condition.holdsFor(ageOf(flaws(9), spreadBought = 2), kind)) { "two steps of three called it down" }
        check(condition.holdsFor(ageOf(flaws(9), spreadBought = 3), kind)) { "all three steps did not call it down" }
    }

    test("flaws counts distinct flaws, of one register where it is named") {
        val any = read("""{"type": "flaws", "at_least": 5}""")
        val dangling = read("""{"type": "flaws", "at_least": 5, "register": "dangles"}""")
        val age = ageOf(flaws(4) + flaws(2, Register.DANGLES).map { it.copy(words = listOf("dangling ${it.words}")) })
        check(any.holdsFor(age, kind)) { "six flaws did not reach five" }
        check(!dangling.holdsFor(age, kind)) { "two dangling flaws reached five" }
    }

    test("tier holds once the flaws reach it, and never in a book too short to") {
        val failing = read("""{"type": "tier", "at_least": "failing"}""")
        check(failing.holdsFor(ageOf(flaws(7)), kind)) { "seven flaws did not fail" }
        check(!failing.holdsFor(ageOf(flaws(6)), kind)) { "six flaws failed" }
        check(!failing.holdsFor(ageOf(flaws(9), pages = 4), kind)) { "a short book failed" }
    }

    test("chance is drawn from the seed, the same every time, and near its share across many") {
        val half = read("""{"type": "chance", "share": 0.5}""")
        val seeds = 1L..2_000L
        val first = seeds.map { half.holdsFor(ageOf(emptyList(), seed = it), kind) }
        val again = seeds.map { half.holdsFor(ageOf(emptyList(), seed = it), kind) }
        check(first == again) { "the same seeds drew differently" }
        val share = first.count { it }.toDouble() / first.size
        check(share in 0.45..0.55) { "half came out at $share" }
    }

    test("all_of, any_of and not combine the rest") {
        val both = read(
            """{"type": "all_of", "of": [{"type": "tier", "at_least": "failing"}, {"type": "not", "of": {"type": "flaws", "at_least": 9}}]}""",
        )
        val either = read("""{"type": "any_of", "of": [{"type": "flaws", "at_least": 9}, {"type": "tier", "at_least": "harmful"}]}""")
        check(both.holdsFor(ageOf(flaws(7)), kind)) { "seven flaws failed and are under nine, and it did not hold" }
        check(!both.holdsFor(ageOf(flaws(9)), kind)) { "nine flaws held under 'not nine'" }
        check(either.holdsFor(ageOf(flaws(4)), kind)) { "four flaws harm, and it did not hold" }
        check(!either.holdsFor(ageOf(flaws(2)), kind)) { "two strange flaws held" }
    }

    test("an unknown type is refused, naming the ones there are") {
        val result = EndCondition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""{"type": "doom"}"""))
        val message = result.error().map { it.message() }.orElse("")
        check("reach" in message) { "an unknown type said: '$message'" }
    }
})
