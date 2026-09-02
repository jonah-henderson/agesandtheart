package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Pool
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.grammar.Grammar

/**
 * What page could be laid next — **asked of the parser and the resolver, never worked out.**
 *
 * Two questions, and they are different. *Can* this page be laid is the grammar's, and it is answered by
 * running `Grammar.parses` over the row with a closing page on the end: no table of what follows what, no
 * second copy of `belongsHere`, nothing to fall out of step when the language moves.
 *
 * *Would it do anything* is the resolver's, and it has to be asked separately because *the grammar admits
 * more than the world does*. `belongsHere` is checked once per run of modifiers rather than per term, so
 * `pillars and arthropods landmass` is a perfectly good sentence in which `arthropods` is scoped to the
 * landmass, finds nothing there, and quietly does nothing at all. A player can learn that the hard way; a
 * tool for ourselves should not offer it.
 *
 * So a page is offered when it parses **and** bears on some aspect this clause could still be aimed at.
 * Which is asked with `carriersOf`, `answersIn` and `turnsAParameter` — the resolver's own reach, not a model
 * of it — so a page is only ever hidden for doing provably nothing.
 */
class Suggestions(private val vocabulary: Vocabulary) {

    /** One page that could be laid, and enough about it to choose between it and a thousand others. */
    data class Offer(
        val page: String,
        val says: String,
        val authored: Boolean,
        /** How specific the page is — its own column, because it is the first thing a writer sorts by. */
        val tier: String,
        /**
         * The parts of an Age this page speaks to, as their pages — a column of its own so a search for
         * `structures` finds every word that touches them.
         *
         * `Word.aspects` rather than what a word declares: the reach is derived from everything it
         * claims, so this is where the word speaks when nobody has aimed it anywhere.
         */
        val targets: String,
        /**
         * Whether this page is a **block**, which the grammar admits far more widely than [targets] says.
         *
         * A material stands in any clause about a part of the world that is *made of* something, whatever
         * the word itself reaches unprompted — `ice sea` is a sentence, and `Word.aspects` does not list
         * the sea for `ice`. Worth saying out loud, because the two columns otherwise look as though they
         * disagree.
         */
        val isMaterial: Boolean,
        /**
         * What it **insists on**, and what it merely **offers** — told apart.
         *
         * The one-line summary cannot say which is which, and the difference is the whole of what a word
         * like `inferno` is: it demands burning air and asks for a red sun, and a reader who cannot see
         * the join reads a word that overrides half the book when it does nothing of the kind.
         */
        val required: List<Claim>,
        val requested: List<Claim>,
        /** Whether it would close the clause being written, which is what most changes the shape. */
        val closes: Boolean,
    )

    /**
     * One thing a word claims — and, where it is a **pool**, the facets an Age draws between.
     *
     * A pool is one claim and several outcomes, and flattening it into a sentence loses which is which:
     * `2 of suns=2|3 colour=red|blue sea=air|lava` reads as three settings when it is two of three
     * choices. So the members travel with the claim rather than inside its wording.
     */
    data class Claim(val said: String, val drawnFrom: List<String> = emptyList()) {
        /** The whole of it on one line, for the column that has no room to break it out. */
        val spelled: String get() = if (drawnFrom.isEmpty()) said else "$said ${drawnFrom.joinToString(" ")}"
    }

    /** What could follow the row: what would do something, and what would parse and then not. */
    data class Offers(val bearing: List<Offer>, val inert: List<Offer>) {
        val all: List<Offer> get() = bearing + inert
    }

