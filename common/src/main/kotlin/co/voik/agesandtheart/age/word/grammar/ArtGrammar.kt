package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.grammar.ArtParser
import org.antlr.v4.runtime.BailErrorStrategy
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenFactory
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenFactory
import org.antlr.v4.runtime.TokenSource
import org.antlr.v4.runtime.misc.Pair
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.TerminalNode
import net.minecraft.resources.Identifier

/**
 * The one file that knows the parser exists. **Nothing else in the mod may import `org.antlr`** —
 * `GrammarCheck` fails the build if anything does. Everything crossing the boundary is ours: [Page] in,
 * [Sentence] out.
 *
 * **No lexing happens**: the input is a list of already-classified pages, so [PageTokens] feeds the parser
 * token types directly and the grammar declares no lexer rules.
 *
 * **Nothing is recovered from.** A book either reads or it does not, and one that does not is [Repair]'s
 * to make a sentence of — so this refuses rather than handing back a tree ANTLR patched up, which was a
 * reading no writer chose and nobody could be told about.
 */
internal object ArtGrammar {

    /** The book [pages] spell, or null where they spell none — which is [Repair]'s cue, never an error. */
    fun parse(pages: List<Page>): Sentence? {
        val parser = ArtParser(CommonTokenStream(PageTokens(pages)))
        // Ours, so a malformed book is an answer rather than noise on standard error; and bail rather than
        // recover, so a book that does not read produces nothing instead of something half-eaten.
        parser.removeErrorListeners()
        parser.errorHandler = BailErrorStrategy()
        val read = try {
            parser.sentence()
        } catch (refused: ParseCancellationException) {
            return null
        }
        // Only what the writer laid: a latent page is the Art's own and costs nobody ink.
        val spelled = pages.filterNot(Page::latent).mapNotNull(Page::production)
        return Sentence(Reading(pages).of(read), structural = spelled)
    }

    /**
     * Token types come from **`ArtParser`**, never an `ArtLexer`: the grammar declares them with `tokens {}`
     * and has no lexer rules, so any lexer on disk is a leftover whose numbering runs off by one — which
     * surfaces as a syntax error on an innocent page.
     */
    private fun typeOf(page: Page): Int = when (page.kind) {
        PageClass.NUCLEUS -> ArtParser.AGE
        PageClass.EVOCATIVE -> ArtParser.EVOCATIVE
        PageClass.MATERIAL -> ArtParser.MATERIAL_TERM
        PageClass.SUBJECT -> subjectTokenFor(page)
        PageClass.TERM -> termTokenFor(page)
        PageClass.JOINER -> ArtParser.AND
        PageClass.RESTRICTOR -> ArtParser.ONLY
        PageClass.EXCLUDER -> ArtParser.EXCEPT
        PageClass.QUANTIFIER -> ArtParser.QUANTIFIER
        PageClass.CONFINER -> ArtParser.IN
        // Filtered out before this is reached; the branch exists so a new class breaks the build here.
        null -> error("an unreadable page reached the parser")
    }

    /**
     * The two tables the aspect-typed grammar costs, and the reason they are exhaustive `when`s rather
     * than a map: **a new aspect must break the build here**, since the grammar now has a rule per part of
     * the world and a token that quietly fell through would be a page nothing could read.
     */
    private fun subjectTokenFor(page: Page): Int = when (page.aspect) {
        Aspect.TERRAIN -> ArtParser.TERRAIN_SUBJECT
        Aspect.SEA -> ArtParser.SEA_SUBJECT
        Aspect.CARVERS -> ArtParser.CARVERS_SUBJECT
        Aspect.BIOMES -> ArtParser.BIOMES_SUBJECT
        Aspect.SURFACE -> ArtParser.SURFACE_SUBJECT
        Aspect.FEATURES -> ArtParser.FEATURES_SUBJECT
        Aspect.SPAWNS -> ArtParser.SPAWNS_SUBJECT
        Aspect.ATMOSPHERE -> ArtParser.ATMOSPHERE_SUBJECT
        Aspect.PHENOMENA -> ArtParser.PHENOMENA_SUBJECT
        Aspect.SKY -> ArtParser.SKY_SUBJECT
        Aspect.STRUCTURES -> ArtParser.STRUCTURES_SUBJECT
        Aspect.CLIMATE -> ArtParser.CLIMATE_SUBJECT
        null -> error("the aiming page '${page.written}' is about no part of the world")
    }

