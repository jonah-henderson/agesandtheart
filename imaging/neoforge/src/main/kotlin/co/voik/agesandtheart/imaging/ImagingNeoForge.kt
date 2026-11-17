package co.voik.agesandtheart.imaging

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod

@Mod(Imaging.MOD_ID)
class ImagingNeoForge(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        Imaging.init()
    }
}
