package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.platform.Services
import net.minecraft.server.level.ServerLevel

/**
 * How an Age ends (design §5.3 "The cataclysm"): a clock, a warning, a strike, an aftermath and a dead link.
 *
 * Only a forced end exists yet (`/age cataclysm`); the dial on collapse's ladder that buys one is to come,
 * and when it does it is answered here, so nothing else learns where an end comes from.
 */
object Cataclysms {

    /** The end coming to the Age [recipe] describes, or null where none is. */
    fun of(recipe: AgeRecipe): Doom? = recipe.forcedCataclysm

    /** Whether the Age [recipe] describes has ended by [time], so that every link into it fails. */
    fun hasEnded(recipe: AgeRecipe, time: Long): Boolean = of(recipe)?.hasStruckBy(time) == true

    /** One tick of whatever end is coming to [level], which somebody is standing in. */
    fun befall(level: ServerLevel, recipe: AgeRecipe) {
        val doom = of(recipe)
        if (level.server.tickCount % TELLING_THE_CLIENTS == 0) tellTheClients(level, doom)
        if (doom == null) {
            Scorch.forget(level)
            return
        }
        when (doom.kind) {
            CataclysmKind.ASTEROID -> Asteroid.befall(level, doom)
        }
    }

    /** Tells everyone in [level] what end is coming, now rather than on the next beat. */
    fun tellTheClients(level: ServerLevel, doom: Doom?) {
        val telling = CataclysmPayload(level.dimension().identifier(), doom)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    private const val TELLING_THE_CLIENTS = 20
}
