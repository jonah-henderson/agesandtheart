package co.voik.agesandtheart.imaging

import net.minecraft.core.registries.Registries
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Imaging.MOD_ID)
class ImagingNeoForge(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        eventBus.addListener(::onRegister)
        eventBus.addListener(::onBuildCreativeTab)
        NeoForge.EVENT_BUS.addListener { event: ServerTickEvent.Post -> Viewfinder.serverTick(event.server) }
    }

    private fun onRegister(event: RegisterEvent) {
        event.register(Registries.BLOCK) { helper -> ImagingContent.blocks.forEach { (id, block) -> helper.register(id, block) } }
        event.register(Registries.ITEM) { helper -> ImagingContent.items.forEach { (id, item) -> helper.register(id, item) } }
    }

    private fun onBuildCreativeTab(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey != ImagingContent.TAB) return
        ImagingContent.items.forEach { (_, item) -> event.accept(item) }
    }
}
