package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import net.minecraft.resources.Identifier

/**
 * Reads a row of pages into what lands where (`notes/meanings-plan.md` §2, Reading a row):
 *
 * 1. A page waits until a word that takes any of its meanings, which takes the whole page.
 * 2. A waiting page stops at the first word that takes anything at all, and falls there if that word does not take it.
 * 3. Several pages may wait together.
 * 4. A word that took a page hands nothing further on.
 * 5. A page nothing takes says what it asserts alone, or has nowhere to land and is laid on the Age instead.
 *
 * `age` takes everything laid before it bare. `and` joins the terms either side of it, `in` sites the last word that
 * took pages, `everywhere` widens it, and `only` and `no` lead the word naming members after them.
 */
object Reader {

    /** The book [pages] spell, read against [lexicon] and drawn at [seed], or null where it has no `age` page. */
    fun read(lexicon: Lexicon, pages: List<String>, seed: Long): Reading? {
        if (pages.none { lexicon.structure(it) == Structure.AGE }) return null
        val pass = Pass(lexicon, pages).read()
        val read = pages.mapIndexed { index, written -> PageRead(index, written, pass.fates[index] ?: Fate.Unread) }
        return Draw.of(pass.claims, read, pass.templates.values.firstOrNull(), seed)
    }

    /** The parts of the world `everywhere` can widen, being the ones that grow in places. */
    val WIDENED: Set<Aspect> = setOf(Aspect.FEATURES, Aspect.SPAWNS)

    /** What `and` may join: a page still waiting, or a word that has taken pages. */
    private sealed interface Term

    /** A page laid before any word that takes it. */
    private data class Waiting(val index: Int, val word: Word, val joined: Int? = null) : Term

    /** The last word that took pages, which `in`, `everywhere` and `and` reach back to. */
    private data class Taker(
        val index: Int,
        val place: Place,
        val took: List<Waiting>,
        val claims: IntRange,
        val joined: Int?,
    ) : Term

    private class Pass(private val lexicon: Lexicon, private val pages: List<String>) {
        val fates = arrayOfNulls<Fate>(pages.size)
        val claims = mutableListOf<Claim>()

        /** Each template a page that landed named, by the page — the first is the world the book starts from. */
        val templates = sortedMapOf<Int, String>()

        private val waiting = mutableListOf<Waiting>()
        private var lastTaker: Taker? = null
        private var andAt: Int? = null
        private var leftOfAnd: Term? = null
        private var polarity: Polarity? = null
        private var polarityAt: Int? = null
        private val bodies = mutableMapOf<Aspect, Int>()
        private var nextJoin = 0
        private var at = 0

        fun read(): Pass {
            while (at < pages.size) {
                val page = pages[at]
                val structure = lexicon.structure(page)
                val word = if (structure == null) lexicon.word(page) else null
                when {
                    structure != null -> structural(structure)
                    word == null -> fates[at++] = Fate.Unread
                    else -> {
                        word(word)
                        at++
                    }
                }
            }
            waiting.forEach(::fell)
            waiting.clear()
            andAt?.let(::impossible)
            polarityAt?.let(::impossible)
            return this
        }

        private fun structural(structure: Structure) {
            fates[at] = Fate.Structural(structure)
            when (structure) {
                Structure.AGE -> {
                    waiting.forEach { laidBare(it, Fate.LaidBare) }
                    waiting.clear()
                    lastTaker = null
                }
                Structure.AND -> joining()
                Structure.IN -> {
                    sitingIn()
                    return
                }
                Structure.EVERYWHERE -> widening()
                Structure.ONLY, Structure.EXCEPT -> {
                    polarityAt?.let(::impossible)
                    polarity = if (structure == Structure.ONLY) Polarity.ONLY else Polarity.EXCEPT
                    polarityAt = at
                }
            }
            at++
        }

        /** `and`: the page before it is the left term, and the next term is joined to it. */
        private fun joining() {
            val previous = at - 1
            val leftWaits = waiting.lastOrNull()?.index == previous
            val leftTook = lastTaker?.index == previous
            leftOfAnd = when {
                leftWaits -> waiting.last()
                leftTook -> lastTaker
                else -> null
            }
            if (leftOfAnd == null) impossible(at) else andAt = at
        }

