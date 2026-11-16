package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.phenomena.Gale
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.Mth
import kotlin.math.cos
import kotlin.math.sin

/** An asteroid's blast: nothing before the shock front, past any gale behind it, away from the impact, and gone in the dust. */
class BlastCheck : FunSpec({

    val doom = Doom.asteroid(seed = 42L, startsAt = 1_000L)

    test("still air until the shock front, and past any gale's fury behind it") {
        val shock = Blast.shockAt(doom)
        check(shock > doom.impactAt && shock < doom.arrivalAt) { "the shock front came at $shock" }
        check(Blast.blowingAt(doom, shock - 1.0) == null) { "the blast blew before its shock front" }
        val behind = Blast.blowingAt(doom, shock + 20.0)
        check(behind != null && behind.lying == 1.0 && behind.fury > 1.0) { "behind the shock front it blew $behind" }
    }

    test("it drives straight away from the impact") {
        val impact = Math.toRadians(doom.bearingDegrees.toDouble())
        val driving = Math.toRadians(Blast.drivingDegrees(doom))
        val towardTheImpact = sin(impact) * cos(driving) + -cos(impact) * sin(driving)
        check(towardTheImpact < -0.999) { "it drove at ${Blast.drivingDegrees(doom)}°, ${towardTheImpact} toward the impact" }
    }

    test("it holds through the heat, surges with the ash, and is gone half way into the dust") {
        val inTheHeat = Blast.blowingAt(doom, doom.arrivalAt + 10.0)
        check(inTheHeat != null && inTheHeat.lying == 1.0) { "in the heat it blew $inTheHeat" }
        val dustBegins = doom.startOf(ImpactPhase.DUST).toDouble()
        val stilled = dustBegins + doom.lengthOf(ImpactPhase.DUST) * 0.55
        check(Blast.blowingAt(doom, stilled) == null) { "it still blew well into the dust" }
        check(Blast.blowingAt(doom, doom.startOf(ImpactPhase.AFTER) + 100.0) == null) { "it blew in the winter after" }
    }

    test("laid over a gale it takes the bearing and the fury once risen, and leaves the gale alone without one") {
        val gale = Gale.Blowing(lying = 0.5, bearingDegrees = 10.0, fury = 0.6)
        val blast = Gale.Blowing(lying = 1.0, bearingDegrees = 200.0, fury = 2.0)
        val both = Blast.over(gale, blast)
        val isTheBlastsBearing = both != null && Mth.wrapDegrees(both.bearingDegrees - blast.bearingDegrees) == 0.0
        check(both != null && isTheBlastsBearing && both.lying == 1.0 && both.fury == 2.0) { "the two blew $both" }
        check(Blast.over(gale, null) == gale) { "a gale alone was changed" }
    }
})
