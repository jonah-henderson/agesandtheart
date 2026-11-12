package co.voik.agesandtheart.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.village.VillageSiege
import net.minecraft.world.entity.npc.CatSpawner
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.levelgen.PatrolSpawner
import net.minecraft.world.level.levelgen.PhantomSpawner

/**
 * The spawners vanilla gives the overworld, given to an Age as well: phantoms, patrols, cats, sieges and wandering
 * traders, each the Age's own. A creature the book struck is kept out of them as out of anything else
 * ([StruckCreatures]).
 */
internal object VanillaSpawners {

    fun forAnAge(): List<CustomSpawner> = listOf(
        PhantomsUnderASky(),
        PatrolSpawner(),
        CatSpawner(),
        VillageSiege(),
        WanderingTradersOfItsOwn(),
    )

    /** Phantoms only where there is a sky, since without one vanilla's swarm comes at any hour and anywhere. */
    private class PhantomsUnderASky : CustomSpawner {
        private val phantoms = PhantomSpawner()

        override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
            if (level.dimensionType().hasSkyLight()) phantoms.tick(level, spawnEnemies)
        }
    }

    /**
     * Wandering traders on a schedule kept in the Age's own storage, so that no Age runs down the overworld's. Built on
     * the first tick, since the level is not open yet when its spawners are handed over.
     */
    private class WanderingTradersOfItsOwn : CustomSpawner {
        private var traders: WanderingTraderSpawner? = null

        override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
            val spawner = traders ?: WanderingTraderSpawner(level.dataStorage).also { traders = it }
            spawner.tick(level, spawnEnemies)
        }
    }
}
