package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.namesARegistryEntry
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.ui.Insistence
import co.voik.agesandtheart.preview.authoring.ui.everythingOn
import co.voik.agesandtheart.preview.authoring.ui.poolsOn

/**
 * What is wrong with a word, and what is merely worth thinking about — **the same rules the checks hold,
 * asked while the word is being written rather than three minutes later in a Gradle run.**
 *
 * Every [Standing.ERROR] here is a check that would fail, and each says which one, so a refusal is
 * traceable to the rule rather than to this tool's opinion. Every [Standing.NUDGED] is a settled ruling
 * from `vocabulary-pass-plan.md` §5.5 — advice, never a bar, because leading an author towards a
 * judgement is worth more than scolding them after the fact and worth much less than stopping them
 * writing a word they meant.
 *
 * Pure: a candidate and a corpus in, a list out. Nothing here touches a terminal or a file.
 */
object Verdict {

    enum class Standing { ERROR, WARNED, NUDGED, NOTED }

    /**
     * One thing to say about a word. [heldBy] names the check that owns the rule where there is one, which
     * is how a person confirms the tool is not making it up.
     */
    data class Finding(
        val standing: Standing,
        val says: String,
        val because: String? = null,
        val heldBy: String? = null,
    )

    fun on(candidate: Candidate, corpus: Corpus): List<Finding> {
        val word = candidate.asWord().getOrElse { failure ->
            return listOf(Finding(Standing.ERROR, "this word will not load", failure.message, "Vocabulary.load"))
        }
        return buildList {
            addAll(nameFaults(candidate, corpus))
            addAll(parameterFaults(word, corpus))
            addAll(strengthFaults(candidate))
            addAll(pooledButNeverDrawn(candidate))
            addAll(tagFaults(word, corpus))
            // **On its own, not inside the reach rules.** A word that chooses a member it may not choose
            // reaches nowhere, so nesting this under them lost it behind "this word does nothing".
            addAll(meaningFaults(candidate, word, corpus))
            addAll(reachFaults(candidate, word, corpus))
            addAll(listingGaps(candidate))
            addAll(nudges(candidate, word, corpus))
            addAll(quarrels(word, corpus))
        }
    }

    /** Whether anything here would fail a check — what the writer has to clear before saving. */
    fun refusals(findings: List<Finding>) = findings.filter { it.standing == Standing.ERROR }

    // -- the name ------------------------------------------------------------------------------------

    /**
     * A pool that takes all of itself.
     *
     * **The other half of this is gone, and could not come back.** `pool` with no `draws` looked like
     * three facets and was three facets the Age would never take; a pool now has to say how much of
     * itself it is, and a count that never draws is refused by the codec. What is left is the nudge: a
     * pool drawing its whole size is what `sets` already means, spelled at more length.
     */
    private fun pooledButNeverDrawn(candidate: Candidate): List<Finding> = buildList {
        for (insistence in Insistence.entries) {
            for (pool in candidate.poolsOn(insistence)) {
                if (pool.draws.least < pool.facets.size) continue
                add(
                    Finding(
                        Standing.NUDGED,
                        "the ${insistence.title} ${pool.said} pool takes all of itself",
                        "it draws ${pool.draws} of ${pool.facets.size}, which is what `sets` already means",
                    ),
                )
            }
        }
    }

    private fun nameFaults(candidate: Candidate, corpus: Corpus): List<Finding> = buildList {
        val name = candidate.name
        if (name.isBlank()) {
            add(Finding(Standing.ERROR, "a word needs a name"))
            return@buildList
        }
        if (!name.matches(LEGAL_NAME)) {
            add(
                Finding(
                    Standing.ERROR,
                    "'$name' cannot be a resource path",
                    "lower case, digits and _ - . / only",
                    "ResourcePathCheck",
                ),
            )
        }
        Aspect.entries.firstOrNull { it.page == name }?.let { aspect ->
            add(
                Finding(
                    Standing.ERROR,
                    "'$name' is already the name of an aspect",
                    "it would collide with ${aspect.key}'s own page",
                    "Vocabulary.load",
                ),
            )
        }
        corpus.vocabulary.grammarWord(name)?.let {
            add(
                Finding(
                    Standing.WARNED,
                    "'$name' is a structural word",
                    "the grammar is checked first, so this word would never be reached",
                ),
            )
        }
    }