        /** `in <biome>`: the last word that took pages, and what it took, sited in the biome. */
        private fun sitingIn() {
            val taker = lastTaker
            val biome = pages.getOrNull(at + 1)?.let(lexicon::word)?.let(::biomeNamedBy)
            val sitable = taker != null && taker.place.aspect.confinable
            if (biome == null || !sitable || taker == null) {
                impossible(at)
                at++
                return
            }
            for (claim in taker.claims) claims[claim] = claims[claim].copy(sitedIn = biome)
            fates[at + 1] = Fate.Sited
            at += 2
        }

        private fun biomeNamedBy(word: Word): Identifier? {
            val choice = word.meanings.singleOrNull()?.effects?.singleOrNull() as? Effect.Chooses ?: return null
            return if (choice.aspect == Aspect.BIOMES) Identifier.tryParse(choice.member) else null
        }

        /** `everywhere`: the last word that took pages widened to every place, where it is one that grows in places. */
        private fun widening() {
            val taker = lastTaker
            if (taker == null || taker.place.aspect !in WIDENED) return impossible(at)
            for (claim in taker.claims) claims[claim] = claims[claim].copy(everywhere = true)
        }

        private fun word(word: Word) {
            val acceptance = lexicon.acceptance(word)
            if (acceptance == null) waits(word) else takes(word, acceptance)
        }

        private fun waits(word: Word) {
            val left = leftOfAnd
            val joined = if (andAt != null && left is Waiting) {
                val group = left.joined ?: nextJoin++
                waiting[waiting.lastIndex] = left.copy(joined = group)
                group
            } else {
                andAt?.let(::impossible)
                null
            }
            andAt = null
            leftOfAnd = null
            waiting += Waiting(at, word, joined)
        }

        private fun takes(word: Word, acceptance: Acceptance) {
            val place = placeOf(acceptance)
            val joinedTo = leftOfAnd as? Taker
            val joinsAWaitingPage = andAt != null && joinedTo == null
            if (joinsAWaitingPage) andAt?.let(::impossible)
            val joined = joinedTo?.let { it.joined ?: nextJoin++ }
            if (joinedTo != null && joined != null) {
                for (claim in joinedTo.claims) claims[claim] = claims[claim].copy(joined = joined)
            }
            andAt = null
            leftOfAnd = null
            val taking = joinedTo?.took ?: waiting.toList()
            waiting.clear()

            val from = claims.size
            for (page in taking) {
                val taken = page.word.meanings.mapNotNull { meaning ->
                    meaning.effects.mapNotNull(acceptance::take).distinct().takeIf { it.isNotEmpty() }?.let { meaning to it }
                }
                // A page the word before `and` took stays taken where this one takes none of it.
                if (taken.isEmpty()) {
                    if (joinedTo == null) fell(page)
                    continue
                }
                if (joinedTo == null) fates[page.index] = Fate.TakenBy(at)
                for ((meaning, effects) in taken) {
                    claims += Claim(page.index, page.word, meaning, place, effects, aimed = true, joined = page.joined ?: joined)
                }
            }
            ownMeanings(word, acceptance, place, describedBy = claims.subList(from, claims.size))
            fates[at] = Fate.Took(place)
            lastTaker = Taker(at, place, taking, from..<claims.size, joined)
        }

        /**
         * What the word that took says of itself: a member's own naming, under any `only` or `no` before it.
         *
         * **A word naming members that took pages describing them is describing them, not asking for them** (Jonah,
         * 2026-10-20): naming a member asks for a little more of it, a consolation for a writer who names one on its
         * own, and `deserted villages` is plainly about the villages there are. It still asks where what it took asks
         * for an amount, where `only` or `no` leads it, or where its members are not there until something brings
         * them, as a phenomenon is not.
         */
        private fun ownMeanings(word: Word, acceptance: Acceptance, place: Place, describedBy: List<Claim>) {
            val pending = polarity
            val namesMembers = acceptance.place.members is Members.Named
            if (pending != null && !namesMembers) polarityAt?.let(::impossible)
            val said = if (namesMembers) pending ?: Polarity.ASSERTED else Polarity.ASSERTED
            polarity = null
            polarityAt = null
            val describes = describedBy.any { claim -> claim.effects.any { it !is Effect.Abundance } }
            val asksForAnAmount = describedBy.any { claim -> claim.effects.any { it is Effect.Abundance } }
            val membersAreThereAnyway = place.aspect.pool?.startsEmpty != true
            val onlyDescribes = namesMembers && describes && !asksForAnAmount && said == Polarity.ASSERTED && membersAreThereAnyway
            if (place.members is Members.Body) {
                claims += Claim(at, word, Meaning(emptyList(), mustAlwaysApply = true), place, emptyList(), aimed = true)
            }
            for (meaning in word.meanings) {
                if (onlyDescribes && meaning.effects.all(::namesMembers)) continue
                namesATemplate(at, meaning)
                for ((ownPlace, effects) in placesOf(meaning)) {
                    claims += Claim(at, word, meaning, ownPlace, effects, aimed = true, polarity = said)
                }
            }
        }

