package co.voik.runtimelevels

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Fabric's half of [RuntimeLevelPlatform].
 *
 * Nothing to invalidate — Fabric's tick loop walks the level map itself — so this is only the courtesy of
 * telling other mods, through the same event Fabric API fires for the levels built at boot.
 */
class FabricRuntimeLevelPlatform : RuntimeLevelPlatform {

    override fun levelOpened(server: MinecraftServer, level: ServerLevel) {
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level)
    }

    override fun levelClosing(server: MinecraftServer, level: ServerLevel) {
        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level)
    }
}
