package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.readout.ReadoutWriting
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import co.voik.agesandtheart.age.resolution.Resolution
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import java.util.Optional
import co.voik.agesandtheart.age.readout.FeatureNames
import co.voik.agesandtheart.age.readout.ReadoutClause
import co.voik.agesandtheart.age.readout.ReadoutShape
import co.voik.agesandtheart.age.readout.ReadoutTerm
import co.voik.agesandtheart.age.words.Speech

/**
 * **A reading, said as an account of an Age** — several sentences, each about one part of the world, the way a
 * chronicle describes a place rather than the way a writer laid its pages. For where a book is *read*, and to teach
 * the grammar: it says each word of the part it applied to, in the writer's words. [Reading.readout] is for where the
 * reading itself is wanted.
 *
 * - **Only what held is said.** A word whose every landing came to nothing in [Resolution] — overruled, set aside
 *   for want of room, honoured by nothing — is left out, and the conflicts are where that is told.
 * - **Every word that took pages is a sentence** about where it landed them, the pages it took said with it; a word
 *   naming members is the subject of what was said of them — "its spiders are large" — and sentences about one
 *   subject are joined.
 * - **What the Age took is the Age's sentence**, the pages a writer laid bare, those laid there with nowhere else to
 *   go, and the blocks it bans; it is said whenever the `age` page was laid, even with nothing before it.
 * - **A minting is said as the shape it mints**, and a landform of ours with only qualities before it as that
 *   landform: `basalt arches` is arches of basalt, `large tunnels` the tunnels, never two sentences.
 * - **A quantifier is said as how much there is of what took it**, never as a word of its own; and it says what was
 *   written, never what it will make.
 * - **What `of` joins is said as one thing**: `herds of warden` is herds of wardens.
 *
 * Built on the server into [ReadoutClause]s; written as sentences by [ReadoutWriting] wherever the book is read.
 */
object Prose {
    /** [reading] as the clauses of its prose, the Age first, with only what held in [resolution]. */
    fun of(reading: Reading, resolution: Resolution, lexicon: Lexicon): List<ReadoutClause> {
        val said = Said(reading, resolution, lexicon)
        val theAge = said.ofTheAge()
        val rest = reading.pages.sortedBy { it.index }.flatMap { page ->
            when (val fate = page.fate) {
                is Fate.Took -> listOfNotNull(said.clauseTakenBy(page, fate.place))
                Fate.AssertedAlone -> listOfNotNull(said.alone(page))
                else -> emptyList()
            }
        }
        return listOfNotNull(theAge) + joined(rest)
    }

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

    /** A reading with its words and what held at hand. */
    private class Said(val reading: Reading, val resolution: Resolution, val lexicon: Lexicon) {
        private fun wordOf(page: PageRead): Word? = lexicon.word(page.written)

        private fun isAQuantifier(page: PageRead): Boolean = wordOf(page)?.isAQuantifier == true

        /** Whether anything [page] landed in the clause [clause] held — every page landed somewhere, or it is not said. */
        private fun heldIn(page: Int, clause: Int?): Boolean {
            val landed = reading.landings.filter { it.page == page && it.saidOf == clause }
            return landed.any { it.at !in resolution.cameToNothing }
        }

        /** Whether what [page] landed anywhere held, or it landed nothing of its own to lose. */
        private fun heldAnywhere(page: Int): Boolean {
            val landed = reading.landings.filter { it.page == page }
            return landed.isEmpty() || landed.any { it.at !in resolution.cameToNothing }
        }

        /** The share of a divided part [page] went to in [clause], where it went to one. */
        private fun shareOf(page: Int, clause: Int?): Int? =
            reading.landings.filter { it.page == page && it.saidOf == clause }.firstNotNullOfOrNull { resolution.divided[it.at] }

        /** The word an `of` joins [taker] to as what it comes in — `herds` before `of warden` — where one does. */
        private fun collectiveBefore(taker: PageRead): PageRead? {
            val of = reading.pages.getOrNull(taker.index - 1)?.takeIf { it.fate == Fate.Structural(Structure.OF) } ?: return null
            return reading.pages.getOrNull(of.index - 1)?.takeIf { it.fate is Fate.Took }
        }

