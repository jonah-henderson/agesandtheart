package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.grammar.ArtParser
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenFactory
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenFactory
import org.antlr.v4.runtime.TokenSource
import org.antlr.v4.runtime.misc.Pair

/**
 * The one file that knows the parser exists.
 *
 * **Nothing else in the mod may import `org.antlr`**, and `:common:grammarcheck` fails the build if
 * anything does — see [Grammar] for why the boundary is drawn here and what it buys. Everything crossing it
 * is ours: [Page] in, [Sentence] out.
 *
 * Two things about driving ANTLR this way are worth knowing:
 *
 * - **No lexing happens.** Our input is a list of pages, each already classified, so a [ListTokenSource]
 *   feeds the parser token types directly and the grammar declares no lexer rules at all. ANTLR is doing
 *   only the job we actually want from it — deciding structure.
 * - **Errors are collected, never thrown.** ANTLR's default behaviour is to print to standard error and
 *   recover; both halves are wrong here. A book that cannot be read must still make an Age (design §2), and
 *   what could not be read has to be *reported* to the writer rather than shouted at a log nobody sees.
 */
internal object ArtGrammar {

    fun parse(pages: List<Page>): Sentence {
        // A page nobody recognises never reaches the parser: it has no token type to be given, and letting
        // ANTLR discover that would turn a vague sentence into a syntax error. Dropped here, reported, and
        // the Age comes out less determined for it — which is exactly what §4.3 asks for.
        val unreadable = pages.filter { it.kind == null }
        val readable = pages.filter { it.kind != null }
        if (readable.isEmpty()) return Sentence(emptyList(), unreadable.map { it.written })

        val parser = ArtParser(CommonTokenStream(PageTokens(readable)))
        // Ours, so a malformed book is data rather than noise on standard error.
        parser.removeErrorListeners()
        parser.addErrorListener(SilentErrorListener)

        val constraints = Reading(readable).of(parser.sentence())

        // What went unread is worked out from the **outcome**, not from ANTLR's error tokens: a page that
        // reached the parser and produced no constraint is a page the Art could not use, however the parser
        // chose to describe that. Asking the error listener instead missed a trailing evocative word with no
        // subject — it was quietly discarded during recovery and reported nowhere, which is precisely the
        // silent drop §3.3 forbids. A structural page is exempt: `and` is meant to yield no constraint of
        // its own, having done its work by joining two that do.
        val used = constraints.map { it.word }.toSet()
        val unused = readable.filter { page -> page.kind in SPEAKS_FOR_ITSELF && page.word !in used }
        return Sentence(constraints, (unreadable + unused).map { it.written })
    }

    // Token types come from the **parser**, not from a lexer: the grammar declares them with `tokens {}` and
    // has no lexer rules at all, so any `ArtLexer` on disk is a leftover. Reading them from there once left
    // the numbering silently off by one past PRESET, which surfaced as a syntax error on an innocent page.
    /** The classes that owe a constraint. A structural page does its work by joining others. */
    private val SPEAKS_FOR_ITSELF = setOf(PageClass.EVOCATIVE, PageClass.PRESET, PageClass.SETTER)

    private fun typeOf(page: Page): Int = when (page.kind) {
        PageClass.EVOCATIVE -> ArtParser.EVOCATIVE
        PageClass.PRESET -> ArtParser.PRESET
        PageClass.SETTER -> ArtParser.SETTER
        PageClass.JOINER -> ArtParser.AND
        PageClass.RESTRICTOR -> ArtParser.ONLY
        PageClass.EXCLUDER -> ArtParser.EXCEPT
        // Filtered out before this is reached; the branch exists so a new class breaks the build here.
        null -> error("an unreadable page reached the parser")
    }

