package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.aspect.Aspect

/**
 * One thing a meaning does once it has landed (`the-world-model.md` §3). A meaning is usually one of these, and
 * occasionally several that only mean anything together — an ice halo is a bow's colour, rain, frequency and size.
 */
sealed interface Effect {

    /**
     * Whether this narrows the world — settles a value, chooses or strikes a member, bars or mints — as opposed to
     * leaning, bending or scaling it. Only what narrows is priced.
     */
    val narrows: Boolean

    /** A parameter settled to [value]: a span, an option, a block or a `#tag` of blocks. */
    data class Sets(val parameter: ParameterKey, val value: String) : Effect {
        override val narrows get() = true
    }

    /** A ranged parameter leaned toward [value] without any of it being taken away. */
    data class Bends(val parameter: ParameterKey, val value: String) : Effect {
        override val narrows get() = false
    }

    /** One member of [aspect] chosen outright, by its key. */
    data class Chooses(val aspect: Aspect, val member: String) : Effect {
        override val narrows get() = true
    }

    /** Members put into [aspect]'s pool by name, past what a broad word may reach. */
    data class Admits(val aspect: Aspect, val members: Set<String>) : Effect {
        override val narrows get() = false
    }

    /** Members taken out of [aspect], by key or by `#tag`. */
    data class Excludes(val aspect: Aspect, val members: Set<String>) : Effect {
        override val narrows get() = true
    }

    /** A bar per tag that [aspect]'s members must clear, signed as `Bars` reads one. */
    data class Restricts(val aspect: Aspect, val bars: Map<String, Double>) : Effect {
        override val narrows get() = true
    }

    /** The draw in [aspect] leaned toward or away from members, by key or by `#tag`. */
    data class Leans(val aspect: Aspect, val weights: Map<String, Double>) : Effect {
        override val narrows get() = false
    }

    /** The same lean on every part of the world it likes something in. */
    data class LeansEverywhere(val weights: Map<String, Double>) : Effect {
        override val narrows get() = false
    }

    /**
     * A feature minted from [pattern], made of what the page is taken with, or of [unstated] where nothing is —
     * and only of something that flows where [flows].
     */
    data class Mints(val pattern: String, val unstated: String?, val flows: Boolean) : Effect {
        override val narrows get() = true
    }

    /** The world the book starts from, by its key. */
    data class Template(val name: String) : Effect {
        override val narrows get() = true
    }

    /** What a minted feature is made of, taken from a substance laid before it: `gold_block obelisks`. */
    data class MadeOf(val substance: String) : Effect {
        override val narrows get() = false
    }

    /** How much of a member there is, as a multiple of what there would be — `teeming` is four. */
    data class Abundance(val rung: Double) : Effect {
        override val narrows get() = false
    }
}

/**
 * A parameter's name, and the part of the world it is fenced to where it names one — `sun.size` is the sun's,
 * a plain `size` everything with a size.
 */
data class ParameterKey(val name: String, val fencedTo: Aspect? = null) {
    /** How a word file spells it. */
    val spelled: String get() = fencedTo?.let { "${it.page}$FENCE_MARK$name" } ?: name

    /** Whether this may land on [aspect]: it is fenced there, or fenced nowhere and [aspect] owns the name. */
    fun landsOn(aspect: Aspect): Boolean = fencedTo?.let { it == aspect } ?: aspect.ownsParameterNamed(name)

    companion object {
        private const val FENCE_MARK = '.'

        /** The parameter [spelled] names, fenced where it starts with an aspect's page and a dot. */
        fun read(spelled: String): ParameterKey {
            val page = spelled.substringBefore(FENCE_MARK, missingDelimiterValue = "")
            val fence = Aspect.byPage(page)
            return if (fence == null) ParameterKey(spelled) else ParameterKey(spelled.substringAfter(FENCE_MARK), fence)
        }
    }
}
