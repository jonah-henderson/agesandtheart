package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon

/**
 * One thing that may begin in one Age: a kind of event, or a spell of weather together with every
 * phenomenon that rides it — a blizzard and a deluge both begin when the rain does, and each costs a place.
 */
data class Episode<A : Any>(val age: A, val kinds: Set<Phenomenon>) {
    val cost: Int get() = kinds.size
}

/**
 * Which episodes are running and which are waiting to — [PhenomenaCeiling]'s arithmetic, free of Minecraft.
 *
 * An episode that may not begin leaves one ticket, and the line is served oldest first without skipping its
 * head. A running episode may go on beginning only while nothing in its scope waits. See
 * `notes/decisions.md`, "The phenomena ceiling".
 */
class CeilingLedger<A : Any> {

    private val running = LinkedHashSet<Episode<A>>()

    /** Oldest first, never holding an episode twice. */
    private val waiting = ArrayDeque<Episode<A>>()

    /** Episodes the line has reached, each with the tick by which it must begin or lose its turn. */
    private val granted = LinkedHashMap<Episode<A>, Long>()

    fun isRunning(episode: Episode<A>): Boolean = episode in running

    fun isWaiting(episode: Episode<A>): Boolean = episode in waiting

    fun isGranted(episode: Episode<A>): Boolean = episode in granted

    fun runningNow(): List<Episode<A>> = running.toList()

    fun waitingNow(): List<Episode<A>> = waiting.toList()

    fun grantedNow(): List<Episode<A>> = granted.keys.toList()

    /**
     * Whether [episode] may begin now, leaving a ticket where it may not. Marks nothing running: the caller
     * calls [begin] once it actually has. A [ceiling] of zero is none; [scopeOf] says which episodes share one.
     */
    fun admits(episode: Episode<A>, ceiling: Int, scopeOf: (A) -> Any): Boolean {
        if (ceiling <= NO_CEILING || episode in granted) return true
        if (episode in waiting) return false
        val scope = scopeOf(episode.age)
        val anotherIsWaiting = waiting.any { scopeOf(it.age) == scope }
        val hasAPlace = episode in running || fits(episode, ceiling, scope, scopeOf)
        if (!anotherIsWaiting && hasAPlace) return true
        waiting.addLast(episode)
        return false
    }

    /** [episode] has begun and holds its place until [end]; any grant it had is spent. */
    fun begin(episode: Episode<A>) {
        granted.remove(episode)
        running += episode
    }

    fun end(episode: Episode<A>) {
        running -= episode
    }

    fun forget(age: A) {
        running.removeAll { it.age == age }
        waiting.removeAll { it.age == age }
        granted.keys.removeAll { it.age == age }
    }

    /**
     * Drops grants unused by [now], then grants each scope's line from its head for as long as the head
     * fits. Returns what was granted, for the caller to start.
     */
    fun serve(now: Long, ceiling: Int, scopeOf: (A) -> Any, grace: Long): List<Episode<A>> {
        granted.entries.removeAll { it.value < now }
        val served = mutableListOf<Episode<A>>()
        val blocked = mutableSetOf<Any>()
        val line = waiting.iterator()
        while (line.hasNext()) {
            val episode = line.next()
            val scope = scopeOf(episode.age)
            if (scope in blocked) continue
            if (ceiling > NO_CEILING && !fits(episode, ceiling, scope, scopeOf)) {
                blocked += scope
                continue
            }
            line.remove()
            granted[episode] = now + grace
            served += episode
        }
        return served
    }

    /** Anything fits an empty scope, or an episode costing more than the whole ceiling would never run. */
    private fun fits(episode: Episode<A>, ceiling: Int, scope: Any, scopeOf: (A) -> Any): Boolean {
        fun holdsAPlaceBeside(other: Episode<A>) = other != episode && scopeOf(other.age) == scope
        val taken = (running + granted.keys).filter(::holdsAPlaceBeside).sumOf { it.cost }
        return taken == 0 || taken + episode.cost <= ceiling
    }

    private companion object {
        const val NO_CEILING = 0
    }
}