    /**
     * Every page that could follow [laid], split into the ones that would do something and the ones that
     * would only parse.
     */
    fun after(laid: List<String>): Offers {
        // Classified once: the row does not change while a thousand candidates are tried against it.
        val row = Grammar.reading(vocabulary, laid)
        fun readsWith(tail: List<String>) = row.parsesWith(tail)
        // The empty tail is one of them: a biome after `in` closes its clause where it stands.
        val everyTail = listOf(emptyList<String>()) + closers.map { listOf(it) }

        val direct = sayable.filter { page -> everyTail.any { readsWith(listOf(page) + it) } }
        // A page that needs *two* more before the row can close — `and`, `only`, a rung, `in`. The middle
        // one is drawn from what is already known to be layable, which is what lets this need no list of
        // structural words and no rule about them.
        //
        // **Only the closes still open are tried here.** A modifier narrows what a clause may be aimed at
        // and never widens it, so a tail ending in an aiming page this clause can no longer take cannot
        // parse whatever is put in front of it — and trying all twenty-three where one is left was four
        // fifths of the time this took.
        val worthTrying = listOf(emptyList<String>()) + closersAfter(laid).map { listOf(it) }
        val fillers = spreadOver(direct)
        val laidAlready = direct.toSet()
        val indirect = sayable.filterNot { it in laidAlready }
            .filter { page -> fillers.any { filler -> worthTrying.any { readsWith(listOf(page, filler) + it) } } }

        val aims = aimsStillOpen(laid)
        val clauseIsOpen = laid.isNotEmpty() && !isASentence(laid)
        val (bearing, inert) = (direct + indirect).map(::offerOf)
            .partition { bearsOn(it, aims, clauseIsOpen) }
        return Offers(ordered(bearing), ordered(inert))
    }

    private fun ordered(offers: List<Offer>) = offers.sortedWith(
        compareByDescending<Offer> { it.closes }.thenByDescending { it.authored }.thenBy { it.page },
    )

    /**
     * The aspects this clause could still be aimed at, or null where that is not yet decided.
     *
     * **Null at a clause boundary**, which is the whole gate. A row that is already a sentence is about to
     * open a new clause, and a clause not yet aimed at anything lets every word speak where it declares —
     * so there is nothing to be inert *against* and nothing worth pruning. Once something has been laid
     * into a clause the aim has narrowed, and that is exactly when a word can be carried in that has
     * nothing to say.
     */
    fun aimsStillOpen(laid: List<String>): Set<Aspect>? {
        if (laid.isEmpty() || isASentence(laid)) return null
        val closers = closersAfter(laid)
        // The nucleus is about the whole Age, so while it can still close, nothing is aimed anywhere.
        if (closers.any { vocabulary.word(it) == null }) return null
        val aimed = closers.mapNotNull { closer -> Aspect.entries.firstOrNull { it.page == closer } }.toSet()
        return aimed.ifEmpty { null }
    }

    private fun bearsOn(offer: Offer, aims: Set<Aspect>?, clauseIsOpen: Boolean): Boolean {
        // Structure carries nothing of its own; what it joins is judged on its own account. The nucleus
        // page arrives here too, and every book must have one.
        val word = vocabulary.word(offer.page) ?: return true
        // **An aiming page with nothing to aim says nothing.** It names a part of the world and supplies
        // no value of its own, so closing a clause that was never opened describes that part in no way at
        // all: `a floating age` then `landmass` is the same Age with a page spent on it.
        if (offer.closes) return clauseIsOpen
        if (aims == null) return true
        // An evocative word tilts the whole Age rather than a part, so it bears wherever it may stand.
        if (!word.tier.narrows) return true
        return aims.any { bears(word, it) }
    }

    /**
     * Whether laying [word] in a clause aimed at [aspect] would do **anything at all**.
     *
     * Every channel a word has, asked through the resolver's own functions: it means something there, its
     * query keeps something, anything answers it either way — `untouched` is entirely negative about a
     * population and perfectly well backed — or it turns a parameter that aspect honours, required or merely
     * requested. Only a word that fails all of them is hidden, so the bias is toward offering.
     */
    private fun bears(word: Word, aspect: Aspect): Boolean {
        if (word.meaningIn(aspect) != null) return true
        if (word.setsIn(aspect).keys.any { turnsAParameter(aspect, it) }) return true
        if (word.requestsIn(aspect).keys.any { turnsAParameter(aspect, it) }) return true
        if (word.requests.queries[aspect]?.isNotEmpty() == true) return true
        if (askableIn(aspect).any { word.acceptsOn(it, vocabulary.tagsOf(it)) }) return true
        return answersIn(word, aspect)
    }

