package co.voik.agesandtheart.age.aspect

/**
 * How much of the time an Age rains, and how much of that is thunder — each a fraction of its axis,
 * where [ORDINARY_SHARE] is "leave it as vanilla would have it".
 *
 * A pair rather than two arguments so a phenomenon can insist on conditions without knowing how they are
 * applied, and so the two can be [atLeast] one another.
 *
 * **In the model rather than in the runtime that obeys it.** This was nested in `AgeWeather`, so
 * [Phenomenon] — a vocabulary enum every part of the Art reads — had to import the phenomena package to
 * name the type its own constants carry. What a phenomenon insists on is a fact about the phenomenon; how
 * that insistence is applied to a level is `AgeWeather`'s, and the arrow now points one way.
 */
data class WeatherConditions(
    val rainfall: Double = ORDINARY_SHARE,
    val thunder: Double = ORDINARY_SHARE,
) {
    /** The wetter and stormier of the two — how a phenomenon raises a floor without lowering one. */
    fun atLeast(other: WeatherConditions): WeatherConditions =
        WeatherConditions(maxOf(rainfall, other.rainfall), maxOf(thunder, other.thunder))

    val saysNothing: Boolean get() = rainfall == ORDINARY_SHARE && thunder == ORDINARY_SHARE

    companion object {
        val ORDINARY = WeatherConditions()
    }
}

/** The middle of a ranged axis, which is where a writer who said nothing leaves it. */
const val ORDINARY_SHARE = 0.5
