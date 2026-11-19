package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookFinderBlockEntity
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.FinderMotions
import co.voik.agesandtheart.book.FinderMotions.Deliver
import co.voik.agesandtheart.book.FinderMotions.Fold
import co.voik.agesandtheart.book.FinderMotions.Pull
import co.voik.agesandtheart.book.FrameTask
import co.voik.agesandtheart.book.Motion
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * What a book finder sounds like at work, worked out on each client from the task it was told and how far through it
 * is, as the renderer draws it: the working loop and the click-clack of its frames under it while the carriage travels
 * ([TravelLoop]), and the extractor's or the lectern's actuator for as long as its motions run ([ActuatorSound]), played
 * backward as forward.
 */
object FinderSounds {

    /**
     * What each finder was last heard doing: the actuator that was running, and its travelling loops. Kept per finder for
     * as long as the client holds it, and forgotten with it, so the loops, which hold the finder, are held weakly.
     */
    private class Heard(var actuator: SoundEvent?, var loops: List<WeakReference<TravelLoop>>)

    private val heard = WeakHashMap<BookFinderBlockEntity, Heard>()

    /** Called each client tick for each finder the client holds. */
    fun tick(finder: BookFinderBlockEntity) {
        val was = heard.getOrPut(finder) { Heard(actuator = null, loops = emptyList()) }
        val isTravelling = finder.task.travels && isUnderway(finder, elapsedOf(finder))

        val areLoopsGone = was.loops.all { it.get()?.isStopped ?: true }
        if (isTravelling && areLoopsGone) {
            val started = TRAVEL_LOOPS.map { TravelLoop(finder, it).also(Minecraft.getInstance().soundManager::play) }
            was.loops = started.map(::WeakReference)
        }

        val actuator = actuatorOf(finder)
        if (actuator != null && actuator != was.actuator) Minecraft.getInstance().soundManager.play(ActuatorSound(finder, actuator))
        was.actuator = actuator
    }

    private fun elapsedOf(finder: BookFinderBlockEntity): Double =
        finder.level?.let { (it.gameTime - finder.startedAt).toDouble() } ?: -1.0

    private fun isUnderway(finder: BookFinderBlockEntity, elapsed: Double): Boolean =
        elapsed >= 0 && elapsed < finder.ticksOf(finder.task)

    /** The actuator the finder is running now, if any. */
    private fun actuatorOf(finder: BookFinderBlockEntity): SoundEvent? {
        val elapsed = elapsedOf(finder)
        if (!isUnderway(finder, elapsed)) return null
        return stepOf(finder.task, elapsed)?.let(::soundOf)
    }

    /** The step of the motion [task] plays, forward or backward, [elapsed] ticks in. */
    private fun stepOf(task: FrameTask, elapsed: Double): Enum<*>? {
        fun <Step : Enum<Step>> forward(motion: Motion<Step>) = motion.stepAt(elapsed.toFloat())
        fun <Step : Enum<Step>> backward(motion: Motion<Step>) = motion.stepAt(motion.ticks - elapsed.toFloat())
        return when (task) {
            FrameTask.FOLDING -> forward(FinderMotions.FOLD)
            FrameTask.UNFOLDING -> backward(FinderMotions.FOLD)
            FrameTask.PULLING -> forward(FinderMotions.PULL)
            FrameTask.PUSHING -> backward(FinderMotions.PULL)
            FrameTask.DELIVERING -> forward(FinderMotions.DELIVER)
            FrameTask.STOWING -> backward(FinderMotions.DELIVER)
            FrameTask.RESTING, FrameTask.GOING_FOR, FrameTask.BRINGING, FrameTask.PRESENTING, FrameTask.CLOSING,
            FrameTask.TAKING_BACK, FrameTask.COMING_BACK, FrameTask.STEPPING_ASIDE, FrameTask.STANDING_ASIDE,
            FrameTask.STEPPING_BACK,
            -> null
        }
    }

    /** The extractor's actuator for its motions, the lectern's for the book surface's, and nothing for a pause. */
    private fun soundOf(step: Enum<*>): SoundEvent? = when (step) {
        is Fold -> when (step) {
            Fold.SURFACE_DOWN -> BookFinders.SURFACE_SOUND
            Fold.PADDLES_OUT -> BookFinders.EXTRACTOR_SOUND
        }
        is Pull -> when (step) {
            Pull.TO_THE_SLOT, Pull.REACH_IN, Pull.DRAW_OUT, Pull.TO_THE_MIDDLE -> BookFinders.EXTRACTOR_SOUND
            Pull.GRIP -> null
        }
        is Deliver -> when (step) {
            Deliver.TIP, Deliver.SHIFT_ASIDE, Deliver.TUCK_IN, Deliver.COME_OUT, Deliver.RIGHT_ITSELF, Deliver.RIDE_UP,
            Deliver.GO_BACK_IN,
            -> BookFinders.EXTRACTOR_SOUND
            Deliver.LEVEL, Deliver.LOWER_THE_BOOK, Deliver.SURFACE_UP -> BookFinders.SURFACE_SOUND
            // The book's own turn is the one step with no machinery.
            Deliver.LET_GO, Deliver.TURN_THE_BOOK -> null
        }
        else -> null
    }

    private val TRAVEL_LOOPS = listOf(BookFinders.TRAVEL_SOUND, BookFinders.JUNCTION_SOUND)

    /** The carriage rolling over its frames, following it, for as long as its task travels. */
    class TravelLoop(finder: BookFinderBlockEntity, sound: SoundEvent) : CarriageSound(finder, sound) {

        init {
            looping = true
        }

        override fun tick() {
            val isStillTravelling = !finder.isRemoved && finder.task.travels && isUnderway(finder, elapsedOf(finder))
            if (isStillTravelling) follow() else stop()
        }
    }

    /**
     * One actuator, the extractor's or the lectern's, from the step its run of motions begins until the finder goes on to
     * a pause or to the other's, when it fades out over [FADE_TICKS].
     */
    class ActuatorSound(finder: BookFinderBlockEntity, private val actuator: SoundEvent) : CarriageSound(finder, actuator) {

        private var isFading = false

        override fun tick() {
            if (finder.isRemoved) return stop()
            isFading = isFading || actuatorOf(finder) != actuator
            if (isFading) volume -= 1.0f / FADE_TICKS
            if (volume <= 0.0f) stop() else follow()
        }
    }

    /** A sound that rides on [finder]'s carriage. */
    abstract class CarriageSound(protected val finder: BookFinderBlockEntity, sound: SoundEvent) :
        AbstractTickableSoundInstance(sound, SoundSource.BLOCKS, RandomSource.create()) {

        init {
            delay = 0
            follow()
        }

        protected fun follow() {
            val where = finder.carriageInTheWorld(finder.carriageAt(elapsedOf(finder).coerceAtLeast(0.0)))
            x = where.x
            y = where.y
            z = where.z
        }
    }

    private const val FADE_TICKS = 2
}
