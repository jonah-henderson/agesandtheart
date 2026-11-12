package co.voik.agesandtheart.generation

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.village.VillageSiege
import net.minecraft.world.entity.npc.CatSpawner
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.levelgen.PatrolSpawner
import net.minecraft.world.level.levelgen.PhantomSpawner
import kotlin.math.roundToInt

/**
 * The spawners vanilla gives the overworld, given to an Age as well: phantoms, patrols, cats, sieges and wandering
 * traders, each the Age's own. A creature the book struck is kept out of them as out of anything else
 * ([StruckCreatures]).
 *
 * **Phantoms, traders and their llamas a book names come through these, never the natural spawner**, which has no
 * rule for any of the three (`by_their_own_spawner` in `art/spawning.json`): what the book [asked] of them tunes the
 * spawner instead ([PhantomsAskedFor], [TradersAskedFor]). Unasked, each is vanilla's own.
 */
internal object VanillaSpawners {

    /** An Age's spawners, with [asked] how thickly its book named each creature, by id. */
    fun forAnAge(asked: Map<Identifier, Double> = emptyMap()): List<CustomSpawner> = listOf(
        PhantomsUnderASky(asked[PHANTOM]),
        PatrolSpawner(),
        CatSpawner(),
        VillageSiege(),
        tradersFor(asked[WANDERING_TRADER], asked[TRADER_LLAMA]),
    )

    private fun tradersFor(traders: Double?, llamas: Double?): CustomSpawner {
        if (traders == null && llamas == null) return WanderingTradersOfItsOwn()
        val led = llamas?.let { (TradersAskedFor.LLAMAS * it).roundToInt().coerceIn(1, TradersAskedFor.MOST_LLAMAS) }
        return TradersAskedFor(amountOf(traders ?: llamas ?: 1.0), led ?: TradersAskedFor.LLAMAS)
    }

    /** How many times as often a creature asked for at [density] comes, kept from vanishing or flooding the Age. */
    private fun amountOf(density: Double): Double = density.coerceIn(SCARCEST, THICKEST)

    private const val SCARCEST = 0.25
    private const val THICKEST = 8.0

    private val PHANTOM = Identifier.withDefaultNamespace("phantom")
    private val WANDERING_TRADER = Identifier.withDefaultNamespace("wandering_trader")
    private val TRADER_LLAMA = Identifier.withDefaultNamespace("trader_llama")

    /**
     * Phantoms only where there is a sky, since without one vanilla's swarm comes at any hour and anywhere: vanilla's
     * for the sleepless, or [PhantomsAskedFor] where the book asked for them.
     */
    private class PhantomsUnderASky(asked: Double?) : CustomSpawner {
        private val phantoms: CustomSpawner = asked?.let { PhantomsAskedFor(amountOf(it)) } ?: PhantomSpawner()

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
