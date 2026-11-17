package co.voik.agesandtheart.imaging

/**
 * How a slide carousel's machinery moves, on the client: the selector arm and its hook catching up with whichever slot
 * the carousel says is at the front, one deliberate step at a time.
 *
 * At rest the arm is either **down with the hook up**, holding the front slot's slide raised before the lens, or **up
 * with the hook down**, where the front slot is empty. To reach another slot it puts the slide back (the hook turns
 * down), rises clear, the disk turns a slot, and where the new slot holds a slide it descends and lifts it. A carousel
 * turned several times at once turns slot by slot the short way round; a slide taken out from under the hook leaves the
 * arm to rise empty.
 *
 * Ticked on the client only, and nothing here is saved or sent: every client works it out from the carousel's own front
 * slot and slides.
 */
class CarouselMotion {

    /** One step of the machinery, and how many ticks it takes. */
    enum class Phase(val ticks: Int) { PUTTING_BACK(28), RISING(24), TURNING(18), DESCENDING(24), LIFTING(28) }

    /** Where the machinery stands this frame: what the renderer draws. */
    data class Pose(
        /** The slot the disk has turned to, fractional while it turns. */
        val diskSlot: Float,
        /** Nought with the arm raised, one lowered onto the slot; a little past either end as it overextends. */
        val armDrop: Float,
        /** Nought with the hook down in the slot, one turned up holding the slide before the lens; it never runs past either. */
        val hookUp: Float,
        /** The slot whose slide the hook holds, or is about to take: the one at the arm. */
        val atTheArm: Int,
        /** Whether the slide at the arm is up before the lens and the machinery still, which is when light goes through it. */
        val isReady: Boolean,
    )

    private var isStarted = false
    private var shownFront = 0
    private var armDown = false
    private var hookUp = false
    private var phase: Phase? = null
    private var ticksIn = 0
    private var step = 0

    /** One tick on, toward [front], with [hasSlideIn] saying which slots hold one and [lastTurn] which way it last turned. */
    fun tick(front: Int, hasSlideIn: (Int) -> Boolean, lastTurn: Int) {
        if (!isStarted) {
            settleAt(front, hasSlideIn(front))
            return
        }
        val current = phase
        if (current != null) {
            ticksIn++
            if (ticksIn < current.ticks) return
            finish(current)
        }
        start(next(front, hasSlideIn, lastTurn))
    }

    /** The pose [partialTick] of the way into the next tick. */
    fun poseAt(partialTick: Float, front: Int, hasSlideIn: (Int) -> Boolean): Pose {
        if (!isStarted) return restingAt(front, hasSlideIn(front))
        val current = phase
        val progress = if (current == null) 1.0f else ((ticksIn + partialTick) / current.ticks).coerceIn(0.0f, 1.0f)
        // The disk turns at an even pace from start to stop, as a geared wheel does.
        val diskSlot = if (current == Phase.TURNING) shownFront + step * progress else shownFront.toFloat()
        val armDrop = when (current) {
            Phase.RISING -> 1.0f - overextended(progress)
            Phase.DESCENDING -> overextended(progress)
            else -> if (armDown) 1.0f else 0.0f
        }
        val hook = when (current) {
            Phase.PUTTING_BACK -> 1.0f - progress
            Phase.LIFTING -> progress
            else -> if (hookUp) 1.0f else 0.0f
        }
        val isReady = current == null && armDown && hookUp && shownFront == front && hasSlideIn(front)
        return Pose(diskSlot, armDrop, hook, shownFront, isReady)
    }

    private fun settleAt(front: Int, hasASlide: Boolean) {
        isStarted = true
        shownFront = front
        armDown = hasASlide
        hookUp = hasASlide
    }

    private fun restingAt(front: Int, hasASlide: Boolean): Pose {
        val held = if (hasASlide) 1.0f else 0.0f
        return Pose(front.toFloat(), held, held, front, hasASlide)
    }

    private fun next(front: Int, hasSlideIn: (Int) -> Boolean, lastTurn: Int): Phase? {
        val wantsToTurn = shownFront != front
        val wantsTheSlideUp = !wantsToTurn && hasSlideIn(front)
        return when {
            wantsTheSlideUp && !armDown -> Phase.DESCENDING
            wantsTheSlideUp && !hookUp -> Phase.LIFTING
            wantsTheSlideUp -> null
            hookUp -> Phase.PUTTING_BACK
            armDown -> Phase.RISING
            wantsToTurn -> Phase.TURNING.also { step = shortestStep(front, lastTurn) }
            else -> null
        }
    }

    private fun start(next: Phase?) {
        phase = next
        ticksIn = 0
    }

    private fun finish(done: Phase) {
        when (done) {
            Phase.PUTTING_BACK -> hookUp = false
            Phase.RISING -> armDown = false
            Phase.TURNING -> shownFront = Math.floorMod(shownFront + step, SlideCarouselBlockEntity.SLIDES)
            Phase.DESCENDING -> armDown = true
            Phase.LIFTING -> hookUp = true
        }
    }

    /** One slot toward [front] the short way round, or [lastTurn]'s way where both ways are as long. */
    private fun shortestStep(front: Int, lastTurn: Int): Int {
        val slides = SlideCarouselBlockEntity.SLIDES
        val ahead = Math.floorMod(front - shownFront, slides)
        return when {
            ahead * 2 == slides -> if (lastTurn < 0) -1 else 1
            ahead * 2 < slides -> 1
            else -> -1
        }
    }

    companion object {
        /** How far past its stop the arm runs, as a share of its travel, and where in the step it reaches it. */
        private const val OVEREXTENDS = 0.05f
        private const val FURTHEST_AT = 0.85f

        /**
         * Nought to one as a machine moves: at an even pace a little past one, then straight back to it, with no easing
         * at either end. For the arm; the hook and the disk run straight to their stops.
         */
        fun overextended(progress: Float): Float {
            val furthest = 1.0f + OVEREXTENDS
            return if (progress < FURTHEST_AT) {
                progress / FURTHEST_AT * furthest
            } else {
                furthest - OVEREXTENDS * (progress - FURTHEST_AT) / (1.0f - FURTHEST_AT)
            }
        }
    }
}
