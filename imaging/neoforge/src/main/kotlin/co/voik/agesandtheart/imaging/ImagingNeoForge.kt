package co.voik.agesandtheart.imaging

import net.minecraft.core.registries.Registries
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Imaging.MOD_ID)
class ImagingNeoForge(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        eventBus.addListener(::onRegister)
        eventBus.addListener(::onBuildCreativeTab)
    }

    private fun onRegister(event: RegisterEvent) {
        event.register(Registries.ITEM) { helper -> ImagingContent.items.forEach { (id, item) -> helper.register(id, item) } }
    }

    private fun onBuildCreativeTab(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey != ImagingContent.TAB) return
        ImagingContent.items.forEach { (_, item) -> event.accept(item) }
    }
}
