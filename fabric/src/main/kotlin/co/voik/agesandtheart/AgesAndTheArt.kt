package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.AgeManager
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

fun init() {
    Constants.LOG.info("Hello Fabric world from Kotlin!")
    CommonObject.init()

    // Loader-specific glue: hand the common command tree Fabric's dispatcher.
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        AgeCommand.register(dispatcher)
    }

    // Re-open persisted Ages once the server has started (Fantasy doesn't auto-restore them).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        AgeManager.reloadSavedAges(server)
    }
}