    /**
     * `Vocabulary.answersIn`, over a candidate list read once.
     *
     * The vocabulary's own is a line long and asks `candidatesFor`, which sorts a couple of hundred
     * presets on every call — fine for the handful of calls resolving a book makes, and four fifths of
     * this screen's time when it is asked of seventeen hundred words in a row. `SuggestionsCheck` holds
     * this and the vocabulary's to the same answer for every word and every aspect, so the copy cannot
     * drift into a second opinion.
     */
    fun answersIn(word: Word, aspect: Aspect): Boolean =
        candidatesIn(aspect).any { word.affinityIn(aspect, vocabulary.tagsOf(it)) != 0.0 }

    /**
     * The aiming pages this clause could still be closed with — the guidance a writer actually needs.
     *
     * Which part of the world a clause is about is decided by the page that *ends* it, so while the
     * modifiers are being laid nothing on screen says what they are being said about. This does.
     */
    fun closersAfter(laid: List<String>): List<String> {
        val row = Grammar.reading(vocabulary, laid)
        return closers.filter { closer ->
            row.parsesWith(listOf(closer)) || everyFiller.any { row.parsesWith(listOf(it, closer)) }
        }
    }

    /** Whether the row as it stands is already a book, which is what makes it writable. */
    fun isASentence(laid: List<String>): Boolean =
        laid.isNotEmpty() && Grammar.parses(vocabulary, laid)

    // -- the corpus, read once ------------------------------------------------------------------------

    /**
     * Every page that closes a clause: the nucleus, and one aiming page per aspect.
     *
     * Taken from the vocabulary rather than listed, so an aspect gaining a page needs nothing here
     * changed — and an aspect whose page nobody wrote simply is not offered.
     */
    private val closers: List<String> by lazy {
        (vocabulary.grammarWords.map { it.name }.filter { Grammar.isABook(vocabulary, listOf(it)) } +
            Aspect.entries.mapNotNull { it.page }.distinct().filter { vocabulary.word(it) != null })
            .distinct()
    }

    /**
     * Every page a writer could lay, once each.
     *
     * Distinct because a name can be two words: `air` is an aspect's aiming page *and* a block, and a list
     * offering it twice is a list somebody has to look at twice to see they are the same.
     */
    private val sayable: List<String> by lazy {
        (vocabulary.words.map { it.name } + vocabulary.grammarWords.map { it.name }).distinct()
    }

    private val everyFiller: List<String> by lazy { spreadOver(sayable) }

    /**
     * A few pages standing for the many, for the one place a sample is safe.
     *
     * Used only to find the *second* page of a two-page tail, so a signature that missed a distinction
     * costs a suggestion that is not offered — never one offered wrongly. One page per tier and aspect
     * set, which is what the parser can see about a page at all.
     */
    private fun spreadOver(pages: List<String>): List<String> =
        pages.groupBy { page -> vocabulary.word(page)?.let { it.tier to it.aspects } }
            .values.map { it.first() }
            .take(FILLERS)

    /** `candidatesFor` sorts a list of a couple of hundred, and this asks it for every page offered. */
    private val candidates = mutableMapOf<Aspect, List<Taggable>>()
    private val askable = mutableMapOf<Aspect, List<Taggable>>()

    private fun candidatesIn(aspect: Aspect): List<Taggable> =
        candidates.getOrPut(aspect) { vocabulary.candidatesFor(aspect) }

    private fun askableIn(aspect: Aspect): List<Taggable> =
        askable.getOrPut(aspect) { vocabulary.askableIn(aspect) }

    private val parameters = mutableMapOf<Pair<Aspect, String>, Boolean>()

    private fun turnsAParameter(aspect: Aspect, parameter: String): Boolean =
        parameters.getOrPut(aspect to parameter) { vocabulary.turnsAParameter(aspect, parameter) }

    private fun offerOf(page: String): Offer {
        val word = vocabulary.word(page)
        val required = word?.let { insistedOn(it) }.orEmpty()
        val requested = word?.let { offeredBy(it) }.orEmpty()
        return Offer(
            page = page,
            says = summaryOf(word, required, requested),
            authored = word != null && !vocabulary.isDerived(word),
            tier = word?.tier?.key.orEmpty(),
            targets = word?.aspects.orEmpty().sortedBy { it.ordinal }.joinToString(" ") { it.page },
            isMaterial = word?.material != null,
            required = required,
            requested = requested,
            closes = page in closers,
        )
    }

