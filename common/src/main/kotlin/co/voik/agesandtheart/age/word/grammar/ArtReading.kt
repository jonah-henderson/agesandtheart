package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.resources.Identifier

/**
 * The parser. **Recursive descent over an already-classified row of pages** — no lexing, no precedence,
 * no recovery, and no dependency.
 *
 * The whole of the language is four productions, and they are short enough to read here:
 *
 * ```
 * book     : nucleus clause*
 * nucleus  : modifier* AGE
 * clause   : modifier* close
 * close    : SUBJECT siting? | siting
 * siting   : IN biome
 * modifier : (ONLY | EXCEPT)? term (AND term)*
 * term     : QUANTIFIER? word
 * ```
 *
 * **Every clause ends with the thing it is about**, which is the whole shape of the language: *a beautiful
 * floating Age*, *pillared and gentle landmass*, *in jungles, an end stone surface*. Modifiers lead and the
 * page they modify closes the clause, the way an English noun phrase does.
 *
 * That is a reversal (§4.3.1, 2026-08-07). Sections used to be *opened* by their aiming page with modifiers
 * trailing, which read as `landmass of pillars` and wanted a particle the writer never laid. Leading
 * modifiers cost nothing to parse — the clause is terminated by its own subject rather than by the next
 * one starting — and the reading is then simply the pages in the order they were written.
 *
 * **It also removed a production.** A mood used to have a slot of its own before the subject, because
 * that was the only way to lean on something that had not been named yet. Everything leads now, so there is
 * one run of modifiers, and whether a word tilts or narrows is read off what it claims.
 *
 * **Why this and not a parser generator** (settled in advance, `decisions.md`). A generated grammar had to
 * name every aspect four times over — a token pair, a section alternative, a modifier rule and a term rule
 * each — so the parts of the world were spelled into a build artefact and the player-facing division could
 * not move without regenerating one. Worse, a page's terminal had to be chosen from the *word* before the
 * parse, so a word at home in two sections could only ever be written in the first of them.
 *
 * **Nothing is recovered from.** A book either reads or it does not, and one that does not is [Repair]'s
 * to make a sentence of — so this returns null rather than a tree patched into something no writer chose.
 */
internal object ArtReading {

    /** `in` and the biome it names — what a siting costs the cursor once it has been read. */
    private const val PAGES_IN_A_SITING = 2

    /** What makes a term unambiguous, and so able to close its own clause — see [Reading.trailingCloseAt]. */
    private const val ONE_PART_OF_THE_WORLD = 1

    /** The book [pages] spell, or null where they spell none — which is [Repair]'s cue, never an error. */
    fun parse(pages: List<Page>): Sentence? {
        val phrases = Reading(pages).book() ?: return null
        // Only what the writer laid: a latent page is the Art's own and costs nobody ink.
        val spelled = pages.filterNot(Page::latent).mapNotNull(Page::production)
        return Sentence(phrases, structural = spelled)
    }

    private class Reading(private val pages: List<Page>) {
        private var at = 0
        private var nextGroup = 0

        /**
         * How many members of each population the book has described so far, so the next clause aimed at one
         * describes the next body rather than arguing with the last (`the-world-model.md` §2).
         */
        private val described = mutableMapOf<Aspect, Int>()

        /**
         * Whether something was met that cannot be read at all — an `and` with nothing after it, an `in`
         * naming no biome, an `only` qualifying nothing.
         *
         * A flag rather than a null return from every helper, because these are *refusals* rather than
         * "this clause has ended": swallowing one and carrying on would make the book read, which is the
         * silent acceptance §3.3 forbids and the reason `Repair` has a channel for a page with nowhere to
         * go at all.
         */
        private var refused = false

        private val here: Page? get() = pages.getOrNull(at)

        private fun take(): Page = pages[at++]