    // -- the parameters -----------------------------------------------------------------------------------

    /**
     * Every parameter the word turns, asked whether anything reads it and whether it takes the value.
     *
     * **`canSet` rather than `everySet`**, which is the hole this tool exists to close: the corpus check's
     * typo guard reads `sets` alone, so `inferno`'s pooled `suns` — a parameter no aspect has owned since the
     * world model deleted counts — has been inert and unreported. A page paid for that sets nothing is
     * §3.3's silent drop, and a pool key is as capable of it as a core one.
     */
    private fun parameterFaults(word: Word, corpus: Corpus): List<Finding> = buildList {
        for (spelled in word.unreadableParameters) {
            add(
                Finding(
                    Standing.ERROR,
                    "there is no aspect called '${spelled.substringBefore('.')}'",
                    "nothing will ever read '$spelled'",
                    "VocabularyCheck.unreadableParameters",
                ),
            )
        }
        val aimedAnywhere = word.aspects.isEmpty()
        for ((parameter, value) in word.canSet) {
            val turnedSomewhere = word.aspects.any { corpus.vocabulary.turnsAParameter(it, parameter) }
            if (!aimedAnywhere && !turnedSomewhere) {
                add(
                    Finding(
                        Standing.ERROR,
                        "nothing this word targets has a parameter called '$parameter'",
                        "so setting it does nothing",
                        "VocabularyCheck",
                    ),
                )
                continue
            }
            addAll(valueFaults(word, parameter, value, corpus))
        }
    }

    /**
     * What the two halves may and may not each hold.
     *
     * **A cast is only ever offered.** A population's members are the writer's to describe (world model
     * §2) — they mint a sun by describing one — so a word that *insisted* on three would be overruling
     * them, and there is no charge that would make that fair. Offered, it fills an empty sky and vanishes
     * the moment a clause mints anything, which is the same rule the template already lives by.
     */
    private fun strengthFaults(candidate: Candidate): List<Finding> = buildList {
        val demanded = candidate.everythingOn(Insistence.REQUIRED)
        for (parameter in demanded.keys.filter { it.substringAfterLast('.') == Parameter.CAST }) {
            add(
                Finding(
                    Standing.ERROR,
                    "'$parameter' cannot be required",
                    "a count can only be requested, so a book that names its own suns still wins. Move it to requested.",
                    "VocabularyCheck",
                ),
            )
        }
        val offered = candidate.everythingOn(Insistence.REQUESTED)
        for (parameter in demanded.keys intersect offered.keys) {
            add(
                Finding(
                    Standing.ERROR,
                    "'$parameter' is both required and requested",
                    "the requested one gives way to the required one, so it can never apply",
                ),
            )
        }
        for (parameter in offered.keys.filter { it.substringAfterLast('.') == Parameter.CAST && '.' !in it }) {
            add(
                Finding(
                    Standing.NUDGED,
                    "'$parameter' does not say which",
                    "as written it counts suns, moons and climates alike. Say sun.cast for suns only.",
                ),
            )
        }
    }

    /** Every alternative of a value, asked of every parameter of that name the word could land on. */
    private fun valueFaults(word: Word, parameter: String, value: String, corpus: Corpus): List<Finding> = buildList {
        val offered = word.aspects.flatMap { parametersNamed(it, parameter, corpus) }
        if (offered.isEmpty()) return@buildList
        val alternatives = value.split('|').map(String::trim).filter(String::isNotEmpty)
        for (one in alternatives.filterNot { one -> offered.any { it.accepts(one) } }) {
            val wouldFallThrough = offered.any { it.holdsYouUp && it.open && namesARegistryEntry(one) }
            add(
                Finding(
                    Standing.ERROR,
                    "'$parameter' does not accept '$one'",
                    if (wouldFallThrough) {
                        "you would fall through it. Ground needs a solid full block with no block entity."
                    } else {
                        offeredValues(offered)
                    },
                    if (wouldFallThrough) "MaterialsCheck" else "VocabularyCheck",
                ),
            )
        }
    }

    private fun offeredValues(offered: List<Parameter>): String {
        val parameter = offered.first()
        return when {
            parameter.holds == Holds.RANGE -> "it takes a band like 0.3..1.0, a floor like >0.4, a nudge like +0.2 or a spread like ~0.1"
            parameter.open -> "a registry id, or one of: ${parameter.options.joinToString(" ")}"
            else -> "one of: ${parameter.options.joinToString(" ")}"
        }
    }

