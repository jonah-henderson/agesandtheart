package co.voik.agesandtheart.age.consequence

import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.Difficulty
import net.minecraft.world.DifficultyInstance

/**
 * What a wound does to how dangerous its ground is (design §5.1).
 *
 * **The lever is vanilla's own inhabited time**, and that is the whole bet: a wound does not invent a
 * difficulty, it ages the ground, and everything downstream — mob equipment, zombie reinforcements, husk and
 * drowned conversion — follows in proportion with no hook of its own. So the numbers here are half ours and
 * half Minecraft's on purpose. **If a future version flattens that curve, the lever stops working silently**,
 * and these are what would say so.
 */
class HostilityCheck : FunSpec({

    /** A place nobody has ever stood, which is nearly every chunk of nearly every Age. */
    val fresh = 0L

    fun difficulty(setting: Difficulty, livedIn: Long) =
        DifficultyInstance(setting, DAWN, livedIn, NO_MOON).effectiveDifficulty

    test("a place no wound reaches is left exactly as it was") {
        check(Hostility.asIfLivedIn(Wounds.NONE, fresh) == fresh) { "an uncorrupted place was aged" }
        val livedIn = 900_000L
        check(Hostility.asIfLivedIn(Wounds.NONE, livedIn) == livedIn) { "an uncorrupted place was moved" }
    }

    /**
     * **A floor and never a setting** — the same rule as an Age's weather. A chunk somebody has genuinely
     * lived in for fifty hours is not made *safer* by a tear opening in it.
     */
    test("a wound never makes a long-lived place safer than it was") {
        val fiftyHours = 3_600_000L
        val halfCorrupted = 0.5
        check(Hostility.asIfLivedIn(halfCorrupted, fiftyHours) == fiftyHours) {
            "a wound lowered a long-lived chunk to ${Hostility.asIfLivedIn(halfCorrupted, fiftyHours)}"
        }
    }

    /**
     * The ladder itself, and the reason the lever was worth choosing: at the throat of a wound the local
     * difficulty **doubles** on Normal, which is the line between mobs that never arrive armed and mobs
     * that often do.
     */
    test("standing at a wound doubles the difficulty of the ground") {
        val ordinary = difficulty(Difficulty.NORMAL, fresh)
        val atTheThroat = difficulty(Difficulty.NORMAL, Hostility.asIfLivedIn(FULLY, fresh))
        check(atTheThroat > ordinary * 1.9f) { "a wound bought almost nothing: $ordinary to $atTheThroat" }
    }

    /** And it is a *gradient*, not a threshold — which is what makes the corruption worth navigating by. */
    test("the ground grows steadily more dangerous as a wound nears") {
        val ladder = listOf(0.0, 0.25, 0.5, 0.75, FULLY)
            .map { difficulty(Difficulty.NORMAL, Hostility.asIfLivedIn(it, fresh)) }
        check(ladder == ladder.sorted() && ladder.toSet().size == ladder.size) {
            "the ladder does not climb: $ladder"
        }
    }

    /**
     * **Peaceful is still peaceful.** Vanilla short-circuits the whole calculation there, so an Age at the
     * top of the register cannot make a difficulty setting mean something other than what it says.
     */
    test("a wound cannot make a peaceful world dangerous") {
        check(difficulty(Difficulty.PEACEFUL, Hostility.asIfLivedIn(FULLY, fresh)) == 0.0f) {
            "peaceful stopped being peaceful"
        }
    }

    /**
     * The corruption a wound is felt at, which both halves of §5.1 read — the air going wrong and the
     * ground growing dangerous are one signal, so a player who learns to fear the fog is learning
     * something true.
     */
    test("corruption falls away with distance and is gone at the reach") {
        check(Wounds.corruptionAtRange(0.0) == 1.0) { "the throat is not fully corrupted" }
        check(Wounds.corruptionAtRange(Wounds.REACH) == Wounds.NONE) { "corruption survives its own reach" }
        check(Wounds.corruptionAtRange(Wounds.REACH * 2) == Wounds.NONE) { "corruption carries past its reach" }
        val falling = (0..32).map { Wounds.corruptionAtRange(it.toDouble()) }
        check(falling == falling.sortedDescending()) { "corruption does not fall away: $falling" }
    }

    /** Squared, so nearly all of it happens in the last few blocks rather than spread thinly over the reach. */
    test("corruption is concentrated near the wound rather than spread over its reach") {
        val halfway = Wounds.corruptionAtRange(Wounds.REACH / 2)
        check(halfway < 0.3) { "halfway out is still $halfway corrupted, which is a haze rather than a source" }
    }
})

/** Early enough that vanilla's day-count term contributes nothing, so the checks read one lever at a time. */
private const val DAWN = 0L
private const val NO_MOON = 0.0f

/** At the throat, where the corruption is total. */
private const val FULLY = 1.0
