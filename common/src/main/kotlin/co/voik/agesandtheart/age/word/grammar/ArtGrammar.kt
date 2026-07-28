package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.grammar.ArtParser
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.Token

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

        val parser = ArtParser(CommonTokenStream(ListTokenSource(readable.map(::tokenFor))))
        // Ours, so a malformed book is data rather than an exception on a server thread.
        val refused = CollectingErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(refused)

        val constraints = Reading(readable).of(parser.sentence())
        return Sentence(constraints, unreadable.map { it.written } + refused.unreadable(readable))
    }

    // Token types come from the **parser**, not from a lexer: the grammar declares them with `tokens {}` and
    // has no lexer rules at all, so any `ArtLexer` on disk is a leftover. Reading them from there once left
    // the numbering silently off by one past PRESET, which surfaced as a syntax error on an innocent page.
    private fun tokenFor(page: Page): Token = CommonToken(
        when (page.kind) {
            PageClass.EVOCATIVE -> ArtParser.EVOCATIVE
            PageClass.PRESET -> ArtParser.PRESET
            PageClass.SETTER -> ArtParser.SETTER
            PageClass.JOINER -> ArtParser.AND
            PageClass.RESTRICTOR -> ArtParser.ONLY
            PageClass.EXCLUDER -> ArtParser.EXCEPT
            // Filtered out before this is reached; the branch exists so a new class breaks the build here.
            null -> error("an unreadable page reached the parser")
        },
        page.written,
    )

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
            val subject = wordAt(section.subject()?.PRESET()?.symbol) ?: return emptyList()
            // What the section is about, and therefore what everything in it is aimed at. This is the whole
            // of "position decides attachment": no word is searched for a home, it simply has the one it
            // was laid down in.
            val aim = subject.slots

            val descriptors = section.descriptor().mapNotNull { descriptor ->
                val word = wordAt(descriptor.EVOCATIVE().symbol) ?: return@mapNotNull null
                // Aimed, and still global. An evocative word tilts and never narrows, so narrowing its
                // scope would be a category error against its own tier (§4.3.1).
                Constraint(word, Scope.Everywhere(emphasised = aim))
            }
            val head = Constraint(subject, scopeFor(subject, aim))
            return descriptors + head + section.modifier().flatMap { modifier -> constraintsIn(modifier, aim) }
        }

        private fun constraintsIn(modifier: ArtParser.ModifierContext, aim: Set<Slot>): List<Constraint> {
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
        private fun scopeFor(word: Word, aim: Set<Slot>): Scope =
            if (word.tier.narrows) Scope.Confined(word.slots.ifEmpty { aim }) else Scope.Everywhere(aim)

        private fun wordAt(token: Token?): Word? = pages.getOrNull(token?.tokenIndex ?: return null)?.word
    }

    /**
     * Every token ANTLR could not fit into the grammar, so the writer can be told which pages went unread.
     *
     * Collected rather than thrown for the reason on the class: a book the Art cannot read still makes an
     * Age. Recovery is ANTLR's default strategy, which resynchronises and carries on — so a garbled middle
     * costs the pages around it and not the whole book.
     */
    private class CollectingErrorListener : org.antlr.v4.runtime.BaseErrorListener() {
        private val offending = mutableListOf<Int>()

        override fun syntaxError(
            recognizer: org.antlr.v4.runtime.Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            message: String?,
            exception: org.antlr.v4.runtime.RecognitionException?,
        ) {
            (offendingSymbol as? Token)?.let { offending += it.tokenIndex }
        }

        fun unreadable(pages: List<Page>): List<String> =
            offending.distinct().sorted().mapNotNull { index -> pages.getOrNull(index)?.written }
    }
}
