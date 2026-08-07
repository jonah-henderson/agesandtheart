package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
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
 * book     : nucleus section*
 * nucleus  : modifier* AGE
 * section  : confinement? modifier* SUBJECT
 * modifier : (ONLY | EXCEPT)? term (AND term)*
 * term     : QUANTIFIER? word
 * ```
 *
 * **Every clause ends with the thing it is about**, which is the whole shape of the language: *a beautiful
 * floating Age*, *pillars and hills landmass*, *in jungles, an end stone surface*. Modifiers lead and the
 * page they modify closes the clause, the way an English noun phrase does.
 *
 * That is a reversal (§4.3.1, 2026-08-07). Sections used to be *opened* by their aiming page with modifiers
 * trailing, which read as `landmass of pillars` and wanted a particle the writer never laid. Leading
 * modifiers cost nothing to parse — the clause is terminated by its own subject rather than by the next
 * one starting — and the reading is then simply the pages in the order they were written.
 *
 * **It also removed a production.** An evocative word used to have a slot of its own before the subject,
 * because that was the only way to lean on something that had not been named yet. Everything leads now, so
 * there is one run of modifiers and [Tier] alone decides whether a word tilts or narrows — which is what
 * decided it all along.
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
            val opening = clause(closedBy = PageClass.NUCLEUS) ?: return null
            val phrases = mutableListOf<Phrase>()
            // `Age` alone is a book — it makes an Age nobody described — and a clause with nothing in it
            // would put an empty phrase into every reading and every count.
            if (opening.said.isNotEmpty()) phrases += opening
            while (at < pages.size) phrases += clause(closedBy = PageClass.SUBJECT) ?: return null
            return phrases.takeUnless { refused }
        }

        /**
         * One clause: what is said, and then the page it is said about.
         *
         * **The closing page is found before the run is read**, which is what leading modifiers buy and
         * cost. A trailing-modifier grammar knew the aim as soon as the clause opened; here the run has to
         * be measured to its end first, and only then is it known what the words in it are aimed at. That
         * is a bounded look ahead inside one clause and nothing like the pre-pass the generated grammar
         * needed — no page is re-stamped, and a word still belongs exactly where it was laid.
         *
         * A run with no closing page at all is a book that does not read: those words are about nothing.
         */
        private fun clause(closedBy: PageClass): Phrase? {
            val confinedTo = if (closedBy == PageClass.SUBJECT) confinement() else null
            // Either kind closes a clause, so meeting the wrong one is a book that does not read rather
            // than a page swallowed: a second `age` mid-book is `Repair`'s to report, not ours to absorb.
            //
            // The *index* is what is searched for, never the page. A `Page` is a data class, so a book
            // that lays the same word twice has equal pages in it and `indexOf` answers with the first —
            // which walked the cursor backwards and read the same clause forever.
            val closesAt = (at..<pages.size).firstOrNull {
                pages[it].kind == PageClass.NUCLEUS || pages[it].kind == PageClass.SUBJECT
            } ?: return null
            val closing = pages[closesAt]
            if (closing.kind != closedBy) return null
            val aim = if (closedBy == PageClass.NUCLEUS) emptySet() else closing.word?.aspects.orEmpty()
            if (confinedTo != null && aim.none { it.confinable }) return null

            val said = modifiers(until = closesAt, aim = aim, confinedTo = confinedTo) ?: return null
            at = closesAt + 1
            return Phrase(
                modifiers = said,
                subject = closing.word?.let {
                    Constraint(it, scopeFor(it, aim), latent = closing.latent, rehomed = closing.rehomed)
                },
                confinedTo = confinedTo,
            )
        }

        /**
         * `IN <biome>` at the head of a clause, governing everything in it (§4.3.1).
         *
         * **What follows must actually name a biome**, and that is load-bearing rather than pedantic. The
         * biome page becomes the confinement's identifier and no constraint of its own, so a page accepted
         * here is a page that leaves the sentence — and this used to accept any term at all, which made
         * `in savage` a clause confined to a biome no pack has and swallowed the page saying so. `Repair`
         * then found that position attractive, laid a writer's `islands` into it, and lost it: neither
         * used, nor dropped, nor charged, which is the one failure §3.3 forbids.
         */
        private fun confinement(): Identifier? {
            if (here?.kind != PageClass.CONFINER) return null
            take()
            // `in` with no biome after it is a book that does not read, not a clause confined to nothing.
            val biome = here?.takeIf { namesABiome(it) } ?: return null.also { refused = true }
            take()
            return biome.word?.id
        }

        /** Whether this page is the name of a biome — one of §8's derived words, and nothing else. */
        private fun namesABiome(page: Page): Boolean {
            if (page.kind != PageClass.TERM) return false
            val word = page.word ?: return false
            return word.names != null && Aspect.BIOMES in word.aspects
        }

        /**
         * Everything said before the closing page, in the order it was laid out.
         *
         * Null where a page here belongs to no part of the world this clause is about — a misaimed page is
         * a book that does not read rather than a silent re-homing, which is what [Repair] then puts right
         * and charges for.
         */
        private fun modifiers(until: Int, aim: Set<Aspect>, confinedTo: Identifier?): List<Constraint>? =
            buildList {
                while (at < until) {
                    if (!belongsHere(here, aim)) return null
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
        private fun belongsHere(page: Page?, aim: Set<Aspect>): Boolean {
            if (page == null) return false
            // Structure carries no aspect of its own; what it joins or qualifies is checked on its own.
            if (page.word == null) return page.kind != null
            if (aim.isEmpty()) return true
            if (page.kind == PageClass.MATERIAL) return aim.any { it.madeOfSomething }
            val declared = page.word.aspects
            return declared.isEmpty() || declared.any { it in aim }
        }
    }

    /**
     * Where a word reaches from the clause it was laid in.
     *
     * **Not intersected with what the word declares.** [Word.aspects] is where a word speaks when nobody
     * aimed it — "only a liquid volunteers for the sea unprompted" — so intersecting would keep every
     * solid out of a sea it was pointed straight at, and `ice sea` would stop being a sentence.
     */
    private fun scopeFor(word: co.voik.agesandtheart.age.word.Word, aim: Set<Aspect>): Scope {
        if (!word.tier.narrows) return Scope.Everywhere(aim)
        return Scope.Confined(aim.ifEmpty { word.aspects })
    }
}
