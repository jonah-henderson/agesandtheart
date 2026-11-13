package co.voik.agesandtheart.age.readout

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Reading
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.DerivedWords
import co.voik.agesandtheart.age.words.Speech
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * **A book's readout: how things actually turned out**, said as an account of an Age — several sentences, each about
 * one thing or one part of the world, the way a chronicle describes a place rather than the way a writer laid its pages
 * (the transcription keeps those). Built on the server into [ReadoutClause]s, written as sentences by [ReadoutWriting]
 * wherever the book is read.
 *
 * - **Only what held is said**: a page whose every landing came to nothing in the resolution is left out.
 * - **Each thing is a clause**, what it took said of it. A thing naming members is the subject of what was said of
 *   them — "its spiders are large"; a group is the subject of the member it took; a formation that took a creature
 *   is what the creature comes in — "herds of wardens"; a minting is said as its shape, made of what was laid before it.
 * - **What `age` handed out is the Age's sentence**, with what the Age took itself: its template, the blocks it bans.
 * - **A dangling page is said where it landed**, as if written before the word it was drawn onto: a sheep drawn onto
 *   `herds` is "herds of sheep". The readout says what the Art made, not what was written.
 * - **An amount is said as how much there is of what took it**, never as a word of its own.
 */
object Readout {
    fun of(reading: Reading, drawn: Drawn, resolution: Resolution, words: Words): List<ReadoutClause> =
        Telling(reading, drawn, resolution, words).clauses()
}

