package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Sky
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **What a word merely offers, and what it insists on** (`the-world-model.md` §5).
 *
 * A `requests` block is laid *under* the sentence: it reaches a parameter nothing demanded and disappears
 * without a word wherever something did. Until it existed, `pool` drew *which* facets fired and then
 * demanded whatever it drew — so an offer contended with the writer, and `a blue sun. an inferno Age.`
 * displaced one of them and charged somebody for a contradiction nobody had made.
 *
 * The other half is `cast`, which is the only way a word can bring a body into being. A writer mints a sun
 * by describing one and there is no clause in `an inferno Age` to mint anything, so an Age that burns had
 * no way to say its sky should hold more than one thing.
 */
@Tags(NEEDS_REGISTRIES)
class OfferCheck : FunSpec({

    /**
     * A book, resolved. **The nucleus leads** — every clause closes on the page it is about, so `age` ends
     * the first one and anything describing a body comes after it. A row that puts it last is not a book
     * and is quietly filled in by `Repair`, which is a fixture bug that reads as a passing test.
     */
    fun resolve(seed: Long, vararg pages: String): Resolution {
        val sentence = read(pages.toList())
        check(sentence.constraints.none { it.latent }) { "$pages is not a book — Repair filled it in" }
        return Resolver.resolve(vocabulary, sentence, seed)
    }

    /** Every seed in a spread, because what a word *offers* is drawn and one Age proves nothing. */
    val SEEDS = (1L..40L).toList()

    /**
     * **An Age that burns lights its own sky.** The cast is offered rather than demanded, so it fires on
     * the seeds that drew it and never on all of them — what is asserted is that it fires at all, and that
     * when it does it asks for what `inferno` says and not for some number nobody wrote.
     */
    test("an inferno hangs more than one sun where nobody described one") {
        val casts = SEEDS.map { seed -> resolve(seed, "inferno", "age").composition.membersIn(Aspect.SUN) }
        check(casts.any { it > 1 }) { "no seed in ${SEEDS.size} gave an inferno a second sun" }
        check(casts.all { it in 1..3 }) { "an inferno asked for a cast outside 2..3: ${casts.distinct()}" }
    }

    /**
     * **And it lights every one of them, not just the first.** The roll has to be sized before the steering
     * runs: `withOptions` writes a parameter to every member there is, so a cast grown afterwards leaves the
     * second and third suns blank and they are filled from the template instead — one red sun and two
     * ordinary ones, which is not a sky anybody asked for.
     */
    test("every sun an inferno hung is the same sun") {
        val lit = SEEDS.map { seed -> resolve(seed, "inferno", "age").composition }
            .filter { it.membersIn(Aspect.SUN) > 1 }
        check(lit.isNotEmpty()) { "no seed hung a second sun, so this proves nothing" }
        for (composition in lit) {
            val bodies = (0..<composition.membersIn(Aspect.SUN)).map { composition.optionsFor(Aspect.SUN, it) }
            val colours = bodies.map { it.of(Sky.SUNCOLOUR) }.distinct()
            val sizes = bodies.map { it.of(Sky.SUNSIZE) }.distinct()
            check(colours.size == 1 && sizes.size == 1) {
                "an inferno's suns did not agree — colours $colours, sizes $sizes"
            }
        }
    }

    /**
     * **A described sun beats the offer, and costs nothing to have described.** This is the whole point of
     * the block: the writer's blue is a demand, so the inferno's offered red is not there to argue with.
     */
    test("a sun the writer described wins, and is not charged for it") {
        for (seed in SEEDS) {
            val resolved = resolve(seed, "inferno", "age", "blue", "sun")
            check(resolved.composition.membersIn(Aspect.SUN) == 1) {
                "the writer's one sun became ${resolved.composition.membersIn(Aspect.SUN)} at seed $seed"
            }
            check(resolved.composition.optionsFor(Aspect.SUN, 0).of(Sky.SUNCOLOUR) == "blue") {
                "the writer's blue sun came out ${resolved.composition.optionsFor(Aspect.SUN, 0).of(Sky.SUNCOLOUR)}"
            }
            check(resolved.instability.flaws.none { it.register == Register.DISPLACED }) {
                "describing a sun was charged against an inferno's offer: ${resolved.instability.flaws}"
            }
        }
    }

    /**
     * **A demand on one parameter does not silence the offers on the others.** Yielding is per parameter and per
     * aspect, or saying one thing about a sun would cost a writer everything else the word was offering.
     */
    test("describing a sun's colour leaves the rest of the offer standing") {
        val lit = SEEDS.map { resolve(it, "inferno", "age", "blue", "sun").composition }
        check(lit.all { it.optionsFor(Aspect.SUN, 0).of(Sky.SUNCOLOUR) == "blue" }) {
            "the writer's sun did not stay blue"
        }
        check(lit.any { it.optionsFor(Aspect.SKY, 0).of(Atmosphere.SKY) == "red" }) {
            "the inferno's offered sky went with the sun the writer described"
        }
    }

    /**
     * **Two words offering the same thing is not a quarrel.** `scorching inferno` is one furnace sky: the
     * roll takes the largest cast asked for rather than the sum, and neither word is charged for the
     * other's offer.
     */
    test("two words that both offer a sky agree without charge") {
        for (seed in SEEDS) {
            val resolved = resolve(seed, "scorching", "inferno", "age")
            check(resolved.composition.membersIn(Aspect.SUN) <= 3) {
                "two offers stacked into ${resolved.composition.membersIn(Aspect.SUN)} suns at seed $seed"
            }
            val overTheSky = resolved.instability.flaws.filter { it.aspect == Aspect.SUN }
            check(overTheSky.isEmpty()) { "two offers were charged against each other: $overTheSky" }
        }
    }

    /**
     * **The cast says how many there are and never becomes one of their properties.** The stored entries
     * *are* the roll, so a `cast` written into the options would record the same fact twice and let the
     * two disagree — and it would reach a saved recipe, where nothing would know to read it.
     */
    test("the cast never lands in an Age's options") {
        for (seed in SEEDS) {
            val composition = resolve(seed, "inferno", "age").composition
            for (member in 0..<composition.membersIn(Aspect.SUN)) {
                val chosen = composition.optionsFor(Aspect.SUN, member).chosen
                check(Parameter.CAST !in chosen) { "a cast was written into the options: $chosen" }
            }
        }
    }

    /**
     * **An offer reaches the *choice* of preset, not only the parameters on it.** A sea is a value from a
     * catalogue picked by tag, and there is no parameter that says lava — so an inferno leans the draw instead.
     */
    test("an inferno's sea is fire or nothing where nobody said") {
        val seas = SEEDS.map { seed -> resolve(seed, "inferno", "age").composition.seas.map { it.key } }
        check(seas.none { it.isEmpty() }) { "an inferno was given no sea at all, so this proves nothing" }
        val burns = seas.count { poured -> poured.any { it.contains("lava") || it.contains("air") } }
        // A **clear majority** rather than a measured fraction: it is an offer, so the ordinary answer must
        // stay possible, and pinning the exact split would make a tag weight anywhere a failing test here.
        check(burns > SEEDS.size / 2) { "only $burns of ${SEEDS.size} infernos got a sea of fire or nothing" }
        check(seas.any { poured -> poured.any { it.contains("lava") } }) { "no inferno got a sea of fire" }
    }

    /**
     * **An offer leans the draw and never divides it.** Harmony is the *sentence* liking several answers,
     * and an offer is not the sentence liking anything — nobody said a word. Where the tilt lifted two
     * seas to comparable strength the aspect's appetite happily gave each its own ground, so an inferno
     * that asked for fire or nothing sometimes got a lake of lava beside open air: a seam invented out of
     * silence, and free, because a harmonious division is never charged.
     */
    test("an offered sea is one sea") {
        for (seed in SEEDS) {
            val seas = resolve(seed, "inferno", "age").composition.seas
            check(seas.size == 1) { "an offer divided the sea into ${seas.map { it.key }} at seed $seed" }
        }
    }

    /** And harmony still happens where the sentence really did choose between several answers. */
    test("a sentence that chose may still keep company") {
        val divided = SEEDS.count { resolve(it, "age", "riddled", "rock").composition.carvers.size > 1 }
        check(divided > 0) { "no seed divided the rock, so the company rule has been switched off" }
    }

    /**
     * **And it yields, without a rule having to say so.** A tilt leans the draw between whatever survived,
     * so a writer who narrowed the sea to water leaves the lean nothing to choose between — which is why
     * an offered query needed no yielding rule of its own, where an offered parameter did.
     */
    test("a sea the writer asked for is the sea they get") {
        for (seed in SEEDS) {
            val resolved = resolve(seed, "inferno", "age", "drowned", "sea")
            val seas = resolved.composition.seas.map { it.key }
            check(seas.isNotEmpty() && seas.all { it.contains("water") }) {
                "the writer's drowned sea came out $seas at seed $seed"
            }
            check(resolved.instability.flaws.none { it.aspect == Aspect.SEA }) {
                "asking for water was charged against the inferno's lean: ${resolved.instability.flaws}"
            }
        }
    }

    /**
     * **A demand still fractures against a demand.** The block must not have made `inferno` soft: its heat
     * is `sets` and stays a demand, so a frozen world that burns is still a contradiction the writer pays
     * for — which is the line `VocabularyCheck` guards from the other side.
     */
    test("what an inferno insists on still fractures") {
        val resolved = resolve(SEEDS.first(), "frozen", "inferno", "age")
        check(resolved.instability.flaws.isNotEmpty()) {
            "a frozen inferno came out coherent, so the demands went soft with the offers"
        }
    }
})
