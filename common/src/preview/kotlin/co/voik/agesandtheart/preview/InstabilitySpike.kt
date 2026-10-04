package co.voik.agesandtheart.preview

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.book.FoundBookDraft
import co.voik.agesandtheart.book.FoundBookKind
import kotlin.random.Random

/**
 * **A SPIKE — do not build on this.** It answers, with numbers, what instability today's grammar lets a
 * book reach, against the thresholds the manifestations open at (16 for phenomena, 120 for worsening
 * wounds, 200 for tectonics and certain collapse) and the desk's page limits (5, 20, unlimited).
 *
 * Four questions: what the found books carry; which registers a random row reaches at all; how high a
 * greedy writer can climb within each page limit, both writing rows that read exactly as laid and writing
 * anything at all (repair and impossible pages included); and what one structural page repeated costs.
 */
fun main(args: Array<String>) {
    val vocabulary = Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen)
    if ("honest" in args) {
        for (limit in PAGE_LIMITS) reportHonestCeiling(vocabulary, limit)
        return
    }
    if ("words" in args) {
        for (word in vocabulary.authoredWords) {
            println("%-14s firm=%-8s wants=%s against=%s sets=%s".format(word.name, word.firmness, word.wanted.sorted(), word.unwanted.sorted(), word.sets.keys.sorted()))
        }
        return
    }
    if ("tags" in args) {
        reportWhoWantsEachTag(vocabulary)
        return
    }
    if ("rows" in args) {
        for (row in UNSTABLE_CANDIDATES) {
            val pages = row.split(" ")
            val readsAsLaid = Grammar.parses(vocabulary, pages)
            val instability = instabilityOf(vocabulary, pages)
            println("%-4s %-5s %-70s %s".format(instability?.index, readsAsLaid, row, instability?.byRegister()))
        }
        return
    }
    if ("summary" in args) {
        reportFoundBooks(vocabulary)
        println("age x5=${instabilityOf(vocabulary, List(5) { "age" })?.index} x20=${instabilityOf(vocabulary, List(20) { "age" })?.index}")
        for (limit in listOf(5, 10, 20)) reportStuffedClause(vocabulary, limit)
        return
    }
    if ("stuff" in args) {
        for (limit in listOf(5, 10, 20)) reportStuffedClause(vocabulary, limit)
        return
    }
    if ("variety" in args) {
        for (limit in PAGE_LIMITS) reportHonestCeiling(vocabulary, limit, varietyOnly = true)
        return
    }
    reportFoundBooks(vocabulary)
    reportRegistersInRandomRows(vocabulary)
    reportRepeatedPages(vocabulary)
    for (honest in listOf(true, false)) {
        for (limit in PAGE_LIMITS) reportGreedyCeiling(vocabulary, limit, honest)
    }
}

private const val SEED = 1L
private val PAGE_LIMITS = listOf(5, 20, 40, 80)

private fun instabilityOf(vocabulary: Vocabulary, pages: List<String>): Instability? {
    val sentence = Grammar.read(vocabulary, pages) ?: return null
    return runCatching { Resolver.resolve(vocabulary, sentence, SEED).instability }.getOrNull()
}

private fun Instability.byRegister(): String =
    flaws.groupBy { it.register }.entries.sortedBy { it.key.ordinal }
        .joinToString(" ") { (register, flaws) -> "${register.key}=${flaws.sumOf { it.severity }}(${flaws.size})" }

private fun reportFoundBooks(vocabulary: Vocabulary) {
    println("=== Found books: instability over 500 seeds ===")
    for (kind in FoundBookKind.entries) {
        val indices = (1L..500L).mapNotNull { FoundBookDraft.drawn(vocabulary, kind, it)?.instability?.index }
        if (indices.isEmpty()) continue
        val sorted = indices.sorted()
        println(
            "%-10s n=%d  min %d  median %d  p90 %d  max %d  coherent %d%%".format(
                kind.key, sorted.size, sorted.first(), sorted[sorted.size / 2], sorted[sorted.size * 9 / 10],
                sorted.last(), sorted.count { it == 0 } * 100 / sorted.size,
            ),
        )
    }
    println()
}

