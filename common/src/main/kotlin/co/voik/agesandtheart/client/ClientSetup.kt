package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.agesandtheart.client.light.DeepLights
import co.voik.agesandtheart.client.light.TintedLights
import co.voik.agesandtheart.client.panel.LecternPanels
import co.voik.agesandtheart.client.panel.LinkingPanel
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * What the client does on each event, invoked by each loader's client entrypoint — the client's twin of
 * `CommonSetup`, and only ever reached from a client entrypoint.
 */
object ClientSetup {

    /** The end of every client tick. */
    fun clientTick(minecraft: Minecraft) {
        // Which of the two winds is playing has to be re-asked as a player walks in and out of shelter, so
        // it rides the client tick rather than the payload.
        Storms.heard(minecraft)
        Storms.blow(minecraft)
        // A deluge's splashes and roar, both of which want re-asking as a player moves under cover.
        Downpours.pour(minecraft)
        // A lure is drawn about its cluster rather than by each block, so it rides the tick as well.
        LureLooks.pulse(minecraft)
        // Which lectern's panel this client shows, since a lectern has no screen to tick it as a book's does.
        LecternPanels.tick(minecraft)
        // Whether a tear is being fallen through, settled once here rather than per fissure per frame.
        StarFissureVeil.tick(minecraft)
        // Waves on a palm beach: the shore is found and the foam laid on the client, and nowhere else.
        Surf.tick(minecraft)
    }

    /**
     * A chunk arriving on the client. Each index here is filled by reading the chunk as it arrives, and
     * each dismisses a section off its palette before touching a block.
     */
    fun chunkLoaded(level: ClientLevel, chunk: ChunkAccess) {
        // Where the wounds are, on this side too — the corruption gradient asks many times a frame and
        // `WoundField` draws straight out of it.
        Wounds.stocked(level, chunk)
        // The coloured-light index.
        TintedLights.stocked(level, chunk)
        // And what can be seen from across an abyss — the same index shape, for the same reason.
        DeepLights.stocked(level, chunk)
    }

    /** A chunk leaving the client. */
    fun chunkUnloaded(level: ClientLevel, at: ChunkPos) {
        Wounds.emptied(level, at)
        TintedLights.emptied(level, at)
        DeepLights.emptied(level, at)
    }

    /** Leaving a server. What these hold is keyed on that server's ids, which mean nothing on the next, and an Age id can be reused. */
    fun disconnected() {
        KnownWords.forgetAll()
        Surf.forget()
        DeskModel.forget()
        Wounds.forget()
        TintedLights.forget()
        DeepLights.forget()
        // A book open when the connection drops never reaches `Screen.removed`, so its preview level and
        // renderer would outlive the connection that fed them.
        LinkingPanel.forget()
        Storms.forget()
        Downpours.forget()
        StarFissureVeil.forget()
    }
}
