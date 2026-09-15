package co.voik.agesandtheart.age.word

/** A pool whose every offer is one setting of [facets]. */
fun poolOfSingleSettings(facets: Map<String, String>, draws: Draws): Facets =
    Facets(facets.entries.map { mapOf(it.key to it.value) }, draws)
