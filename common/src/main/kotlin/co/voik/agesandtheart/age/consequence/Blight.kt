package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.WeakHashMap

/**
 * An Age that will not stop tearing (design §5.2.1) — **blight, as a property of the Age rather than of any
 * wound**.
 *
 * Nothing here spreads from anything. There are no child wounds and a sealed wound seeds nothing, because
 * there is nothing to seed: the *Age* is unstable, and a seal contains what one wound does to its
 * surroundings rather than whether the world keeps holing itself. Box in every wound in a blighted Age and
 * come back to more of them.
 *
 * **One function, asked from three places.** [Tearing.wantedIn] says how holed a chunk should be;
 * generation asks it as a chunk is written, [catchUp] asks it for a chunk that has just come back, and
 * [creep] asks it while somebody is standing there. The first two settle the truth in bulk and the third is
 * the animation — it opens one at a time so the Age is seen to worsen rather than found worse, and it can
 * never run past what the other two would have done, so none of them can disagree.
 *
 * **It advances whether or not anybody is there**, which §5.4 makes a per-manifestation choice rather than
 * an inherited property. A blight that waited for an audience would be one you could outlast by leaving.
 */
object Blight {

    /**
     * Chunks that have arrived and not yet been brought up to date, by level.
     *
     * **A queue rather than a search**, because there is no cheap way to ask a level which of its chunks
     * are behind: the answer is a property of each chunk's contents, and reading every loaded chunk to find
     * out would cost more than the tearing does. A chunk announces itself instead, on the event both
     * loaders already fire into [Wounds].
     *
     * Weakly keyed on the same argument as [Wounds]: a world that goes away takes its queue with it and
     * there is no cleanup for anyone to forget. Bounded by [chunkLeft], so it can never hold more than the
     * chunks a level currently has loaded.
     */
    private val waiting = WeakHashMap<Level, MutableSet<Long>>()

    /**
     * A chunk has arrived and may have missed some of the Age's days — **noted now, torn on the tick.**
     *
     * The work cannot happen here and the reason is worth keeping: writing blocks inside the chunk-load
     * event mutates a chunk in the middle of its own transition to full, which re-enters chunk loading and
     * lighting. Measured behaviour was a generation that never finished, where the same Age at zero days
     * generated instantly. So this records a position and nothing else.
     *
     * Called from both loaders' chunk-load events, the same shape as [Wounds.stocked] — there is no shared
     * entry point, and a service for one method would fragment `PlatformHelper` for a one-off (`CLAUDE.md`).
     */
    fun chunkArrived(level: Level, at: ChunkPos) {
        if (level !is ServerLevel) return
        waiting.getOrPut(level) { LinkedHashSet() }.add(ChunkPos.pack(at.x, at.z))
    }

    /** And as it goes, so the queue holds only chunks that are still there to tear. */
    fun chunkLeft(level: Level, at: ChunkPos) {
        val here = waiting[level] ?: return
        here.remove(ChunkPos.pack(at.x, at.z))
        if (here.isEmpty()) waiting.remove(level)
    }

    /**
     * One tick of blight in [level] — what has to catch up, then what is worsening in front of somebody.
     *
     * Takes what the Age bought rather than working it out, because the caller is holding it: `Happenings`
     * has already found the recipe and priced its instability for the phenomena, and doing it twice a tick
     * per Age is the same answer arrived at twice.
     */
    fun advance(level: ServerLevel, recipe: AgeRecipe, spending: Spending) {
        val worsening = worseningIn(recipe, spending)
        if (worsening == null) {
            // Not a blighted Age, so nothing owes it anything and the queue is only holding memory.
            waiting.remove(level)
            return
        }
        catchUp(level, worsening)
        creep(level, worsening)
    }

