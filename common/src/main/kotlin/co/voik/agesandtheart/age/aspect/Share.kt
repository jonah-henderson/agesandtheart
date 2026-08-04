package co.voik.agesandtheart.age.aspect

/**
 * How much of an Age one preset covers, relative to the others in its aspect: the sentence's claim on it
 * as a fraction of its strongest claim, so the widest territory is [EVEN] and the rest measure against it.
 *
 * A number rather than a ladder of named rungs. The rungs were geometric and snapped to in log space,
 * which is to say they were this ratio rounded — and what the rounding bought was a name for each rung
 * that nothing ever said, since a writer's "a few" is a quantifier on a claim (§4.5) and not a share.
 *
 * [Rung] is the neighbouring idea and the opposite one: absolute emphasis, where this is relative ground.
 */
object Share {
    /** What a territory covers when nothing favours another over it — so an even division is all [EVEN]. */
    const val EVEN = 1.0

    /** Below a hundredth of the world a territory stops being scarce and starts being absent. */
    const val LEAST_SHARE_OF_A_WORLD = 0.01

    fun isEven(share: Double): Boolean = share == EVEN

    /**
     * These shares with nothing in them so faint it would never be found: a word a writer wrote must be
     * **findable**, and a claim can be arbitrarily fainter than the strongest without being nothing.
     *
     * Deliberately a low floor, so that scarce means scarce and finding it is the reward.
     */
    fun findable(shares: List<Double>): List<Double> {
        if (shares.size <= 1) return shares
        // Raising a share raises the whole it is measured against, so the floor is solved for rather than
        // iterated towards: with the faintest few sitting exactly on it, it is that fraction of what is
        // left over. The first count of raised shares the arithmetic agrees with is the answer.
        val ascending = shares.sorted()
        for (raised in 0..<shares.size) {
            val untouched = ascending.drop(raised).sum()
            val floor = LEAST_SHARE_OF_A_WORLD * untouched / (1.0 - LEAST_SHARE_OF_A_WORLD * raised)
            val everythingUnderItIsBeingRaised = ascending.take(raised).all { it < floor }
            val nothingElseNeedsRaising = ascending[raised] >= floor
            if (everythingUnderItIsBeingRaised && nothingElseNeedsRaising) {
                return shares.map { it.coerceAtLeast(floor) }
            }
        }
        return shares
    }

    /**
     * [share] as a recipe holds it. Two decimals is as fine as a division of ground can mean, and it is
     * what lets the spelling a command reads back be exactly the share that was written down.
     */
    fun legible(share: Double): Double = Rung.legible(share)

    /** The share [spelled] after an `@`, or null where that is not a share at all. */
    fun read(spelled: String): Double? = spelled.toDoubleOrNull()?.takeIf { it > 0.0 }
}
