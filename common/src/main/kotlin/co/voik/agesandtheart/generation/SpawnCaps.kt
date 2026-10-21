package co.voik.agesandtheart.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobCategory
import kotlin.math.roundToInt

/**
 * The mob caps an Age's book moved (`Spawns.capsOf`), laid on vanilla's by `NaturalSpawnerMixin`, `SpawnStateMixin` and
 * `MobCountsMixin`. A level's spawning begins with its own `NaturalSpawner.createState`, on the server thread, and
 * every cap it asks for is asked before the next level's begins — so the caps are taken there and held until then.
 */
object SpawnCaps {

    private val spawning = ThreadLocal<Map<MobCategory, Double>>()

    /** Holds [level]'s caps for the spawning it is about to do. */
    @JvmStatic
    fun countingFor(level: ServerLevel) {
        spawning.set((level.chunkSource.generator as? AgeChunkGenerator)?.spawnCaps.orEmpty())
    }

    /** [vanilla] cap of [category], as the level spawning now holds it — never below one. */
    @JvmStatic
    fun capOf(category: MobCategory, vanilla: Int): Int {
        val times = spawning.get()?.get(category) ?: return vanilla
        return (vanilla * times).roundToInt().coerceAtLeast(LEAST_CAP)
    }

    /** So a pass a book thinned still turns something up now and then. */
    private const val LEAST_CAP = 1
}