        /** Whether [taker] is said as what an `of` after it joins, rather than as a clause of its own. */
        private fun isACollective(taker: PageRead): Boolean =
            reading.pages.getOrNull(taker.index + 1)?.fate == Fate.Structural(Structure.OF)

        fun ofTheAge(): ReadoutClause? {
            val bare = reading.pages.filter { it.fate == Fate.LaidBare || it.fate == Fate.Moved }.filter { heldAnywhere(it.index) }
            val banned = reading.pages.filter { it.fate == Fate.Banned }.mapNotNull(::termOf).map { it.copy(polarity = Polarity.EXCEPT) }
            val ageWasLaid = reading.pages.any { it.fate == Fate.Structural(Structure.AGE) }
            val terms = bare.mapNotNull(::termOf) + banned
            if (terms.isEmpty()) return if (ageWasLaid) ReadoutClause(about = null, terms = emptyList()) else null
            return ReadoutClause(about = null, terms = terms)
        }

        fun clauseTakenBy(taker: PageRead, place: Place): ReadoutClause? {
            if (isACollective(taker)) return null
            val word = wordOf(taker) ?: return null
            val taken = reading.pages.filter { it.fate == Fate.TakenBy(taker.index) && heldIn(it.index, taker.index) }
            val ownHeld = heldAnywhere(taker.index)
            if (!ownHeld && taken.isEmpty()) return null
            val (amounts, described) = taken.partition(::isAQuantifier)
            val landed = reading.landings.filter { it.saidOf == taker.index }
            val confinedTo = landed.firstNotNullOfOrNull { it.sitedIn }?.let(::biomeTerm)
            val everywhere = landed.any { it.everywhere }
            val body = (place.members as? Members.Body)?.index
            val ownPolarity = reading.landings.firstOrNull { it.page == taker.index && it.polarity != Polarity.ASSERTED }?.polarity
            val counted = termOf(taker)?.copy(
                quantifier = amounts.firstOrNull()?.written,
                polarity = ownPolarity ?: Polarity.ASSERTED,
                share = shareOf(taker.index, taker.index),
            )
            fun said(page: PageRead) = termOf(page)?.copy(share = shareOf(page.index, taker.index))
            val isTheAspectsPage = word.id.namespace == Constants.MOD_ID && Aspect.byPage(word.name) == place.aspect
            // `rainbow`, `aurora phenomena`: a part's page that brings its phenomenon and is described by nothing is
            // said as that phenomenon, as a word naming it would be.
            val announces = reading.landings.any { landing ->
                landing.page == taker.index && landing.place.aspect == Aspect.PHENOMENA &&
                    landing.effects.any { it is Effect.Chooses } && landing.at !in resolution.cameToNothing
            }
            if (isTheAspectsPage && announces && taken.isEmpty()) {
                return ReadoutClause(Aspect.PHENOMENA, listOfNotNull(counted?.copy(speech = Speech.NOUN)), confinedTo, everywhere)
            }
            if (isTheAspectsPage) {
                // `no features`: the page itself, struck, which the writing says as the part holding nothing.
                val emptied = landed.any { landing ->
                    landing.page == taker.index && landing.effects.any { it is Effect.Excludes && Effect.Excludes.EVERY_MEMBER in it.members }
                }
                val itself = listOfNotNull(counted?.copy(polarity = Polarity.EXCEPT)).takeIf { emptied }.orEmpty()
                return ReadoutClause(place.aspect, taken.mapNotNull(::said) + itself, confinedTo, everywhere, body)
            }
            val shape = shapeOf(word, counted, described)
            if (shape != null) return ReadoutClause(place.aspect, emptyList(), confinedTo, everywhere, body, shape)
            val collective = collectiveBefore(taker)?.let(::collectiveOf)
            val namesMembers = word.partOfSpeech.names && place.members is Members.Named
            val subject = counted?.takeIf { namesMembers && (described.isNotEmpty() || collective != null) }
            val terms = described.mapNotNull(::said) + listOfNotNull(counted.takeIf { subject == null })
            return ReadoutClause(place.aspect, terms, confinedTo, everywhere, body, subject = subject, collective = collective)
        }

