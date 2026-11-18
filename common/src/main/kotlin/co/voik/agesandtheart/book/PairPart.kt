package co.voik.agesandtheart.book

import net.minecraft.util.StringRepresentable

/**
 * One of the two blocks of something placed two at a time, as a bed or a door is: a large book wheel, a carousel, a
 * book lift's frame. The first — the left, as its front is seen, or the lower — is the one that holds what it
 * holds; the second is the rest of it.
 */
enum class PairPart(private val key: String, val isFirst: Boolean) : StringRepresentable {
    LEFT("left", true),
    RIGHT("right", false),
    LOWER("lower", true),
    UPPER("upper", false),
    ;

    override fun getSerializedName(): String = key

    /** Which of the books side by side on a wheel's tray this part holds: the first part's is the first. */
    val along: Int get() = if (isFirst) 0 else 1
}
