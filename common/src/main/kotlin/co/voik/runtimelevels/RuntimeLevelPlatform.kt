package co.voik.runtimelevels

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The few things about a runtime level that genuinely differ per loader: **telling the loader a level
 * arrived, and telling a client what it looks like**.
 *
 * Everything else — building the level, putting it in the map, the border, the chunk source — is vanilla and
 * lives in `common`. These are not, and it is not a matter of taste:
 *
 * - **NeoForge caches the level array.** `MinecraftServer` keeps a `ServerLevel[]` rebuilt only when
 *   `markWorldsDirty()` is called, and the tick loop walks the array rather than the map. A level added
 *   without it is a level that never ticks. Both that method and `LevelEvent.Load` are NeoForge additions,
 *   invisible to `common`, which compiles against vanilla.
 * - **Fabric has its own event** and no such cache.
 * - **Sending a payload has no shared API at all**, and the loaders disagree about a vanilla client:
 *   NeoForge's `NetworkRegistry.checkPacket` throws when a channel was never negotiated where Fabric sends
 *   and lets the client discard it. So an implementation checks first and a caller may assume sending is
 *   always safe.
 *
 * So the shape is the same as every other platform split in this codebase: an interface here, one
 * implementation per loader, resolved by `ServiceLoader`.
 *
 * **One service rather than several**, so adopting the library is one class and one `META-INF/services` line
 * per loader. Splitting networking off would buy nothing — a consumer needing one of these needs the loader
 * glue anyway.
 *
 * **A no-op default is deliberate.** A consumer that has not registered an implementation still gets a
 * working level on Fabric, which is what makes this library usable before its loader glue is written.
 */
interface RuntimeLevelPlatform {

    /** Called on the server thread immediately after a level has joined the map. */
    fun levelOpened(server: MinecraftServer, level: ServerLevel)

    /** And immediately before one leaves it, while it is still usable. */
    fun levelClosing(server: MinecraftServer, level: ServerLevel)

    /**
     * Sends [payload] to [player], or does nothing where that player cannot receive it — a vanilla client
     * simply keeps vanilla's sky rather than being disconnected over it.
     */
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)

    companion object {
        private val resolved: RuntimeLevelPlatform by lazy {
            ServiceLoader.load(RuntimeLevelPlatform::class.java).firstOrNull() ?: Silent
        }

        fun of(): RuntimeLevelPlatform = resolved
    }

    /**
     * What a consumer gets before it has written its loader glue. Levels work on Fabric; on NeoForge they
     * never tick, and nothing this side can tell the difference.
     *
     * **Sending says so, once.** A level that looks wrong is the quietest failure in the library — the
     * client draws vanilla's sky, which is a perfectly ordinary thing for a sky to do — so the one place a
     * missing implementation is cheap to name, it gets named.
     */
    private object Silent : RuntimeLevelPlatform {
        private val complained = AtomicBoolean(false)

        override fun levelOpened(server: MinecraftServer, level: ServerLevel) = Unit

        override fun levelClosing(server: MinecraftServer, level: ServerLevel) = Unit

        override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
            if (complained.compareAndSet(false, true)) {
                RuntimeLevelLog.warn(
                    "No RuntimeLevelPlatform is registered, so nothing can be told what a level looks like. " +
                        "Register one in META-INF/services/co.voik.runtimelevels.RuntimeLevelPlatform.",
                )
            }
        }
    }
}
