package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorFlight
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.Glow
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyMoment
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

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
        val lights = mutableListOf<Glow>()
        for (entity in moment.level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            gatherWhatIsStillToCome(storm, moment.partOfATickOn, moment.camera.position(), lights)
        }
        Blaze3dSkyCanvas.drawGlows(lights)
    }

    /**
     * Every body of this storm that has not fallen yet and is near enough its moment to be showing.
     *
     * **Uncapped, and that is a correctness matter rather than a budget one** (Jonah, walked). This used to
     * draw the first fourteen it found, because a glow cost a render pass apiece — but the ones it left out
     * were still approaching, so each appeared from nowhere at nine tenths of its size the moment a slot
     * freed. A long storm is a hundred bodies and every one has to be in the sky for its whole approach or
     * the sky and the ground are telling different stories. `drawGlows` puts the lot in one submission.
     *
     * **Drawn against the frame's own clock rather than the tick's** ([MeteorStorm.ageWithin]), since a
     * body covers twenty blocks in the last tick of its approach and a light stepping that far at a time
     * reads as one being redrawn somewhere else.
     */
    private fun gatherWhatIsStillToCome(
        storm: MeteorStorm,
        partOfATickOn: Float,
        eye: Vec3,
        into: MutableList<Glow>,
    ) {
        val age = storm.ageWithin(partOfATickOn)
        for (number in 0..<storm.bodies) {
            val flight = storm.flightOf(number)
            val until = flight.fallsAt - age
            if (until <= NONE_LEFT) continue
            if (until > SEEN_COMING) continue
            val nearness = ONE_WHOLE - until / SEEN_COMING
            into += lightFor(storm.seenFrom(flight, nearness).subtract(eye), nearness)
        }
    }

    /**
     * One body's light, [awayFromTheEye] blocks off and [nearness] of the way from sighting to falling.
     *
     * **Pointed at where the body actually is, rather than at a bearing chosen for the sky.** The lights
     * used to be hung on the storm's entry bearing alone, which put every storm's telegraph in the same
     * part of the sky wherever the storm itself was — so a shower could be pounding the country north of
     * you while its lights hung in the west (Jonah, walked). [MeteorStorm.seenFrom] is the one place that
     * answers where a light is, and the command that turns you to face one asks it too.
     *
     * **Which is also what makes them split.** The bodies are aimed across a disc and hang three thousand
     * blocks out to begin with, where that whole disc is a couple of degrees; they come apart as they
     * close, on nothing but the geometry, and no animation says so.
     */
    private fun lightFor(awayFromTheEye: Vec3, nearness: Float): Glow {
        val aim = Quaternionf().rotateTo(
            UP_X,
            UP_Y,
            UP_Z,
            awayFromTheEye.x.toFloat(),
            awayFromTheEye.y.toFloat(),
            awayFromTheEye.z.toFloat(),
        )
        return Glow(
            orientation = aim,
            distance = FAR_OFF,
            // Squared, so most of the growth is at the end: a light swelling evenly would read as being
            // turned up rather than coming closer.
            angularSize = LIKE_A_STAR + (ON_ARRIVAL - LIKE_A_STAR) * nearness * nearness,
            tint = COLD_FIRE.copy(alpha = COLD_FIRE.alpha * (DIMMEST + (ONE_WHOLE - DIMMEST) * nearness)),
        )
    }

    /** Violet-white, and paler than the violet [MeteorGlow] lights the ground in. */
    private val COLD_FIRE = Rgba(0.62f, 0.45f, 1.0f, 0.95f)

    /** How long before its own fall a body's light appears, in ticks — the thirty-second warning. */
    private const val SEEN_COMING = MeteorStorm.APPROACHING.toFloat()

    /**
     * **A star, growing to about two of them** (Jonah, walked), and measured rather than judged: vanilla
     * draws its own stars as quads of half-extent 0.15 to 0.25 at this same distance of 100, so these are
     * one of them and then two, once the halo `drawGlow` puts round a core is counted.
     *
     * Three walks read the arrival size as too big — the last of them "comparable to the moon" — because
     * the halo was never in the arithmetic. It is now: the apparent size is the core times the spread.
     */
    private const val LIKE_A_STAR = 0.12f
    private const val ON_ARRIVAL = 0.25f
    private const val FAR_OFF = 100.0f

    /** Small lights need to be bright to be lights at all, so they arrive already burning. */
    private const val DIMMEST = 0.5f

    private const val NONE_LEFT = 0.0f
    private const val ONE_WHOLE = 1.0f

    private const val UP_X = 0.0f
    private const val UP_Y = 1.0f
    private const val UP_Z = 0.0f
}
