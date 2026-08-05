package co.voik.agesandtheart.sky

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What skies this client has been told about, by dimension. **In `common` despite being client-only
 * state**, because both loaders' receivers write here and duplicating it would mean two caches that could
 * disagree.
 *
 * [of] returns null for an Age nobody mentioned, and the renderer must treat that as "draw nothing of
 * ours" rather than "an ordinary sky" — the first frames of a connection genuinely have no answer, and
 * conflating that with a decision would make a dropped packet look intentional.
 */
object KnownLooks {

    private val skies = mutableMapOf<ResourceKey<Level>, SkySpec>()
    private val looks = mutableMapOf<ResourceKey<Level>, LookPayload.Entry>()

    /** The sky of [dimension], or null if this client has not been told. */
    fun of(dimension: ResourceKey<Level>): SkySpec? = skies[dimension]

    /** How the air of [dimension] is painted, Age-wide and per corner — empty where nobody said. */
    fun airOf(dimension: ResourceKey<Level>): LookPayload.Entry? = looks[dimension]

    /**
     * Records what a payload said, **replacing** any earlier answer for the same dimension: an Age whose
     * book is edited gets a new sky, and a cache keeping the first answer would show the old one until the
     * client restarted. Also why the renderer reads this every frame rather than registering per
     * dimension — Fabric's own registry is `putIfAbsent` with no removal.
     */
    fun remember(payload: LookPayload) {
        for (entry in payload.skies) {
            skies[entry.dimension] = entry.spec
            looks[entry.dimension] = entry
        }
    }

    /**
     * Forgotten on disconnect, because these keys mean nothing on the next server and an Age id can be reused.
     * Keeping them would let one world's sky appear in another's.
     */
    fun forgetAll() {
        skies.clear()
        looks.clear()
    }
}
