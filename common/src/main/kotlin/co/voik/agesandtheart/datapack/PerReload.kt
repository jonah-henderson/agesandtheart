package co.voik.agesandtheart.datapack

import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager

/**
 * What a server's datapacks say, **read once per datapack load and kept**.
 *
 * Keyed on the resource manager's identity, which is exactly the thing a reload replaces:
 * `reloadResources` builds a fresh `MultiPackResourceManager`, so a reloaded pack misses and rebuilds, and
 * nothing has to remember to invalidate anything. It also outlives nothing it should not — an integrated
 * server's next world has a new manager.
 *
 * No lock. Two threads arriving together read it twice and one wins, which cannot produce a wrong answer
 * while [read] is pure in what it is handed.
 */
class PerReload<T : Any>(private val read: (MinecraftServer) -> T) {
    @Volatile
    private var held: Pair<ResourceManager, T>? = null

    fun of(server: MinecraftServer): T {
        val resources = server.resourceManager
        held?.let { (from, known) -> if (from === resources) return known }
        return read(server).also { held = resources to it }
    }
}
