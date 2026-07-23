package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

@Mod(Constants.MOD_ID)
class AgesAndTheArt(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        Constants.LOG.info("Hello NeoForge world from Kotlin!")
        CommonObject.init()

        // Loader-specific glue: hand the common command tree NeoForge's dispatcher.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }
}
