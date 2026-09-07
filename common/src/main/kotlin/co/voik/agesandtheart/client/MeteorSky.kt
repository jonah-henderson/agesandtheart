package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorFlight
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyMoment
import org.joml.Quaternionf
import kotlin.math.cos
import kotlin.math.sin

/**
 * A meteor storm coming in, drawn where it can be seen from a long way off (design §5.2).
 *
 * **The telegraph is the mechanic, not decoration on it.** A crater that simply appears is a punishment
 * and one you watched arrive for half a minute is a challenge, and they differ in nothing else.
 *
 * **Every light here is a particular body, and it goes out at the instant that body falls** (Jonah,
 * 2026-09-06). The first version was a decorative count — a few lights that grew and stopped while the
 * storm went on dropping rocks nobody had been shown — so the sky agreed with the ground for a few seconds
 * and lied for the rest. Now the two are one list: [MeteorFlight] answers where and when for body *n*, the
 * server drops it then, and this draws it until then.
 *
 * **Nothing is sent and nothing is remembered.** A storm is an entity, so a client has its position, its
 * identity and its clock; every light is arithmetic over those three.
 *
 * **Drawn as bare quads of light rather than sprites.** A borrowed sun brings its own yellow into whatever
 * it is tinted and its own soft edge into whatever size it is drawn at, so a point of light came out as a
 * small pale sun (Jonah, walked). `SkyCanvas.drawGlow` is the untextured primitive added for this.
 *
 * **Violet, and that is a deliberate break**: the pack's fire, lava, magma, bombs and gobbets are all red
 * and orange, so a hazard arriving out of the sky reads as a different *kind* of thing.
 */
object MeteorSky {

    /** Called from [AgeLooks], which is called from each loader's client entrypoint. */
    fun register() {
        // An overlay rather than a sky of our own: a storm is something an Age hangs *above* its sun and
        // stars, and taking the sky to draw one would take the Age's own sky away for the duration.
        LevelRendering.skyOverlay(::draw)
    }

    private fun draw(moment: SkyMoment) {
        for (entity in moment.level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            drawWhatIsStillToCome(storm)
        }
    }

    /**
     * Every body of this storm that has not fallen yet and is near enough its moment to be showing.
     *
     * **Capped at the nearest few in time**, which is what keeps a shower of a hundred bodies from being a
     * hundred quads: the ones further off would be a pixel apiece and indistinguishable from the stars.
     */
    private fun drawWhatIsStillToCome(storm: MeteorStorm) {
        var showing = 0
        for (number in 0..<storm.bodies) {
            if (showing >= MOST_AT_ONCE) return
            val flight = storm.flightOf(number)
            val until = flight.fallsAt - storm.tickCount
            if (until <= NONE_LEFT) continue
            if (until > SEEN_COMING) continue
            showing++
            drawOne(flight, ONE_WHOLE - until.toFloat() / SEEN_COMING)
        }
    }

    /**
     * One body's light, [nearness] of the way from first sighting to falling.
     *
     * **Hung where it is coming *from*, not where it will land**, which is what makes a light and the rock
     * that follows it the same object: a body enters obliquely, so a light over its landing site would go
     * out on one side of the sky and reappear as a streak on the other.
     *
     * **The whole storm shares one line in and each body strays only slightly off it** (Jonah, walked).
     * Bodies each drawing their own bearing came in from all over the sky, so the approach read as
     * unrelated lights rather than as one thing breaking up — and "it splits" means nothing unless they
     * were together first. The stray is enough to cover the ground being aimed at and no more, so what a
     * player watches is a single arrival separating, which is neither a fan nor a scatter.
     */
    private fun drawOne(flight: MeteorFlight, nearness: Float) {
        val across = cos(flight.entryAngle)
        val aim = Quaternionf().rotateTo(
            UP_X,
            UP_Y,
            UP_Z,
            (cos(flight.comingFrom) * across).toFloat(),
            sin(flight.entryAngle).toFloat(),
            (sin(flight.comingFrom) * across).toFloat(),
        )
        Blaze3dSkyCanvas.drawGlow(
            orientation = aim,
            distance = FAR_OFF,
            // Squared, so most of the growth is at the end: a light swelling evenly would read as being
            // turned up rather than coming closer. It stops well short of what it first did — the jump
            // from the light to the much smaller rock that replaced it was the tell (Jonah, walked).
            angularSize = LIKE_A_STAR + (ON_ARRIVAL - LIKE_A_STAR) * nearness * nearness,
            tint = COLD_FIRE.copy(alpha = COLD_FIRE.alpha * (DIMMEST + (ONE_WHOLE - DIMMEST) * nearness)),
        )
    }

    /** Violet-white, and the same violet the ground under a storm is lit in. */
    private val COLD_FIRE = Rgba(0.62f, 0.45f, 1.0f, 0.95f)

    /** How long before its own fall a body's light appears, in ticks — the thirty-second warning. */
    private const val SEEN_COMING = MeteorStorm.APPROACHING

    /** Enough to read as a shower, few enough to tell apart. */
    private const val MOST_AT_ONCE = 14

    /**
     * Vanilla's sun is 30 at a distance of 100, so this runs from under a star to a good deal under a moon.
     *
     * **Judged against the rock, not against the sky.** Twice now the arrival size has read as too big,
     * and the measure that matters is the jump: a light noticeably larger than the body that replaces it
     * reads as a swap. The bodies were made bigger at the same time, so the two meet nearer the middle.
     */
    private const val LIKE_A_STAR = 0.7f
    private const val ON_ARRIVAL = 2.2f
    private const val FAR_OFF = 100.0f

    private const val DIMMEST = 0.3f

    private const val NONE_LEFT = 0
    private const val ONE_WHOLE = 1.0f

    private const val UP_X = 0.0f
    private const val UP_Y = 1.0f
    private const val UP_Z = 0.0f
}