        /**
         * `nucleus section*` — **every book opens with the `age` page**, and one that does not is no more a
         * sentence than one whose pages are in the wrong order (§4.3.1, and the Art's only refusal).
         *
         * Anything left unread at the end is a book that does not read.
         */
        fun book(): List<Phrase>? {
            val opening = clause(nucleus = true) ?: return null
            val phrases = mutableListOf<Phrase>()
            // `Age` alone is a book — it makes an Age nobody described — and a clause with nothing in it
            // would put an empty phrase into every reading and every count.
            if (opening.said.isNotEmpty()) phrases += opening
            while (at < pages.size) phrases += clause(nucleus = false) ?: return null
            return phrases.takeUnless { refused }
        }

        /**
         * One clause: what is said, and then what it is said about — `modifier* (SUBJECT siting? | siting)`.
         *
         * **The close is found before the run is read**, which is what leading modifiers buy and cost. A
         * trailing-modifier grammar knew the aim as soon as the clause opened; here the run has to be
         * measured to its end first, and only then is it known what the words in it are aimed at. That is a
         * bounded look ahead inside one clause — no page is re-stamped, and a word still belongs exactly
         * where it was laid.
         *
         * **Every clause closes on something**, and the last page of a book may be that something itself
         * when it says what it is about — see [speaksForItself].
         */
        private fun clause(nucleus: Boolean): Phrase? {
            // The *index* is what is searched for, never the page. A `Page` is a data class, so a book that
            // lays the same word twice has equal pages in it and `indexOf` answers with the first — which
            // walked the cursor backwards and read the same clause forever.
            val closesAt = (at..<pages.size).firstOrNull { closes(pages[it]) }
                ?: (if (nucleus) null else trailingCloseAt())
                ?: return null
            // Meeting the wrong kind is a book that does not read rather than a page swallowed: a second
            // `age` mid-book is `Repair`'s to report, not ours to absorb.
            if ((pages[closesAt].kind == PageClass.NUCLEUS) != nucleus) return null

            // A siting closes a clause on its own, so the subject is whatever is not one.
            val subject = pages[closesAt].takeUnless { it.kind == PageClass.CONFINER }
            val sitingAt = if (subject == null) closesAt else closesAt + 1
            val confinedTo = sitingIn(sitingAt) ?: if (refused) return null else null
            val aim = if (nucleus) emptySet() else subject?.word?.aspects.orEmpty()
            // A subject the sentence sited must be something vanilla resolves through the biome. Where there
            // is no subject the terms answer for themselves, which `belongsHere` asks of each in turn.
            if (confinedTo != null && subject != null && aim.none { it.confinable }) return null

            val said = modifiers(until = closesAt, aim = aim, confinedTo = confinedTo, closes = subject)
                ?: return null
            at = if (confinedTo == null) sitingAt else sitingAt + PAGES_IN_A_SITING
            // A clause closing on a population brings a member of it into being, and everything said in the
            // clause is said about *that* one.
            val population = aim.singleOrNull()?.takeIf { it.holds == Holds.POPULATION }
            val body = population?.let { described.getOrDefault(it, 0).also { at -> described[it] = at + 1 } }
            return Phrase(
                modifiers = said.map { it.copy(describes = body) },
                subject = subject?.word?.let {
                    Constraint(
                        it,
                        scopeFor(it, aim),
                        latent = subject.latent,
                        rehomed = subject.rehomed,
                        // The siting is the clause's, so it is the subject's too — `mud pits in jungle`
                        // mints off the subject, and a subject that forgot its ground minted everywhere.
                        confinedTo = confinedTo,
                        describes = body,
                    )
                },
                confinedTo = confinedTo,
            )
        }

        /** Whether this page ends the clause it is in — an aiming page, or the `in` that opens a siting. */
        private fun closes(page: Page): Boolean =
            page.kind == PageClass.NUCLEUS || page.kind == PageClass.SUBJECT || page.kind == PageClass.CONFINER