private fun reportRegistersInRandomRows(vocabulary: Vocabulary) {
    println("=== Registers reached by 2000 random rows of 2..20 pages ===")
    val sayable = vocabulary.authoredWords.map { it.name } + vocabulary.grammarWords.map { it.name }
    val counts = mutableMapOf<Register, Int>()
    val charged = mutableMapOf<Register, Int>()
    val indices = mutableListOf<Int>()
    for (seed in 1L..2000L) {
        val random = Random(seed)
        val row = listOf("age") + List(random.nextInt(1, 20)) { sayable.random(random) }
        val instability = instabilityOf(vocabulary, row) ?: continue
        indices += instability.index
        for (flaw in instability.flaws) {
            counts.merge(flaw.register, 1, Int::plus)
            charged.merge(flaw.register, flaw.severity, Int::plus)
        }
    }
    for (register in Register.entries) {
        println("%-11s flaws %5d  charged %6d".format(register.key, counts[register] ?: 0, charged[register] ?: 0))
    }
    val sorted = indices.sorted()
    println("index: median %d  p90 %d  max %d".format(sorted[sorted.size / 2], sorted[sorted.size * 9 / 10], sorted.last()))
    println()
}

private fun reportRepeatedPages(vocabulary: Vocabulary) {
    println("=== One structural page repeated, after 'age' ===")
    for (grammarWord in vocabulary.grammarWords) {
        val line = listOf(1, 5, 20, 40).joinToString("  ") { times ->
            val index = instabilityOf(vocabulary, listOf("age") + List(times) { grammarWord.name })?.index
            "x$times=$index"
        }
        println("%-12s %s".format(grammarWord.name, line))
    }
    println()
}

/**
 * Appends, one page at a time, whichever candidate raises the index most. [honest] keeps to rows that read
 * exactly as laid, so no repair and no impossible page — what a writer gets for writing properly.
 */
private fun reportGreedyCeiling(vocabulary: Vocabulary, limit: Int, honest: Boolean) {
    val random = Random(SEED)
    val authored = vocabulary.authoredWords.map { it.name }
    val structural = vocabulary.grammarWords.map { it.name }
    val derivedSample = vocabulary.derivedWords.map { it.name }.shuffled(random).take(DERIVED_CANDIDATES)
    val candidates = (authored + structural + derivedSample).distinct()
    var row = listOf("age")
    var best = instabilityOf(vocabulary, row) ?: Instability.NONE
    while (row.size < limit) {
        val reading = Grammar.reading(vocabulary, row)
        var stepBest: Pair<List<String>, Instability>? = null
        for (page in candidates) {
            val tried = row + page
            if (honest && !reading.parsesWith(listOf(page))) continue
            val instability = instabilityOf(vocabulary, tried) ?: continue
            if (stepBest == null || instability.index > stepBest.second.index) stepBest = tried to instability
        }
        val (next, instability) = stepBest ?: break
        row = next
        best = instability
    }
    val mode = if (honest) "reads as laid" else "anything     "
    println("=== Greedy ceiling, $mode, $limit pages: index ${best.index} ===")
    println("  ${best.byRegister()}")
    println("  ${row.joinToString(" ")}")
    println()
}

private const val DERIVED_CANDIDATES = 120

/**
 * The honest ceiling, a clause at a time: every contradiction needs two pages at least, so a page-at-a-time
 * search never finds one. Candidate clauses are a word and an aiming page, or two words from opposite
 * sides of an antonym pair and an aiming page; each step adds whichever clause raises the index most per
 * page, keeping only books that read exactly as laid.
 */
private fun reportHonestCeiling(vocabulary: Vocabulary, limit: Int, varietyOnly: Boolean = false) {
    val aims = Aspect.entries.map { it.page }.filter { vocabulary.word(it) != null || vocabulary.grammarWord(it) != null }
    fun wanting(tag: String) = vocabulary.authoredWords.filter { it.narrows && tag in it.wanted }.take(WANTERS_A_SIDE)
    val pairs = vocabulary.antonyms.flatMap { antonym ->
        wanting(antonym.first).flatMap { first -> wanting(antonym.second).map { second -> first.name to second.name } }
    }
    val opposedWords = pairs.flatMap { listOf(it.first, it.second) }.distinct()
    val clauses = aims.flatMap { aim ->
        opposedWords.map { listOf(it, aim) } + pairs.map { (first, second) -> listOf(first, second, aim) }
    }
    // Repeats counted once: the index a once-per-flaw rule would charge.
    fun indexOf(instability: Instability): Int =
        if (!varietyOnly) instability.index
        else instability.flaws.distinctBy { Triple(it.register, it.words.sorted(), it.aspect) }.sumOf { it.severity }
    var book = listOf("age")
    var best = Instability.NONE
    val used = mutableSetOf<List<String>>()
    while (true) {
        val reading = Grammar.reading(vocabulary, book)
        var stepBest: Triple<List<String>, Instability, Double>? = null
        var stepClause: List<String>? = null
        for (clause in clauses) {
            if (varietyOnly && clause in used) continue
            if (book.size + clause.size > limit) continue
            if (!reading.parsesWith(clause)) continue
            val instability = instabilityOf(vocabulary, book + clause) ?: continue
            val gain = (indexOf(instability) - indexOf(best)).toDouble() / clause.size
            if (gain <= 0.0) continue
            if (stepBest == null || gain > stepBest.third) {
                stepBest = Triple(book + clause, instability, gain)
                stepClause = clause
            }
        }
        val (next, instability, _) = stepBest ?: break
        stepClause?.let { used += it }
        book = next
        best = instability
    }
    val mode = if (varietyOnly) "variety only, repeats once" else "reads as laid"
    println("=== Honest ceiling ($mode), $limit pages: index ${indexOf(best)} in ${book.size} pages ===")
    println("  ${best.byRegister()}")
    println("  ${book.joinToString(" ")}")
    println()
}