        /** What an `of` joined a word to, said as the shape it comes in: its rung, and the qualities said of it. */
        private fun collectiveOf(collective: PageRead): ReadoutShape? {
            val taken = reading.pages.filter { it.fate == Fate.TakenBy(collective.index) }
            val (amounts, qualities) = taken.partition(::isAQuantifier)
            val shape = termOf(collective)?.copy(quantifier = amounts.firstOrNull()?.written) ?: return null
            return ReadoutShape(shape, qualities = qualities.mapNotNull(::termOf))
        }

        /**
         * A minting said as its shape, made of the substances laid before it; or a landform of ours with nothing
         * but qualities before it, said as that landform. Null for anything else.
         */
        private fun shapeOf(word: Word, counted: ReadoutTerm?, described: List<PageRead>): ReadoutShape? {
            val shape = counted ?: return null
            val (substances, qualities) = described.partition { wordOf(it)?.block != null }
            if (word.mints != null) {
                return ReadoutShape(shape, substances.mapNotNull(::termOf), qualities.mapNotNull(::termOf))
            }
            val namesADesign = word.meanings.flatMap { it.effects }.any { effect ->
                effect is Effect.Chooses && !effect.aspect.open && word.partOfSpeech.names
            }
            val onlyQualities = described.all { wordOf(it)?.partOfSpeech == Speech.ADJECTIVE }
            return if (namesADesign && onlyQualities) ReadoutShape(shape, qualities = described.mapNotNull(::termOf)) else null
        }

        fun alone(page: PageRead): ReadoutClause? {
            if (!heldAnywhere(page.index)) return null
            val about = reading.landings.firstOrNull { it.page == page.index }?.place?.aspect ?: return null
            return ReadoutClause(about, listOfNotNull(termOf(page)))
        }

        private fun termOf(page: PageRead): ReadoutTerm? {
            if (lexicon.phrase(page.written) != null) return phraseTerm(page.written)
            val word = wordOf(page) ?: return null
            // `north rising sun` is said as "rising in the north", by the carrier and the point together, and `north
            // wind` as "from the north", by the word that took it.
            val carrier = reading.pages.firstOrNull { (it.fate as? Fate.Carries)?.carried == page.index }
            val taker = reading.pages.getOrNull(page.index + 1)?.takeIf { page.fate == Fate.TakenBy(it.index) }
            val carriedKey = (carrier ?: taker)?.let { "prose.${Constants.MOD_ID}.${it.written}_${page.written}" }
            val plain = carrier?.let { "${page.written} ${it.written}" } ?: plainNameOf(word)
            return ReadoutTerm(word.id, word.partOfSpeech, nameKey = carriedKey ?: nameKeyOf(word), plain = plain)
        }

        /** An old page retired into a phrase, said by its own name, as the books it was written in already say it. */
        private fun phraseTerm(written: String): ReadoutTerm {
            val name = written.removePrefix("${Constants.MOD_ID}:")
            return ReadoutTerm(Identifier.fromNamespaceAndPath(Constants.MOD_ID, name), Speech.ADJECTIVE, plain = name.replace('_', ' '))
        }
    }

    private fun biomeTerm(biome: Identifier): ReadoutTerm = ReadoutTerm(
        word = biome,
        speech = Speech.NOUN,
        nameKey = "biome.${biome.namespace}.${biome.path}",
        plain = biome.path.replace('_', ' '),
    )

    /** Vanilla's name for what a derived word is, where vanilla gives it one. */
    private fun nameKeyOf(word: Word): String? {
        val registries = word.referentRegistries
        return when {
            Registries.BLOCK in registries -> BuiltInRegistries.BLOCK.getOptional(word.id).map { it.descriptionId }.orElse(null)
            Registries.ENTITY_TYPE in registries ->
                BuiltInRegistries.ENTITY_TYPE.getOptional(word.id).map { it.descriptionId }.orElse(null)
            Registries.BIOME in registries -> "biome.${word.id.namespace}.${word.id.path}"
            else -> null
        }
    }

    private fun plainNameOf(word: Word): String =
        if (Registries.PLACED_FEATURE in word.referentRegistries) FeatureNames.legible(word.id.path) else word.id.path.replace('_', ' ')
}
