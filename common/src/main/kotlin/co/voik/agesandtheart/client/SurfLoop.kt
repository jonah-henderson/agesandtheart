package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.PalmBeach
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3

/**
 * The sea breaking on a palm beach, as one long recording looping for as long as there is shore in reach —
 * see [Surf], which says where the shore is each tick.
 *
 * **On the nearest shore, and following it.** The sound is positional so it comes from the water's edge, and
 * it eases towards wherever the nearest shore now is rather than jumping, so walking along a beach carries the
 * sea along beside you.
 *
 * **Heard as far as the waves are drawn.** A volume over one is how a sound reaches further than sixteen
 * blocks — its range is sixteen times it, and its loudness stays capped at full — so [AS_FAR_AS_THE_FOAM]
 * puts the edge of hearing where the foam stops.
 *
 * **The range is fixed the moment it starts**, from the volume it starts at, and only its loudness moves after
 * that. It first started silent to fade in, which fixed its range at sixteen blocks for good and left it
 * out of earshot from most of a beach (Jonah, walked 2026-10-01: "inaudible"). So it starts at
 * [AS_FAR_AS_THE_FOAM] for the range, drops to silence on its first tick, and fades in from there — to
 * [FULL], which is as loud as a sound plays whatever its volume says.
 *
 * **Fades rather than cuts**, in when a shore comes into reach and out when the last one goes, and stops
 * itself once silent; [Surf] starts a new one the next time there is shore.
 */
class SurfLoop(at: Vec3) : AbstractTickableSoundInstance(PalmBeach.SURF, SoundSource.AMBIENT, RandomSource.create()) {

    /** Where the nearest shore is now, or null where there is none in reach. */
    var toward: Vec3? = at

    init {
        looping = true
        delay = 0
        volume = AS_FAR_AS_THE_FOAM
        x = at.x
        y = at.y
        z = at.z
    }

    private var started = false

    override fun tick() {
        if (!started) {
            started = true
            volume = SILENT
        }
        val goal = toward
        volume = Mth.approach(volume, if (goal == null) SILENT else FULL, FADE_A_TICK)
        if (goal != null) {
            x = Mth.lerp(FOLLOWS, x, goal.x)
            y = Mth.lerp(FOLLOWS, y, goal.y)
            z = Mth.lerp(FOLLOWS, z, goal.z)
        } else if (volume <= SILENT) {
            stop()
        }
    }

    private companion object {
        /** Forty blocks of range, which is `Surf`'s reach: sixteen blocks a unit of volume. */
        const val AS_FAR_AS_THE_FOAM = 2.5f
        const val SILENT = 0.0f
        const val FULL = 1.0f

        /** About three seconds to fade all the way in or out. */
        const val FADE_A_TICK = FULL / 60

        /** How much of the way to the nearest shore the sound moves each tick. */
        const val FOLLOWS = 0.1
    }
}