    /** One line for the column — what it insists on, or what it offers where it insists on nothing. */
    private fun summaryOf(word: Word?, required: List<Claim>, requested: List<Claim>): String = when {
        word == null -> "structure — joins, qualifies or aims what is around it"
        required.isNotEmpty() -> required.joinToString(", ") { it.spelled } +
            if (requested.isEmpty()) "" else "  (and offers ${requested.size} more)"
        requested.isNotEmpty() -> "offers " + requested.joinToString(", ") { it.spelled }
        // **What is left is an aiming page and nothing else** — now that a template counts as a claim.
        // It opens a clause, says which part of the world that clause is about, and supplies no value of
        // its own. Which part is the targets column's to say, so this says the other half.
        else -> "opens a clause about this part of the world; sets nothing itself"
    }

    /** What the word demands: meant outright, set, drawn from a pool, minted, or asked of the tags. */
    private fun insistedOn(word: Word): List<Claim> = buildList {
        // **The template is a claim like any other, and the largest one a page can make.** It was read
        // nowhere here, so `dark_void` and `infernal` — whose whole job is choosing the world the book
        // starts from — fell through to the sentence about aiming pages and said they did nothing.
        word.template?.let { add(Claim("begins the Age from $it, not the overworld")) }
        word.meansExactly.forEach { (aspect, key) -> add(Claim("means $key in the ${aspect.page}")) }
        word.mints?.let { add(Claim("mints $it")) }
        addAll(word.everySet.map { (parameter, value) -> Claim("$parameter=$value") })
        addAll(word.pools.mapNotNull(::poolOf))
        if (word.wanted.isNotEmpty()) add(Claim(word.wanted.joinToString(" ") { "$TAG_MARK$it" }))
        if (word.unwanted.isNotEmpty()) add(Claim(word.unwanted.joinToString(" ") { "-$TAG_MARK$it" }))
    }

    /** What it offers instead — laid under the sentence, and given up wherever the book already spoke. */
    private fun offeredBy(word: Word): List<Claim> = buildList {
        addAll(word.requests.sets.map { (parameter, value) -> Claim("$parameter=$value") })
        addAll(word.requests.pools.mapNotNull(::poolOf))
        val tags = word.requests.queries.values.flatMap { it.entries }
        val pulled = tags.filter { it.value > 0.0 }.map { it.key }.distinct()
        val pushed = tags.filter { it.value < 0.0 }.map { it.key }.distinct()
        if (pulled.isNotEmpty()) add(Claim(pulled.joinToString(" ") { "$TAG_MARK$it" }))
        if (pushed.isNotEmpty()) add(Claim(pushed.joinToString(" ") { "-$TAG_MARK$it" }))
    }

    /**
     * A pool, said the way `facetsDrawnAt` reads it.
     *
     * **Zero draws nothing**, which is worth saying outright rather than leaving as a pool that quietly
     * never fires; a count at or past the pool's size takes all of it, which makes the pool no different
     * from more `sets` and is equally worth saying.
     */
    private fun poolOf(pool: Pool): Claim? {
        if (pool.facets.isEmpty()) return null
        val members = pool.facets.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }
        val how = if (pool.draws.least >= pool.facets.size) {
            "all of these, every Age"
        } else {
            "${pool.draws} of these, drawn per Age"
        }
        return Claim(how, members)
    }

    private companion object {
        /** What marks a tag, the same mark the word screens use. */
        const val TAG_MARK = "#"

        /**
         * How many stand-ins the two-page tail tries.
         *
         * The whole spread is a couple of hundred and the tail is tried for every page that failed the
         * one-page probe, so this is the cost of the slow half. What is looked for is a page that fits
         * *anywhere* in the clause, and where a spread of two dozen finds none there is rarely one.
         */
        const val FILLERS = 24
    }
}
