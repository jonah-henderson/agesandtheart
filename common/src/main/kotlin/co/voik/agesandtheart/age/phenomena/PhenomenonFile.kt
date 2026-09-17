package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager

/**
 * One phenomenon's datapack file, `art/phenomenon/<key>.json`, read with [codec] once per datapack load.
 *
 * A missing file is [ordinary]. So is an unreadable one, which also logs a line, so a pack that writes
 * nonsense does not stop the server from starting.
 */
class PhenomenonFile<T : Any>(phenomenon: Phenomenon, private val codec: Codec<T>, private val ordinary: T) {
    private val file = "$DIRECTORY/${phenomenon.key}.json".location()

    private val current = PerReload { server -> read(server.resourceManager) }

    /** What this server's file says. */
    fun of(server: MinecraftServer): T = current.of(server)

    private fun read(resources: ResourceManager): T {
        val resource = resources.getResource(file).orElse(null) ?: return ordinary
        val problems = mutableListOf<String>()
        val read = ResourceParsing.parse(resource, file, codec, problems)
        for (problem in problems) Constants.LOG.warn("Phenomenon file: {}", problem)
        return read ?: ordinary
    }

    private companion object {
        const val DIRECTORY = "art/phenomenon"
    }
}
