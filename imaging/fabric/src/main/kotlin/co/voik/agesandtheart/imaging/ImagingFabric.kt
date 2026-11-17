package co.voik.agesandtheart.imaging

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries

fun init() {
    ImagingContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    CreativeModeTabEvents.modifyOutputEvent(ImagingContent.TAB).register { output ->
        ImagingContent.items.forEach { (_, item) -> output.accept(item) }
    }
}
