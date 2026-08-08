package co.voik.agesandtheart.age.phenomena

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/**
 * The arithmetic an inferno is tuned by (design §5.2.2).
 *
 * **The rates are datapack content and only a walk can settle them**, so what is worth checking here is not
 * the numbers but that the numbers *mean* something: that a file round-trips, that an absent one is a
 * default rather than a dead phenomenon, and that the shipped values sit where the design says they should.
 * A rate nobody can reason about is a rate that drifts.
 */
class InfernoCheck : FunSpec({

    fun read(json: String) = Intensity.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()

    test("a tuning file round-trips") {
        val written = Intensity(reach = 2.0, chance = 0.5, harm = 3.0, betweenHarms = 15)
        val json = Intensity.CODEC.encodeStart(JsonOps.INSTANCE, written).getOrThrow()
        val back = Intensity.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()
        check(back == written) { "$written came back as $back" }
    }

    /** An absent field is the ordinary value, so a pack may tune one number without restating the rest. */
    test("a file may say only what it changes") {
        val only = read("""{"chance": 0.9}""")
        check(only.chance == 0.9) { "the one stated value was lost: $only" }
        check(only.reach == Intensity.ORDINARY.reach) { "an unstated field did not default: $only" }
        check(only.betweenHarms == Intensity.ORDINARY.betweenHarms) { "an unstated field did not default: $only" }
    }

    /**
     * **A small reach still acts.** `sweeps` is a rate turned into a count, and an integer conversion that
     * rounded a fractional reach to zero would be a phenomenon that silently never happens — which is the
     * worst way for a tuning value to fail, because the file still reads as though it were on.
     */
    test("a reach below one still offers a chunk up") {
        for (reach in listOf(0.01, 0.1, 0.5, 0.99)) {
            check(Intensity(reach = reach).sweeps >= 1) { "reach $reach never sweeps" }
        }
    }

    /**
     * The one rate the design fixes rather than leaves to taste: **damage must be slow enough to react to.**
     * A writer who links in at noon has to have time to dig, or the Age kills people for arriving, which is
     * the punishment register however fair the rule reads (§5.2).
     */
    test("the shipped inferno leaves time to dig") {
        val shipped = read(SHIPPED)
        val halfHeartsPerSecond = shipped.harm * TICKS_PER_SECOND / shipped.betweenHarms
        val secondsFromFullHealth = FULL_HEALTH / halfHeartsPerSecond
        check(secondsFromFullHealth > COMFORTABLY_ENOUGH) {
            "the open air kills from full in %.0fs, which is not time to think".format(secondsFromFullHealth)
        }
    }

    /** And it is not so gentle that standing in the open is free, or the counterplay never gets learned. */
    test("the shipped inferno is not survivable by ignoring it") {
        val shipped = read(SHIPPED)
        check(shipped.harm > 0.0 && shipped.betweenHarms in 1..TOO_RARE_TO_NOTICE) {
            "the open air does not meaningfully burn: $shipped"
        }
    }

    /** Sampling is calibrated against vanilla's own precipitation pass, so a rate can be reasoned about. */
    test("sampling matches the rate vanilla ticks weather at") {
        check(Sampling.BETWEEN_CHUNK_SAMPLES == 48) {
            "the reference rate moved to ${Sampling.BETWEEN_CHUNK_SAMPLES}, so every tuning file now means " +
                "something different"
        }
    }
})

/** What `art/phenomenon/inferno.json` ships, restated so a change to it has to be a deliberate one. */
private const val SHIPPED = """{"reach": 4.0, "chance": 0.5, "harm": 1.0, "between_harms": 40}"""

private const val TICKS_PER_SECOND = 20.0
private const val FULL_HEALTH = 20.0
private const val COMFORTABLY_ENOUGH = 20.0
private const val TOO_RARE_TO_NOTICE = 200
