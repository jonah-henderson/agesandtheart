package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.registries.Registries
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Constants.MOD_ID)
class AgesAndTheArt(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        CommonSetup.init()

        // Content registration is a mod-bus event on NeoForge.
        eventBus.addListener(::onRegister)
        // Commands are a game-bus event.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegister(event: RegisterEvent) {
        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            AgeContent.components.forEach { (id, comp) -> helper.register(id, comp) }
        }
        event.register(Registries.ITEM) { helper ->
            AgeContent.items.forEach { (id, item) -> helper.register(id, item) }
        }
        event.register(Registries.CHUNK_GENERATOR) { helper ->
            AgeContent.chunkGeneratorCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.BIOME_SOURCE) { helper ->
            AgeContent.biomeSourceCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.CARVER) { helper ->
            AgeContent.carvers.forEach { (id, carver) -> helper.register(id, carver) }
        }
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }
}
