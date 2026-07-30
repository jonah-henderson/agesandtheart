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

/**
 * Telling a client what the skies of this server's Ages look like.
 *
 * Derived, never stored: every spec here comes from `AgeGeneration.skySpec(recipe)`, a pure function, so the
 * server never keeps a sky anywhere and two clients told at different moments are told the same thing.
 */
object Skies {

    /**
     * Tells [player] about one Age, and **must be called before the player is moved into it**.
     *
     * Ordering is the whole point. Both packets go down one TCP stream in the order they are written, and the
     * client drains its task queue to empty before each frame — so a payload sent before the teleport cannot
     * arrive after the dimension change, and the sky is known before the first frame is drawn. Sending afterwards
     * would work almost always, which is a different thing from working.
     *
     * (There is slack even so: `ReceivingLevelScreen` covers the whole transition opaquely until chunks arrive
     * and the player's own section is meshed. Relying on that would be relying on a screen nobody promised.)
     */
    fun tellAbout(player: ServerPlayer, level: ServerLevel) {
        val server = player.server ?: return
        val entry = entryFor(server, level.dimension()) ?: return
        Services.NETWORK.sendToPlayer(player, SkyPayload(listOf(entry)))
    }

    /**
     * Tells [player] about every Age this server knows, which is what a joining player gets.
     *
     * Sent whole rather than lazily so that re-entering an Age needs no packet at all, and so a player who
     * arrives by any route the mod does not control — a book, a portal, `/execute in` — still has the sky. It is
     * a few hundred bytes per Age against a 1 MiB ceiling.
     */
    fun tellAboutEverything(player: ServerPlayer) {
        val server = player.server ?: return
        val entries = AgeSavedData.get(server).ages.mapNotNull { id ->
            entryFor(server, ResourceKey.create(Registries.DIMENSION, id))
        }
        if (entries.isEmpty()) return
        Services.NETWORK.sendToPlayer(player, SkyPayload(entries))
    }

    /**
     * Shows [spec] to everyone standing in [level], without changing what the Age actually is.
     *
     * **The tuning instrument, and deliberately stateless.** Orbits are the kind of thing that has to be *seen*
     * to be judged, and re-authoring an Age to move a sun by ten degrees would make that loop useless. So this
     * sends a spec and stores nothing: tweak, look, tweak again. Walking out and back in re-sends the recipe's
     * own sky, which makes the preview self-cancelling rather than something you can forget you left on.
     *
     * That also avoids the mutable server-side override map the obvious design wants, which would be exactly the
     * global mutable state the Kotlin conventions name as an anti-pattern — and would have needed a story for
     * what happens on restart, for a debug feature that should not have one.
     */
    fun preview(level: ServerLevel, spec: SkySpec) {
        val payload = SkyPayload(listOf(SkyPayload.Entry(level.dimension(), spec)))
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, payload)
    }

    /**
     * The sky of the Age at [dimension], or null when that dimension is not an Age of ours.
     *
     * An ordinary sky is deliberately still *sent*. It costs nothing and it means the client's answer to "what is
     * the sky here" is never "I was not told" — which matters because the renderer treats an unknown Age and an
     * ordinary one differently, and conflating them would make a missing packet look like a design decision.
     */
    private fun entryFor(server: MinecraftServer, dimension: ResourceKey<Level>): SkyPayload.Entry? {
        val saved = AgeSavedData.get(server)
        val id = dimension.location()
        if (id !in saved.ages) return null
        return SkyPayload.Entry(dimension, AgeGeneration.skySpec(saved.recipe(id)))
    }
}
