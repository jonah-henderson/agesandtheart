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
 * book     : (nucleus section*)?
 * nucleus  : evocative* AGE modifier*
 * section  : confinement? evocative* SUBJECT modifier*
 * modifier : (ONLY | EXCEPT)? term (AND term)*
 * term     : QUANTIFIER? word
 * ```
 *
 * **Why this and not a parser generator** (settled in advance, `decisions.md`). A generated grammar had to
 * name every aspect four times over — a token pair, a section alternative, a modifier rule and a term rule
 * each — so the parts of the world were spelled into a build artefact and the player-facing division could
 * not move without regenerating one. Worse, a page's terminal had to be chosen from the *word* before the
 * parse, so a word at home in two sections could only ever be written in the first of them, and a
 * left-to-right pre-pass existed to stamp it for the section it actually sat in.
 *
 * **That pre-pass is gone, and with it the class of bug it carried.** Reading left to right, the open
 * section is simply known when a term is reached, so a word belongs where it was laid and nothing has to
 * be re-stamped. Two pages spelling one word are interchangeable again, because neither carries a meaning
 * of its own to lose.
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
         * Whether something was met that cannot be read at all — a `and` with nothing after it, an `in`
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

        private fun looking(vararg kinds: PageClass): Boolean = here?.kind in kinds

        /**
         * `nucleus section*` — **every book opens with the `age` page**, and one that does not is no more
         * a sentence than one whose pages are in the wrong order.
         *
         * The nucleus was optional and an empty book read as a sentence saying nothing, which made a bound
         * book of no pages a legal way to author an Age nobody described. It is a hard requirement now
         * (§4.3.1) and the desk refuses to bind without it; the pen still never refuses (§2), because a
         * book arriving here without one is [Repair]'s and comes back with the Art's own sentence.
         *
         * Anything left unread at the end is a book that does not read.
         */
        fun book(): List<Phrase>? {
            val opening = nucleus() ?: return null
            val phrases = mutableListOf<Phrase>()
            // `Age` alone is a book — it makes an Age nobody described — and a clause with nothing in it
            // would put an empty phrase into every reading and every count.
            if (opening.said.isNotEmpty()) phrases += opening
            while (at < pages.size) phrases += section() ?: return null
            return phrases.takeUnless { refused }
        }

        /**
         * `evocative* AGE modifier*` — the beginner's book, and the commonest thing anyone writes.
         *
         * **The Age is structure, not a claim**: it gives the sentence a head and says nothing about the
         * world, so its clause is subjectless and everything in it is about the whole Age. Its modifiers
         * are loose, nothing having been aimed at yet, so a word goes where it declares it goes.
         */
        private fun nucleus(): Phrase? {
            val descriptors = descriptors(aim = emptySet())
            if (!looking(PageClass.NUCLEUS)) return null
            take()
            return Phrase(
                descriptors = descriptors,
                subject = null,
                modifiers = modifiers(aim = emptySet(), confinedTo = null),
                confinedTo = null,
            )
        }

        /** `confinement? evocative* SUBJECT modifier*` — a part of the world, and what is said of it. */
        private fun section(): Phrase? {
            val confinedTo = confinement()
            val descriptorsAt = at
            val descriptors = descriptors(aim = emptySet())
            val opening = here?.takeIf { it.kind == PageClass.SUBJECT } ?: return null
            // Re-read now that the aim is known: an evocative word leans on what it precedes.
            val aim = opening.word?.aspects.orEmpty()
            at = descriptorsAt
            val aimed = descriptors(aim)
            take()
            if (confinedTo != null && aim.none { it.confinable }) return null
            return Phrase(
                descriptors = aimed,
                subject = opening.word?.let {
                    Constraint(it, scopeFor(it, aim), latent = opening.latent, rehomed = opening.rehomed)
                },
                modifiers = modifiers(aim, confinedTo),
                confinedTo = confinedTo,
            )
        }

        /** `IN <biome>` at the head of a clause, governing everything in it (§4.3.1). */
        private fun confinement(): Identifier? {
            if (!looking(PageClass.CONFINER)) return null
            take()
            // `in` with no biome after it is a book that does not read, not a clause confined to nothing.
            val biome = here?.takeIf { it.kind == PageClass.TERM } ?: return null.also { refused = true }
            take()
            return biome.word?.id
        }

        private fun descriptors(aim: Set<Aspect>): List<Constraint> = buildList {
            while (looking(PageClass.EVOCATIVE)) {
                val page = take()
                val word = page.word ?: continue
                // Aimed, and still global: an evocative word tilts and never narrows (§4.3.1).
                add(Constraint(word, Scope.Everywhere(emphasised = aim), latent = page.latent, rehomed = page.rehomed))
            }
        }

        private fun modifiers(aim: Set<Aspect>, confinedTo: Identifier?): List<Constraint> = buildList {
            while (opensAModifier(aim)) addAll(modifier(aim, confinedTo))
        }

        /**
         * Whether what stands here continues this clause.
         *
         * An evocative page never does: it precedes a subject, so meeting one means the next clause has
         * begun. A term that does not [belongsIn] this section does not either — and having nowhere else
         * to go, it will fail to open a section of its own, which is exactly what makes a misaimed page a
         * book that does not read rather than a silent re-homing.
         */
        private fun opensAModifier(aim: Set<Aspect>): Boolean = when (here?.kind) {
            PageClass.RESTRICTOR, PageClass.EXCLUDER, PageClass.QUANTIFIER -> true
            PageClass.TERM, PageClass.MATERIAL -> belongsIn(here, aim)
            else -> false
        }

        /** `(ONLY | EXCEPT)? term (AND term)*` — the shape every section's modifiers share. */
        private fun modifier(aim: Set<Aspect>, confinedTo: Identifier?): List<Constraint> {
            val polarity = when (here?.kind) {
                PageClass.RESTRICTOR -> Polarity.ONLY.also { take() }
                PageClass.EXCLUDER -> Polarity.EXCEPT.also { take() }
                else -> Polarity.ASSERTED
            }
            val terms = mutableListOf<Pair<Page, Page?>>()
            // A polarity or a rung standing in front of nothing is a refusal, as is a trailing `and`: the
            // page is there, it means something, and there is no term for it to mean it about.
            terms += term() ?: return emptyList<Constraint>().also { refused = true }
            while (looking(PageClass.JOINER)) {
                take()
                terms += term() ?: return emptyList<Constraint>().also { refused = true }
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
        private fun term(): Pair<Page, Page?>? {
            val quantifier = here?.takeIf { it.kind == PageClass.QUANTIFIER }?.also { take() }
            val page = here?.takeIf { it.kind == PageClass.TERM || it.kind == PageClass.MATERIAL } ?: return null
            take()
            return page to quantifier
        }

        /**
         * Whether this page may stand in a section aimed at [aim] — **the rule the generated grammar spent
         * a rule per aspect saying**, and the whole of what a section admits.
         *
         * A material stands wherever the part of the world is made of something. Any other term stands
         * where it declares it belongs, and a word declaring nothing belongs anywhere. An empty [aim] is
         * the nucleus, which aims at nothing and so admits everything.
         */
        private fun belongsIn(page: Page?, aim: Set<Aspect>): Boolean {
            if (page == null) return false
            if (aim.isEmpty()) return true
            if (page.kind == PageClass.MATERIAL) return aim.any { it.madeOfSomething }
            val declared = page.word?.aspects.orEmpty()
            return declared.isEmpty() || declared.any { it in aim }
        }
    }

    /**
     * Where a word reaches from the section it was laid in.
     *
     * **Not intersected with what the word declares.** [Word.aspects] is where a word speaks when nobody
     * aimed it — "only a liquid volunteers for the sea unprompted" — so intersecting would keep every
     * solid out of a sea it was pointed straight at, and `sea ice` would stop being a sentence.
     */
    private fun scopeFor(word: co.voik.agesandtheart.age.word.Word, aim: Set<Aspect>): Scope {
        if (!word.tier.narrows) return Scope.Everywhere(aim)
        return Scope.Confined(aim.ifEmpty { word.aspects })
    }
}
