package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.SkyBodies

/**
 * A book in, a world out (`the-world-model.md` §5). What landed fills the catalogues, steers the parameters,
 * weighs the populations and mints the features, the template lies underneath what the book spoke to, and every
 * disagreement between words is charged as incoherence. A pure function of (world, book, seed): an Age is rebuilt
 * from its recipe on every open.
 */
object Resolver {

    fun resolve(world: WorldFacts, book: Book, seed: Long): Resolution {
        // A ban is said of the whole Age rather than of any part of it, so it is set aside from what each part resolves.
        val (bans, landings) = book.landings.partition { it.banned.isNotEmpty() }
        val resolving = Resolving(world, landings, draw = seed xor saltOf(book.landings))
        resolving.flaws += book.chargedByTheReading
        resolving.flaws += incoherence(world, book.possible.filter { it.banned.isEmpty() })
        resolving.flaws += bannedButAskedFor(book.possible)
        val filled = Aspect.entries.associateWith { resolving.fill(it) }
        val resolved = with(resolving) { describedMembers(weighed(steer(cast(compose(filled))))) }
        val template = book.template?.let(AgeTemplate::named) ?: AgeTemplate.ORDINARY
        val spokenTo = resolving.said.map { it.aspect }.toSet()
        val composition = with(resolving) { counted(mintedFeatures(resolved)) }.laidOver(template.world(), spokenTo)
            .copy(banned = bans.flatMap { it.banned }.toSet())
        resolving.flaws += resolving.mintingsThatCannotHold()
        resolving.flaws += resolving.tidesWithNoMoon(composition)
        return Resolution(
            composition = composition,
            instability = Instability(resolving.flaws.distinctBy(Flaw::identity), pages = book.pagesLaid),
            cost = book.cost,
            template = template,
            dropped = book.unread,
        )
    }

    /** A seed-sized number for what was written, whatever order it was laid in, so silence varies with the book. */
    private fun saltOf(landings: List<Landing>): Long = landings.map(Landing::word).distinct().sorted()
        .fold(0L) { salt, word -> salt xor (word.hashCode().toLong() * Resolving.WORD_MIXER) }

    /** A pull asked of a moon in an Age with no moon: the tide cannot happen, and the book asked for one. */
    private fun Resolving.tidesWithNoMoon(composition: AgeComposition): List<Flaw> {
        if (!composition.optionsFor(Aspect.MOON).isTrue(SkyBodies.ABSENT)) return emptyList()
        val onTheMoon = speakingTo(Aspect.MOON)
        val removing = onTheMoon.filter { SkyBodies.ABSENT.name in it.settings }
        return onTheMoon.filter { SkyBodies.PULL.name in it.settings }.map { pulling ->
            flaw(Register.INCOHERENT, listOf(pulling) + removing, Aspect.MOON)
        }
    }
}
