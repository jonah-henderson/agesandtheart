package co.voik.agesandtheart.age.aspect

/**
 * The eight points a writer names, as bearings in degrees clockwise from north, so east is 90. The four cardinals
 * and the four between them are what a person points at; a sun at 22.5° is a question the Art keeps from a writer.
 */
object Compass {

    val POINTS: Map<String, Float> = linkedMapOf(
        "north" to 0.0f,
        "northeast" to 45.0f,
        "east" to 90.0f,
        "southeast" to 135.0f,
        "south" to 180.0f,
        "southwest" to 225.0f,
        "west" to 270.0f,
        "northwest" to 315.0f,
    )

    /** The bearing of the point [named], or null where it names none. */
    fun bearingOf(named: String): Float? = POINTS[named]
}
