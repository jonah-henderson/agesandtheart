package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import co.voik.ephemeris.sky.ColourCycles
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

@Tags(NEEDS_REGISTRIES)
class PrismCheck : FunSpec({

    fun cyclesOf(row: String): ColourCycles {
        val composition = resolved(row).composition
        return Prism.cyclesIn(composition, Sky.specFor(composition, SEED), SEED)
    }

    test("a turn takes three days at the slowest, a day and two and a half minutes at the unsaid ends, a minute at the fastest") {
        check(Prism.periodAt(-1.0) == 72_000) { "the slowest turn took ${Prism.periodAt(-1.0)} ticks" }
        check(Prism.periodAt(-0.6) == 24_000) { "the slowest unsaid turn took ${Prism.periodAt(-0.6)} ticks" }
        check(Prism.periodAt(0.6) == 3_000) { "the fastest unsaid turn took ${Prism.periodAt(0.6)} ticks" }
        check(Prism.periodAt(1.0) == 1_200) { "the fastest turn took ${Prism.periodAt(1.0)} ticks" }
        check(Prism.periodAt(0.0) in 3_000..24_000) { "the middle of the axis took ${Prism.periodAt(0.0)} ticks" }
    }

    test("prismatic turns every coloured part in step, and leaves the light alone") {
        val cycles = cyclesOf("age prismatic")
        val parts = listOfNotNull(
            cycles.sky, cycles.fog, cycles.cloud, cycles.waterFog, cycles.stars, cycles.aurora, cycles.ground, cycles.water,
        ) + cycles.bodies.values
        check(parts.size == 8 + cycles.bodies.size && cycles.bodies.isNotEmpty()) { "a part was left still: $cycles" }
        check(parts.map { it.periodTicks }.distinct().size == 1) { "the parts turned on different clocks: $cycles" }
        check(parts.first().periodTicks in 3_000..24_000) { "an unsaid turn took ${parts.first().periodTicks} ticks" }
        check(cycles.light == null) { "a bare prismatic turned the light" }
    }

    test("prismatic air turns the light, and nothing else in the sky") {
        val cycles = cyclesOf("age prismatic air")
        check(cycles.light != null && cycles.fog != null) { "the air's light or fog did not turn: $cycles" }
        check(cycles.sky == null && cycles.bodies.isEmpty()) { "more than the air turned: $cycles" }
    }

    test("swift prismatic sun turns the sun alone, and fast") {
        val cycles = cyclesOf("age swift prismatic sun")
        val sun = cycles.bodies.values.singleOrNull()
        check(sun != null && sun.periodTicks <= 3_000) { "the sun turned as $sun" }
        check(cycles.sky == null && cycles.stars == null) { "more than the sun turned: $cycles" }
    }

    test("prismatic grass and prismatic leaves turn the ground's mesh only, and prismatic waters the water's") {
        for (row in listOf("age prismatic grass", "age prismatic leaves")) {
            val cycles = cyclesOf(row)
            check(cycles.ground != null && cycles.water == null && cycles.sky == null) { "$row turned $cycles" }
            check(cycles.ground?.tinge == 0.0f) { "$row tinged the mesh, which would colour every grey block" }
        }
        val waters = cyclesOf("age prismatic waters")
        check(waters.water != null && waters.waterFog != null && waters.ground == null) { "prismatic waters turned $waters" }
    }

    test("an Age that says nothing of it turns nothing") {
        check(cyclesOf("age").isNone) { "a plain Age turned: ${cyclesOf("age")}" }
    }
})

private const val SEED = 7L