        private fun namesMembers(effect: Effect) = effect is Effect.Chooses || effect is Effect.Restricts

        /** Where a word taking pages lands them: its place, or a body of its own where it names a population. */
        private fun placeOf(acceptance: Acceptance): Place {
            val aspect = acceptance.place.aspect
            val bringsABody = aspect.holds == Holds.POPULATION && acceptance.place.members == Members.All
            if (!bringsABody) return acceptance.place
            val body = bodies.getOrDefault(aspect, 0)
            bodies[aspect] = body + 1
            return Place(aspect, Members.Body(body))
        }

        /** A page nothing takes says what it asserts alone, or is laid on the Age with nowhere else to land. */
        private fun fell(page: Waiting) {
            val asserted = page.word.meanings.filter(Meaning::asserts)
            if (asserted.isEmpty()) return laidBare(page, Fate.Moved)
            fates[page.index] = Fate.AssertedAlone
            for (meaning in asserted) {
                namesATemplate(page.index, meaning)
                for ((place, effects) in placesOf(meaning)) {
                    claims += Claim(page.index, page.word, meaning, place, effects, aimed = true, joined = page.joined)
                }
            }
        }

        private fun laidBare(page: Waiting, fate: Fate) {
            fates[page.index] = fate
            for (meaning in page.word.meanings.filter(Meaning::appliesUnaimed)) {
                namesATemplate(page.index, meaning)
                for ((place, effects) in placesOf(meaning)) {
                    claims += Claim(page.index, page.word, meaning, place, effects, aimed = false, joined = page.joined)
                }
            }
        }

        private fun namesATemplate(page: Int, meaning: Meaning) {
            meaning.effects.filterIsInstance<Effect.Template>().firstOrNull()?.let { templates.putIfAbsent(page, it.name) }
        }

        /** Each part of the world [meaning] reaches laid bare, and what it says there. */
        private fun placesOf(meaning: Meaning): List<Pair<Place, List<Effect>>> {
            val aspects = meaning.effects.flatMap(lexicon::barePlacesOf).distinct().sortedBy(Aspect::ordinal)
            return aspects.map { aspect ->
                val here = meaning.effects.mapNotNull { effect ->
                    when {
                        aspect !in lexicon.barePlacesOf(effect) -> null
                        effect is Effect.LeansEverywhere -> Effect.Leans(aspect, effect.weights)
                        else -> effect
                    }
                }
                val minted = here.filterIsInstance<Effect.Mints>().map { it.pattern }.distinct()
                val place = if (minted.size == 1 && here.all { it is Effect.Mints }) {
                    Place(aspect, Members.Named(minted.toSet()))
                } else {
                    Place(aspect)
                }
                place to here
            }
        }

        private fun impossible(index: Int) {
            fates[index] = Fate.Impossible
            if (andAt == index) andAt = null
            if (polarityAt == index) {
                polarityAt = null
                polarity = null
            }
        }
    }
}

/** One meaning of one page at one place, before the draw decides whether it lands. */
internal data class Claim(
    val page: Int,
    val word: Word,
    val meaning: Meaning,
    val place: Place,
    val effects: List<Effect>,
    val aimed: Boolean,
    val joined: Int? = null,
    val sitedIn: Identifier? = null,
    val everywhere: Boolean = false,
    val polarity: Polarity = Polarity.ASSERTED,
)
