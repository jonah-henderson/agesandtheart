package co.voik.agesandtheart.platform

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.services.Flammability
import co.voik.agesandtheart.platform.services.InkFluids
import co.voik.agesandtheart.platform.services.MobSpawning
import co.voik.agesandtheart.platform.services.Network
import co.voik.agesandtheart.platform.services.Platform
import java.util.ServiceLoader

object Services {
    val PLATFORM = load(Platform::class.java)

    /** Sending a payload to one player. See [Network] for why it is a service of its own. */
    val NETWORK = load(Network::class.java)

    /** The registered ink fluids, which only a loader can build. See [InkFluids]. */
    val INK_FLUIDS = load(InkFluids::class.java)

    /** Whether fire takes hold on a block, which NeoForge asks per face and vanilla does not. */
    val FLAMMABILITY = load(Flammability::class.java)

    /** Finishing a mob the Age puts down, which NeoForge lets other mods have a say in. */
    val MOB_SPAWNING = load(MobSpawning::class.java)

    fun <T> load(clazz: Class<T>): T {
        val loadedService = ServiceLoader.load(clazz)
            .findFirst()
            .orElseThrow {
                IllegalStateException("Failed to load service for ${clazz.name}")
            }
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz)
        return loadedService
    }
}