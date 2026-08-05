package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Options

/**
 * Telling a client what the skies of this server's Ages look like.
 *
 * Derived, never stored: every spec here comes from `AgeGeneration.skySpec(recipe)`, a pure function, so the
 * server never keeps a sky anywhere and two clients told at different moments are told the same thing.
 */
object Skies {

    /**
     * Tells [player] about one Age, and **must be called before the player is moved into it**. Both packets
     * go down one TCP stream in write order and the client drains its queue before each frame, so a
     * payload sent first cannot arrive after the dimension change. Sending afterwards would work almost
     * always, which is a different thing from working.
     */
    fun tellAbout(player: ServerPlayer, level: ServerLevel) {
        val server = level.server
        val entry = entryFor(server, level.dimension()) ?: return
        Services.NETWORK.sendToPlayer(player, LookPayload(listOf(entry)))
    }

    /**
     * Tells [player] about every Age this server knows, which is what a joining player gets. Whole rather
     * than lazily, so a player arriving by a route the mod does not control — a book, a portal,
     * `/execute in` — still has the sky. A few hundred bytes per Age against a 1 MiB ceiling.
     */
    fun tellAboutEverything(player: ServerPlayer) {
        val server = player.level().server ?: return
        val entries = AgeSavedData.get(server).ages.mapNotNull { id ->
            entryFor(server, ResourceKey.create(Registries.DIMENSION, id))
        }
        if (entries.isEmpty()) return
        Services.NETWORK.sendToPlayer(player, LookPayload(entries))
    }

    /**
     * Shows [spec] to everyone standing in [level], without changing what the Age is — **the tuning
     * instrument, and deliberately stateless**. Walking out and back in re-sends the recipe's own sky, so
     * the preview is self-cancelling rather than something you can forget you left on, and there is no
     * server-side override map to keep or to restore on restart.
     */
    fun preview(level: ServerLevel, spec: SkySpec) {
        val payload = LookPayload(listOf(LookPayload.Entry(level.dimension(), spec)))
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, payload)
    }

    /**
     * The sky of the Age at [dimension], or null when that dimension is not an Age of ours. An ordinary sky
     * is still *sent*, so the client's answer is never "I was not told" — the renderer treats an unknown
     * Age and an ordinary one differently, and conflating them would make a lost packet look deliberate.
     */
    private fun entryFor(server: MinecraftServer, dimension: ResourceKey<Level>): LookPayload.Entry? {
        val saved = AgeSavedData.get(server)
        val id = dimension.identifier()
        if (id !in saved.ages) return null
        val recipe = saved.recipe(id)
        val air = recipe.composition?.optionsFor(Aspect.ATMOSPHERE, 0) ?: Options.NONE
        return LookPayload.Entry(
            dimension,
            AgeGeneration.skySpec(recipe),
            Atmosphere.lookIn(air, recipe.seed),
            Atmosphere.cornersOf(air).associateWith { Atmosphere.lookIn(air, recipe.seed, it) },
        )
    }
}
