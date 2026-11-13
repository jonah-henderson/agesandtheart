package co.voik.agesandtheart.age.reading

/**
 * What became of each page of a row, a line a page — for the instruments and `/age write`, where the reading itself is
 * wanted rather than the account of an Age a book's readout gives. Pure.
 */
object Trace {
    fun of(reading: Reading, drawn: Drawn): List<String> {
        val written = reading.pages.map { it.written }
        val dangledOnto = drawn.dangling.associate { it.page to it.drawnOnto }
        val heldIn = drawn.landed.groupBy { it.page }.mapValues { (_, landings) -> landings.map { it.place.aspect.page }.distinct() }
        return reading.pages.map { page ->
            val landed = heldIn[page.index].orEmpty().joinToString(", ").ifEmpty { "nothing" }
            val what = when (val fate = page.fate) {
                Fate.Unknown -> "no word of the Art"
                Fate.Structure -> "binds the words about it"
                Fate.Closed -> "a thing, landing on $landed"
                is Fate.PassedOn -> "passed on to ${written[fate.to]}"
                Fate.Placed -> "said of what took it, landing on $landed"
                Fate.Dangles -> when {
                    page.index !in dangledOnto -> "dangles"
                    dangledOnto[page.index] == null -> "dangles, and no word takes it"
                    else -> "dangles, said instead of ${dangledOnto[page.index]}, landing on $landed"
                }
            }
            val sense = page.sense?.let { " (sense ${it + 1})" }.orEmpty()
            "${page.index + 1}. ${page.written}$sense: $what"
        } + drawn.flaws.map { "  ${it.describe()}" }
    }
}
