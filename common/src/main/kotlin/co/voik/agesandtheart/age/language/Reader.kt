package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Prism
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.GROWS_IN_PLACES
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.resolution.Place
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * Reads a row of pages into what lands where (`the-world-model.md` §3):
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


    /** What `and` may join: a page still waiting, or a word that has taken pages. */
    private sealed interface Term

    /** A page laid before any word that takes it. */
    private data class Waiting(val index: Int, val word: Word, val joined: Int? = null) : Term

    /** The last word that took pages, which `in`, `everywhere`, `and` and `of` reach back to. */
    private data class Taker(
        val index: Int,
        val place: Place,
        val took: List<Waiting>,
        val claims: IntRange,
        val joined: Int?,
        val word: Word,
        val acceptance: Acceptance,
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
        private var linkAt: Int? = null

        /** An `of` and the word before it, waiting for the word it is said of. */
        private var ofAt: Int? = null
        private var ofFrom: Taker? = null
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
            linkAt?.let(::impossible)
            ofAt?.let(::impossible)
            return this
        }

        private fun structural(structure: Structure) {
            fates[at] = Fate.Structural(structure)
            ofAt?.let(::impossible).also { ofAt = null; ofFrom = null }
            val linkWantsItsIn = linkAt != null && structure != Structure.IN
            if (linkWantsItsIn) linkAt?.let(::impossible).also { linkAt = null }
            when (structure) {
                Structure.AGE -> {
                    lastTaker?.takeIf(::isSaidOfTheAge)?.let(::saidOfTheAge)
                    waiting.forEach { laidBare(it, Fate.LaidBare) }
                    waiting.clear()
                    lastTaker = null
                    // An `and` with nothing but the Age after it joins nothing, as one at the end of the book does.
                    andAt?.let(::impossible)
                    andAt = null
                    leftOfAnd = null
                }
                Structure.AND -> joining()
                Structure.IN -> {
                    if (linkAt == at - 1) linkingIn() else sitingIn()
                    return
                }
                Structure.LINK -> linkAt = at
                Structure.OF -> {
                    val before = lastTaker
                    if (before == null || before.index != at - 1) impossible(at) else {
                        ofAt = at
                        ofFrom = before
                    }
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
            // `no snow and powder_snow`: the block after a ban's `and` is banned too.
            if (fates.getOrNull(previous) == Fate.Banned) {
                polarity = Polarity.EXCEPT
                polarityAt = at
                return
            }
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
            if (taker == null || biome == null || !taker.place.aspect.confinable) {
                impossible(at)
                at++
                return
            }
            for (claim in taker.claims) claims[claim] = claims[claim].copy(sitedIn = biome)
            fates[at + 1] = Fate.Sited
            at += 2
        }

        /**
         * `link in <anchor>`: where a link into the Age arrives — a biome, a structure, the sea, or where a feature or
         * a creature can appear. The anchor is any word that takes pages, and says nothing of the world itself.
         */
        private fun linkingIn() {
            val link = linkAt ?: return
            linkAt = null
            val anchorAt = at + 1
            val anchor = pages.getOrNull(anchorAt)?.takeIf { lexicon.structure(it) == null }?.let(lexicon::word)
            val acceptance = anchor?.let(lexicon::acceptance)
            if (anchor == null || acceptance == null) {
                impossible(link)
                impossible(at)
                at++
                return
            }
            val members = (acceptance.place.members as? Members.Named)?.keys.orEmpty()
            val arrives = Meaning(listOf(Effect.LinksIn(LinkAnchor(acceptance.place.aspect, members))), mustAlwaysApply = true, asserts = true)
            claims += Claim(anchorAt, anchor, arrives, Place(acceptance.place.aspect), arrives.effects, clause = anchorAt, aimed = true)
            fates[anchorAt] = Fate.Anchors
            at += 2
        }

        private fun biomeNamedBy(word: Word): Identifier? {
            val choice = word.meanings.singleOrNull()?.effects?.singleOrNull() as? Effect.Chooses ?: return null
            return if (choice.aspect == Aspect.BIOMES) Identifier.tryParse(choice.member) else null
        }

        /** `everywhere`: the last word that took pages widened to every place, where it is one that grows in places. */
        private fun widening() {
            val taker = lastTaker
            if (taker == null || taker.place.aspect !in GROWS_IN_PLACES) return impossible(at)
            for (claim in taker.claims) claims[claim] = claims[claim].copy(everywhere = true)
        }

        private fun word(word: Word) {
            linkAt?.let(::impossible).also { linkAt = null }
            val acceptance = lexicon.acceptance(word)
            val bansIt = polarity == Polarity.EXCEPT && acceptance == null && isABlock(word)
            val madeOfIt = ofFrom?.acceptance?.mints == true && acceptance == null && isABlock(word)
            when {
                bansIt -> bans(word)
                madeOfIt -> ofFrom?.let { madeOf(it, word) }
                acceptance == null -> waits(word)
                else -> takes(word, acceptance)
            }
        }

        /** `<shape> of <block>`: the block is what the shape before `of` is made of, as `<block> <shape>` reads. */
        private fun madeOf(shape: Taker, block: Word) {
            val of = ofAt
            ofAt = null
            ofFrom = null
            val taken = block.meanings.filter { !it.isOffered }.flatMap { meaning ->
                takenFrom(meaning, shape.acceptance, shape.place, shape.word).map { (takenAt, effects) -> Triple(meaning, takenAt, effects) }
            }
            if (taken.isEmpty()) {
                of?.let(::impossible)
                return impossible(at)
            }
            fates[at] = Fate.TakenBy(shape.index)
            for ((meaning, takenAt, effects) in taken) {
                claims += Claim(at, block, meaning, takenAt, effects, clause = shape.index, aimed = true, joined = shape.joined)
            }
        }

        /**
         * `<members> of <members>`: the word before `of` is said of the one after instead of its own — what it took
         * goes over to them, its own naming is dropped, and its meanings offered to `of` land there too where nothing
         * it took said the same. `herds of warden` are herds of wardens, plentiful, and `teeming herds of` many of them.
         */
        private fun saidOf(before: Taker, after: Place) {
            val of = ofAt
            ofAt = null
            ofFrom = null
            val sameKind = before.place.aspect == after.aspect && after.members is Members.Named
            if (!sameKind) return of?.let(::impossible) ?: Unit
            val carried = before.claims.filter { claims[it].page != before.index }
            for (index in before.claims) {
                val claim = claims[index]
                claims[index] = if (claim.page == before.index) claim.copy(effects = emptyList()) else claim.copy(place = after)
            }
            val carriedEffects = carried.flatMap { claims[it].effects }
            val alreadySaid = carriedEffects.mapNotNull { (it as? Effect.Sets)?.parameter?.name }.toSet()
            val anAmountWasSaid = carriedEffects.any { it is Effect.Abundance }
            fun wasSaidAlready(effect: Effect) = when (effect) {
                is Effect.Sets -> effect.parameter.name in alreadySaid
                is Effect.Abundance -> anAmountWasSaid
                else -> false
            }
            for (meaning in before.word.meanings.filter { OF_PAGE in it.offeredTo }) {
                val effects = meaning.effects.filterNot(::wasSaidAlready)
                if (effects.isNotEmpty()) {
                    claims += Claim(before.index, before.word, meaning, after, effects, clause = before.index, aimed = true, joined = before.joined)
                }
            }
        }

        private fun isABlock(word: Word) = BuiltInRegistries.BLOCK.containsKey(word.id)

        /** `no <block>`: a block the Age never places, whatever else asks for it — its own meanings said of nothing. */
        private fun bans(word: Word) {
            polarity = null
            polarityAt = null
            fates[at] = Fate.Banned
            val ban = Meaning(listOf(Effect.Bans(word.id.toString())), mustAlwaysApply = true, asserts = true)
            claims += Claim(at, word, ban, Place(Aspect.TERRAIN), ban.effects, clause = at, aimed = true)
        }

        private fun waits(word: Word) {
            val left = leftOfAnd
            // `gentle and colossal eroded landmass`: the `and` stays open for the word that takes this page.
            if (andAt != null && left is Taker) {
                waiting += Waiting(at, word, joined = null)
                return
            }
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
            val named = lastTaker?.takeIf { namesThePartOf(word, it) }
            if (named != null) {
                fates[at] = Fate.Names(named.index)
                return
            }
            val place = placeOf(acceptance)
            val saidOf = lastTaker?.takeIf { isSaidOf(it, word, acceptance, place) }
            val joinedTo = leftOfAnd as? Taker
            val joinsAWaitingPage = andAt != null && joinedTo == null
            if (joinsAWaitingPage) andAt?.let(::impossible)
            val joined = joinedTo?.let { it.joined ?: nextJoin++ }
            if (joinedTo != null && joined != null) {
                for (claim in joinedTo.claims) claims[claim] = claims[claim].copy(joined = joined)
            }
            andAt = null
            leftOfAnd = null
            // A word joined to one that took pages takes them again, unless pages of its own wait for it: `colossal
            // gentle and eroded` is both colossal, `gentle and colossal eroded` only the second.
            val borrowsTheLeftsPages = joinedTo != null && waiting.isEmpty()
            // A member word said of this one hands over the pages it took, to be taken again here.
            val taking = if (borrowsTheLeftsPages) joinedTo.took else saidOf?.took.orEmpty() + waiting
            waiting.clear()

            val from = claims.size
            saidOf?.let { saidOfThisPage(it, word, acceptance, place) }
            fun isOfferedHere(meaning: Meaning) = isOfferedTo(meaning, word, acceptance)
            for (page in taking) {
                val meanings = page.word.meanings.filter { keptByOrder(page, it, taking) }
                // A meaning that only ever applies laid bare is never the taking word's to land.
                val taken = meanings.filter { !it.isOffered && it.appliesWhereAimed }.mapNotNull { meaning ->
                    takenFrom(meaning, acceptance, place, word).takeIf { it.isNotEmpty() }?.let { meaning to it }
                }
                val offered = meanings.filter(::isOfferedHere)
                // A page the word before `and` took stays taken where this one takes none of it.
                if (taken.isEmpty() && offered.isEmpty()) {
                    if (!borrowsTheLeftsPages) fell(page)
                    continue
                }
                if (!borrowsTheLeftsPages) fates[page.index] = Fate.TakenBy(at)
                val pageJoined = page.joined ?: joined
                for ((meaning, landed) in taken) {
                    for ((takenAt, effects) in landed) {
                        claims += Claim(page.index, page.word, meaning, takenAt, effects, clause = at, aimed = true, joined = pageJoined)
                    }
                }
                for (meaning in offered) {
                    for ((offeredPlace, effects) in offeredPlacesOf(meaning, acceptance, place)) {
                        claims += Claim(page.index, page.word, meaning, offeredPlace, effects, clause = at, aimed = true, joined = pageJoined)
                    }
                }
            }
            val saidOfBy = ofFrom
            ownMeanings(word, acceptance, place, describedBy = claims.subList(from, claims.size), joined = joined, asked = saidOfBy != null)
            fates[at] = Fate.Took(place)
            lastTaker = Taker(at, place, taking, from..<claims.size, joined, word, acceptance)
            saidOfBy?.let { saidOf(it, place) }
        }

        /**
         * **A pace said beside turning colours goes where it stands** (Jonah, 2026-11-08): `swift prismatic sun` turns the
         * sun's colours faster and leaves the sun's own pace alone, and `prismatic swift sun` is a fast sun whose colours
         * turn as they would. A pace word waiting just before a word that turns colours keeps only its colours' pace; one
         * waiting after such a word keeps only its own. With no colours turning in the run, both land, as they always did.
         */
        private fun keptByOrder(page: Waiting, meaning: Meaning, taking: List<Waiting>): Boolean {
            val pacesTheColours = meaning.sets(COLOUR_CYCLE_PACE)
            val pacesItself = meaning.sets(OWN_PACE)
            if (pacesTheColours == pacesItself) return true
            val turnsColoursNext = taking.any { it.index == page.index + 1 && turnsColours(it.word) }
            val turnedColoursBefore = taking.any { it.index < page.index && turnsColours(it.word) }
            return when {
                turnsColoursNext -> pacesTheColours
                turnedColoursBefore -> pacesItself
                else -> true
            }
        }

        private fun turnsColours(word: Word): Boolean = word.meanings.any { it.sets(COLOURS_CYCLING) }

        private fun Meaning.sets(parameter: String): Boolean =
            effects.any { it is Effect.Sets && it.parameter.name == parameter }

        /**
         * Whether [meaning] is offered to [word]: by its name, or by the name of the part it is a member of, so what a
         * word offers `inhabitants` it offers `zombie` too.
         */
        private fun isOfferedTo(meaning: Meaning, word: Word, acceptance: Acceptance): Boolean {
            val isAMemberOfAnOfferedPart = acceptance.place.members is Members.Named && acceptance.place.aspect.page in meaning.offeredTo
            return word.name in meaning.offeredTo || isAMemberOfAnOfferedPart
        }

        /**
         * **The last word is the head, and a member word before it is said of it** (Jonah, 2026-11-05): the word that
         * last took names members, nothing but pages waiting stand between it and [word], nothing else is open, and it
         * says something this word takes or is offered. `ordered eroded features` and `eroded ordered features` are
         * both ordered and eroded features, and the landmass is neither. A member word this word takes nothing of keeps
         * its own naming, so `pillared eroded features` still stands pillars.
         */
        private fun isSaidOf(before: Taker, word: Word, acceptance: Acceptance, place: Place): Boolean {
            val onlyPagesWaitBetween = waiting.map(Waiting::index) == (before.index + 1..<at).toList()
            val namesMembers = before.place.members is Members.Named
            val nothingElseIsOpen = andAt == null && ofFrom == null
            return onlyPagesWaitBetween && namesMembers && nothingElseIsOpen && saysSomethingOf(before.word, word, acceptance, place)
        }

        private fun saysSomethingOf(member: Word, word: Word, acceptance: Acceptance, place: Place): Boolean {
            val offersIt = member.meanings.any { isOfferedTo(it, word, acceptance) }
            val itTakes = member.meanings.filter { !it.isOffered }.any { takenFrom(it, acceptance, place, word).isNotEmpty() }
            return offersIt || itTakes
        }

        /**
         * A member word said of the page after it, which then takes it as it would a page waiting for it: its own
         * naming is dropped, as before an `of`. Where it offers this word something, only that lands — `eroded
         * features` crumbles the features and leaves the land as it was — and otherwise what this word takes of it.
         */
        private fun saidOfThisPage(before: Taker, word: Word, acceptance: Acceptance, place: Place) {
            for (index in before.claims) claims[index] = claims[index].copy(effects = emptyList())
            fates[before.index] = Fate.TakenBy(at)
            val offered = before.word.meanings.filter { isOfferedTo(it, word, acceptance) }
            val landing = offered.flatMap { meaning -> offeredPlacesOf(meaning, acceptance, place).map { meaning to it } }
                .ifEmpty {
                    before.word.meanings.filter { !it.isOffered }
                        .flatMap { meaning -> takenFrom(meaning, acceptance, place, word).map { meaning to it } }
                }
            for ((meaning, landed) in landing) {
                val (landsAt, effects) = landed
                claims += Claim(before.index, before.word, meaning, landsAt, effects, clause = at, aimed = true, joined = before.joined)
            }
            lastTaker = null
        }

        /** Whether [before] stands just before this `age`, with nothing open between, and offers the Age something. */
        private fun isSaidOfTheAge(before: Taker): Boolean {
            val followsIt = before.index == at - 1
            val nothingElseIsOpen = waiting.isEmpty() && andAt == null && ofFrom == null
            val offersTheAge = before.word.meanings.any { AGE_PAGE in it.offeredTo }
            return followsIt && nothingElseIsOpen && offersTheAge
        }

        /**
         * **A member word said of the Age says what it offers the Age** (Jonah, 2026-11-10): `cratered age` is the
         * cratered country, and now and then the craters over it too, where `cratered landmass` is the country alone.
         * Its own naming is dropped and what it offers `age` lands in its place, aimed, at whatever each effect reaches;
         * what it took from the pages before it stays as it was.
         */
        private fun saidOfTheAge(before: Taker) {
            for (index in before.claims) {
                val claim = claims[index]
                if (claim.page == before.index) claims[index] = claim.copy(effects = emptyList())
            }
            for (meaning in before.word.meanings.filter { AGE_PAGE in it.offeredTo }) {
                for ((place, effects) in placesOf(meaning)) {
                    claims += Claim(before.index, before.word, meaning, place, effects, clause = before.index, aimed = true, joined = before.joined)
                }
            }
        }

        /**
         * Whether [word] is the page of the part the word just before it named, with nothing waiting: `insular
         * landmass`, `tunnels underground`. It says where that word belongs, and takes nothing of its own.
         */
        private fun namesThePartOf(word: Word, before: Taker): Boolean {
            val isOurPage = word.id.namespace == Constants.MOD_ID && Aspect.byPage(word.name) == before.place.aspect
            val followsIt = before.index == at - 1
            val nothingElseIsOpen = waiting.isEmpty() && andAt == null && ofFrom == null
            return isOurPage && followsIt && nothingElseIsOpen
        }

        /**
         * What the word that took says of itself: a member's own naming, under any `only` or `no` before it.
         *
         * **A word naming members that took pages describing them is describing them, not asking for them** (Jonah,
         * 2026-10-20): naming a member asks for a little more of it, a consolation for a writer who names one on its
         * own, and `deserted villages` is plainly about the villages there are. It still asks where what it took asks
         * for an amount, where `only` or `no` leads it, or where its members are not there until something brings
         * them, as a phenomenon or a catalogue's member is not.
         */
        /** [asked] where nothing it took may make it only describe — the word after an `of`, which a herd is made of. */
        private fun ownMeanings(word: Word, acceptance: Acceptance, place: Place, describedBy: List<Claim>, joined: Int?, asked: Boolean = false) {
            val pending = polarity
            val namesMembers = acceptance.place.members is Members.Named
            // `no features`: before a whole population that may hold nothing, `no` strikes every member at once.
            val emptier = place.aspect.pool?.emptiedBy
            val emptiesIt = pending == Polarity.EXCEPT && !namesMembers && emptier != null
            if (pending != null && !namesMembers && !emptiesIt) polarityAt?.let(::impossible)
            val said = if (namesMembers) pending ?: Polarity.ASSERTED else Polarity.ASSERTED
            polarity = null
            polarityAt = null
            val describes = describedBy.any { claim -> claim.effects.any { it !is Effect.Abundance } }
            val asksForAnAmount = describedBy.any { claim -> claim.effects.any { it is Effect.Abundance } }
            // A catalogue's member is there only where it is chosen, so `large chambered` still chooses the chambers.
            val isAPopulationOfMembers = place.aspect.holds == Holds.WEIGHTED_SET
            val membersAreThereAnyway = isAPopulationOfMembers && place.aspect.pool?.startsEmpty != true
            val onlyDescribes = !asked && namesMembers && describes && !asksForAnAmount && said == Polarity.ASSERTED && membersAreThereAnyway
            if (place.members is Members.Body) {
                claims += Claim(at, word, Meaning(emptyList(), mustAlwaysApply = true), place, emptyList(), clause = at, aimed = true)
            }
            for (meaning in word.meanings.filter { !it.isOffered }) {
                if (onlyDescribes && meaning.effects.all(::namesMembers)) continue
                namesATemplate(at, meaning)
                for ((ownPlace, effects) in placesOf(meaning)) {
                    claims += Claim(at, word, meaning, ownPlace, effects, clause = at, aimed = true, polarity = said, joined = joined)
                }
            }
            // Last, since what it describes is read off the claims as they stood.
            if (emptiesIt) {
                val everything = Meaning(listOf(Effect.Excludes(place.aspect, setOf(Effect.Excludes.EVERY_MEMBER))), mustAlwaysApply = true, asserts = true)
                claims += Claim(at, word, everything, Place(place.aspect), everything.effects, clause = at, aimed = true)
            }
        }

        /**
         * What [acceptance] takes of [meaning] and where: at [place], or at the part its companion speaks for — each
         * parameter [taker] takes as another ([Word.takesAs]) taken as that one.
         */
        private fun takenFrom(meaning: Meaning, acceptance: Acceptance, place: Place, taker: Word? = null): List<Pair<Place, List<Effect>>> =
            meaning.effects.map { takenAs(it, taker, place.aspect) }.mapNotNull(acceptance::landing)
                .groupBy({ (landsAt, _) -> if (landsAt == acceptance.place) place else landsAt }, { (_, effect) -> effect })
                .map { (landsAt, effects) -> landsAt to effects.distinct() }

        /** [effect] as [taker] takes it: a parameter it takes as another, renamed and fenced to [aspect]. */
        private fun takenAs(effect: Effect, taker: Word?, aspect: Aspect): Effect {
            val renamings = taker?.takesAs.orEmpty()
            return when (effect) {
                is Effect.Sets -> renamings[effect.parameter.name]?.let { effect.copy(parameter = ParameterKey(it, aspect)) } ?: effect
                is Effect.Bends -> renamings[effect.parameter.name]?.let { effect.copy(parameter = ParameterKey(it, aspect)) } ?: effect
                else -> effect
            }
        }

        /** An offered meaning lands whole: what [acceptance] takes of it where it takes it, the rest where it lies bare. */
        private fun offeredPlacesOf(meaning: Meaning, acceptance: Acceptance, place: Place): List<Pair<Place, List<Effect>>> {
            val untaken = meaning.effects.filter { acceptance.take(it) == null }
            return takenFrom(meaning, acceptance, place) + placesOf(meaning.copy(effects = untaken))
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
            // A rock's formations assert, and never apply where aimed: a rock nothing took is laid on the Age.
            fun couldApplyAlone(meaning: Meaning) = meaning.mustAlwaysApply || meaning.chanceToApply > Meaning.NEVER
            val asserted = page.word.meanings.filter { it.asserts && !it.isOffered && couldApplyAlone(it) }
            if (asserted.isEmpty()) return laidBare(page, Fate.Moved)
            fates[page.index] = Fate.AssertedAlone
            for (meaning in asserted) {
                namesATemplate(page.index, meaning)
                for ((place, effects) in placesOf(meaning)) {
                    claims += Claim(page.index, page.word, meaning, place, effects, clause = page.index, aimed = true, joined = page.joined)
                }
            }
        }

        /** A page laid on the Age, or impossible where nothing it means could land anywhere — a quantifier alone. */
        private fun laidBare(page: Waiting, fate: Fate) {
            val landing = page.word.meanings.filter(Meaning::appliesUnaimed)
            val couldLand = landing.any { meaning -> placesOf(meaning).isNotEmpty() || meaning.effects.any { it is Effect.Template } }
            if (!couldLand) return impossible(page.index)
            fates[page.index] = fate
            for (meaning in landing) {
                namesATemplate(page.index, meaning)
                for ((place, effects) in placesOf(meaning)) {
                    claims += Claim(page.index, page.word, meaning, place, effects, clause = null, aimed = false, joined = page.joined)
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

/** The page whose name a meaning is offered to where it is said of the word after an `of`. */
private const val OF_PAGE = "of"
private const val AGE_PAGE = "age"

/** What turns a part's colours, and how fast they turn — `prismatic`'s and `swift`'s colour meaning. */
private val COLOURS_CYCLING = Prism.COLOURS_CYCLING.name
private val COLOUR_CYCLE_PACE = Prism.COLOUR_CYCLE_PACE.name

/** The name every part's own pace goes by — a sun's, a creature's, a phenomenon's — which `swift` and `slow` set. */
private const val OWN_PACE = "pace"

/** One meaning of one page at one place, before the draw decides whether it lands. */
internal data class Claim(
    val page: Int,
    val word: Word,
    val meaning: Meaning,
    val place: Place,
    val effects: List<Effect>,
    val clause: Int?,
    val aimed: Boolean,
    val joined: Int? = null,
    val sitedIn: Identifier? = null,
    val everywhere: Boolean = false,
    val polarity: Polarity = Polarity.ASSERTED,
)
