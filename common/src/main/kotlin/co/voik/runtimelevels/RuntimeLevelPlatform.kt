package co.voik.runtimelevels

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.ServiceLoader

/**
 * The one thing about a runtime level that genuinely differs per loader: **telling the loader a level
 * arrived**.
 *
 * Everything else — building the level, putting it in the map, the border, the chunk source — is vanilla and
 * lives in `common`. This is not, and it is not a matter of taste:
 *
 * - **NeoForge caches the level array.** `MinecraftServer` keeps a `ServerLevel[]` rebuilt only when
 *   `markWorldsDirty()` is called, and the tick loop walks the array rather than the map. A level added
 *   without it is a level that never ticks. Both that method and `LevelEvent.Load` are NeoForge additions,
 *   invisible to `common`, which compiles against vanilla.
 * - **Fabric has its own event** and no such cache.
 *
 * So the shape is the same as every other platform split in this codebase: an interface here, one
 * implementation per loader, resolved by `ServiceLoader`.
 *
 * **A no-op default is deliberate.** A consumer that has not registered an implementation still gets a
 * working level on Fabric, which is what makes this library usable before its loader glue is written — and
 * the failure on NeoForge is loud and specific rather than a level that silently never ticks.
 */
interface RuntimeLevelPlatform {

    /** Called on the server thread immediately after a level has joined the map. */
    fun levelOpened(server: MinecraftServer, level: ServerLevel)

    /** And immediately before one leaves it, while it is still usable. */
    fun levelClosing(server: MinecraftServer, level: ServerLevel)

    companion object {
        private val resolved: RuntimeLevelPlatform by lazy {
            ServiceLoader.load(RuntimeLevelPlatform::class.java).firstOrNull() ?: Silent
        }

        fun of(): RuntimeLevelPlatform = resolved
    }

    /** What a consumer gets before it has written its loader glue. Correct on Fabric; inert on NeoForge. */
    private object Silent : RuntimeLevelPlatform {
        override fun levelOpened(server: MinecraftServer, level: ServerLevel) = Unit
        override fun levelClosing(server: MinecraftServer, level: ServerLevel) = Unit
    }
}
