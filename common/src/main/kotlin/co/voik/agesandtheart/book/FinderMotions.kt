package co.voik.agesandtheart.book

/**
 * A movement of a book finder's machinery: steps one after another, each lasting its ticks. The server needs only how
 * long the whole lasts ([ticks]); the client asks how far through each step it is ([through]) to pose the parts.
 */
class Motion<Step : Enum<Step>>(private val steps: List<Pair<Step, Int>>) {

    val ticks: Int = steps.sumOf { it.second }

    /** How far through [step] the motion is [elapsed] ticks in: 0 before it starts, 1 once it is done. */
    fun through(step: Step, elapsed: Float): Float {
        var startsAt = 0
        for ((each, lasts) in steps) {
            if (each == step) return ((elapsed - startsAt) / lasts).coerceIn(0.0f, 1.0f)
            startsAt += lasts
        }
        error("$step is not a step of this motion")
    }

    /** The step under way [elapsed] ticks in, or null before the motion starts or once it is done. */
    fun stepAt(elapsed: Float): Step? {
        if (elapsed < 0.0f) return null
        var endsAt = 0
        for ((step, lasts) in steps) {
            endsAt += lasts
            if (elapsed < endsAt) return step
        }
        return null
    }

    /** Whether [step] has begun [elapsed] ticks in. */
    fun hasBegun(step: Step, elapsed: Float): Boolean = through(step, elapsed) > 0.0f
}

/**
 * The finder's motions (Jonah, 2026-11-18). Each is played forward by one task and backward by its opposite, so a book
 * put away retraces exactly how one was brought: [FOLD] before setting out and, backward, on coming home; [PULL] at a
 * shelf, and backward to push a book in; [DELIVER] onto the lectern, and backward to take a book off it. The book is
 * brought shut, and opened and shut by players as a lectern's is.
 *
 * The finder has one extractor: rails that slide across the frame, a rotator that rides them up and down and turns,
 * and the rotator's two paddles, the only part that goes into a shelf. The rotator stands at the top while it has no
 * book, and carries one in the middle, between the shelf's two rows, where it has room to turn.
 */
object FinderMotions {

    /** The book surface folds flat onto the lectern's arms, then the paddles come out of the shelf. */
    enum class Fold { SURFACE_DOWN, PADDLES_OUT }

    /**
     * The extractor slides across to the slot's column as the rotator rides from the top to its row, the paddles reach
     * into the slot either side of the book as far as they go and take hold of it, draw it out as far as they come, which
     * leaves it where it is laid on the lectern, and the extractor goes back to the middle. The book keeps a shelf's size
     * until it is on the lectern.
     */
    enum class Pull { TO_THE_SLOT, REACH_IN, GRIP, DRAW_OUT, TO_THE_MIDDLE }

    /**
     * Onto the lectern, the paddles out of the shelf only while they are needed: the rotator turns the book onto its
     * cover, the lectern rises to catch it, the paddles let go, the extractor shifts toward the gap between two shelved
     * books and the paddles tuck into the shelf, and the lectern brings the book down. The paddles come out again, the
     * rotator rights itself and rides up to the top, and they go back in; the book turns to lie as a lectern holds it and
     * grows to its own size, and the book surface rises. Backward, it takes a book off the lectern without
     * the book meeting the paddles.
     */
    enum class Deliver {
        TIP, LEVEL, LET_GO, SHIFT_ASIDE, TUCK_IN, LOWER_THE_BOOK,
        COME_OUT, RIGHT_ITSELF, RIDE_UP, GO_BACK_IN, TURN_THE_BOOK, SURFACE_UP,
    }

    val FOLD = Motion(listOf(Fold.SURFACE_DOWN to 10, Fold.PADDLES_OUT to 8))

    val PULL = Motion(
        listOf(Pull.TO_THE_SLOT to 8, Pull.REACH_IN to 8, Pull.GRIP to 4, Pull.DRAW_OUT to 12, Pull.TO_THE_MIDDLE to 8),
    )

    val DELIVER = Motion(
        listOf(
            Deliver.TIP to 8,
            Deliver.LEVEL to 8,
            Deliver.LET_GO to 4,
            Deliver.SHIFT_ASIDE to 4,
            Deliver.TUCK_IN to 8,
            Deliver.LOWER_THE_BOOK to 8,
            Deliver.COME_OUT to 6,
            Deliver.RIGHT_ITSELF to 8,
            Deliver.RIDE_UP to 6,
            Deliver.GO_BACK_IN to 6,
            Deliver.TURN_THE_BOOK to 10,
            Deliver.SURFACE_UP to 10,
        ),
    )
}