    /**
     * Ground that was generated, left, and returned to — **brought up to date in one pass, before anybody
     * has had a chance to look at it twice.**
     *
     * This is the third of [Tearing]'s touchpoints and the one the other two cannot cover. A chunk written
     * after the blight started comes out at the right density, and a chunk somebody is standing in creeps
     * toward it; a chunk written on day one and next seen on day thirty is neither, and without this it
     * stays as it was written however far the Age has come apart.
     *
     * **Spent by wounds rather than by chunks**, so the cost of a tick is bounded by the work actually
     * done: a hundred chunks that are already up to date drain in one tick for a map lookup apiece, and a
     * chunk that is fifty behind takes the budget and the rest wait. Every chunk here is one somebody is
     * about to see, so the budget is what keeps a player arriving in a long-abandoned Age from paying for
     * the whole of it in one frame.
     *
     * **One residue, and it is the honest place for it.** A chunk kept loaded while the Age was empty —
     * forceloaded, or held by a spawn chunk — fires no load event when somebody comes back, so it catches
     * up only by [creep], and not at all if it is beyond [Sampling]'s reach. Rare, and the alternative is
     * enumerating loaded chunks every tick to find the few that ever want it.
     */
    private fun catchUp(level: ServerLevel, worsening: Worsening) {
        val here = waiting[level] ?: return
        val taken = takeFrom(here)
        var budget = WOUNDS_PER_TICK
        for ((index, at) in taken.withIndex()) {
            if (budget <= 0) {
                // The rest go back rather than being dropped: they are chunks somebody can see.
                for (waited in index..<taken.size) here.add(taken[waited])
                break
            }
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(at), ChunkPos.getZ(at)) ?: continue
            budget -= Tearing.tearInto(
                level,
                chunk,
                level.seed,
                worsening.wantedIn(chunk, level),
                already = Wounds.countIn(level, chunk.pos),
            )
        }
        if (here.isEmpty()) waiting.remove(level)
    }

    /**
     * A batch of arrivals, out of the queue and into a list nothing else can touch.
     *
     * **The tearing must not run under an iterator over [waiting], and that is a crash rather than a
     * caution** (2026-08-31): placing a wound drives a chunk load or unload of its own, whose event calls
     * straight back into [chunkArrived] or [chunkLeft], and the set is structurally modified underneath the
     * pass walking it. Taking the batch first is what breaks the re-entrancy — nothing but the iterator
     * itself runs in this loop.
     *
     * Bounded as well as safe: a player arriving in an Age loads several hundred chunks at once, and a
     * snapshot of all of them would be one allocation the size of the backlog.
     */
    private fun takeFrom(here: MutableSet<Long>): List<Long> {
        val taken = ArrayList<Long>(CHUNKS_PER_TICK)
        val arrivals = here.iterator()
        while (arrivals.hasNext() && taken.size < CHUNKS_PER_TICK) {
            taken.add(arrivals.next())
            arrivals.remove()
        }
        return taken
    }

    /** And the part somebody is present for: one more hole, where they can watch it open. */
    private fun creep(level: ServerLevel, worsening: Worsening) {
        Sampling.sweep(level, ONE_PLACE) { chunk, _ ->
            Tearing.tearInto(
                level,
                chunk,
                level.seed,
                worsening.wantedIn(chunk, level),
                atMost = ONE_AT_A_TIME,
                already = Wounds.countIn(level, chunk.pos),
            )
        }
    }

    /** What an Age is worth tearing at, or null where it was written to hold together. */
    private fun worseningIn(recipe: AgeRecipe, spending: Spending): Worsening? {
        val perDay = Tearing.blightPerDayAt(spending.bought(Manifestation.BLIGHT))
        // A coherent Age, or one holed exactly as far as its book holed it — either way nothing to do.
        if (perDay <= Tearing.NONE) return null
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        return Worsening(written, perDay, recipe)
    }

    /** How holed this Age should be by now, and what it takes to work that out for one chunk. */
    private data class Worsening(val written: Double, val perDay: Double, val recipe: AgeRecipe) {
        fun wantedIn(chunk: ChunkAccess, level: ServerLevel): Int {
            val days = recipe.ageAt(level.server) / Tearing.TICKS_PER_DAY
            return Tearing.wantedIn(chunk.pos, level.seed, Tearing.densityAt(written, perDay, days))
        }
    }

    /**
     * How many wounds one visible step opens.
     *
     * One, and the word is the design: a chunk is brought up to date in bulk before anybody has looked at
     * it and a block at a time while they are there, so what a player sees is a world opening in front of
     * them rather than a chunk that changed while they blinked.
     */
    private const val ONE_AT_A_TIME = 1

    /** How many positions each loaded chunk offers per tick — vanilla's precipitation rate, once. */
    private const val ONE_PLACE = 1

    /**
     * How much tearing a catch-up may do in one tick, in wounds.
     *
     * One saturated chunk's worth ([Tearing]'s own ceiling), so the worst a single tick can cost is the
     * worst a single chunk can hold. A player linking into an Age abandoned for a month loads several
     * hundred chunks at once and the whole backlog is paid over a few seconds rather than in the frame
     * they arrive in.
     */
    private const val WOUNDS_PER_TICK = 64

    /**
     * And how many chunks one may look at, which bounds the batch rather than the tearing.
     *
     * Most arrivals owe nothing — a chunk that generated a moment ago is already right — so this is the
     * rate the *quiet* case drains at, and a few hundred chunks of nothing clear in a handful of ticks.
     */
    private const val CHUNKS_PER_TICK = 64
}