    /**
     * The pages, handed to the parser as tokens.
     *
     * **A token source of our own rather than `ListTokenSource` over hand-built `CommonToken`s**, and the
     * difference is not cosmetic: `CommonToken(type, text)` leaves the token's source *null*, and ANTLR
     * dereferences it while composing a syntax-error message. So that shortcut works perfectly until the
     * first unreadable book and then throws — on exactly the path where a dropped fragment is supposed to
     * become vagueness, which is designed behaviour rather than an edge case. A server found it; the offline
     * check had not, because its unknown page was filtered out before the parser ever saw a problem.
     *
     * [inputStream] answers with an empty stream rather than null for the same reason: every error-reporting
     * path in ANTLR is then walking real objects.
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
     * Walks the parse tree once, turning sections into [Constraint]s.
     *
     * Kept as a class holding [pages] because ANTLR's tokens carry only text and type, and two pages may be
     * written the same — `blackstone and blackstone` — so the *index* is what identifies which page a token
     * came from. Matching by text would silently collapse them.
     */
    private class Reading(private val pages: List<Page>) {
        private var nextGroup = 0

        fun of(sentence: ArtParser.SentenceContext): List<Constraint> =
            sentence.section().flatMap(::constraintsIn)

        private fun constraintsIn(section: ArtParser.SectionContext): List<Constraint> {
            // Null where the section named no subject — a book that only steers, like "blackstone" alone.
            // Its modifiers then aim at nothing in particular and fall back to the aspects they declare
            // themselves, which is what an unaimed word has always meant.
            val subject = wordAt(section.subject()?.PRESET()?.symbol)
            // What the section is about, and therefore what everything in it is aimed at. This is the whole
            // of "position decides attachment": no word is searched for a home, it simply has the one it
            // was laid down in.
            val aim = subject?.aspects.orEmpty()

            val descriptors = section.descriptor().mapNotNull { descriptor ->
                val word = wordAt(descriptor.EVOCATIVE().symbol) ?: return@mapNotNull null
                // Aimed, and still global. An evocative word tilts and never narrows, so narrowing its
                // scope would be a category error against its own tier (§4.3.1).
                Constraint(word, Scope.Everywhere(emphasised = aim))
            }
            val head = subject?.let { listOf(Constraint(it, scopeFor(it, aim))) }.orEmpty()
            return descriptors + head + section.modifier().flatMap { modifier -> constraintsIn(modifier, aim) }
        }

        private fun constraintsIn(modifier: ArtParser.ModifierContext, aim: Set<Aspect>): List<Constraint> {
            val polarity = when {
                modifier.ONLY() != null -> Polarity.ONLY
                modifier.EXCEPT() != null -> Polarity.EXCEPT
                else -> Polarity.ASSERTED
            }
            val joined = modifier.conjunction()
            val terms = joined.term()
            // A group identifies words a writer joined; standing alone is *not* a group of one, because
            // unjoined juxtaposition has to keep meaning contention (§3.2).
            val group = if (terms.size > 1) Group(nextGroup++) else null
            return terms.mapNotNull { term ->
                val word = wordAt(term.SETTER()?.symbol ?: term.PRESET()?.symbol) ?: return@mapNotNull null
                Constraint(word, scopeFor(word, aim), polarity, group)
            }
        }

        /** §4.3.1's tier rule: a word that cannot narrow candidates cannot narrow its own scope either. */
        private fun scopeFor(word: Word, aim: Set<Aspect>): Scope =
            if (word.tier.narrows) Scope.Confined(word.aspects.ifEmpty { aim }) else Scope.Everywhere(aim)

        private fun wordAt(token: Token?): Word? = pages.getOrNull(token?.tokenIndex ?: return null)?.word
    }

    /**
     * Swallows ANTLR's complaints.
     *
     * Not the source of what went unread — that is worked out from the outcome, above. This exists only so a
     * malformed book does not print to standard error: ANTLR's default listener shouts at a log nobody
     * reads, and a book the Art cannot read is an ordinary event here rather than a fault.
     */
    private object SilentErrorListener : org.antlr.v4.runtime.BaseErrorListener()
}
