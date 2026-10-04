package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Speech
import net.minecraft.locale.Language
import java.util.Locale

/**
 * **[ProseClause]s written out as sentences**, in whatever language [said] speaks.
 *
 * English first and by design (§4.1): every frame is a key in the language file, so a translation can make
 * a best effort, though not every language will bend to sentences built this way. The two places English is
 * assumed outright — names lower-cased mid-sentence, and plurals and articles guessed by English rules —
 * each have a key a translation can turn off.
 *
 * Pure, so it runs wherever a book is read and a check can drive it with a map for a language file.
 */
object ProseWriting {
    /** The running game's language — the client's, wherever a book is read. */
    val inTheGamesLanguage: (String) -> String? = { key ->
        Language.getInstance().takeIf { it.has(key) }?.getOrDefault(key)
    }

    /** [clauses] as sentences, each capitalised and closed — one per thing said. */
    fun sentencesOf(clauses: List<ProseClause>, said: (String) -> String? = inTheGamesLanguage): List<String> =
        Writer(said, clauses).sentences()

    private class Writer(private val said: (String) -> String?, private val clauses: List<ProseClause>) {
        private val lowersNames = said(key("lowercase_names")) != "false"
        private val inflectsInEnglish = said(key("english_inflection")) != "false"

        /** How many members of each population the book describes, so a second sun is called the second. */
        private val bodies: Map<Aspect, Int> = clauses
            .filter { it.about?.holds == Holds.POPULATION }
            .groupBy { it.about!! }
            .mapValues { (_, about) -> about.mapNotNull { it.body }.distinct().size }

        fun sentences(): List<String> = clauses.flatMap { clause ->
            val about = clause.about
            if (about == null) listOf(theAge(clause)) else sentencesAbout(about, clause)
        }.map(::capitalised)

        /**
         * The Age itself: "A beautiful, ancient Age of basalt." Its qualities stand in front of it with
         * commas, the way a chronicle opens; anything it is made of or full of follows.
         */
        private fun theAge(clause: ProseClause): String {
            val qualities = qualities(clause.terms.filter { it.speech == Speech.ADJECTIVE })
            val things = clause.terms.filter { it.speech != Speech.ADJECTIVE }
            val (without, with) = things.partition { it.polarity == Polarity.EXCEPT }
            val described = qualities.joinToString(word("list.separator"))
            val opening = listOf(articleFor(described.ifEmpty { word("age.noun") }), described)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val after = buildString {
                if (with.isNotEmpty()) append(framed("age.of", listOf(list(things(with)))))
                if (without.isNotEmpty()) append(framed("age.without", listOf(list(without.map(::bare)))))
            }
            return framed("age", listOf(opening, after))
        }

        /**
         * What one clause says about one part of the world: what it is like, then what it holds — two
         * sentences where it said both, since "its land is gentle" and "its land bears arches" are two
         * claims and joining them would make one of them a clause of the other.
         */
        private fun sentencesAbout(about: Aspect, clause: ProseClause): List<String> {
            val where = whereOf(clause)
            clause.shape?.let { return listOf(sentence(about, "has", shaped(it), where, ordinal = null)) }
            val ordinal = clause.body?.takeIf { (bodies[about] ?: 0) > 1 }?.let { ordinal(it + 1) }
            val (named, described) = clause.terms.partition { it.speech.names }
            return buildList {
                if (described.isNotEmpty()) add(sentence(about, "is", predicate(described), where, ordinal))
                if (named.isNotEmpty()) add(sentence(about, "has", list(things(named)), where, ordinal))
            }
        }

        private fun whereOf(clause: ProseClause): String = when {
            clause.confinedTo != null -> framed("in", listOf(plural(clause.confinedTo)))
            clause.everywhere -> word("everywhere")
            else -> ""
        }

        /** A minted shape: its rung and qualities before it, what it is made of after. */
        private fun shaped(minted: ProseShape): String {
            val qualities = minted.qualities.map(::nameOf)
            val named = (qualities + plural(minted.shape)).joinToString(" ")
            val counted = quantified(minted.shape, named)
            val madeOf = if (minted.madeOf.isEmpty()) "" else framed("shape_of", listOf(list(things(minted.madeOf))))
            return polarised(listOf(minted.shape), except = { "no" }) { counted + madeOf }.single()
        }

        /** The most particular frame the language file has for this sentence, filled. */
        private fun sentence(about: Aspect, kind: String, said: String, where: String, ordinal: String?): String {
            val base = "${about.key}.$kind"
            val candidates = buildList {
                if (ordinal != null && where.isNotEmpty()) add("$base.nth.sited")
                if (where.isNotEmpty()) add("$base.sited")
                if (ordinal != null) add("$base.nth")
                add(base)
            }
            val frame = candidates.firstNotNullOfOrNull { said(key(it)) } ?: key(base)
            return format(frame, said, where, ordinal.orEmpty())
        }

        /** What something is like: its qualities, and then what it is made of. */
        private fun predicate(terms: List<ProseTerm>): String {
            val qualities = qualities(terms.filter { it.speech == Speech.ADJECTIVE })
            val substances = things(terms.filter { it.speech == Speech.MASS })
            val madeOf = if (substances.isEmpty()) emptyList() else listOf(framed("made_of", listOf(list(substances))))
            return list(qualities + madeOf)
        }

        /** Qualities, each with its rung, and `only` or `except` said as the writer laid them. */
        private fun qualities(terms: List<ProseTerm>): List<String> =
            polarised(terms, except = { "not" }) { quantified(it, nameOf(it)) }

