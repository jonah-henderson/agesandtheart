package co.voik.agesandtheart.platform

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.services.AgeBackend
import co.voik.agesandtheart.platform.services.InkFluids
import co.voik.agesandtheart.platform.services.Network
import co.voik.agesandtheart.platform.services.Platform
import java.util.ServiceLoader

object Services {
    val PLATFORM = load(Platform::class.java)

    /** Runtime dimension backend — Fantasy on Fabric, unsupported stub on NeoForge. */
    val AGE_BACKEND = load(AgeBackend::class.java)

    /** Sending a payload to one player. See [Network] for why it is a service of its own. */
    val NETWORK = load(Network::class.java)

    /** The registered ink fluids, which only a loader can build. See [InkFluids]. */
    val INK_FLUIDS = load(InkFluids::class.java)

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