        /**
         * The last page of a run nothing else closes, where that page reaches **exactly one** part of the
         * world — `age torchflowers`, which needs no `features` after it to say where it was aimed
         * (§4.3.1).
         *
         * **A last resort, tried only when no aiming page and no siting follow**, so every book that reads
         * today reads the same way: `teeming volcano features` still closes on `features`, because a closer
         * was found before this was asked. And only an unambiguous term qualifies — a word reaching two
         * parts of the world is exactly the case an aiming page exists to settle, and a material is
         * ambiguous by construction (`PageClass.MATERIAL`).
         */
        private fun trailingCloseAt(): Int? {
            val last = pages.lastIndex.takeIf { it >= at } ?: return null
            val page = pages[last]
            if (page.kind != PageClass.TERM) return null
            return last.takeIf { page.word?.aspects?.size == ONE_PART_OF_THE_WORLD }
        }

        /**
         * `IN <biome>` closing a clause — *teeming temples in jungles* (§4.3.1).
         *
         * **It trails rather than leads**, which it did not always: when the language reversed so that every
         * clause ends with the thing it is about, `in` kept the old shape and was the last page that opened
         * anything. A siting aims at a place exactly as a subject aims at a part, so it closes like one.
         *
         * **What follows must actually name a biome**, and that is load-bearing rather than pedantic. The
         * biome page becomes the siting's identifier and no constraint of its own, so a page accepted here is
         * a page that leaves the sentence — and this used to accept any term at all, which made `in savage` a
         * clause sited in a biome no pack has and swallowed the page saying so. `Repair` then found that
         * position attractive, laid a writer's `islands` into it, and lost it: neither used, nor dropped, nor
         * charged, which is the one failure §3.3 forbids.
         */
        private fun sitingIn(index: Int): Identifier? {
            if (pages.getOrNull(index)?.kind != PageClass.CONFINER) return null
            // `in` with no biome after it is a book that does not read, not a clause sited nowhere.
            val biome = pages.getOrNull(index + 1)?.takeIf(::namesABiome) ?: return null.also { refused = true }
            return biome.word?.id
        }

        /**
         * Whether this page is the name of a biome — one of §8's derived words, and nothing else.
         *
         * **Asked of what a biome word actually is**: it chooses one member of the biomes, which every
         * derived biome word does and nothing else in the corpus does by accident.
         */
        private fun namesABiome(page: Page): Boolean {
            if (page.kind != PageClass.TERM) return false
            val word = page.word ?: return false
            return word.choiceIn(Aspect.BIOMES) != null
        }

        /**
         * Everything said before the closing page, in the order it was laid out.
         *
         * Null where a page here belongs to no part of the world this clause is about — a misaimed page is
         * a book that does not read rather than a silent re-homing, which is what [Repair] then puts right
         * and charges for.
         */
        private fun modifiers(
            until: Int,
            aim: Set<Aspect>,
            confinedTo: Identifier?,
            closes: Page? = null,
        ): List<Constraint>? =
            buildList {
                while (at < until) {
                    if (!belongsHere(here, aim, sited = confinedTo != null, closing = closes)) return null
                    addAll(modifier(until, aim, confinedTo))
                    if (refused) return@buildList
                }
            }

        /** `(ONLY | EXCEPT)? term (AND term)*` — the shape every clause's modifiers share. */
        private fun modifier(until: Int, aim: Set<Aspect>, confinedTo: Identifier?): List<Constraint> {
            val polarity = when (here?.kind) {
                PageClass.RESTRICTOR -> Polarity.ONLY.also { take() }
                PageClass.EXCLUDER -> Polarity.EXCEPT.also { take() }
                else -> Polarity.ASSERTED
            }
            val terms = mutableListOf<Pair<Page, Page?>>()
            // A polarity or a rung standing in front of nothing is a refusal, as is a trailing `and`: the
            // page is there, it means something, and there is no term for it to mean it about.
            terms += term(until) ?: return emptyList<Constraint>().also { refused = true }
            while (at < until && here?.kind == PageClass.JOINER) {
                take()
                terms += term(until) ?: return emptyList<Constraint>().also { refused = true }
            }
            // A group identifies words a writer joined; standing alone is *not* a group of one, because
            // unjoined juxtaposition has to keep meaning contention (§3.2).
            val group = if (terms.size > 1) Group(nextGroup++) else null
            return terms.mapNotNull { (page, quantifier) ->
                val word = page.word ?: return@mapNotNull null
                Constraint(
                    word,
                    scopeFor(word, aim),
                    polarity,
                    group,
                    // The rung sits on the page before the term it counts, and travels with the value from
                    // here on: what a quantifier modifies is the *claim*, never the word (§3.2).
                    quantifier?.rung ?: Rung.ORDINARY,
                    quantifier?.written,
                    confinedTo = confinedTo,
                    latent = page.latent,
                    rehomed = page.rehomed,
                    // Aimed at nothing, so what the word lists as `unaimed` is rolled for (`Word.unaimed`).
                    laidBare = aim.isEmpty(),
                )
            }
        }

