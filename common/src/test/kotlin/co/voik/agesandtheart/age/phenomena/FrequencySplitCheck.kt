package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.AspectOptions
import co.voik.agesandtheart.age.FormerRungs
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.ORDINARY_SHARE
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.word.grammar.Grammar
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * A rung counts a phenomenon in space and a frequency says how often it comes, and the two are never the same
 * page: the rung is refused where there is nothing to count, and an Age written while it meant how often
 * still comes as often.
 */
@Tags(NEEDS_REGISTRIES)
class FrequencySplitCheck : FunSpec({

    fun parses(vararg pages: String) = Grammar.parses(ShippedCorpus.vocabulary, listOf("age", *pages))

    test("a rung is refused before a phenomenon that is one state over the whole Age") {
        for (phenomenon in listOf("tempest", "fog", "blizzard", "meteors", "deluge")) {
            check(!parses("teeming", phenomenon, "phenomena")) { "'teeming $phenomenon phenomena' was read" }
            check(parses("frequent", phenomenon, "phenomena")) { "'frequent $phenomenon phenomena' was not read" }
        }
    }

    test("a page saying only what a phenomenon has no dial for is refused before it") {
        val refused = listOf(
            listOf("frequent", "inferno"), listOf("frequent", "inferno", "phenomena"), listOf("constant", "overgrowth"),
            listOf("strong", "overgrowth"), listOf("swift", "tempest"),
        )
        for (pages in refused) check(!parses(*pages.toTypedArray())) { "'${pages.joinToString(" ")}' was read" }
        val read = listOf(listOf("swift", "overgrowth"), listOf("strong", "inferno", "phenomena"), listOf("frequent", "tempest"))
        for (pages in read) check(parses(*pages.toTypedArray())) { "'${pages.joinToString(" ")}' was refused" }
    }

    test("a swift overgrowth grows faster, and a slow one slower") {
        fun growth(word: String) = Phenomena.growthOf(
            ShippedCorpus.resolved(SEED, word, "overgrowth").composition.optionsFor(Aspect.PHENOMENA, 0),
            Phenomenon.OVERGROWTH, SEED,
        )
        check(growth("swift") > 1.0) { "a swift overgrowth grows at ${growth("swift")}" }
        check(growth("slow") < 1.0) { "a slow overgrowth grows at ${growth("slow")}" }
    }

    test("a rung still counts what can be counted in space") {
        check(parses("teeming", "sandfall", "phenomena")) { "'teeming sandfall phenomena' was refused" }
    }

    test("a frequency said of one phenomenon brings it, and is that phenomenon's alone") {
        val composition = ShippedCorpus.resolved(SEED, "frequent", "tempest").composition
        val options = composition.optionsFor(Aspect.PHENOMENA, 0)
        check(Phenomena.claimFor(options, Phenomenon.TEMPEST) != null) { "'frequent tempest' brought no tempest" }
        check(Phenomena.recurrenceOf(options, Phenomenon.TEMPEST, SEED).often > 1.0) { "the tempest came no more often" }
        check(Phenomena.recurrenceOf(options, Phenomenon.METEORS, SEED) == Recurrence.ORDINARY) { "the meteors moved too" }
    }

    test("a modifier is said of the members named after it, wherever the clause closes") {
        val one = ShippedCorpus.resolved(SEED, "frequent", "tempest", "phenomena").composition.optionsFor(Aspect.PHENOMENA, 0)
        check(Phenomena.recurrenceOf(one, Phenomenon.TEMPEST, SEED).often > 1.0) { "'frequent tempest phenomena' left the tempest" }
        check(Phenomena.recurrenceOf(one, Phenomenon.METEORS, SEED) == Recurrence.ORDINARY) {
            "'frequent tempest phenomena' reached every phenomenon"
        }
        val two = ShippedCorpus.resolved(SEED, "frequent", "tempest", "strong", "meteors", "phenomena").composition
            .optionsFor(Aspect.PHENOMENA, 0)
        check(Phenomena.recurrenceOf(two, Phenomenon.METEORS, SEED) == Recurrence.ORDINARY) { "the meteors were made frequent" }
        check(Phenomena.strengthOf(two, Phenomenon.TEMPEST, SEED) == 0.0) { "the tempest was made strong" }
        check(Phenomena.strengthOf(two, Phenomenon.METEORS, SEED) > 0.0) { "the meteors were not made strong" }
        check(Phenomena.claimFor(two, Phenomenon.METEORS) != null) { "the meteors were not brought" }
    }

    test("constant is uninterrupted wherever the phenomenon is a share of the time") {
        val constant = Recurrence.of(1.0)
        check(Fog.shareOfTheTime(constant, often = 0.0) == 1.0) { "a constant fog lifts" }
        check(Blizzard.shareOfTheTime(constant, often = 0.0) == 1.0) { "a constant blizzard lifts" }
        check(Deluge.risingOf(constant, Spending.NOTHING).rainShare == 1.0) { "a constant deluge lets up" }
    }

    test("an ordinary blizzard storms more of the time than vanilla's weather rains") {
        val share = Blizzard.shareOfTheTime(Recurrence.ORDINARY, 0.0)
        check(share > ORDINARY_SHARE) { "an ordinary blizzard asks $share, which cuts vanilla's rain short" }
    }

    test("seldom is less often than ordinary, even where ordinary was the floor") {
        val seldom = Recurrence.of(-0.6)
        check(Blizzard.shareOfTheTime(seldom, 0.0) < Blizzard.shareOfTheTime(Recurrence.ORDINARY, 0.0)) {
            "an infrequent blizzard comes as often as an ordinary one"
        }
        check(Fog.shareOfTheTime(seldom, 0.0) < Fog.shareOfTheTime(Recurrence.ORDINARY, 0.0)) {
            "an infrequent fog lies as often as an ordinary one"
        }
    }

    test("how hard a phenomenon is, is its strength, and strong and weak say it of that one alone") {
        for ((word, harder) in listOf("strong" to true, "weak" to false)) {
            val options = ShippedCorpus.resolved(SEED, word, "tempest").composition.optionsFor(Aspect.PHENOMENA, 0)
            val tempest = Phenomena.strengthOf(options, Phenomenon.TEMPEST, SEED)
            check(if (harder) tempest > 0.0 else tempest < 0.0) { "'$word tempest' wrote a strength of $tempest" }
            check(Phenomena.strengthOf(options, Phenomenon.FOG, SEED) == 0.0) { "'$word tempest' reached the fog" }
        }
    }

    test("a weak phenomenon is gentler than one that bought nothing, and never past its floors") {
        val weak = TempestDials.of(Spending.NOTHING, written = -1.0)
        check(weak.blast < TempestDials.NONE.blast) { "a weak tempest blasts as hard as an ordinary one" }
        check(Fog.thicknessAt(FogDials.of(Spending.NOTHING, written = -1.0).thickness) < Fog.thicknessAt(0.0)) {
            "a weak fog closes in as close as an ordinary one"
        }
        check(Blizzard.hardnessAt(BlizzardDials.of(Spending.NOTHING, written = -1.0).visibility) > 0.0) {
            "a weak blizzard's whiteout went to nothing"
        }
        val strong = SandfallDials.of(Spending.NOTHING, written = 1.0)
        check(strong.size in 0.0..1.0 && strong.size > 0.0) { "a strong sandfall's size went to ${strong.size}" }
    }

    test("an Age written while a rung meant how often comes as often as it did") {
        val happens = Options(mapOf(Phenomena.HAPPENS.name to listOf("tempest[amount=4]", "sandfall[amount=4]")))
        val composition = AgeComposition(
            terrains = listOf(Terrain.HILLS),
            options = AspectOptions().with(Aspect.PHENOMENA, listOf(happens)),
        )
        val old = AgeRecipe(
            AgeWorld.Composed(composition),
            seed = SEED,
            generatorVersion = FormerRungs.LAST_GENERATOR_BEFORE_THE_SPLIT,
        )
        val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow()
        val read = AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        val befalling = Happenings.befallingIn(read, SEED, Spending.NOTHING)
        val tempest = befalling[Phenomenon.TEMPEST] ?: error("the tempest was lost")
        check(tempest.amount == 1.0) { "the tempest kept a rung it can no longer have: $tempest" }
        check(abs(tempest.recurrence.often - 4.0) < CLOSE_ENOUGH) { "a teeming tempest now comes ${tempest.recurrence.often}× as often" }
        check(befalling[Phenomenon.SANDFALL]?.amount == 4.0) { "the sandfall lost its rung, which still counts columns" }
    }
})

private const val SEED = 4242L
private const val CLOSE_ENOUGH = 0.01
