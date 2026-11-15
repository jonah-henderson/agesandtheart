package co.voik.agesandtheart.worldgen.feature

/** Which of [count] things a [roll] in `0..1` lands on, each as heavily as [weights] says, or evenly where none are given. */
object WeightedDraw {
    fun indexAt(roll: Double, weights: List<Double>, count: Int): Int {
        if (weights.size != count || weights.sum() <= 0.0) return (roll * count).toInt().coerceIn(0, count - 1)
        var left = roll * weights.sum()
        for ((index, weight) in weights.withIndex()) {
            left -= weight
            if (left < 0.0) return index
        }
        return weights.indexOfLast { it > 0.0 }
    }
}
