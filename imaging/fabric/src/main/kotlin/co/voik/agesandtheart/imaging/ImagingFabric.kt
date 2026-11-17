package co.voik.agesandtheart.imaging

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries

fun init() {
    ImagingContent.blocks.forEach { (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block) }
    ImagingContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    CreativeModeTabEvents.modifyOutputEvent(ImagingContent.TAB).register { output ->
        ImagingContent.items.forEach { (_, item) -> output.accept(item) }
    }
    ServerTickEvents.END_SERVER_TICK.register(Viewfinder::serverTick)
}
