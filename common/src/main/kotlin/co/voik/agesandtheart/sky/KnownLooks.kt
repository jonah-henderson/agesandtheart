package co.voik.agesandtheart.sky

import co.voik.runtimelevels.sky.LevelLook
import co.voik.runtimelevels.sky.LevelLooks
import co.voik.runtimelevels.sky.SkySpec
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What skies this client has been told about — **now the Art's thin end of `LevelLooks`**.
 *
 * The store itself moved to `co.voik.runtimelevels`, where it belongs: which level looks how is a question
 * any runtime level has, and only *deciding* the answer is the Art's business. What is left here is the
 * translation from our payload into the library's vocabulary, which is exactly the "bring your own
 * transport" rung the library offers.
 */
object KnownLooks {

    /** The sky of [dimension], or null if this client has not been told. */
    fun of(dimension: ResourceKey<Level>): SkySpec? = LevelLooks.of(dimension)?.sky

    /** How the air of [dimension] is painted, Age-wide and per corner — null where nobody said. */
    fun airOf(dimension: ResourceKey<Level>): LevelLook? = LevelLooks.of(dimension)

    /**
     * Records what a payload said, replacing any earlier answer for the same dimension: an Age whose book
     * is edited gets a new sky, and a cache keeping the first answer would show the old one.
     */
    fun remember(payload: LookPayload) {
        for (entry in payload.skies) {
            LevelLooks.remember(entry.dimension, LevelLook(entry.spec, entry.look, entry.corners))
        }
    }

    /** Forgotten on disconnect — the keys mean nothing on the next server and an Age id can be reused. */
    fun forgetAll() = LevelLooks.forgetAll()
}
