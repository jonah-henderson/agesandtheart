package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import net.minecraft.resources.Identifier

/**
 * What a word list may be ordered under, worked out from the acceptances (Jonah, 2026-10-21): which words each word
 * applies to, the words naming members, and the words naming a whole part of the world. Whether one word takes another
 * is [Usage]'s, which is the reader's rule.
 */
object Headings {

    /**
     * What a word applies to, as a list shows it: the [words] it is listed under, each with the concepts that word takes
     * it as ([takenAs]), and the parts of the world it reaches [whole] — every one of the game's creatures, features,
     * biomes or structures there, said once rather than each.
     */
    data class Applying(val words: List<Identifier>, val whole: Set<Aspect>, val takenAs: List<Set<Identifier>> = words.map { emptySet() })

    /** The words each word applies to ([applying]'s words), for a list ordered under them. */
    fun appliesTo(usage: Usage): Map<Identifier, List<Identifier>> =
        applying(usage).mapValues { it.value.words }.filterValues { it.isNotEmpty() }

    /**
     * What each word applies to: every thing or part's page of a pack's that would take something it offers more than a
     * lean ([Usage.describes]), and each
     * creature, feature, biome or structure of the game's that would, except where it reaches all of them in that part
     * — then the part is reached [whole][Applying.whole], and said of its page rather than of each — or where it is one of
     * a group's that takes it too and reaches most of that group's, which the group then says for them (the game's
     * trees, under `trees`; but `charged` keeps `creeper`, the one monster it charges). A word naming
     * members of a part, or any other thing of one, is listed under that part's page — `zombie` and `monsters` under
     * `inhabitants`, `spikes` under `features` — unless it is that page (`aurora`).
     */
    fun applying(usage: Usage): Map<Identifier, Applying> {
        val words = usage.words
        val takers = takersIn(words)
        val (ofTheGame, headings) = takers.partition { it.word.id in words.derived && it.namesMembers }
        val ofTheGameIn = ofTheGame.groupingBy { it.part }.eachCount()
        val takerIds = takers.map { it.word.id }.toSet()
        val pagesFor = takers.filter { it.isAPartsPage(words) }.groupBy({ it.part }, { it.word.id })
        fun takersOf(word: Word, among: List<Heading>) = among.filter { usage.describes(it.word, word) }
        val gameMembersOf = groupsOfTheGame(usage, headings, ofTheGame)
        val describing = words.all.filter { it.id !in takerIds && it.defaultSense?.behaviour == null }.associate { word ->
            val under = takersOf(word, headings)
            val (whole, some) = takersOf(word, ofTheGame).groupBy { it.part }.entries
                .partition { (part, reached) -> reached.size == ofTheGameIn[part] }
            val reachedOfTheGame = some.flatMap { it.value }.map { it.word.id }.toSet()
            fun reachedOf(group: Set<Identifier>) = group.intersect(reachedOfTheGame)
            fun reachesMostOf(group: Set<Identifier>) = reachedOf(group).size * 2 > group.size
            val saidByAGroup = under.mapNotNull { gameMembersOf[it.word.id] }.filter(::reachesMostOf).flatMap(::reachedOf).toSet()
            val listedUnder = (under + some.flatMap { it.value }.filter { it.word.id !in saidByAGroup }).distinctBy { it.word.id }
            // A reading is the taker's own idiom, no kind of meaning to file a word under, and a lean is not said of it.
            val takenAs = listedUnder.map { usage.conceptsTaken(it.word, word) - CoreConcepts.READING - CoreConcepts.LEAN }
            word.id to Applying(listedUnder.map { it.word.id }, whole.map { it.key }.toSet(), takenAs)
        }
        val naming = takers.filter { it.namesMembers || !it.isAPartsPage(words) }.associate { taker ->
            val pages = pagesFor[taker.part].orEmpty() - taker.word.id
            taker.word.id to Applying(pages, emptySet(), pages.map { setOf(CoreConcepts.MEMBER) })
        }
        return (describing + naming).filterValues { it.words.isNotEmpty() || it.whole.isNotEmpty() }
    }

    /** Each group's members of the game's own — every tree feature, under `trees` — where it has any. */
    private fun groupsOfTheGame(usage: Usage, headings: List<Heading>, ofTheGame: List<Heading>): Map<Identifier, Set<Identifier>> {
        val groups = headings.filter { it.namesMembers && !it.isAPartsPage(usage.words) }
        return groups.associate { group ->
            val members = ofTheGame.filter { member ->
                member.part == group.part && CoreConcepts.MEMBER in usage.conceptsTaken(group.word, member.word)
            }
            group.word.id to members.map { it.word.id }.toSet()
        }.filterValues { it.isNotEmpty() }
    }

    /** How many of the game's own creatures, features, biomes and structures each part of the world holds. */
    fun ofTheGame(words: Words): Map<Aspect, Int> =
        takersIn(words).filter { it.word.id in words.derived && it.namesMembers }.groupingBy { it.part }.eachCount()

    /** The part of the world each word others may be listed under belongs to. */
    fun partsOf(words: Words): Map<Identifier, Aspect> = takersIn(words).associate { it.word.id to it.part }

    /** The words naming members of a part of the world — `zombie`, `monsters`, `plains`. */
    fun members(words: Words): Set<Identifier> = takersIn(words).filter { it.namesMembers }.map { it.word.id }.toSet()

    /** The words naming a whole part of the world — `inhabitants`, `sky`, `landmass`. */
    fun parts(words: Words): Set<Identifier> = takersIn(words).filter { it.isAPartsPage(words) }.map { it.word.id }.toSet()

    private fun takersIn(words: Words): List<Heading> = words.all.mapNotNull { word ->
        val sense = word.defaultSense?.takeIf { it.endsAPhrase && it.behaviour == null } ?: return@mapNotNull null
        val part = sense.speaksFor ?: return@mapNotNull null
        Heading(word, sense, part)
    }

    /** A word others may be listed under: a thing or a part's page, by its default sense. */
    private class Heading(val word: Word, sense: Sense, val part: Aspect) {
        val namesMembers: Boolean = sense.offers.any { it.concept == CoreConcepts.MEMBER } ||
            sense.means.any { it is Effect.Chooses || it is Effect.Admits || it is Effect.Restricts }

        fun isAPartsPage(words: Words): Boolean = words.partPage(part)?.id == word.id
    }
}
