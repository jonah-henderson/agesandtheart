package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.location
import io.kotest.core.spec.style.FunSpec
import io.netty.buffer.Unpooled

/** An asteroid's schedule: each stage begins where the last ends, the links fail at the impact, and it travels whole. */
class DoomCheck : FunSpec({

    val timeline = ImpactTimeline(approach = 100, entry = 10, travel = 50, heat = 20, dust = 30)
    val doom = Doom.asteroid(seed = 42L, startsAt = 1_000L, timeline = timeline)

    test("each stage begins where the one before it ends") {
        check(doom.phaseAt(999L) == null) { "the end had begun before it started" }
        val expected = mapOf(
            1_000L to ImpactPhase.APPROACH,
            1_099L to ImpactPhase.APPROACH,
            1_100L to ImpactPhase.ENTRY,
            1_110L to ImpactPhase.TRAVEL,
            1_160L to ImpactPhase.HEAT,
            1_180L to ImpactPhase.DUST,
            1_210L to ImpactPhase.AFTER,
            9_999_999L to ImpactPhase.AFTER,
        )
        for ((time, phase) in expected) {
            val found = doom.phaseAt(time)
            check(found == phase) { "at $time the end was in $found, not $phase" }
        }
    }

    test("the links fail from the impact and not before") {
        check(!doom.hasStruckBy(doom.impactAt - 1)) { "the links failed before the impact" }
        check(doom.hasStruckBy(doom.impactAt)) { "the links held at the impact" }
        check(doom.impactAt == 1_110L) { "the impact came at ${doom.impactAt}" }
        check(doom.arrivalAt == 1_160L) { "the debris arrived at ${doom.arrivalAt}" }
    }

    test("the same Age is always struck from the same bearing") {
        val again = Doom.asteroid(seed = 42L, startsAt = 5L)
        check(again.bearingDegrees == doom.bearingDegrees) { "one Age drew two bearings" }
        check(doom.bearingDegrees in 0.0f..<360.0f) { "a bearing of ${doom.bearingDegrees}" }
    }

    test("an end and its absence survive the wire") {
        for (sent in listOf(CataclysmPayload("an_age".location(), doom), CataclysmPayload("an_age".location(), null))) {
            val buffer = Unpooled.buffer()
            CataclysmPayload.STREAM_CODEC.encode(buffer, sent)
            val read = CataclysmPayload.STREAM_CODEC.decode(buffer)
            check(read == sent) { "sent $sent, read $read" }
        }
    }
})
