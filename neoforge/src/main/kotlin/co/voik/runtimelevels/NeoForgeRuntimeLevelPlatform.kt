package co.voik.runtimelevels

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent

/**
 * NeoForge's half of [RuntimeLevelPlatform].
 *
 * **`markWorldsDirty` is not optional and its absence is invisible.** NeoForge patches `MinecraftServer` to
 * walk a cached `ServerLevel[]` in the tick loop rather than the map itself, rebuilt only when this is
 * called. A level added without it sits in the map, answers every query, accepts teleports — and never
 * ticks. Nothing logs; it simply does not run.
 *
 * `LevelEvent.Load` is posted for the same reason vanilla's own boot posts it: every NeoForge mod expecting
 * to hear about a level expects to hear about this one.
 */
class NeoForgeRuntimeLevelPlatform : RuntimeLevelPlatform {

    override fun levelOpened(server: MinecraftServer, level: ServerLevel) {
        server.markWorldsDirty()
        NeoForge.EVENT_BUS.post(LevelEvent.Load(level))
    }

    override fun levelClosing(server: MinecraftServer, level: ServerLevel) {
        NeoForge.EVENT_BUS.post(LevelEvent.Unload(level))
        server.markWorldsDirty()
    }
}
