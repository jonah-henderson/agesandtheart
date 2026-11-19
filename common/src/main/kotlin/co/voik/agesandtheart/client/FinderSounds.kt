package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookFinderBlockEntity
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.FinderMotions
import co.voik.agesandtheart.book.FinderMotions.Deliver
import co.voik.agesandtheart.book.FinderMotions.Fold
import co.voik.agesandtheart.book.FinderMotions.Pull
import co.voik.agesandtheart.book.FramePath
import co.voik.agesandtheart.book.FrameTask
import co.voik.agesandtheart.book.Motion
import co.voik.agesandtheart.book.WallCell
import co.voik.agesandtheart.book.WallPoint
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * What a book finder sounds like at work, worked out on each client from the task it was told and how far through it
 * is, as the renderer draws it: a loop while the carriage travels ([TravelLoop]), a click-clack each time it crosses from
 * one frame onto the next, and an actuator as each motion of the extractor or of the lectern begins, played backward as
 * forward.
 */
object FinderSounds {

    /**
     * What each finder was last heard doing: the frame its carriage was over, the step of the motion it was in, and its
     * travelling loop. Kept per finder for as long as the client holds it, and forgotten with it, so the loop, which
     * holds the finder, is held weakly.
     */
    private class Heard(var cell: WallCell?, var step: Enum<*>?, var loop: WeakReference<TravelLoop>?)

    private val heard = WeakHashMap<BookFinderBlockEntity, Heard>()

    /** Called each client tick for each finder the client holds. */
    fun tick(level: Level, finder: BookFinderBlockEntity) {
        val was = heard.getOrPut(finder) { Heard(cell = null, step = null, loop = null) }
        val elapsed = (level.gameTime - finder.startedAt).toDouble()
        val isUnderway = elapsed >= 0 && elapsed < finder.ticksOf(finder.task)
        val isTravelling = finder.task.travels && isUnderway
        val at = finder.carriageAt(elapsed)

        val cell = FramePath.cellUnder(at)
        if (isTravelling && was.cell != null && cell != was.cell) play(level, finder, BookFinders.JUNCTION_SOUND, at)
        was.cell = cell

        val loop = was.loop?.get()
        if (isTravelling && (loop == null || loop.isStopped)) {
            val started = TravelLoop(finder).also { Minecraft.getInstance().soundManager.play(it) }
            was.loop = WeakReference(started)
        }

        val step = if (isUnderway) stepOf(finder.task, elapsed) else null
        if (step != null && step != was.step) soundOf(step)?.let { play(level, finder, it, at) }
        was.step = step
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

    private fun play(level: Level, finder: BookFinderBlockEntity, sound: SoundEvent, at: WallPoint) {
        val where = finder.carriageInTheWorld(at)
        level.playLocalSound(where.x, where.y, where.z, sound, SoundSource.BLOCKS, 1.0f, 1.0f, false)
    }

    /** The carriage rolling over its frames, following it, for as long as its task travels. */
    class TravelLoop(private val finder: BookFinderBlockEntity) :
        AbstractTickableSoundInstance(BookFinders.TRAVEL_SOUND, SoundSource.BLOCKS, RandomSource.create()) {

        init {
            looping = true
            delay = 0
            follow()
        }

        override fun tick() {
            val level = finder.level
            val elapsed = level?.let { (it.gameTime - finder.startedAt).toDouble() } ?: -1.0
            val isStillTravelling = !finder.isRemoved && finder.task.travels && elapsed >= 0 && elapsed < finder.ticksOf(finder.task)
            if (isStillTravelling) follow() else stop()
        }

        private fun follow() {
            val elapsed = finder.level?.let { (it.gameTime - finder.startedAt).toDouble() } ?: 0.0
            val where = finder.carriageInTheWorld(finder.carriageAt(elapsed))
            x = where.x
            y = where.y
            z = where.z
        }
    }
}
