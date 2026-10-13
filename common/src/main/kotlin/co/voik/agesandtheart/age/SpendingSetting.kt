package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.datapack.PerReload
import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.StructureTags
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.util.concurrent.ConcurrentHashMap

/** Something an Age has for a manifestation to show on. */
enum class Showing(private val key: String) : StringRepresentable {
    /** More than one territory, so seams between them. */
    REGIONS("regions"),

    /** Trees that grow as the Age generates. */
    TREES("trees"),

    /** Villages, in biomes the Age grows. */
    VILLAGES("villages"),

    /** Anything living at all. */
    CREATURES("creatures"),

    /** Rain: weather that is ever wet, over biomes warm enough for it to fall as rain. */
    RAIN("rain"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Showing> = StringRepresentable.fromEnum(Showing::values)
    }
}

/**
 * What a finished Age is like, as far as choosing what its instability shows: its climate on each axis, from
 * −1 to 1, and what it [has] for a manifestation to show on.
 */
data class SpendingSetting(val climate: Map<String, Double>, val has: Set<Showing>) {
    companion object {
        /** An Age nothing is known about: vanilla's whole climate, and nothing promised to show on. */
        val UNKNOWN = SpendingSetting(emptyMap(), emptySet())

        /** The axes a ladder may lean on, as the climate parameters are named. */
        val AXES = listOf("temperature", "humidity")

        /**
         * [composition]'s setting as far as it can be read without a server — its climate, its territories and
         * whether anything lives — or [UNKNOWN] for an Age that is not a composition.
         */
        fun of(composition: AgeComposition?): SpendingSetting {
            if (composition == null) return UNKNOWN
            val climate = AXES.associateWith { axis -> middleOf(composition, axis) }
            val has = buildSet {
                if (composition.membersIn(Aspect.TERRAIN) > 1) add(Showing.REGIONS)
                if (!Spawns.nothingLives(composition.optionsFor(Aspect.SPAWNS, 0))) add(Showing.CREATURES)
            }
            return SpendingSetting(climate, has)
        }

        /**
         * The whole setting, on the server that will build it: what [of] reads, and whether the biomes this
         * [composition] grows over [template] carry trees and villages. What every draw an Age is built or
         * surveyed by goes through, so a desk and the Age it writes buy the same things.
         */
        fun of(server: MinecraftServer, composition: AgeComposition?, template: AgeTemplate, seed: Long): SpendingSetting {
            val read = of(composition)
            if (composition == null) return read
            val grown = alreadyRead.of(server).computeIfAbsent(Read(composition, template, seed)) {
                grownAndBuilt(server, composition, template, seed)
            }
            return read.copy(has = read.has + grown)
        }

        private data class Read(val composition: AgeComposition, val template: AgeTemplate, val seed: Long)

        /** Held per datapack load: every tick asks it of every Age, and looking through the biomes is not free. */
        private val alreadyRead = PerReload { ConcurrentHashMap<Read, Set<Showing>>() }

        private fun grownAndBuilt(
            server: MinecraftServer,
            composition: AgeComposition,
            template: AgeTemplate,
            seed: Long,
        ): Set<Showing> {
            val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
            val biomes = template.biomesOf(
                server,
                Biomes.preferencesIn(biomeOptions),
                Biomes.keepsOnlyNamed(biomeOptions),
                seed,
            ).possibleBiomes()
            return buildSet {
                if (growsTrees(server, composition, biomes, seed)) add(Showing.TREES)
                if (buildsVillages(server, composition, template, biomes)) add(Showing.VILLAGES)
                if (rains(composition, biomes)) add(Showing.RAIN)
            }
        }

        private fun growsTrees(
            server: MinecraftServer,
            composition: AgeComposition,
            biomes: Set<Holder<Biome>>,
            seed: Long,
        ): Boolean {
            val placed = Features.placedIn(server, Features.writtenAndCarried(composition), seed, rock = emptyList())
            fun isTree(biome: Holder<Biome>) = placed(biome).features().any { step ->
                step.any { feature -> feature.value().features.anyMatch { it.value() is TreeFeature } }
            }
            return biomes.any(::isTree)
        }

        private fun buildsVillages(
            server: MinecraftServer,
            composition: AgeComposition,
            template: AgeTemplate,
            biomes: Set<Holder<Biome>>,
        ): Boolean {
            val sets = Structures.structureSets(
                server,
                composition.optionsFor(Aspect.STRUCTURES, 0),
                template.standingStructures,
                withoutWhatIsBuiltUnderground = composition.hasNothingBeneathIt,
            )
            fun isAVillageThatCanStand(entry: StructureSet.StructureSelectionEntry): Boolean {
                val structure = entry.structure()
                return structure.`is`(StructureTags.VILLAGE) && biomes.any(structure.value().biomes()::contains)
            }
            return sets.any { set -> set.value().structures().any(::isAVillageThatCanStand) }
        }

        private fun rains(composition: AgeComposition, biomes: Set<Holder<Biome>>): Boolean {
            val rainfall = Atmosphere.weatherAsked(composition.optionsFor(Aspect.WEATHER, 0), Atmosphere.RAINFALL)
            fun rainsOver(biome: Holder<Biome>) =
                biome.value().hasPrecipitation() && biome.value().warmEnoughToRain(AT_SEA_LEVEL, SEA_LEVEL)
            return rainfall > NEVER && biomes.any(::rainsOver)
        }

        private const val SEA_LEVEL = 63
        private val AT_SEA_LEVEL = BlockPos(0, SEA_LEVEL, 0)
        private const val NEVER = 0.0

        /** The middle of [composition]'s range on [axis], averaged over its climates — nought where nothing bent it. */
        private fun middleOf(composition: AgeComposition, axis: String): Double {
            val territories = composition.membersIn(Aspect.CLIMATE).coerceAtLeast(1)
            return (0..<territories).map { member ->
                val written = composition.optionsFor(Aspect.CLIMATE, member).chosen[axis].orEmpty()
                    .map(Claim::read).firstOrNull { it.confinedTo == null }?.value
                val span = written?.let(Span::read) ?: Span.NATURAL
                (span.least + span.most) / 2
            }.average()
        }
    }
}
