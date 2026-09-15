package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager

/**
 * One phenomenon's datapack file, `art/phenomenon/<key>.json`, read with [codec].
 *
 * **Cached on the resource manager's identity**, as `Vocabulary.of` is: `/reload` builds a new one, so the
 * cache invalidates itself. A missing file is [ordinary]. So is an unreadable one, which also logs a
 * line, so a pack that writes nonsense does not stop the server from starting.
 */
class PhenomenonFile<T : Any>(phenomenon: Phenomenon, private val codec: Codec<T>, private val ordinary: T) {
    private val file = "$DIRECTORY/${phenomenon.key}.json".location()

    private var loaded: Pair<ResourceManager, T>? = null

    /** What this server's file says. */
    fun of(server: MinecraftServer): T {
        val resources = server.resourceManager
        loaded?.let { (from, known) -> if (from === resources) return known }
        return read(resources).also { loaded = resources to it }
    }

    private fun read(resources: ResourceManager): T {
        val resource = resources.getResource(file).orElse(null) ?: return ordinary
        val read = runCatching {
            resource.open().use { codec.parse(JsonOps.INSTANCE, JsonParser.parseReader(it.reader())).getOrThrow() }
        }
        read.onFailure { Constants.LOG.warn("Could not read '{}': {}", file, it.message) }
        return read.getOrNull() ?: ordinary
    }

    private companion object {
        const val DIRECTORY = "art/phenomenon"
    }
}
