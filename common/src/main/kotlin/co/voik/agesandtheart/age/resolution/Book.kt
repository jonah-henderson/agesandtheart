package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable

/** A book as resolution is handed it: what landed where, and what the reading already knew about it. */
data class Book(
    /** What landed, once the reading drew it. */
    val landings: List<Landing>,
    /** Everything that could have landed, before the draw — what incoherence is charged from. */
    val possible: List<Landing>,
    /** The world the book starts from, by its key, or null for the ordinary one. */
    val template: String?,
    /** The ink it cost to write. */
    val cost: Int,
    /** How many pages were laid, which caps how far its instability may reach. */
    val pagesLaid: Int,
    /** What the reading charged: pages laid on the Age with nowhere to land, and structure that bound nothing. */
    val chargedByTheReading: List<Flaw> = emptyList(),
    /** Pages that are no word at all. */
    val unread: List<String> = emptyList(),
)

/** What a book turned into: the world it describes, what it cost to say, and where it argued with itself. */
data class Resolution(
    val composition: AgeComposition,
    val instability: Instability,
    val cost: Int,
    /** The world the book started from. What it supplied is already merged into [composition]. */
    val template: AgeTemplate = AgeTemplate.ORDINARY,
    /** Pages that are no word at all. */
    val dropped: List<String> = emptyList(),
    /** Where what landed came to nothing — overruled, set aside for want of room, or honoured by nothing. */
    val cameToNothing: Set<LandingAt> = emptySet(),
    /** Which share of a part divided between rival words each landing went to, where they did not ask for it with `and`. */
    val divided: Map<LandingAt, Int> = emptyMap(),
)

/** What resolution asks of the world beyond the book: the members each part holds, and what each is like. */
interface WorldFacts {
    fun tagsOf(member: Taggable): Map<String, Double>

    /** Every member [aspect] has, a vague word's reach and a writer's naming alike. */
    fun candidatesFor(aspect: Aspect): List<Taggable>

    /** The members a vague word may land on, and an aspect nobody spoke to is drawn from. */
    fun availableToBroadWordsIn(aspect: Aspect): List<Taggable>

    /** How willingly the Art reaches for [member] when nothing asked for it. */
    fun readinessOf(member: Taggable): Double

    /** Whether [member] is in the world anyway, so naming it asks for more of it rather than for it. */
    fun isPresentAnyway(member: Taggable): Boolean

    /** Whether anything in [aspect] would act on [parameter], rather than only declaring it. */
    fun turnsAParameter(aspect: Aspect, parameter: String): Boolean

    /** One block for a material asked for by tag in [parameter], drawn at [seed], or null where none answers. */
    fun drawnMaterial(query: String, parameter: String, seed: Long): String?

    /** Whether two tags mean opposite things. */
    fun areOpposed(first: String, second: String): Boolean
}