    private fun termTokenFor(page: Page): Int = when (page.aspect) {
        Aspect.TERRAIN -> ArtParser.TERRAIN_TERM
        Aspect.SEA -> ArtParser.SEA_TERM
        Aspect.CARVERS -> ArtParser.CARVERS_TERM
        Aspect.BIOMES -> ArtParser.BIOMES_TERM
        Aspect.SURFACE -> ArtParser.SURFACE_TERM
        Aspect.FEATURES -> ArtParser.FEATURES_TERM
        Aspect.SPAWNS -> ArtParser.SPAWNS_TERM
        Aspect.ATMOSPHERE -> ArtParser.ATMOSPHERE_TERM
        Aspect.PHENOMENA -> ArtParser.PHENOMENA_TERM
        Aspect.SKY -> ArtParser.SKY_TERM
        Aspect.STRUCTURES -> ArtParser.STRUCTURES_TERM
        Aspect.CLIMATE -> ArtParser.CLIMATE_TERM
        null -> error("the page '${page.written}' belongs to no part of the world")
    }

    /**
     * The pages, handed to the parser as tokens.
     *
     * **A token source of our own, never `ListTokenSource` over hand-built `CommonToken`s**:
     * `CommonToken(type, text)` leaves the token's source null and ANTLR dereferences it while composing a
     * syntax-error message, so that shortcut throws on the first unreadable book. [inputStream] answers
     * with an empty stream rather than null for the same reason.
     */
    private class PageTokens(private val pages: List<Page>) : TokenSource {
        private var next = 0
        private var factory: TokenFactory<*> = CommonTokenFactory.DEFAULT

        override fun nextToken(): Token {
            val page = pages.getOrNull(next)
            val type = if (page == null) Token.EOF else typeOf(page)
            val token = CommonToken(Pair(this, EMPTY_TEXT), type, Token.DEFAULT_CHANNEL, next, next)
            token.text = page?.written ?: "<end of book>"
            token.line = 1
            token.charPositionInLine = next
            next++
            return token
        }

        override fun getLine(): Int = 1
        override fun getCharPositionInLine(): Int = next
        override fun getInputStream(): CharStream = EMPTY_TEXT
        override fun getSourceName(): String = "a book"
        override fun setTokenFactory(tokenFactory: TokenFactory<*>) { factory = tokenFactory }
        override fun getTokenFactory(): TokenFactory<*> = factory
    }

    // There is no text anywhere in this pipeline; this exists only so ANTLR's error paths have an object.
    private val EMPTY_TEXT: CharStream = CharStreams.fromString("")

    /**
     * Walks the parse tree once, turning sections into [Phrase]s. Holds [pages] because ANTLR's tokens
     * carry only text and type, and two pages may be written the same — `blackstone and blackstone` — so
     * the token *index* is what identifies which page it came from.
     */
    private class Reading(private val pages: List<Page>) {
        private var nextGroup = 0

        fun of(sentence: ArtParser.SentenceContext): List<Phrase> =
            // A nucleus that said nothing is not a clause. `Age` alone is a legal book — it simply makes an
            // Age nobody described — and emitting an empty phrase for it would put a clause with no words
            // in it into every reading and every count.
            listOfNotNull(sentence.nucleus()?.let(::phraseOf)?.takeIf { it.said.isNotEmpty() }) +
                sentence.section().map(::phraseOf)

        /**
         * **Read structurally, not per aspect.** Every alternative of `section` has the same shape —
         * descriptors, at most one subject terminal, then modifiers — so seven near-identical branches
         * here would only restate what the grammar has already said. The subject is the one terminal
         * standing directly under a section; everything else below it is a rule.
         */
        private fun phraseOf(section: ParserRuleContext): Phrase {
            val laid = section.children.orEmpty()
            val head = laid.filterIsInstance<TerminalNode>().firstOrNull()?.symbol
            // **The Age is structure, not a claim.** It says nothing about the world — it gives the
            // sentence a head — so like `and` it owes no constraint, and charging one would report the one
            // page every book must have as a word aimed nowhere. Its clause is therefore subjectless, which
            // is what the beginner's book always was: everything in it is about the whole Age.
            val namesTheAge = pageAt(head)?.kind == PageClass.NUCLEUS
            val subject = if (namesTheAge) null else wordAt(head)
            // What everything in the section is aimed at: position decides attachment, so no word is
            // searched for a home — it has the one it was laid down in.
            val aim = subject?.aspects.orEmpty()

            val descriptors = laid.filterIsInstance<ArtParser.DescriptorContext>().mapNotNull { descriptor ->
                val token = descriptor.EVOCATIVE().symbol
                val word = wordAt(token) ?: return@mapNotNull null
                // Aimed, and still global: an evocative word tilts and never narrows (§4.3.1).
                Constraint(
                    word,
                    Scope.Everywhere(emphasised = aim),
                    latent = wasDrawn(token),
                    rehomed = wasMoved(token),
                )
            }
            val modifiers = laid.filterIsInstance<ParserRuleContext>()
                .filterNot { it is ArtParser.DescriptorContext || it is ArtParser.ConfinementContext }
            // The clause's own `in`, which every term in it inherits — see [confinement] in the grammar.
            val confinedTo = biomeOf(section)
            return Phrase(
                descriptors = descriptors,
                subject = subject?.let {
                    Constraint(
                        it,
                        scopeFor(it, aim),
                        latent = wasDrawn(head),
                        rehomed = wasMoved(head),
                    )
                },
                modifiers = modifiers.flatMap { modifier -> constraintsIn(modifier, aim, confinedTo) },
                confinedTo = confinedTo,
            )
        }