        /** `QUANTIFIER? word` — the page, and the rung standing in front of it if there is one. */
        private fun term(until: Int): Pair<Page, Page?>? {
            if (at >= until) return null
            val quantifier = here?.takeIf { it.kind == PageClass.QUANTIFIER }?.also { take() }
            if (at >= until) return null
            val page = here?.takeIf { it.kind == PageClass.TERM || it.kind == PageClass.MATERIAL } ?: return null
            take()
            return page to quantifier
        }

        /**
         * Whether this page may stand in a clause about [aim] — **the rule the generated grammar spent a
         * rule per aspect saying**, and the whole of what a clause admits.
         *
         * A material stands wherever the part of the world is made of something. Any other term stands
         * where it declares it belongs, and a word declaring nothing belongs anywhere. An empty [aim] is
         * the nucleus, which is about the whole Age and so admits everything.
         */
        private fun belongsHere(page: Page?, aim: Set<Aspect>, sited: Boolean, closing: Page? = null): Boolean {
            if (page == null) return false
            // Structure carries no aspect of its own; what it joins or qualifies is checked on its own.
            if (page.word == null) return page.kind != null
            val word = page.word
            // **A mood belongs to whatever it is aimed at**, and leans there and nowhere else — `beautiful sky`
            // is a beautiful sky. Not to a place: a mood confined to a biome has no reading.
            if (word.isAMood) return !sited
            // Where nothing was aimed, every term answers for itself — which is what lets a word naming one
            // registry object need no page after it: `teeming igloos` is a sentence and `igloos structures`
            // says the same thing twice.
            val declared = word.aspects
            if (aim.isEmpty()) return !sited || declared.isEmpty() || declared.any { it.confinable }
            // A material stands where the part of the world is made of something — and also where a
            // **minting** page is, since `ink springs` is a substance qualifying a pattern rather than a
            // claim of its own (world model §2).
            //
            // **And where it can be made of *this***, which the aspect alone cannot say. A world cannot be
            // built of signs ([Materials]), so a sign aimed at the landmass is refused here rather than
            // admitted and quietly dropped — `Repair` then moves the page somewhere it reads and the aiming
            // is charged, which is what `stormy landmass` has always got.
            if (page.kind == PageClass.MATERIAL) {
                if (closing?.word?.mints != null) return true
                val substance = word.material
                return aim.any {
                    it.madeOfSomething && (substance == null || it.canBeMadeOf(substance))
                }
            }
            return declared.isEmpty() || declared.any { it in aim }
        }
    }

    /**
     * Where a word reaches from the clause it was laid in.
     *
     * **Not intersected with what the word declares.** [co.voik.agesandtheart.age.word.Word.aspects] is where
     * a word speaks when nobody aimed it — "only a liquid volunteers for the sea unprompted" — so intersecting
     * would keep every solid out of a sea it was pointed straight at, and `ice sea` would stop being a sentence.
     */
    private fun scopeFor(word: co.voik.agesandtheart.age.word.Word, aim: Set<Aspect>): Set<Aspect> {
        // A mood laid bare reaches wherever it finds purchase, which the resolver reads off an empty scope.
        if (word.isAMood) return aim
        return aim.ifEmpty { word.aspects }
    }
}
