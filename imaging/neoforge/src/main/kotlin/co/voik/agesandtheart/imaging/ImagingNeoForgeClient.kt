package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.ViewfinderView
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge

/** The companion's client entrypoint on NeoForge: a second `@Mod` of the same id, loaded only on a client. */
@Mod(value = Imaging.MOD_ID, dist = [Dist.CLIENT])
class ImagingNeoForgeClient {
    init {
        ViewfinderView.install()
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> ViewfinderView.tick(Minecraft.getInstance()) }
    }
}