        /**
         * One modifier of whichever aspect's rule it came from, read through the shape they share:
         * an optional polarity, then terms joined by `and`.
         */
        private fun constraintsIn(
            modifier: ParserRuleContext,
            aim: Set<Aspect>,
            confinedTo: Identifier? = null,
        ): List<Constraint> {
            val polarity = when {
                modifier.getToken(ArtParser.ONLY, 0) != null -> Polarity.ONLY
                modifier.getToken(ArtParser.EXCEPT, 0) != null -> Polarity.EXCEPT
                else -> Polarity.ASSERTED
            }
            val terms = modifier.children.orEmpty().filterIsInstance<ParserRuleContext>()
            // A group identifies words a writer joined; standing alone is *not* a group of one, because
            // unjoined juxtaposition has to keep meaning contention (§3.2).
            val group = if (terms.size > 1) Group(nextGroup++) else null
            return terms.mapNotNull { term ->
                // A term is `QUANTIFIER? WORD confinement?`, so the word is the first token that is not the
                // rung. Reading the *last* one was right until a term could end with the biome it names.
                val spoken = term.children.orEmpty().filterIsInstance<TerminalNode>().map { it.symbol }
                val word = spoken.firstOrNull { it.type != ArtParser.QUANTIFIER }?.let(::wordAt)
                    ?: return@mapNotNull null
                // The rung sits on the page before the term it counts, and travels with the value from here
                // on: what a quantifier modifies is the *claim*, never the word (§3.2).
                val quantifier = pageAt(term.getToken(ArtParser.QUANTIFIER, 0)?.symbol)
                Constraint(
                    word,
                    scopeFor(word, aim),
                    polarity,
                    group,
                    quantifier?.rung ?: Rung.ORDINARY,
                    quantifier?.written,
                    confinedTo = confinedTo,
                    latent = wasDrawn(term.stop),
                    rehomed = wasMoved(term.stop),
                )
            }
        }

        /**
         * §4.3.1's tier rule: a word that cannot narrow candidates cannot narrow its own scope either, so
         * an evocative word stays global however it was aimed.
         *
         * **A narrowing word's scope is simply where it was laid.** The grammar has already decided a term
         * may sit here — a section admits only terms belonging to the part of the world it aims at — so
         * placement is *read* rather than re-derived, and there is no arrangement of words that produces a
         * word aimed nowhere.
         *
         * That the grammar decides it is also what makes `sea ice` a sentence. [Word.aspects] is where a
         * word speaks when **nobody aimed it** — "only a liquid volunteers for the sea unprompted" — so
         * intersecting with it would have kept every solid out of a sea it was pointed straight at.
         */
        private fun scopeFor(word: Word, aim: Set<Aspect>): Scope {
            if (!word.tier.narrows) return Scope.Everywhere(aim)
            return Scope.Confined(aim.ifEmpty { word.aspects })
        }

        /**
         * The biome a **clause** was confined to, or null where it was not — §4.3.1's `in`.
         *
         * The biome arrives as an ordinary biome *term* page, so what it means here is simply its word's
         * id: a derived biome word is named after the biome it is for.
         */
        private fun biomeOf(section: ParserRuleContext): Identifier? = section.children.orEmpty()
            .filterIsInstance<ArtParser.ConfinementContext>()
            .firstNotNullOfOrNull { confinement -> wordAt(confinement.stop)?.id }

        private fun pageAt(token: Token?): Page? = pages.getOrNull(token?.tokenIndex ?: return null)

        private fun wordAt(token: Token?): Word? = pageAt(token)?.word

        private fun wasDrawn(token: Token?): Boolean = pageAt(token)?.latent == true

        private fun wasMoved(token: Token?): Boolean = pageAt(token)?.rehomed == true
    }
}
