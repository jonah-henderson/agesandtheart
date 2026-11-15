package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * How heavily each block of a tag is drawn where the tag is drawn as a pool — one `art/pools/<tag>.json` in the tag's
 * namespace, a block it does not list weighing one. So a boulder is mostly stone and all but never amethyst.
 */
data class PoolWeights(val byBlock: Map<Identifier, Double>) {

    fun of(block: Identifier): Double = byBlock[block] ?: ORDINARY

    /** The weights of every pool, each read when first asked for. */
    private class Loaded(private val resources: ResourceManager) {
        private val byTag = ConcurrentHashMap<Identifier, PoolWeights>()

        fun of(tag: Identifier): PoolWeights = byTag.computeIfAbsent(tag) {
            val file = Identifier.fromNamespaceAndPath(tag.namespace, "$DIRECTORY/${tag.path}${ResourceParsing.JSON_SUFFIX}")
            val problems = mutableListOf<String>()
            val layers = resources.getResourceStack(file)
            val weights = layers.asReversed().firstNotNullOfOrNull { ResourceParsing.parse(it, file, CODEC, problems) }
            for (problem in problems) Constants.LOG.error("Pool weights: {}", problem)
            weights ?: NONE
        }
    }

    companion object {
        const val ORDINARY = 1.0

        val NONE = PoolWeights(emptyMap())

        private const val DIRECTORY = "art/pools"

        val CODEC: Codec<PoolWeights> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Identifier.CODEC, Codec.doubleRange(0.0, Double.MAX_VALUE)).fieldOf("weights")
                    .forGetter(PoolWeights::byBlock),
            ).apply(instance, ::PoolWeights)
        }

        private val current = PerReload { server -> Loaded(server.resourceManager) }

        /** The weights of the pool [tag] on [server]: the last pack to ship its file wins outright. */
        fun of(server: MinecraftServer, tag: Identifier): PoolWeights = current.of(server).of(tag)
    }
}
