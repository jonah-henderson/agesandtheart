package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import co.voik.agesandtheart.age.resolution.WorldFacts
import co.voik.agesandtheart.age.resolution.clashesAmong
import co.voik.agesandtheart.age.resolution.flawsOf
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.JoinedPair
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.math.mix64
import net.minecraft.resources.Identifier
import kotlin.random.Random

/** What lands of a reading once its clashes are settled and its chances drawn, what could have, and what it cost. */
data class Drawn(
    val landed: List<Landing>,
    /** Everything the reading placed, before anything was settled or drawn. */
    val possible: List<Landing>,
    val dangling: List<Dangled>,
    /** One per clash and one per page that dangled for any other reason. */
    val flaws: List<Flaw>,
    /** The world the Age starts from, where the Age took a template. */
    val template: String?,
)

/** A page that placed nothing, and the word its meaning was drawn onto, or null where no word takes it. */
data class Dangled(val page: Int, val written: String, val drawnOnto: String?)

/**
 * A reading settled and drawn off the seed (`notes/the-word-rework.md`):
 *
 * - where two things that could land clash, the weaker comes to nothing there ([clashesAmong]);
 * - everything placed lands on its chance, a page's offers of one concept to one part as alternatives drawn once, and a
 *   page `age` handed out lands somewhere at least;
 * - a page that placed nothing, or all of whose landings were overruled, is drawn onto one word that takes it, never a
 *   gated one, and that word's phrase lands whole.
 */
object Draw {
    fun of(reading: Reading, words: Words, membership: Membership, world: WorldFacts, isGated: (Word) -> Boolean, seed: Long): Drawn =
        Drawing(reading, words, membership, world, isGated, seed).drawn()
}