    /** Every parameter of that name [aspect] could actually act on — its own parameters, and its presets'. */
    fun parametersNamed(aspect: Aspect, parameter: String, corpus: Corpus): List<Parameter> =
        (corpus.vocabulary.candidatesFor(aspect).flatMap { it.ownParameters } + aspect.parameters)
            .filter { it.name == parameter }

    // -- the tags ------------------------------------------------------------------------------------

    private fun tagFaults(word: Word, corpus: Corpus): List<Finding> = buildList {
        val carried = corpus.vocabulary.carriedTags
        val onlyAServerGrants = corpus.vocabulary.tagsOnlyAServerGrants
        for (tag in word.everyTagAsked.keys + word.leanedTags) {
            if (tag in carried) continue
            if (tag in onlyAServerGrants) {
                val members = corpus.snapshot?.serverOnly?.get(tag)
                add(
                    Finding(
                        Standing.NOTED,
                        "'$tag' only exists on a running server",
                        members?.let {
                            "a server had it on ${it.size} thing(s) — ${corpus.snapshot?.provenance}"
                        } ?: "nothing offline can carry it — load minecraft data to see what a server does",
                    ),
                )
                continue
            }
            if (corpus.vocabulary.antonyms.any { it.first == tag || it.second == tag }) {
                add(
                    Finding(
                        Standing.NOTED,
                        "'$tag' matches nothing, which is fine here",
                        "it is in the antonym table, so it describes the word instead of picking anything",
                        "TagCoverageCheck",
                    ),
                )
                continue
            }
            add(
                Finding(
                    Standing.ERROR,
                    "nothing has the tag '$tag'",
                    "check the spelling; as written it does nothing",
                    "TagCoverageCheck",
                ),
            )
        }
    }

    // -- what it is about ----------------------------------------------------------------------------

    private fun reachFaults(candidate: Candidate, word: Word, corpus: Corpus): List<Finding> = buildList {
        // **A narrowing word has to reach somewhere**, and now that the reach is derived that means it
        // has to claim something: a parameter, a named preset, a keyed query, a pattern to mint. A word that
        // claims nothing is not a word that narrows everything, it is a word that does nothing at all.
        if (word.tier.narrows && word.template == null && word.aspects.isEmpty()) {
            // **Say which claim failed to place it.** "Reaches nowhere" is the consequence; a parameter no
            // aspect owns is the cause, and the more specific complaints below never run because they
            // work aspect by aspect and there are none.
            val homeless = word.canSet.keys.filterNot { parameter ->
                Aspect.entries.any { it.ownsParameterNamed(parameter.substringAfterLast('.')) }
            }
            add(
                Finding(
                    Standing.ERROR,
                    if (homeless.isEmpty()) {
                        "a ${word.tier.key} word that reaches nowhere"
                    } else {
                        "no aspect owns ${homeless.sorted().joinToString(" ")}, so the word reaches nowhere"
                    },
                    "nothing it claims names a part of the world — key a query, turn a parameter, or name a preset",
                    "VocabularyCheck",
                ),
            )
            return@buildList
        }
        if (Resolver.pricedIn(corpus.vocabulary, word).isEmpty() && word.template == null) {
            add(
                Finding(
                    Standing.ERROR,
                    "this word does nothing",
                    "it sets no parameter, names nothing and matches no tag",
                    "VocabularyCheck",
                ),
            )
            return@buildList
        }
        addAll(meaningFaults(candidate, word, corpus))
        for (aspect in word.aspects.sortedBy { it.ordinal }) {
            addAll(sayInFaults(word, aspect, corpus))
        }
        // **Asked of authored words only**, as `VocabularyCheck` asks it. A derived word *is* the thing it
        // means — `amethyst_block` is the block, and sets it as the stone and as the surface — so measuring
        // it against this rule reported every one of them as a synonym of itself.
        val referents = (word.chooses.values + word.sets.values)
            .filter(::namesARegistryEntry)
            .distinct()
        val saysNothingByTag = word.leansEverywhere.isEmpty() && word.biases.isEmpty() &&
            word.restricts.isEmpty()
        if (!candidate.isDerived && referents.isNotEmpty() && saysNothingByTag) {
            add(
                Finding(
                    Standing.ERROR,
                    "there is already a word for this",
                    "${referents.joinToString()} already has its own word, and this adds nothing to it",
                    "VocabularyCheck",
                ),
            )
        }
    }

