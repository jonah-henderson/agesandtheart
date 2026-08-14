package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import com.mojang.serialization.Codec
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.biome.TheEndBiomeSource
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * **The world a book starts from** (`the-world-model.md` §4).
 *
 * Every property has an answer before a sentence is read, and this is where it comes from. That is what
 * lets `An Age` be a complete book: silence is not neutral, it is overworld-shaped, and a world nobody
 * described is a world like the one they came from.
 *
 * **A template is a whole world we wrote; the catalogue is what a writer can reach**, and only the second
 * has to be complete. The nether's terrain is not one of the shapes a page can name and need not become
 * one — which is what lets a template be arbitrarily bespoke without every part of it becoming vocabulary.
 *
 * **It does not persist**, and that is §4.6 rather than an omission: what a template supplies is merged
 * into the composition before the recipe is written, so an Age is rebuilt from the answer rather than from
 * the question. Retuning a template therefore changes what *new* Ages are like and can never reach one
 * already written.
 */
enum class AgeTemplate(val key: String, val rock: ResourceKey<NoiseGeneratorSettings>) : StringRepresentable {
    /** What a world is like when nobody said otherwise. No word names it; it is what you get. */
    OVERWORLD("overworld", NoiseGeneratorSettings.OVERWORLD) {
        override val biomeList = MultiNoiseBiomeSourceParameterLists.OVERWORLD
        override val standingStructures = Structures.OVERWORLD_STRUCTURE_SETS

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.VANILLA),
            seas = listOf(Sea.WATER),
            carvers = listOf(Carvers.CAVES),
        )
    },

    /**
     * A world that burns, sealed over and lit by nothing — vanilla's own nether rock under it.
     */
    INFERNAL("infernal", NoiseGeneratorSettings.NETHER) {
        override val biomeList = MultiNoiseBiomeSourceParameterLists.NETHER
        override val standingStructures = listOf(
            BuiltinStructureSets.NETHER_COMPLEXES,
            BuiltinStructureSets.NETHER_FOSSILS,
            BuiltinStructureSets.RUINED_PORTALS,
        )

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.VANILLA),
            seas = listOf(Sea.LAVA),
            carvers = listOf(Carvers.SOLID),
        )
            .withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Sky.ALWAYS))
            .withOptions(Aspect.SUN, Sky.SHINING.name, listOf(Sky.NEVER))
        // **And it paints no fog, which it also used to.** `air.fog=red` was one colour for a whole world,
        // written while an infernal Age grew overworld biomes and had nothing else to make it look like
        // the nether. Its own biomes carry their fog now — crimson red, warped teal, soul-sand brown — and
        // a colour named here would flatten all of them to one. (It was inert besides: nothing reads
        // `Look.fog` yet, the visual half of `Atmosphere` being unbuilt.)
        // **It bends no climate, and used to.** `temperature=0.7..1.0` was here to drag the *overworld's*
        // table toward its hot end, which is what an infernal Age had to do while it grew overworld
        // biomes. Against the nether's own table it only narrows: with the bend this world was 100%
        // crimson forest, and without it soul sand valleys, wastes, basalt deltas and warped forest.
    },

    /**
     * Islands in nothing, under a sky with no sun in it — vanilla's own end rock under it.
     *
     * Not sealed: the end is open overhead and simply has nothing up there, which is a different fact from
     * the nether's and one the derived rules keep apart (`Sky.dimensionType`).
     */
    DARK_VOID("dark_void", NoiseGeneratorSettings.END) {
        // **The one world whose biomes are not chosen by climate.** The End picks by distance from the
        // centre, so there is no table to weigh and no climate to bend — see [biomesOf].
        override val biomeList: ResourceKey<MultiNoiseBiomeSourceParameterList>? = null
        override val standingStructures = listOf(BuiltinStructureSets.END_CITIES)

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.VANILLA),
            seas = listOf(Sea.NONE),
            carvers = listOf(Carvers.SOLID),
        )
            .withOptions(Aspect.SUN, Sky.SHINING.name, listOf(Sky.NEVER))
            // **And nothing circles it either.** Silencing the sun alone left a full moon over the void,
            // because a cast nobody described falls back to vanilla's one. The nether needs no such line —
            // it is sealed, and a world shut overhead has nothing overhead whatever its cast says.
            .withOptions(Aspect.MOON, Sky.ORBITING.name, listOf(Sky.NEVER))
            .withOptions(Aspect.SKY, Atmosphere.SKY.name, listOf("black"))
            .withOptions(Aspect.AIR, Atmosphere.FOG.name, listOf("purple"))
    },
    ;

    /** The world this starts from. Built on demand, so no two Ages can share a mutable one. */
    abstract fun world(): AgeComposition

    /**
     * The list this world's biomes are chosen from by climate, or null where they are chosen by a rule of
     * its own.
     *
     * Only two of vanilla's three worlds have one — the End has no climate at all — which is why this is
     * nullable rather than every template naming a table.
     */
    abstract val biomeList: ResourceKey<MultiNoiseBiomeSourceParameterList>?

    /**
     * What this world builds when a book says nothing about structures.
     *
     * **Per world rather than one list for all of them.** Every Age used to start from the overworld's,
     * on the reasoning that no overworld biome could admit a nether set — which stopped being true the
     * moment a template brought its own biomes, and put villages and shipwrecks in the nether.
     */
    abstract val standingStructures: List<ResourceKey<StructureSet>>

    /** Whether a book may weigh or narrow this world's biomes, which needs a table to adjust. */
    val biomesAreChosenByClimate: Boolean get() = biomeList != null

    /**
     * The biomes this world grows, with whatever the book said about them already applied.
     *
     * A preference adjusts the *table* a climate is looked up in, so it can only reach a world that has
     * one. What the End cannot honour is reported rather than dropped — see [AgeRecipe.unhonoured].
     */
    fun biomesOf(
        server: MinecraftServer,
        preferences: List<BiomePreference>,
        keepsOnlyNamed: Boolean,
        seed: Long,
    ): BiomeSource {
        val lookup = server.registryAccess().lookupOrThrow(Registries.BIOME)
        val list = biomeList ?: return TheEndBiomeSource.create(lookup)
        val table = server.registryAccess()
            .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(list)
            .value()
            .parameters()
        return MultiNoiseBiomeSource.createFromList(
            BiomePreference.applied(table, preferences, keepsOnlyNamed, lookup, seed),
        )
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AgeTemplate> = StringRepresentable.fromEnum(AgeTemplate::values)

        /** The template a book that named none starts from. */
        val ORDINARY = OVERWORLD

        /** The template [key] names, or null where it names none. */
        fun named(key: String): AgeTemplate? = entries.firstOrNull { it.key == key }
    }
}
