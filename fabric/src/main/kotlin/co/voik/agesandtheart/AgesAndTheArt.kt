package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.AgeManager
import co.voik.agesandtheart.content.AgeContent
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries

fun init() {
    Constants.LOG.info("Hello Fabric world from Kotlin!")
    CommonObject.init()

    // Register content (components before items). On Fabric this is done directly during init.
    AgeContent.components.forEach { (id, comp) -> Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, comp) }
    AgeContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }

    // Loader-specific glue: hand the common command tree Fabric's dispatcher.
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        AgeCommand.register(dispatcher)
    }

    // Re-open persisted Ages once the server has started (Fantasy doesn't auto-restore them).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        AgeManager.reloadSavedAges(server)
    }
}
