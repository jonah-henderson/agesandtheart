package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.preview.authoring.Pairings.Pairing
import co.voik.agesandtheart.preview.authoring.Pairings.Verdict

/**
 * What a person has ruled of a phrase: `high sea | silent | wanted | deep, as low's is shallow`. A ruling settles the
 * phrase while its verdict stands, and a phrase whose verdict has changed since asks again. The phrases ruled out of
 * the inert report ([InertPhrases.REVIEWED]) settle a silent or straying phrase too.
 */
class PairingRulings(private val byPhrase: Map<String, Ruling>, private val ruledInert: (InertPhrases.Gap) -> Boolean) {

    data class Ruling(val phrase: String, val verdict: String, val ruling: String, val note: String)

    fun settles(pairing: Pairing): Boolean {
        val ruling = byPhrase[pairing.phrase]
        if (ruling != null) return ruling.verdict == pairing.verdict.said
        val couldBeInert = pairing.verdict == Verdict.SILENT || pairing.verdict == Verdict.STRAYS
        return couldBeInert && ruledInert(asInert(pairing))
    }

    fun rulingOf(pairing: Pairing): Ruling? = byPhrase[pairing.phrase]

    private fun asInert(pairing: Pairing) =
        InertPhrases.Gap(pairing.modifier, pairing.noun, InertPhrases.Why.NOT_TAKEN, emptySet(), pairing.phrase)

    companion object {
        private const val APART = "|"
        private const val PARTS = 4

        fun read(): PairingRulings {
            val lines = if (Pairings.RULINGS.isFile) Pairings.RULINGS.readLines() else emptyList()
            val rulings = lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
                val parts = line.split(APART).map(String::trim)
                if (parts.size < PARTS - 1) return@mapNotNull null
                Ruling(parts[0], parts[1], parts[2], parts.getOrElse(PARTS - 1) { "" })
            }
            return PairingRulings(rulings.associateBy { it.phrase }, InertPhrases.reviewed())
        }

        /**
         * A word's report: the phrases it makes before other words, by part of the world, and the words before it. A
         * member that came out as its part did is folded into the part's line; only one that differs is named.
         */
        fun report(word: String, before: List<Pairing>, after: List<Pairing>, ruled: PairingRulings): String = buildString {
            appendLine("# The phrases `$word` makes\n")
            appendLine("Rule on one by adding `phrase | verdict | ruling | note` to `${Pairings.RULINGS.path}`; a ruling")
            appendLine("stands while the verdict does. Hollow is always a fault.\n")
            appendLine("## `$word` before another word\n")
            appendFolded(before, ruled)
            if (after.isNotEmpty()) {
                appendLine("## Before `$word`, the words that mean something before its part\n")
                after.sortedBy { it.verdict.ordinal }.forEach { appendLine(line(it, ruled)) }
                appendLine()
            }
        }

        private fun StringBuilder.appendFolded(found: List<Pairing>, ruled: PairingRulings) {
            for ((part, ofThePart) in found.groupBy { it.nounPart }.toSortedMap()) {
                val partLine = ofThePart.firstOrNull { it.isOfAPart }
                val members = ofThePart.filterNot { it.isOfAPart }
                fun likeThePart(member: Pairing) =
                    partLine != null && member.verdict == partLine.verdict && sameGist(member.said, partLine.said)
                val (folded, differing) = members.partition(::likeThePart)
                val alsoSaid = if (folded.isEmpty()) "" else " (and ${folded.size} of its member words)"
                appendLine("### $part\n")
                partLine?.let { appendLine(line(it, ruled, alsoSaid)) }
                differing.sortedWith(compareBy({ it.verdict.ordinal }, { it.noun })).forEach { appendLine(line(it, ruled)) }
                appendLine()
            }
        }

        /** Whether two phrases came to the same thing, read off what they said but for what only one member names. */
        private fun sameGist(one: String, other: String): Boolean =
            one.substringBefore(':') == other.substringBefore(':') && one.startsWith("lands only") == other.startsWith("lands only")

        private fun line(pairing: Pairing, ruled: PairingRulings, alsoSaid: String = ""): String = buildString {
            val ruling = ruled.rulingOf(pairing)?.takeIf { ruled.settles(pairing) }
            val mark = when {
                ruling != null -> " — ruled ${ruling.ruling}${if (ruling.note.isEmpty()) "" else ": ${ruling.note}"}"
                ruled.settles(pairing) -> " — ruled inert"
                else -> ""
            }
            append("- **${pairing.verdict.said}** `${pairing.phrase}` — ${pairing.said}$alsoSaid$mark")
            pairing.hints.forEach { append("\n  - `${it.substringBefore(':')}`:${it.substringAfter(':')}") }
        }
    }
}
