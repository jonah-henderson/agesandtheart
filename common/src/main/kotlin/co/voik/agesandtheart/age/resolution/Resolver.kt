package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.Spread
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Terrain
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * A book in, a world out (`the-world-model.md` §5). What landed fills the catalogues, steers the parameters,
 * weighs the populations and mints the features, the template lies underneath what the book spoke to, and every
 * disagreement between words is charged as incoherence. A pure function of (world, book, seed): an Age is rebuilt
 * from its recipe on every open.
 */
object Resolver {

    fun resolve(world: WorldFacts, book: Book, seed: Long): Resolution {
        // A ban or a link-in is said of the whole Age rather than of any part of it, so it is set aside from what each
        // part resolves.
        val (ofTheWholeAge, landings) = book.landings.partition { it.banned.isNotEmpty() || it.linksIn != null }
        val resolving = Resolving(world, landings, draw = seed xor saltOf(book.landings))
        resolving.flaws += book.chargedByTheReading
        resolving.flaws += incoherence(world, book.possible.filter { it.banned.isEmpty() && it.linksIn == null })
        resolving.flaws += bannedButAskedFor(book.possible)
        val filled = Aspect.entries.associateWith { resolving.fill(it) }
        val resolved = with(resolving) { describedMembers(weighed(steer(cast(compose(filled))))) }
        val template = book.template?.let(AgeTemplate::named) ?: AgeTemplate.ORDINARY
        val spokenTo = resolving.said.map { it.aspect }.toSet() + listOfNotNull(Aspect.TERRAIN.takeIf { resolving.mixesUnaskedLand(template) })
        val composition = with(resolving) { reconciled(counted(mintedFeatures(resolved))) }.laidOver(template.world(), spokenTo)
            .copy(banned = ofTheWholeAge.flatMap { it.banned }.toSet(), linkIn = ofTheWholeAge.firstNotNullOfOrNull { it.linksIn })
        resolving.flaws += resolving.mintingsThatCannotHold()
        resolving.flaws += resolving.tidesWithNoMoon(composition)
        resolving.flaws += resolving.weatherUnderARoof(composition)
        return Resolution(
            composition = composition,
            instability = Instability(resolving.flaws.distinctBy(Flaw::identity), pages = book.pagesLaid),
            cost = book.cost,
            template = template,
            dropped = book.unread,
            cameToNothing = resolving.cameToNothing(),
            divided = resolving.divisions(),
        )
    }

    /**
     * Landforms that `and` joined, or that one word brought together, meeting as one country ([Seam.MERGED]) wherever
     * two with ground to ease into each other meet. A territory that cannot be eased keeps a drawn seam round it
     * ([Spread.uneased], drawn with the rest in [AgeComposition.seamed]). Rival words keep the seam chance draws.
     */
    private fun Resolving.reconciled(composition: AgeComposition): AgeComposition {
        val divided = composition.membersIn(Aspect.TERRAIN) > 1
        val twoCanMeet = composition.terrains.count(Terrain::reconciles) >= 2
        val formAskedFor = composition.spreadOf(Aspect.TERRAIN).drawn != null
        if (!divided || !twoCanMeet || formAskedFor || wasContested(Aspect.TERRAIN)) return composition
        return composition.copy(spreads = composition.spreads.withSeam(Aspect.TERRAIN, Seam.MERGED))
    }

    /**
     * Whether an Age whose book said nothing of its land stands on a mixture of the ordinary country ([fill] drew it)
     * rather than its world's own rock. Only over the overworld's, half the time; the nether and the End keep theirs.
     */
    private fun Resolving.mixesUnaskedLand(template: AgeTemplate): Boolean {
        val saidNothingOfTheLand = speakingTo(Aspect.TERRAIN).isEmpty()
        val standsOnTheOverworld = template.ownRock == Terrain.OVERWORLD
        if (!saidNothingOfTheLand || !standsOnTheOverworld) return false
        return XoroshiroRandomSource(draw xor Resolving.UNASKED_LAND_SALT xor MIXTURE_SALT).nextDouble() < UNASKED_LAND_MIXED
    }

    private const val UNASKED_LAND_MIXED = 0.5
    private const val MIXTURE_SALT = 0x0B1E_7D3AL

    /** A seed-sized number for what was written, whatever order it was laid in, so silence varies with the book. */
    private fun saltOf(landings: List<Landing>): Long = landings.map(Landing::word).distinct().sorted()
        .fold(0L) { salt, word -> salt xor (word.hashCode().toLong() * Resolving.WORD_MIXER) }

    /**
     * Weather asked for where the Age is roofed, by a word or by its rock: it cannot fall, and the book asked for it.
     * An Age without a sun is not roofed, and keeps its weather.
     */
    private fun Resolving.weatherUnderARoof(composition: AgeComposition): List<Flaw> {
        if (!Sky.isRoofed(composition)) return emptyList()
        val needingWeather = Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0))
            .mapNotNull { claim -> Phenomenon.entries.firstOrNull { it.key == claim.value } }
            .filter(Phenomenon::needsWeather)
            .map(Phenomenon::key)
        fun asksForWeather(landing: Landing) = landing.effects.any { effect ->
            when (effect) {
                is Effect.Chooses -> effect.member in needingWeather
                is Effect.Admits -> effect.members.any { it in needingWeather }
                else -> false
            }
        }
        fun roofs(landing: Landing): Boolean {
            val sealsTheSky = landing.settings[Sky.SEALED.name] == true.toString()
            val rock = Terrain.entries.firstOrNull { it.key == landing.choice?.key }
            val choosesRockThatRoofs = landing.aspect == Aspect.TERRAIN && rock?.roofsTheWorld == true
            return sealsTheSky || choosesRockThatRoofs
        }
        val roofing = said.filter(::roofs)
        return speakingTo(Aspect.PHENOMENA).filter(::asksForWeather).map { asking ->
            flaw(Register.INCOHERENT, listOf(asking) + roofing, Aspect.SKY)
        }
    }

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