private const val WANTERS_A_SIDE = 3

/**
 * One clause stuffed word by word: for each aiming page, the word that raises the index most is laid in
 * front of it, as long as the book still reads as laid. Pairs are charged per two different words, so
 * synonyms on both sides of one opposition grow the index with the square of the clause.
 */
private fun reportStuffedClause(vocabulary: Vocabulary, limit: Int) {
    val aims = Aspect.entries.map { it.page }.filter { vocabulary.word(it) != null || vocabulary.grammarWord(it) != null }
    // Only words that can disagree with something: a word with no claim to oppose never raises the index.
    val pool = vocabulary.authoredWords.filter { it.wanted.isNotEmpty() || it.unwanted.isNotEmpty() || it.sets.isNotEmpty() }
    val candidates = pool.map { it.name }
    val opposedPairs = pool.flatMap { first -> pool.filter { second -> first.name < second.name && vocabulary.disagreement(first, second) != null }.map { listOf(first.name, it.name) } }
    println("($limit pages: ${pool.size} words can disagree, ${opposedPairs.size} opposed pairs among them)")
    var overall: Pair<List<String>, Instability>? = null
    for (aim in aims) {
        val seeded = opposedPairs.mapNotNull { pair ->
            val row = listOf("age") + pair + aim
            if (!Grammar.parses(vocabulary, row)) return@mapNotNull null
            instabilityOf(vocabulary, row)?.let { pair to it }
        }.maxByOrNull { it.second.index } ?: continue
        var words = seeded.first
        var best = seeded.second
        while (words.size + 2 < limit) {
            var stepBest: Pair<List<String>, Instability>? = null
            for (word in candidates) {
                val tried = words + word
                val row = listOf("age") + tried + aim
                if (!Grammar.parses(vocabulary, row)) continue
                val instability = instabilityOf(vocabulary, row) ?: continue
                if (instability.index <= best.index) continue
                if (stepBest == null || instability.index > stepBest.second.index) stepBest = tried to instability
            }
            val (next, instability) = stepBest ?: break
            words = next
            best = instability
        }
        if (overall == null || best.index > overall.second.index) overall = (listOf("age") + words + aim) to best
    }
    val (row, best) = overall ?: return
    println("=== Stuffed clause, $limit pages: index ${best.index} ===")
    println("  ${best.byRegister()}")
    println("  ${row.joinToString(" ")}")
    println()
}

/** Per tag, the authored words that ask for it and against it — what an antonym pair over it would reach. */
private fun reportWhoWantsEachTag(vocabulary: Vocabulary) {
    val authored = vocabulary.authoredWords
    val tags = authored.flatMap { it.wanted + it.unwanted }.toSortedSet()
    for (tag in tags) {
        val wanting = authored.filter { tag in it.wanted }.map { it.name }
        val against = authored.filter { tag in it.unwanted }.map { it.name }
        println("%-14s wanted by %-3d %s".format(tag, wanting.size, wanting.joinToString(" ")))
        if (against.isNotEmpty()) println("%-14s against  %-3d %s".format("", against.size, against.joinToString(" ")))
    }
    println("pairs in the table: " + vocabulary.antonyms.joinToString { "${it.first}/${it.second}" })
}

private val UNSTABLE_CANDIDATES = listOf(
    "age packed_ice landmass frozen scorching tropical climate",
    "age snow_block landmass frozen scorching tropical climate",
    "age packed_ice landmass frozen scorching inferno climate",
    "age arid landmass flooded rock drenched weather",
    "age arid landmass flooded rock verdant climate",
    "age colossal minuscule landmass blizzard inferno weather",
    "age colossal minuscule landmass parched deluge weather",
    "age villagers ruins structures trees arid landmass",
    "age villagers ruins structures drowned arid landmass",
    "age trees arid landmass parched deluge weather",
    "age frozen scorching climate trees arid landmass",
)
