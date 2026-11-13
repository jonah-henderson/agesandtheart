package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect

/**
 * What the reader asks the world about a value, so that it stays a pure function of the words, the pages and these
 * answers: whether a block or member is in a tag, what part a member belongs to, and whether it clears a group's bars.
 */
interface Membership {
    /** Whether [value] is [keyOrTag], or in it where it is a `#tag`. */
    fun isIn(value: String, keyOrTag: String): Boolean

    /** Whether [member] is one of [part]'s. */
    fun isMemberOf(part: Aspect, member: String): Boolean

    /** Whether [member]'s tags clear [bars]. */
    fun clears(member: String, bars: Map<String, Double>): Boolean
}
