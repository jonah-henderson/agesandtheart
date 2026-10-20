package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.aspect.Aspect

/** Where a meaning lands: one part of the world, and which of its members. */
data class Place(val aspect: Aspect, val members: Members = Members.All)

/** Which members of a part of the world a meaning is said of. */
sealed interface Members {
    /** The whole of it — `colossal landmass`. */
    data object All : Members

    /** These members and no others — `deserted villages`. */
    data class Named(val keys: Set<String>) : Members

    /** One body of a population, counted in the order the book described them — the second sun. */
    data class Body(val index: Int) : Members
}
