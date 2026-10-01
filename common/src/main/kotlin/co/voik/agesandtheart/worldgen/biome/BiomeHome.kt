package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.level.biome.Climate

/**
 * **Where in the climate one of our biomes lives** — `data/<namespace>/biome_home/<path>.json`, for the
 * biome `<namespace>:<path>`, in vanilla's own parameter format.
 *
 * A biome the table has never seen is otherwise put down by [BiomePreference]'s `homesFor`: clustered on a
 * surface entry drawn at random. That is right for a summoned nether or End biome, which has no meaningful
 * place in an overworld climate, so anywhere the noise reaches will do. It is wrong for a biome that does
 * have a place — a palm beach belongs on the coast, and drawn at random it lands in a forest.
 *
 * **Weight widens only the climate axes** ([ClimateAxis]: temperature and humidity), not the shape axes.
 * Those are *how much of the world* a biome answers for, which is what a stronger or weaker naming means.
 * Continentalness and erosion are *where on the ground* it stands, and growing them would carry a beach
 * up the hillside and out over deep water — a bigger beach in the wrong sense (Jonah, 2026-09-30). So a
 * home is pinned in place and only its climate stretches.
 */
data class BiomeHome(val parameters: List<Climate.ParameterPoint>) {

    /** The boxes this biome is given at [weight], widened or narrowed on the climate axes alone. */
    fun at(weight: Double): List<Climate.ParameterPoint> = parameters.map { box ->
        Climate.ParameterPoint(
            box.temperature().scaledBy(weight),
            box.humidity().scaledBy(weight),
            box.continentalness(),
            box.erosion(),
            box.depth(),
            box.weirdness(),
            box.offset(),
        )
    }

    companion object {
        val CODEC: Codec<BiomeHome> = RecordCodecBuilder.create { instance ->
            instance.group(
                Climate.ParameterPoint.CODEC.listOf().fieldOf("parameters").forGetter(BiomeHome::parameters),
            ).apply(instance, ::BiomeHome)
        }

        private const val DIRECTORY = "biome_home"

        private val current = PerReload { server -> read(server.resourceManager) }

        /** Every declared home on this server, by the biome it is for. */
        fun of(server: MinecraftServer): Map<Identifier, BiomeHome> = current.of(server)

        private fun read(resources: ResourceManager): Map<Identifier, BiomeHome> {
            val problems = mutableListOf<String>()
            val homes = resources.listResources(DIRECTORY, ResourceParsing::isJson).mapNotNull { (file, resource) ->
                val biome = file.withPath(ResourceParsing.nameUnder(file, DIRECTORY))
                ResourceParsing.parse(resource, file, CODEC, problems)?.let { biome to it }
            }.toMap()
            for (problem in problems) Constants.LOG.warn("Biome home: {}", problem)
            return homes
        }
    }
}