    /**
     * What only a narrowing word may mean, and what only one page may mean.
     *
     * **An evocative word's meaning is never read.** `Resolver.fill` asks `carriersOf` of narrowing words
     * alone, so the preset is not chosen — and the tag pass then *excludes* a word that means a member,
     * on the grounds that it arrived with its answer in hand, so the tilt the word was written for goes
     * with it. Two claims lost for one that was never going to land.
     *
     * **And a preset has one page.** Every landform mints its own now, so a word meaning one is a synonym
     * for a page that already exists — which `duplicates` cannot see, since it skips derived words
     * deliberately.
     */
    private fun meaningFaults(candidate: Candidate, word: Word, corpus: Corpus): List<Finding> = buildList {
        for ((aspect, key) in word.chooses) {
            if (!word.tier.narrows) {
                add(
                    Finding(
                        Standing.ERROR,
                        "an evocative word cannot choose '$key' outright",
                        "only a narrowing word chooses a member; here it would lose the lean as well",
                        "VocabularyCheck",
                    ),
                )
                continue
            }
            val already = corpus.vocabulary.words.distinct()
                .firstOrNull { it.choiceIn(aspect)?.key == key && it.name != candidate.name }
                ?: continue
            add(
                Finding(
                    Standing.ERROR,
                    "'${already.name}' already chooses '$key'",
                    "a member has one page, and ${aspect.page}'s '$key' has that one",
                    "VocabularyCheck",
                ),
            )
        }
    }

    /** Whether the word has anything to do in one aspect it declares — mirroring `VocabularyCheck`'s gate. */
    private fun sayInFaults(word: Word, aspect: Aspect, corpus: Corpus): List<Finding> {
        val turnsAParameterHere = word.canSet.keys.any { corpus.vocabulary.turnsAParameter(aspect, it) }
        if (!word.constrainsPresetsIn(aspect) || turnsAParameterHere) return emptyList()
        if (aspect.holds == Holds.WEIGHTED_SET) {
            val onlyAServerCouldAnswer = word.wanted.isNotEmpty() &&
                word.wanted.all(corpus.vocabulary.tagsOnlyAServerGrants::contains)
            if (onlyAServerCouldAnswer || corpus.vocabulary.answersIn(word, aspect)) return emptyList()
            return listOf(
                Finding(
                    Standing.ERROR,
                    "nothing in ${aspect.page} matches",
                    "nothing there has the tags ${word.wanted.joinToString(" ")}",
                    "VocabularyCheck",
                ),
            )
        }
        if (corpus.vocabulary.carriersOf(word, aspect).isNotEmpty()) return emptyList()
        return listOf(
            Finding(
                Standing.ERROR,
                "nothing in ${aspect.page} is tagged strongly enough",
                "${word.tier.key} needs ${word.wanted.joinToString(" ")} at ${word.tier.threshold} or better",
                "VocabularyCheck",
            ),
        )
    }

    // -- the third currency --------------------------------------------------------------------------

    private fun listingGaps(candidate: Candidate): List<Finding> = buildList {
        val listing = WordFile.listingFor(candidate.listingKey)
        if (listing.rarity == null) {
            add(
                Finding(
                    Standing.WARNED,
                    "no rarity set",
                    "how hard this is to find has not been decided",
                ),
            )
        }
        if (listing.ink == null) {
            add(Finding(Standing.NOTED, "no ink quality set", "common by default"))
        }
    }

    // -- the settled rulings -------------------------------------------------------------------------

