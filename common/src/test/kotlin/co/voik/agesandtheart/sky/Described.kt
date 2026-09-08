package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.aspect.AgeParts
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options

/**
 * An Age described by hand — what a composition would answer, without needing one.
 *
 * The sky is drawn by pure functions, so the checks work in plain integers and options; this is the
 * smallest thing that satisfies [AgeParts] for them.
 */
internal class Described(
    private val options: Map<Aspect, Options> = emptyMap(),
    private val cast: Map<Aspect, Int> = emptyMap(),
    override val roofedByItsRock: Boolean = false,
) : AgeParts {
    override fun optionsFor(aspect: Aspect, member: Int): Options = options[aspect] ?: Options.NONE
    override fun membersIn(aspect: Aspect): Int = cast[aspect] ?: 0
}

/** The two aspects every sky question is asked of, as the parts a hand-described Age would answer with. */
internal fun described(sky: Options, sun: Options, roofedByItsRock: Boolean = false) =
    Described(mapOf(Aspect.SKY to sky, Aspect.SUN to sun), roofedByItsRock = roofedByItsRock)
