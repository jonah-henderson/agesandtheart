package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * The escape hatch: a word whose sense runs as code, because what it does reaches past the word after it — joining,
 * turning round, siting. Each arranges the row at its own page, and says how many pages after it it bound.
 */
sealed interface StructureWord {
    val id: Identifier

    fun arrange(at: Int, row: Arranging): Int

    /** The Age: a term of its own, which hands each meaning reaching it to every part that takes it. */
    data object Age : StructureWord {
        override val id = "age".location()

        override fun arrange(at: Int, row: Arranging): Int {
            row.addAge(at)
            return 0
        }
    }

    /** Joins like with like — two describing words, or two things — into one term: `red and blue`, `trees and villages`. */
    data object And : StructureWord {
        override val id = "and".location()

        override fun arrange(at: Int, row: Arranging): Int {
            val left = row.lastTerm()
            val right = at + 1
            val leftSense = left?.takeUnless { it.isAge }?.let { row.senseAt(it.pages.last()) }
            val rightSense = row.senseAt(right)?.takeIf { row.isPlainWord(right) }
            if (left == null || leftSense == null || rightSense == null || leftSense.endsAPhrase != rightSense.endsAPhrase) {
                row.dangles(at)
                return 0
            }
            row.replaceLast(left.copy(pages = left.pages + right))
            row.binds(at)
            return 1
        }
    }

    /** Turns its words round: `herds of warden` is `warden herds`, `pyramids of gold_block` is `gold_block pyramids`. */
    data object Of : StructureWord {
        override val id = "of".location()

        override fun arrange(at: Int, row: Arranging): Int {
            val before = row.lastTerm()
            val after = at + 1
            val beforeIsOneThing = before != null && !before.isAge && before.pages.size == 1 &&
                row.senseAt(before.pages.single())?.endsAPhrase == true
            if (before == null || !beforeIsOneThing || !row.isPlainWord(after)) {
                row.dangles(at)
                return 0
            }
            row.replaceLast(Term(listOf(after)))
            row.terms += before.copy(saidOfWhatFollows = true)
            row.binds(at)
            return 1
        }
    }

    /** Sites the thing before it in the biome after it: `beautiful plants in jungle`. */
    data object In : StructureWord {
        override val id = "in".location()

        override fun arrange(at: Int, row: Arranging): Int {
            val thing = row.lastTerm()?.takeIf { !it.isAge && row.senseAt(it.pages.last())?.endsAPhrase == true }
            val biome = row.biomeNamedAt(at + 1)
            if (thing == null || biome == null) {
                row.dangles(at)
                return 0
            }
            row.replaceLast(thing.copy(sitedIn = biome))
            row.binds(at, at + 1)
            return 1
        }
    }

    /** Widens the thing before it to every place: `cats everywhere`. */
    data object Everywhere : StructureWord {
        override val id = "everywhere".location()

        override fun arrange(at: Int, row: Arranging): Int {
            val thing = row.lastTerm()?.takeIf { !it.isAge && row.senseAt(it.pages.last())?.endsAPhrase == true }
            if (thing == null) {
                row.dangles(at)
                return 0
            }
            row.replaceLast(thing.copy(everywhere = true))
            row.binds(at)
            return 0
        }
    }

    /** Where a link into the Age arrives, read with the `in` and the anchor after it: `link in jungle`. To become `arrive`. */
    data object Link : StructureWord {
        override val id = "link".location()

        override fun arrange(at: Int, row: Arranging): Int {
            val anchorAt = at + 2
            val isIn = row.structureAt(at + 1) == In
            val anchor = row.senseAt(anchorAt)?.takeIf { row.isPlainWord(anchorAt) }
            val part = anchor?.speaksFor
            if (!isIn || anchor == null || part == null) {
                row.dangles(at)
                return 0
            }
            val members = anchor.means.filterIsInstance<Effect.Chooses>().filter { it.aspect == part }.map { it.member }.toSet()
            row.arrival = LinkAnchor(part, members)
            row.binds(at, at + 1, anchorAt)
            return 2
        }
    }

    /** The thing after it and nothing else of its kind: `only villages`. */
    data object Only : StructureWord {
        override val id = "only".location()

        override fun arrange(at: Int, row: Arranging): Int {
            row.awaitThing(at, Polarity.ONLY)
            return 0
        }
    }

    /** Anything of its kind but the thing after it — `no villages` — or, before a block, a ban: `no snow`. */
    data object No : StructureWord {
        override val id = "no".location()

        override fun arrange(at: Int, row: Arranging): Int {
            row.awaitThing(at, Polarity.EXCEPT)
            return 0
        }
    }
}

/** Every structure word there is, by the name a word file's `behaviour` gives. */
object StructureWords {
    val ALL: List<StructureWord> = listOf(
        StructureWord.Age, StructureWord.And, StructureWord.Of, StructureWord.In, StructureWord.Everywhere,
        StructureWord.Link, StructureWord.Only, StructureWord.No,
    )

    val IDS: Set<Identifier> = ALL.map(StructureWord::id).toSet()

    fun named(id: Identifier): StructureWord? = ALL.firstOrNull { it.id == id }
}
