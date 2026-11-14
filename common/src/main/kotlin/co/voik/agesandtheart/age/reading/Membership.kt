package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect

/**
 * What the reader asks the world about a value, so that it stays a pure function of the words, the pages and these
 * answers: whether a block or member is in a tag, what part a member belongs to, whether it clears a group's bars, and
 * whether it is in a world nobody asked for it in.
 */
interface Membership {
    /** Whether [value] is [keyOrTag], or in it where it is a `#tag`. */
    fun isIn(value: String, keyOrTag: String): Boolean

    /** Whether [member] is one of [part]'s. */
    fun isMemberOf(part: Aspect, member: String): Boolean

    /** Whether [member]'s tags clear [bars]. */
    fun clears(member: String, bars: Map<String, Double>): Boolean

    /** Whether [member] of [part] is there in a world that never named it, so naming it beside a description only describes it. */
    fun isThereByDefault(part: Aspect, member: String): Boolean

    /** Whether [member] of [part] does anything with [effect], as its code answers: a tempest holds no amount, an inferno no frequency. */
    fun doesAnythingWith(part: Aspect, member: String, effect: Effect): Boolean = true
}