        /** Things there are, said as many where they can be counted. */
        private fun things(terms: List<ProseTerm>): List<String> =
            polarised(terms, except = { if (it.speech == Speech.MASS) "anything_but" else "no" }, say = ::bare)

        /** A thing as itself, with no `only` or `except` — for where the frame already says which. */
        private fun bare(term: ProseTerm): String = quantified(term, plural(term))

        /**
         * [terms] said, with **one `only` or `except` over each run that shares it** — `only wolf and witch`
         * is "only wolves and witches", where saying it per term would read as two separate pins.
         */
        private fun polarised(
            terms: List<ProseTerm>,
            except: (ProseTerm) -> String,
            say: (ProseTerm) -> String,
        ): List<String> {
            val runs = mutableListOf<MutableList<ProseTerm>>()
            for (term in terms) {
                val previous = runs.lastOrNull()?.last()
                val continuesTheRun = previous != null && previous.polarity == term.polarity &&
                    except(previous) == except(term)
                if (continuesTheRun) runs.last() += term else runs += mutableListOf(term)
            }
            return runs.flatMap { run ->
                val first = run.first()
                when (first.polarity) {
                    Polarity.ASSERTED -> run.map(say)
                    Polarity.ONLY -> listOf(framed("only", listOf(list(run.map(say)))))
                    Polarity.EXCEPT -> listOf(framed(except(first), listOf(list(run.map(say)))))
                }
            }
        }

        private fun quantified(term: ProseTerm, said: String): String {
            val rung = term.quantifier ?: return said
            val rungSaid = said(key(rung)) ?: rung.replace('_', ' ')
            return framed("quantified", listOf(rungSaid, said))
        }

        /**
         * What a word is called mid-sentence: a name written for prose, else vanilla's name for the thing,
         * else the name a page carries, else its id made legible.
         */
        private fun nameOf(term: ProseTerm): String {
            val id = term.word
            said("prose.${id.namespace}.${id.path}")?.let { return it }
            val named = term.nameKey?.let(said) ?: said("word.${id.namespace}.${id.path}")
            return named?.let { if (lowersNames) it.lowercase(Locale.ROOT) else it } ?: term.plain
        }

        private fun plural(term: ProseTerm): String {
            val id = term.word
            said("prose.${id.namespace}.${id.path}.plural")?.let { return it }
            val name = nameOf(term)
            return if (term.speech == Speech.NOUN && inflectsInEnglish) English.plural(name) else name
        }

        private fun articleFor(next: String): String =
            word(if (inflectsInEnglish && English.takesAn(next)) "an" else "a")

        private fun ordinal(position: Int): String = said(key("ordinal.$position")) ?: "$position."

        /** "a, b and c" — the separator and the last joint are the language file's. */
        private fun list(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items.single()
            else -> framed("list.last", listOf(items.dropLast(1).joinToString(word("list.separator")), items.last()))
        }

        private fun word(name: String): String = said(key(name)) ?: name

        private fun framed(name: String, filling: List<String>): String =
            format(said(key(name)) ?: key(name), *filling.toTypedArray())

        private fun format(frame: String, vararg filling: String): String =
            runCatching { String.format(Locale.ROOT, frame, *filling) }.getOrDefault(frame)

        private fun capitalised(sentence: String): String = sentence.replaceFirstChar { it.titlecase(Locale.ROOT) }

        private fun key(name: String): String = "prose.agesandtheart.$name"
    }
}

/**
 * The two pieces of English a sentence cannot be written without and a language file cannot carry: which
 * article a word takes, and how a name is made plural. Guesses, both, and good enough that the language
 * file's `.plural` keys are for the exceptions rather than the rule.
 */
internal object English {
    /** Words already plural, or never counted — said as they stand. */
    private val UNCOUNTED = setOf(
        "algae", "bamboo", "clay", "cod", "dark", "debris", "deer", "dirt", "dripstone", "fish", "fungi", "glowstone",
        "grass", "gravel", "ice", "kelp", "lichen", "litter", "magma", "moss", "ore", "salmon", "sand",
        "seagrass", "sheep", "silverfish", "snow", "undead", "vegetation", "wildflowers",
    )

    private val IRREGULAR = mapOf(
        "wolf" to "wolves",
        "leaf" to "leaves",
        "enderman" to "endermen",
        "volcano" to "volcanoes",
        "mouse" to "mice",
    )

    /** [name] made plural, by its last word: "dark forest" → "dark forests". */
    fun plural(name: String): String {
        val last = name.substringAfterLast(' ')
        val head = name.dropLast(last.length)
        return head + pluralOf(last)
    }

    private fun pluralOf(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        IRREGULAR[lower]?.let { return it }
        return when {
            lower in UNCOUNTED || lower.endsWith("fish") -> word
            lower.endsWith("ss") || lower.endsWith("x") || lower.endsWith("z") ||
                lower.endsWith("ch") || lower.endsWith("sh") -> word + "es"
            // Already plural, as most names ending in `s` are: "plains", "badlands", "nether wastes".
            lower.endsWith("s") -> word
            lower.length > 1 && lower.endsWith("y") && lower[lower.length - 2] !in VOWELS -> word.dropLast(1) + "ies"
            else -> word + "s"
        }
    }

    /** Whether [next] takes "an" — by its first letter, which is right for every word this corpus has. */
    fun takesAn(next: String): Boolean {
        val lower = next.trimStart().lowercase(Locale.ROOT)
        if (lower.startsWith("uni") || lower.startsWith("use") || lower.startsWith("one")) return false
        return lower.firstOrNull()?.let { it in VOWELS } ?: false
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
}