private class Drawing(
    private val reading: Reading,
    private val words: Words,
    private val membership: Membership,
    private val world: WorldFacts,
    private val isGated: (Word) -> Boolean,
    private val seed: Long,
) {
    private val written = reading.pages.map { it.written }
    private val bodies = mutableMapOf<Aspect, Int>()

    fun drawn(): Drawn {
        val sayings = reading.phrases.map(::sayingOf)
        val possible = sayings.flatMap { it.landings(it.placements) } +
            reading.handedOut.flatMap(::landingsHandedOut) + ofTheWholeAge(reading.handedOut)
        val clashes = clashesAmong(world, possible, seed)
        val overruled = clashes.map { whereSaid(it.weaker) }.toSet()
        fun stands(landing: Landing) = whereSaid(landing) !in overruled

        val handedOutLanding = thatLandFromTheAge(reading.handedOut)
        val landed = sayings.flatMap { it.landings(thatLand(it.placements)) } +
            handedOutLanding.flatMap(::landingsHandedOut) + ofTheWholeAge(handedOutLanding)
        val outclashed = clashes.filterNot { it.isAYield }.map { whereSaid(it.weaker) }.toSet()
        fun lostAClash(ofThePage: List<Landing>) = ofThePage.any { whereSaid(it) in outclashed }
        val overruledWhole = possible.groupBy { it.page }.filterValues { ofThePage -> ofThePage.none(::stands) && lostAClash(ofThePage) }.keys
        val dangling = (reading.dangling.map(PageRead::index) + overruledWhole).distinct().sorted().map(::drawnOnto)
        val danglingFlaws = dangling.filter { (dangled, _) -> dangled.page !in overruledWhole }.map { (dangled, drawnLandings) ->
            Flaw(Register.DANGLES, listOfNotNull(dangled.written, dangled.drawnOnto), drawnLandings.firstOrNull()?.place?.aspect, emptyList())
        }
        val template = handedOutLanding.filter { it.part == null }.flatMap { it.effects }
            .filterIsInstance<Effect.Template>().firstOrNull()?.name
        return Drawn(
            landed = landed.filter(::stands) + dangling.flatMap { it.second },
            possible = possible,
            dangling = dangling.map { it.first },
            flaws = flawsOf(clashes) + danglingFlaws,
            template = template,
        )
    }

    /** What a clash overrules: the one landing that clashed — what one page said of one thing at one place — and no other. */
    private fun whereSaid(landing: Landing) = OverruledAt(landing.page, landing.saidOf, landing.place, landing.effects)

    private data class OverruledAt(val page: Int, val saidOf: Int, val place: Place, val effects: List<Effect>)

    // ----- Chance -----

    private fun randomFor(page: Int, salt: Long = 0L) = Random(mix64(seed xor (page * PAGE_STRIDE) xor salt))

    private val pageRandoms = mutableMapOf<Int, Random>()

    private fun randomOf(page: Int): Random = pageRandoms.getOrPut(page) { randomFor(page) }

    /**
     * Those of [placements] that land, each on its own chance — but a page's offers of one concept to one part are
     * alternatives, `frozen`'s white or grey sky: drawn once at the likeliest's chance, then one of them by their chances.
     */
    private fun thatLand(placements: List<Placement>): List<Placement> = placements.groupBy(::alternativesKey).values.flatMap { alternatives ->
        val random = randomOf(alternatives.first().from)
        when {
            alternatives.size == 1 -> alternatives.filter { random.nextDouble() < it.chance }
            random.nextDouble() >= alternatives.maxOf { it.chance } -> emptyList()
            else -> listOf(pickedFrom(alternatives, random))
        }
    }

    /**
     * What makes placements alternatives: one page, one part, one concept. Each reading stands alone, and so does each
     * value of a concept a part holds several of — `volcanic`'s four features, a member each — and each lean, since
     * nudges add up: `rich features` leans toward its ores and toward all it leans toward everywhere.
     */
    private fun alternativesKey(placement: Placement): Any {
        val concept = words.concepts[placement.offer.concept]
        val holdsSeveral = concept?.joined == JoinedPair.DIVIDE || concept?.values == ValueType.WEIGHTS
        return if (placement.offer.isAReading || holdsSeveral) placement else Triple(placement.from, placement.part, placement.offer.concept)
    }

    private fun pickedFrom(alternatives: List<Placement>, random: Random): Placement {
        var roll = random.nextDouble() * alternatives.sumOf { it.chance }
        for (alternative in alternatives) {
            roll -= alternative.chance
            if (roll <= 0.0) return alternative
        }
        return alternatives.last()
    }

    /** What `age` handed out that lands, and a page all of whose chances missed at its likeliest. */
    private fun thatLandFromTheAge(handedOut: List<Placement>): List<Placement> = handedOut.groupBy { it.at to it.from }.values
        .flatMap { ofThePage -> thatLand(ofThePage).ifEmpty { listOf(ofThePage.maxBy { it.chance }) } }

    // ----- Dangling -----

    /**
     * Where the dangling page at [page] is said instead: one word drawn from every word that would take something of it
     * laid after it, never a structure word or a gated one, read with it — the drawn word's phrase lands whole.
     */
    private fun drawnOnto(page: Int): Pair<Dangled, List<Landing>> {
        // Read again beside the word drawn, a page written with `no` or `only` would land as the opposite of what it says.
        val saidWithAPolarity = reading.pages.getOrNull(page)?.polarity?.let { it != Polarity.ASSERTED } == true
        if (saidWithAPolarity) return Dangled(page, written[page], null) to emptyList()
        val word = words.word(written[page])
        val candidates = if (word == null || word.defaultSense?.behaviour != null) emptyList() else candidatesFor(word)
        val drawn = takerDrawn(written[page], candidates, randomFor(page, DANGLING_SALT))
            ?: return Dangled(page, written[page], null) to emptyList()
        val (taker, read) = drawn
        val phrase = read.phrases.first()
        val saying = sayingOf(phrase, page = page, takerWord = taker)
        val landing = thatLand(saying.placements).ifEmpty { listOf(saying.placements.maxBy { it.chance }) }
        return Dangled(page, written[page], taker) to saying.landings(landing)
    }

    /**
     * One of [candidates] drawn by [random] that takes something of [dangling], with that reading, or null where none
     * does: each is read only once it is drawn, and one that takes nothing is set aside and the draw made again.
     */
    private fun takerDrawn(dangling: String, candidates: List<Word>, random: Random): Pair<String, Reading>? {
        val left = candidates.toMutableList()
        while (left.isNotEmpty()) {
            val taker = left.removeAt(random.nextInt(left.size))
            readWith(dangling, taker)?.let { return it }
        }
        return null
    }

    /**
     * [taker] laid after [dangling], where it closes a phrase taking something of it through an acceptance that resolves
     * dangling offers: its page, and that reading.
     */
    private fun readWith(dangling: String, taker: Word): Pair<String, Reading>? {
        val page = words.pageFor(taker)
        val read = Reader.read(words, listOf(dangling, page), membership, seed)
        val phrase = read.phrases.singleOrNull { it.head == 1 } ?: return null
        val sense = read.pages[1].sense?.let(taker.senses::getOrNull) ?: taker.defaultSense
        fun resolvesIt(placement: Placement): Boolean {
            val acceptance = sense?.acceptanceOf(placement.offer.concept)
            val resolves = placement.offer.isAReading || acceptance?.canResolveDanglingOffers == true
            return placement.from == 0 && resolves
        }
        val resolving = phrase.copy(placements = phrase.placements.filter(::resolvesIt))
        return if (resolving.placements.isEmpty()) null else page to read.copy(phrases = listOf(resolving))
    }

    /**
     * The words that could take something of [dangling]: no behaviour, not gated, and with a sense whose acceptance could
     * resolve one of its offers, or of the part one of its readings is for. Whether one does is for reading the two.
     */
    private fun candidatesFor(dangling: Word): List<Word> {
        val offers = dangling.senses.flatMap { it.offers }
        val concepts = offers.filterNot(Offer::isAReading).map(Offer::concept).toSet()
        val readingParts = offers.filter(Offer::isAReading).mapNotNull { (it.audience as? Audience.Part)?.aspect }.toSet()
        fun resolvesOne(sense: Sense) = concepts.any { sense.acceptanceOf(it)?.canResolveDanglingOffers == true }
        fun couldTakeIt(taker: Word) = taker.senses.any { sense -> resolvesOne(sense) || sense.speaksFor in readingParts }
        return words.all.filter { it.defaultSense?.behaviour == null && !isGated(it) && couldTakeIt(it) }
    }

    // ----- Landings -----

    /**
     * A phrase as what it says: its own meaning and what it took, said of its members. Read at [page] where it is a
     * dangling page's drawn phrase, its word [takerWord].
     */
    private fun sayingOf(phrase: Phrase, page: Int? = null, takerWord: String? = null): Saying {
        val part = phrase.part
        val placements = page?.let { at -> phrase.placements.map { it.copy(from = at, at = at, carrying = it.carrying.map { at }) } }
            ?: phrase.placements
        val named = namedMembersOf(phrase)
        val describesOnly = onlyDescribes(phrase, named)
        // A description of members asks for none of them, whether the thing names them or a word it took does: `red oak trees`.
        val own = if (describesOnly) phrase.own.filterNot(::namesMembers) else phrase.own
        val asking = if (describesOnly) placements.map { it.copy(effects = it.effects.filterNot(::namesMembers)) } else placements
        fun ofThePart(): Members =
            if (part?.holds == Holds.POPULATION) Members.Body(bodies.getOrDefault(part, 0).also { bodies[part] = it + 1 }) else Members.All
        val members = if (named != null) Members.Named(named) else ofThePart()
        val namingPages = placements.filter { it.offer.concept == CoreConcepts.MEMBER }.map { it.from }.toSet()
        val namesItselfToo = named != null && placements.any { it.from in namingPages && it.offer.concept != CoreConcepts.MEMBER }
        return Saying(
            head = page ?: phrase.head,
            word = takerWord ?: written[phrase.head],
            part = part,
            members = members,
            membersOfTheNamers = if (namesItselfToo) ofThePart() else members,
            namingPages = namingPages,
            own = own,
            placements = asking,
            polarity = phrase.polarity,
            joined = phrase.joined,
            sitedIn = phrase.sitedIn,
            everywhere = phrase.everywhere,
            biome = biomeNamedBy(phrase),
            isAPartsPage = part != null && words.partPage(part)?.id == words.word(written[phrase.head])?.id,
        )
    }

    /** The biome a phrase's thing names, where it is one: what is said of a biome is said in it. */
    private fun biomeNamedBy(phrase: Phrase): Identifier? {
        if (phrase.part != Aspect.BIOMES) return null
        val chosen = phrase.own.filterIsInstance<Effect.Chooses>().singleOrNull { it.aspect == Aspect.BIOMES } ?: return null
        return Identifier.tryParse(chosen.member)
    }

    /** Whether [effect] may be sited in one biome on [aspect]: a setting that may, or a population that may be. */
    private fun isConfinable(effect: Effect, aspect: Aspect): Boolean {
        val parameter = when (effect) {
            is Effect.Sets -> effect.parameter.name
            is Effect.Bends -> effect.parameter.name
            is Effect.Chooses, is Effect.Admits, is Effect.Excludes, is Effect.Restricts, is Effect.Leans -> return aspect.confinablePool != null
            else -> return false
        }
        return aspect.confinableParameters.any { it.name == parameter }
    }

    /** The members a phrase is said of: the member it took, or those its own meaning names or mints, or null for all of its part. */
    private fun namedMembersOf(phrase: Phrase): Set<String>? {
        val taken = phrase.placements.filter { it.offer.concept == CoreConcepts.MEMBER }
            .mapNotNull { (it.offer.value as? Value.Text)?.text }
        val own = phrase.own.mapNotNull { effect ->
            when (effect) {
                is Effect.Chooses -> effect.member.takeIf { effect.aspect == phrase.part }
                is Effect.Mints -> effect.pattern
                else -> null
            }
        } + phrase.own.filterIsInstance<Effect.Admits>().filter { it.aspect == phrase.part }.flatMap { it.members }
        // A struck or kept group is its bar, over every creature the Age could hold: `no monsters` strikes the warden too.
        val describesItsGroup = phrase.polarity == Polarity.ASSERTED
        return (taken + own).toSet().ifEmpty { null } ?: membersOfItsGroup(phrase)?.takeIf { describesItsGroup }
    }

    /**
     * A group's members, where its thing names none outright: those of its part clearing every bar its own meaning sets
     * — `monsters` the hostile creatures, `trees` the wooded features — so `slow monsters` slows those and no cow.
     */
    private fun membersOfItsGroup(phrase: Phrase): Set<String>? {
        val part = phrase.part ?: return null
        val holdsMembersToChooseBetween = part.holds == Holds.WEIGHTED_SET || (part.holds == Holds.CATALOGUE && !part.open)
        val bars = phrase.own.filterIsInstance<Effect.Restricts>().filter { it.aspect == part }.map { it.bars }
        if (!holdsMembersToChooseBetween || bars.isEmpty()) return null
        fun clearsEveryBar(member: Taggable) = bars.all { Bars.cleared(world.tagsOf(member), it) }
        return world.availableToBroadWordsIn(part).filter(::clearsEveryBar).map { it.key }.toSet().ifEmpty { null }
    }

    /**
     * Whether a phrase naming members only describes them, asking for none: `deserted villages` is about the villages
     * there are, and `red oak trees` about the oaks, though `oak` named them and not `trees`. Not where it asks an amount or a polarity, nor where its members are not there until something brings them,
     * and a bar is no description: `settled ruins` asks for ruins that are lived in.
     */
    private fun onlyDescribes(phrase: Phrase, named: Set<String>?): Boolean {
        val part = phrase.part ?: return false
        val effects = phrase.placements.flatMap { it.effects }
        val namesMembers = named != null && (phrase.own.any(::namesMembers) || effects.any(::namesMembers))
        val describes = effects.any { it !is Effect.Abundance && !namesMembers(it) }
        val asksForAnAmount = effects.any { it is Effect.Abundance }
        val isAPopulationOfMembers = part.holds == Holds.WEIGHTED_SET && part.pool?.startsEmpty != true
        val namedAreThereAnyway = named.orEmpty().all { membership.isThereByDefault(part, it) }
        val isAsserted = phrase.polarity == Polarity.ASSERTED
        return namesMembers && describes && !asksForAnAmount && isAsserted && isAPopulationOfMembers && namedAreThereAnyway
    }

    private fun namesMembers(effect: Effect) = effect is Effect.Chooses || effect is Effect.Restricts

    /** What `age` handed to a part, said of all of it. */
    private fun landingsHandedOut(placement: Placement): List<Landing> {
        val part = placement.part ?: return emptyList()
        return landingsOf(placement.effects, part, Members.All).map { (place, effects) ->
            Landing(effects, place, written[placement.from], placement.from, placement.at, placement.chance, joined = placement.joined)
        }
    }

    /** What the Age took itself that is said of it whole: a ban, and where a link arrives. */
    private fun ofTheWholeAge(handedOut: List<Placement>): List<Landing> {
        val bans = handedOut.filter { it.part == null }.mapNotNull { placement ->
            val banned = placement.effects.filterIsInstance<Effect.Bans>().ifEmpty { return@mapNotNull null }
            Landing(banned, WHOLE_AGE, written[placement.from], placement.from, placement.at, placement.chance)
        }
        val arrival = reading.arrival?.let { arrival ->
            Landing(listOf(Effect.LinksIn(arrival.anchor)), Place(arrival.anchor.aspect), written[arrival.page], arrival.page, arrival.page, CERTAIN)
        }
        return bans + listOfNotNull(arrival)
    }

    /** What a thing says, ready to become landings for whichever of its placements land. */
    private inner class Saying(
        val head: Int,
        val word: String,
        val part: Aspect?,
        val members: Members,
        /**
         * What a page naming members says besides is said of: the whole part, since only another page's description is
         * of the members named — `beautiful biomes` chooses three biomes and leans every one, `large zombie inhabitants`
         * makes the zombies large.
         */
        val membersOfTheNamers: Members,
        val namingPages: Set<Int>,
        val own: List<Effect>,
        val placements: List<Placement>,
        val polarity: Polarity,
        val joined: Int?,
        val sitedIn: Identifier?,
        val everywhere: Boolean,
        /** The biome it names, in which what it takes is said, or null where it is no biome. */
        val biome: Identifier?,
        /** Whether its thing is its part's own page — `features` — which, struck with nothing to narrow it, is every member. */
        val isAPartsPage: Boolean,
    ) {
        /**
         * Its own meaning and [landing] of its placements, said of its members. A negated or `only` thing is one claim,
         * struck or kept as described: what it took narrows it, a lean toward a tag becoming a bar on it, and it is said
         * by the word naming its members where that is another's — `desert` in `only desert biomes`.
         */
        fun landings(landing: List<Placement>): List<Landing> {
            val isNarrowed = polarity != Polarity.ASSERTED
            if (isNarrowed) {
                val described = own + landing.flatMap { it.effects }.map(::asANarrowing)
                val strikesEveryMember = described.isEmpty() && isAPartsPage && polarity == Polarity.EXCEPT && part != null
                val struck = if (strikesEveryMember && part != null) listOf(Effect.Excludes(part, setOf(Effect.Excludes.EVERY_MEMBER))) else described
                val namer = landing.firstOrNull { placement -> placement.effects.any(::namesMembers) }.takeIf { own.none(::namesMembers) }
                return if (namer == null) said(struck, word, head, CERTAIN, joined) else said(struck, written[namer.from], namer.from, CERTAIN, joined)
            }
            val bringsABody = members is Members.Body
            val ownLandings = if (own.isEmpty() && bringsABody) said(emptyList(), word, head, CERTAIN, joined, alwaysAtThePart = true)
                else said(own, word, head, CERTAIN, joined)
            val taken = landing.flatMap { placement ->
                val saidOf = if (placement.from in namingPages) membersOfTheNamers else members
                said(placement.effects, written[placement.from], placement.from, placement.chance, placement.joined, saidOf = saidOf)
            }
            return ownLandings + taken.mapNotNull(::sitedInItsBiome)
        }

        private fun said(
            effects: List<Effect>,
            by: String,
            page: Int,
            chance: Double,
            joinedBy: Int?,
            alwaysAtThePart: Boolean = false,
            saidOf: Members = members,
        ): List<Landing> {
            val grouped = if (alwaysAtThePart && part != null) listOf(Place(part, saidOf) to effects) else landingsOf(effects, part, saidOf)
            return grouped.map { (place, here) ->
                Landing(here, place, by, page, head, chance, joinedBy, sitedIn, everywhere, polarity)
            }
        }

        /**
         * [landing] said in the biome this names, where it is said of some other part: what may be sited there, and
         * nothing where nothing may — a biome never turns a setting for the whole Age. What lands on the biome itself is its own.
         */
        private fun sitedInItsBiome(landing: Landing): Landing? {
            val biome = biome ?: return landing
            if (landing.place.aspect == part) return landing
            val confined = landing.effects.filter { isConfinable(it, landing.place.aspect) }
            return if (confined.isEmpty()) null else landing.copy(effects = confined, sitedIn = biome)
        }
    }

    /** A lean toward tags, in a negated or `only` phrase, as the bar it narrows by: carrying the tag at all. */
    private fun asANarrowing(effect: Effect): Effect {
        if (effect !is Effect.Leans) return effect
        val towardTags = effect.weights.filter { (named, weight) -> named.startsWith(TAG_MARK) && weight > 0.0 }
        if (towardTags.isEmpty()) return effect
        return Effect.Restricts(effect.aspect, towardTags.keys.associate { it.drop(1) to CARRIES_IT })
    }

    /**
     * [effects] grouped by the part each lands on: [part] for what it does not say otherwise, with [members] where it is
     * that part — a parameter fenced elsewhere, a lean on another part, is said of all of that one.
     */
    private fun landingsOf(effects: List<Effect>, part: Aspect?, members: Members): List<Pair<Place, List<Effect>>> {
        val placed = effects.flatMap { effect -> aspectsOf(effect, part).map { aspect -> aspect to localised(effect, aspect) } }
        val grouped = placed.groupBy({ it.first }, { it.second }).toSortedMap(compareBy(Aspect::ordinal)).map { (aspect, here) ->
            val place = Place(aspect, if (aspect == part) members else Members.All)
            place to here.distinct().filterNot { countsNothing(it, place) }
        }
        return grouped.filter { (_, here) -> here.isNotEmpty() }
    }

    /** An abundance said of a catalogue counts a member it names, and of the whole of one, which has nothing to count, nothing: `few underground`. */
    private fun countsNothing(effect: Effect, place: Place): Boolean {
        val isACatalogueWithNoCount = place.aspect.holds == Holds.CATALOGUE && place.aspect.count == null
        return effect is Effect.Abundance && isACatalogueWithNoCount && place.members == Members.All
    }

    /** The parts [effect], said of a thing of [part], lands on; none for what is said of the Age whole. */
    private fun aspectsOf(effect: Effect, part: Aspect?): List<Aspect> = when (effect) {
        is Effect.Sets -> listOfNotNull(effect.parameter.fencedTo ?: part)
        is Effect.Bends -> listOfNotNull(effect.parameter.fencedTo ?: part)
        is Effect.Chooses -> listOf(effect.aspect)
        is Effect.Admits -> listOf(effect.aspect)
        is Effect.Excludes -> listOf(effect.aspect)
        is Effect.Restricts -> listOf(effect.aspect)
        is Effect.Leans -> listOf(effect.aspect)
        is Effect.LeansEverywhere -> Aspect.entries.filter { it.holds == Holds.CATALOGUE || it.holds == Holds.WEIGHTED_SET }
        is Effect.Mints -> listOf(part ?: Aspect.FEATURES)
        is Effect.Abundance, is Effect.MadeOf -> listOfNotNull(part)
        is Effect.Template, is Effect.Bans, is Effect.LinksIn -> emptyList()
    }

    private fun localised(effect: Effect, aspect: Aspect): Effect = if (effect is Effect.LeansEverywhere) Effect.Leans(aspect, effect.weights) else effect

    companion object {
        private const val CERTAIN = 1.0

        /** What a bar made of a lean asks: that a member carry the tag at all, as a group's bars ask. */
        private const val CARRIES_IT = 0.3

        private const val TAG_MARK = "#"

        /** Where what is said of the whole Age lands, set aside by the resolver before any part is resolved. */
        private val WHOLE_AGE = Place(Aspect.TERRAIN)

        // Arbitrary large odds, only so that neighbouring pages draw apart.
        private const val PAGE_STRIDE = -0x61c8_8646_80b5_83ebL
        private const val DANGLING_SALT = 0x0DA6_611EL
    }
}
