package co.voik.agesandtheart.sky

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What skies this client has been told about, by dimension.
 *
 * **In `common` despite being client-only state**, because both loaders' receivers put things here and the type
 * is pure data — putting it in one loader would mean the other could not reach it, and duplicating it would mean
 * two caches that could disagree.
 *
 * Deliberately *not* a substitute for asking. [of] returns null for an Age nobody mentioned, and the renderer
 * must treat that as "draw nothing of ours" rather than as "an ordinary sky": the first frames of a first
 * connection genuinely have no answer, and conflating that with a decision would make a dropped packet look
 * intentional.
 */
object KnownSkies {

    private val skies = mutableMapOf<ResourceKey<Level>, SkySpec>()

    /** The sky of [dimension], or null if this client has not been told. */
    fun of(dimension: ResourceKey<Level>): SkySpec? = skies[dimension]

    /**
     * Records what a payload said, replacing any earlier answer for the same dimension.
     *
     * Replacing rather than ignoring is the point: an Age whose book is edited gets a new sky, and a cache that
     * kept the first answer would show the old one until the client restarted. It is also why the renderer reads
     * this every frame instead of being registered per dimension — Fabric's own per-dimension renderer registry
     * is `putIfAbsent` with no removal, so it could never be corrected.
     */
    fun remember(payload: SkyPayload) {
        for (entry in payload.skies) skies[entry.dimension] = entry.spec
    }

    /**
     * Forgotten on disconnect, because these keys mean nothing on the next server and an Age id can be reused.
     * Keeping them would let one world's sky appear in another's.
     */
    fun forgetAll() = skies.clear()
}
