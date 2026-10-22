package co.voik.agesandtheart.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.LevelReader

/**
 * Where a creature an Age asks for in herds turns up (Jonah, walking): as the farm animals do, offered wherever they
 * are ([co.voik.agesandtheart.age.aspect.Spawns.livingIn]) and placed by a cow's rules — on grass, in the light —
 * whatever its own, so `herds of parrot` graze the plains and `herds of warden` stand in a meadow. The peaceful rule
 * is kept. `NaturalSpawnerMixin` asks [placedAs] at vanilla's checks, and `ChunkGenerationSpawns` places by it.
 */
object HerdedSpawns {

    /** Whose placement and spawn rules a herd takes. */
    @JvmField
    val PLACED_AS: EntityType<*> = EntityTypes.COW

    /** Whether [type] comes in herds in [level]. */
    @JvmStatic
    fun isHerded(level: LevelReader, type: EntityType<*>): Boolean {
        val generator = (level as? ServerLevel)?.chunkSource?.generator as? AgeChunkGenerator ?: return false
        return generator.herds(type)
    }

    /** Whose rules say where [type] may stand in [level]: its own, or a cow's where it comes in herds. */
    @JvmStatic
    fun placedAs(level: LevelReader, type: EntityType<*>): EntityType<*> = if (isHerded(level, type)) PLACED_AS else type
}