private class Telling(
    private val reading: Reading,
    private val drawn: Drawn,
    private val resolution: Resolution,
    private val words: Words,
) {
    private val written = reading.pages.map { it.written }
    private val ages = reading.pages.filter { page -> words.word(page.written)?.defaultSense?.behaviour == AGE }.map { it.index }.toSet()

    fun clauses(): List<ReadoutClause> {
        val theAge = ofTheAge()
        val things = reading.phrases.sortedBy { it.head }.mapNotNull { phrase ->
            val head = words.word(written[phrase.head]) ?: return@mapNotNull null
            clauseOf(head, headPage = phrase.head, saidOf = phrase.head, phrase.part, phrase.polarity, phrase.sitedIn, phrase.everywhere)
        }
        val dangled = drawn.dangling.mapNotNull { dangled ->
            val taker = dangled.drawnOnto?.let(words::word) ?: return@mapNotNull null
            clauseOf(taker, headPage = null, saidOf = dangled.page, taker.defaultSense?.speaksFor, Polarity.ASSERTED, null, false)
        }
        return listOfNotNull(theAge) + joined(things + dangled)
    }

    // ----- What held -----

    private fun landedOf(saidOf: Int): List<Landing> = drawn.landed.filter { it.saidOf == saidOf }

    private fun held(landings: List<Landing>): Boolean = landings.any { it.at !in resolution.cameToNothing }

    /** The share of a divided part [landings] went to, where they went to one. */
    private fun shareOf(landings: List<Landing>): Int? = landings.firstNotNullOfOrNull { resolution.divided[it.at] }

    // ----- The Age -----

    private fun ofTheAge(): ReadoutClause? {
        if (ages.isEmpty()) return null
        val handed = drawn.landed.filter { it.saidOf in ages }
        val (bans, rest) = handed.partition { landing -> landing.effects.any { it is Effect.Bans } }
        val templated = reading.handedOut.filter { it.part == null && it.effects.any { effect -> effect is Effect.Template } }.map { it.from }
        val pages = (rest.filter { it.at !in resolution.cameToNothing }.map { it.page } + templated).distinct().sorted()
        val struck = bans.map { it.page }.distinct().sorted().mapNotNull { termOf(it)?.copy(polarity = Polarity.EXCEPT) }
        return ReadoutClause(about = null, terms = pages.mapNotNull { termOf(it) } + struck)
    }

    // ----- A thing -----

    /**
     * What was said of [head] — a phrase's thing at [headPage], or the word a dangling page was drawn onto, which has no
     * page of its own — with what landed said of it at [saidOf].
     */
    private fun clauseOf(
        head: Word,
        headPage: Int?,
        saidOf: Int,
        part: Aspect?,
        polarity: Polarity,
        sitedIn: Identifier?,
        everywhere: Boolean,
    ): ReadoutClause? {
        val about = part ?: return null
        val landed = landedOf(saidOf)
        val own = landed.filter { if (headPage != null) it.page == headPage else it.word == head.name || it.word == words.pageFor(head) }
        val ofOthers = (landed - own.toSet()).groupBy { it.page }.filterValues(::held)
        val isNarrowed = polarity != Polarity.ASSERTED
        val pages = if (isNarrowed && held(own)) narrowing(saidOf) else ofOthers.keys.sorted()
        if (pages.isEmpty() && !held(own)) return null
        val sense = head.defaultSense
        val amounts = pages.filter { page -> ofOthers[page].orEmpty().any { landing -> landing.effects.any { it is Effect.Abundance } } }
        val members = pages.filter { page -> offersAMember(page) && ofOthers[page].orEmpty().any { it.aspect == about && it.namesMembers } }
        val substances = pages.filter { page -> ofOthers[page].orEmpty().any { landing -> landing.effects.any { it is Effect.MadeOf } } }
        val described = pages - amounts.toSet() - members.toSet()
        val headTerm = term(head, own)?.copy(
            quantifier = amounts.firstOrNull()?.let { written[it] },
            polarity = polarity,
            share = shareOf(own),
        ) ?: return null
        val confinedTo = sitedIn?.let(::biomeTerm)
        val body = own.firstNotNullOfOrNull { (it.place.members as? Members.Body)?.index }
        fun termsOf(of: List<Int>) = of.mapNotNull { page -> termOf(page)?.copy(share = shareOf(ofOthers[page].orEmpty())) }

        val isThePartsPage = words.partPage(about)?.id == head.id
        val announces = own.any { landing -> landing.aspect == Aspect.PHENOMENA && landing.namesMembers }
        if (isThePartsPage && announces && pages.isEmpty()) {
            return ReadoutClause(Aspect.PHENOMENA, listOf(headTerm.copy(speech = Speech.NOUN)), confinedTo, everywhere)
        }
        if (isThePartsPage) {
            val emptied = own.any { landing -> landing.effects.any { it is Effect.Excludes && Effect.Excludes.EVERY_MEMBER in it.members } }
            val itself = listOf(headTerm.copy(polarity = Polarity.EXCEPT)).takeIf { emptied }.orEmpty()
            return ReadoutClause(about, termsOf((members + described).sorted()) + itself, confinedTo, everywhere, body)
        }
        val mints = sense?.means.orEmpty().any { it is Effect.Mints }
        if (mints) {
            val shape = ReadoutShape(headTerm, termsOf(substances), termsOf(described - substances.toSet()))
            return ReadoutClause(about, emptyList(), confinedTo, everywhere, body, shape)
        }
        val tookACreature = members.isNotEmpty()
        val isAFormation = sense?.acceptanceOf(CoreConcepts.MEMBER)?.only == ValueFilter.Anything
        if (tookACreature && isAFormation) {
            val collective = ReadoutShape(headTerm, qualities = termsOf(described))
            val subject = termOf(members.first()) ?: return null
            return ReadoutClause(about, emptyList(), confinedTo, everywhere, body, subject = subject, collective = collective)
        }
        val meansMembers = sense?.means.orEmpty().any { it is Effect.Chooses || it is Effect.Admits || it is Effect.Restricts }
        val offersItself = sense?.offers.orEmpty().any { it.concept == CoreConcepts.MEMBER }
        val namesMembers = tookACreature || meansMembers || offersItself || isNarrowed
        val hasSomethingSaidOfIt = described.isNotEmpty() || amounts.isNotEmpty()
        if (namesMembers && hasSomethingSaidOfIt) {
            return ReadoutClause(about, termsOf((members + described).sorted()), confinedTo, everywhere, body, subject = headTerm)
        }
        val ownTerm = listOf(headTerm).takeIf { held(own) || members.isEmpty() }.orEmpty()
        return ReadoutClause(about, termsOf((members + described).sorted()) + ownTerm, confinedTo, everywhere, body)
    }

    /** The pages a negated or `only` thing took, which narrowed it: said with it, since it lands as one claim. */
    private fun narrowing(head: Int): List<Int> = reading.phrases.firstOrNull { it.head == head }?.placements.orEmpty()
        .flatMap { listOf(it.from) + it.carrying }.filter { it != head }.distinct().sorted()

    /** Whether the word at [page] offers a member — a creature, an oak, a landform — rather than describing one. */
    private fun offersAMember(page: Int): Boolean =
        words.word(written[page])?.defaultSense?.offers.orEmpty().any { it.concept == CoreConcepts.MEMBER }

    private val Landing.aspect: Aspect get() = place.aspect

    private val Landing.namesMembers: Boolean get() = effects.any { it is Effect.Chooses || it is Effect.Admits }


    // ----- Terms -----

    /**
     * The term for the page at [page]: its word, said as the word that reshaped it or took it straight after it says it
     * where the language has that pairing — `north rising sun` is "rising in the north", by `prose.<namespace>.rising_north`,
     * and `north wind` "from the north" — and otherwise by vanilla's name where vanilla has one.
     */
    private fun termOf(page: Int): ReadoutTerm? {
        val word = words.word(written[page]) ?: return null
        val base = term(word, emptyList()) ?: return null
        val reshaper = reading.placements.filter { it.from == page }.flatMap { it.carrying }.firstOrNull { it != page }
        val takenStraightAfter = reading.placements.firstOrNull { it.from == page && it.at == page + 1 }?.at
        val pairedWith = reshaper ?: takenStraightAfter ?: return base
        val pairing = "prose.${word.id.namespace}.${written[pairedWith]}_${written[page]}"
        val plain = if (reshaper != null) "${written[page]} ${written[reshaper]}" else base.plain
        return base.copy(nameKey = pairing, plain = plain)
    }

    private fun term(word: Word, landed: List<Landing>): ReadoutTerm? = ReadoutTerm(
        word = word.id,
        speech = word.speech ?: Speech.ADJECTIVE,
        nameKey = nameKeyOf(word),
        plain = plainNameOf(word),
        share = shareOf(landed),
    )

    /** Vanilla's name for what a derived word is, where vanilla gives it one: a block's, a creature's, a biome's. */
    private fun nameKeyOf(word: Word): String? {
        val sense = word.defaultSense ?: return null
        val offersItself = sense.offers.any { it.concept == CoreConcepts.BLOCK && it.value == Value.Text(word.id.toString()) }
        return when {
            offersItself -> BuiltInRegistries.BLOCK.getOptional(word.id).map { it.descriptionId }.orElse(null)
            sense.kind == DerivedWords.CREATURE -> BuiltInRegistries.ENTITY_TYPE.getOptional(word.id).map { it.descriptionId }.orElse(null)
            sense.kind == DerivedWords.BIOME -> "biome.${word.id.namespace}.${word.id.path}"
            else -> null
        }
    }

    private fun plainNameOf(word: Word): String {
        val sense = word.defaultSense
        val isAPlacedFeature = sense?.kind == DerivedWords.FEATURE ||
            (word.id.namespace != MOD_NAMESPACE && sense?.offers.orEmpty().any { it.concept == CoreConcepts.MEMBER })
        return if (isAPlacedFeature) FeatureNames.legible(word.id.path) else word.id.path.replace('_', ' ')
    }

    private fun biomeTerm(biome: Identifier): ReadoutTerm = ReadoutTerm(
        word = biome,
        speech = Speech.NOUN,
        nameKey = "biome.${biome.namespace}.${biome.path}",
        plain = biome.path.replace('_', ' '),
    )

    // ----- Joining -----

    /** [clauses] with every two about the same subject in the same place made one, where the first stood. */
    private fun joined(clauses: List<ReadoutClause>): List<ReadoutClause> {
        fun subjectOf(clause: ReadoutClause) =
            listOf(clause.about, clause.subject?.word, clause.body, clause.confinedTo?.word, clause.everywhere)
        val mayJoin = clauses.filter { it.shape == null && it.collective == null }
        val gathered = mayJoin.groupBy(::subjectOf).mapValues { (_, same) ->
            same.first().copy(terms = same.flatMap { it.terms }.distinct())
        }
        val placed = mutableSetOf<List<Any?>>()
        return clauses.mapNotNull { clause ->
            if (clause !in mayJoin) return@mapNotNull clause
            val subject = subjectOf(clause)
            if (placed.add(subject)) gathered.getValue(subject) else null
        }
    }

    companion object {
        private val AGE: Identifier = "age".location()
        private const val MOD_NAMESPACE = Constants.MOD_ID
    }
}
