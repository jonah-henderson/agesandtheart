package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitFloat
import net.minecraft.util.Mth

/** Where the panel's camera stands for one shot, each value `0..1` over a range [PanelCamera] owns. */
data class Shot(val turns: Float, val closeness: Float, val loft: Float)

/**
 * The panel's slideshow: one still view held for a beat, then a cut to another (design §7.8.1).
 *
 * Riven's panel flashed through frames rather than panning, and cuts are cheaper besides — a camera that
 * does not move renders the same picture every frame, where an orbit re-culls and re-meshes as it turns.
 *
 * Which shot is showing is a pure function of the clock and [seed], so nothing has to be advanced and two
 * things asking in the same frame agree. The seed is the Age's, so a book always flicks through the same
 * sequence of views.
 *
 * **[unsettled] is how far the cuts scatter, and that is where the instability index reaches the picture.**
 * The panel is interlaced out of the shot showing and the one before it ([PanelDistortion]), so how far
 * apart two consecutive shots stand *is* how much the two fields disagree.
 *
 * It scatters rather than paces for a reason. Every Age's panel eases round at the same [TURNS_PER_SECOND],
 * and what an unsettled one adds is a jitter on top of it — so a coherent Age steps a few degrees and combs almost
 * invisibly, and one at odds with itself lands somewhere different every tenth of a second and combs hard,
 * *without* the camera ending up spinning. A faster pace would read as a spinning camera, which is a
 * different claim from a picture that will not hold still.
 *
 * **At the top of the range the jitter is a whole turn and the stance moves every shot**, which is the
 * point at which nothing is recoverable: an Age this far gone is not a view of somewhere from an angle
 * that keeps changing, it is fragments with no view behind them. Anything less than a whole turn leaves
 * the eye a rotation to follow.
 *
 * **And at the bottom of it there are no shots at all.** A coherent Age's pace runs off the clock rather
 * than off the shot number and its stance never moves, so with no jitter to add nothing here is stepped and
 * the panel turns smoothly at frame rate. Being cut into shots is what the *distortion* does, and a panel
 * wearing none of it should not wear that either.
 */
class PanelShots(private val seed: Long, unsettled: Float) {

    private val openedAt = System.nanoTime()

    /** Whether the panel is cut into shots at all, or simply turns ([PanelDistortion.distorts]). */
    private val stepped = PanelDistortion.distorts(unsettled)

    /**
     * How long one shot is held, which is the panel's frame rate.
     *
     * It *slows* as an Age comes apart. A book with a flaw or two is a picture running a little short of
     * frames; one written to come apart is a handful of stills a second, which is long enough to look at
     * each one and find nothing in it.
     */
    private val nanosPerShot = Mth.lerp(unsettled, BRIEFEST_SHOT, LONGEST_SHOT).toLong()

    /**
     * Squared, where everything the panel *draws* ramps straight.
     *
     * The scatter is the one register with a hard floor and a hard ceiling: a book with a couple of
     * contradictions should wobble by a few degrees, and only an Age written to come apart should throw
     * the view right round. A straight ramp cannot be both, and this is the half that has to give.
     */
    private val jitter = MOST_JITTER * unsettled * unsettled

    /** How often the framing moves, in stances per shot: never at all for a coherent Age. */
    private val stancesPerShot = unsettled

    /** Which shot is showing, counting from the first. Also what the panel's banding is drawn from. */
    val number: Int get() = (sinceOpened() / nanosPerShot).toInt()

    fun showing(): Shot {
        val shot = number
        // The distance and the height are held across a run of shots where the angle moves every one, so
        // two shots in a row are the same look from a little further round. The panel is interlaced out of
        // consecutive shots ([PanelDistortion]) and only reads as one picture combed if they are related:
        // a view drawn freely each time would put two unrelated pictures in alternating rows.
        val stance = (shot * stancesPerShot).toInt()
        return Shot(
            turns = (paced(shot) + rolled(shot, TURN_SALT) * jitter) % 1.0f,
            closeness = rolled(stance, CLOSENESS_SALT),
            loft = rolled(stance, LOFT_SALT),
        )
    }

    /**
     * How far round the pace alone has carried, `0..1`.
     *
     * Held at the shot's own moment where the panel is stepped, which is the whole of what a frame rate is
     * here. A pace that went on running between cuts made every frame a new render, and the panel read as
     * smooth motion with jumps in it rather than as a picture short of frames.
     */
    private fun paced(shot: Int): Float {
        val moment = if (stepped) shot.toLong() * nanosPerShot else sinceOpened()
        return (moment / NANOS_PER_SECOND * TURNS_PER_SECOND) % 1.0f
    }

    private fun sinceOpened(): Long = System.nanoTime() - openedAt

    /** `0..1` from [shot] and [salt], mixed with the Age's seed. */
    private fun rolled(shot: Int, salt: Long): Float =
        unitFloat(mix64(seed xor salt xor (shot.toLong() * SHOT_STRIDE)))

    companion object {
        /** Either side of the tenth of a second Riven's panel ran at, which is the shape of the ramp. */
        private const val BRIEFEST_SHOT = 80_000_000.0f
        private const val LONGEST_SHOT = 130_000_000.0f

        private const val NANOS_PER_SECOND = 1_000_000_000.0f

        /** The look every Age's panel takes whatever it is like: a full turn in about fourteen seconds. */
        private const val TURNS_PER_SECOND = 0.07f

        /** How far a shot is thrown off the pace. A whole turn is anywhere at all, and is the worst Age. */
        private const val MOST_JITTER = 1.0f

        /** Splitmix64's, written signed because Kotlin has no unsigned literal that is a `const val`. */
        private const val SHOT_STRIDE = -0x61C8_8646_80B5_83EBL

        private const val TURN_SALT = 0x51ED_2701L
        private const val CLOSENESS_SALT = 0x2D3F_6A19L
        private const val LOFT_SALT = 0x7A41_C0DDL
    }
}