    private fun nudges(candidate: Candidate, word: Word, corpus: Corpus): List<Finding> = buildList {
        for (spelled in candidate.everythingOn(Insistence.REQUIRED).keys) {
            if (!spelled.contains('.')) continue
            val parameter = spelled.substringAfter('.')
            val owners = Aspect.entries.filter { it.ownsParameterNamed(parameter) }
            if (owners.size > 1) continue
            add(
                Finding(
                    Standing.NUDGED,
                    "'$spelled' does not need the prefix",
                    "only ${owners.firstOrNull()?.page ?: "nothing"} has a parameter called '$parameter'",
                ),
            )
        }
        for ((parameter, value) in word.everySet) {
            val reached = word.aspects.filter { corpus.vocabulary.turnsAParameter(it, parameter) }
            if (reached.size > 1) {
                add(
                    Finding(
                        Standing.NOTED,
                        "'$parameter' applies to ${reached.joinToString(" ") { it.page }}",
                        "check that is what you meant on each of them",
                    ),
                )
            }
            addAll(quietEndNudge(word, parameter, value, corpus))
        }
        addAll(duplicates(candidate, word, corpus))
    }

    /**
     * Another word that would make the same Age.
     *
     * **Asked only of a word that claims something**, which is not fussiness: a template word and an
     * aiming page both have an empty query, an empty `sets` and mean nothing outright, so a comparison that
     * counted those as agreement said `dark_void` duplicated every page in the corpus.
     */
    private fun duplicates(candidate: Candidate, word: Word, corpus: Corpus): List<Finding> {
        val claimsSomething = word.everySet.isNotEmpty() || word.leansEverywhere.isNotEmpty() ||
            word.biases.isNotEmpty() || word.restricts.isNotEmpty() || word.chooses.isNotEmpty() ||
            word.admits.isNotEmpty() || word.excludes.isNotEmpty()
        if (!claimsSomething) return emptyList()
        return corpus.otherThan(candidate.name)
            .filterNot(corpus.vocabulary::isDerived)
            .filter { other ->
                // **The reach is part of what a word is**, and leaving it out said `fish` duplicated
                // `reefs`: both are restrictive and want `aquatic`, and one chooses creatures where the
                // other chooses features.
                other.tier == word.tier && other.aspects == word.aspects &&
                    other.everySet == word.everySet && other.leansEverywhere == word.leansEverywhere &&
                    other.biases == word.biases && other.restricts == word.restricts &&
                    other.chooses == word.chooses && other.admits == word.admits &&
                    other.excludes == word.excludes && other.template == word.template &&
                    other.mints == word.mints
            }
            .map { other ->
                Finding(
                    Standing.WARNED,
                    "'${other.name}' already does this",
                    "two words for one thing should differ somehow",
                )
            }
    }

    /**
     * **Never ship a word that says what saying nothing says.** `finely` asked for the fine end of the
     * mingling after the fine end became the default, so it changed nothing — and the corpus is something
     * a player has to find and learn, which makes a word that does nothing worse than no word.
     */
    private fun quietEndNudge(word: Word, parameter: String, value: String, corpus: Corpus): List<Finding> = buildList {
        val offered = word.aspects.flatMap { parametersNamed(it, parameter, corpus) }.distinct()
        val parameter = offered.firstOrNull() ?: return@buildList
        if (parameter.holds != Holds.RANGE) {
            if (offered.all { it.default == value }) {
                add(
                    Finding(
                        Standing.NUDGED,
                        "'$parameter' is already '$value' by default",
                        "so this word says what saying nothing says",
                    ),
                )
            }
            return@buildList
        }
        val settled = Setting.read(value) as? Setting.Fixed ?: return@buildList
        if (settled.span.width >= WHOLE_AXIS) {
            add(
                Finding(
                    Standing.NUDGED,
                    "'$parameter' is bounded to the whole axis",
                    "a band that excludes nothing asks for nothing — put the default at the quiet end and " +
                        "make every word ask for more than it",
                ),
            )
        }
    }

    // -- who it argues with --------------------------------------------------------------------------

    private fun quarrels(word: Word, corpus: Corpus): List<Finding> {
        val against = corpus.otherThan(word.name)
            .filterNot(corpus.vocabulary::isDerived)
            .mapNotNull { other -> corpus.vocabulary.disagreement(word, other)?.let { other.name to it } }
        if (against.isEmpty()) return emptyList()
        return listOf(
            Finding(
                Standing.NOTED,
                "it contradicts ${against.size} word(s)",
                against.joinToString(", ") { (name, why) -> "$name over ${why.over.joinToString("/")}" },
            ),
        )
    }

    private val LEGAL_NAME = Regex("[a-z0-9/._-]+")

    /** A band this wide over a natural axis of -1..1 leaves nothing out, so it demands nothing. */
    private const val WHOLE_AXIS = 2.0
}
