package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import com.mojang.serialization.Codec
import net.minecraft.core.HolderGetter
import net.minecraft.data.worldgen.material.EndMaterialRules
import net.minecraft.data.worldgen.material.NetherMaterialRules
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.biome.TheEndBiomeSource
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.material.MaterialRules
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
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
enum class AgeTemplate(
    val key: String,
    /**
     * This world's rock: what its [ownRock] generates wherever an Age wears it, and what a landform of ours
     * is made of under this template where the book named no material.
     */
    val rock: ResourceKey<NoiseGeneratorSettings>,
) : StringRepresentable {
    /** What a world is like when nobody said otherwise. No word names it; it is what you get. */
    OVERWORLD("overworld", NoiseGeneratorSettings.OVERWORLD) {
        override val ownRock = Terrain.OVERWORLD
        override val biomeList = MultiNoiseBiomeSourceParameterLists.OVERWORLD
        override val standingStructures = Structures.OVERWORLD_STRUCTURE_SETS
        override val dimensionType get() = BuiltinDimensionTypes.OVERWORLD
        // Vanilla's surface and underground trees and not its whole overworld rule, which since 26.3 also
        // carries the copper and iron veins: those were off on our rock before the port, and `veins` is how a
        // book asks for them now.
        override fun skin(rules: HolderGetter<MaterialRule>) = MaterialRules.sequence(
            MaterialRules.getRule(rules, overworldRule("surface")),
            MaterialRules.getRule(rules, overworldRule("underground")),
        )

        // The sulfur caves' banded stone, which `overworld/underground` gives only to a sulfur cave, and whose
        // spikes, springs and pools stand on it. Not the rest of that tree: its deepslate would repaint a rock
        // of blackstone or tuff as readily as one of stone.
        override fun beneathTheSkin(rules: HolderGetter<MaterialRule>, biomes: HolderGetter<Biome>) =
            MaterialRules.ifTrue(
                MaterialRules.isBiome(biomes, Biomes.SULFUR_CAVES),
                MaterialRules.getRule(rules, overworldRule("sulfur_cave_bands")),
            )

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(ownRock),
            seas = listOf(Sea.WATER),
            carvers = listOf(Carvers.CAVES),
            underground = Underground.NOISE_CAVES,
        )
    },

    /**
     * A world that burns, sealed over and lit by nothing — vanilla's own nether rock under it.
     */
    INFERNAL("infernal", NoiseGeneratorSettings.NETHER) {
        override val ownRock = Terrain.NETHER
        override val biomeList = MultiNoiseBiomeSourceParameterLists.NETHER
        override val dimensionType get() = BuiltinDimensionTypes.NETHER
        override fun skin(rules: HolderGetter<MaterialRule>) =
            MaterialRules.getRule(rules, NetherMaterialRules.NETHER)
        override val standingStructures = listOf(
            BuiltinStructureSets.NETHER_COMPLEXES,
            BuiltinStructureSets.NETHER_FOSSILS,
            BuiltinStructureSets.RUINED_PORTALS,
        )

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(ownRock),
            seas = listOf(Sea.LAVA),
            carvers = listOf(Carvers.SOLID),
            // The nether's own rock is riddled enough; nothing of ours is cut into it.
            underground = Underground.NONE,
        )
            .withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
            .withOptions(Aspect.SUN, SkyBodies.ABSENT.name, listOf(Parameter.TRUE))
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
        override val ownRock = Terrain.END
        // **The one world whose biomes are not chosen by climate.** The End picks by distance from the
        // centre, so there is no table to weigh and no climate to bend — see [biomesOf].
        override val biomeList: ResourceKey<MultiNoiseBiomeSourceParameterList>? = null
        override val standingStructures = listOf(BuiltinStructureSets.END_CITIES)
        override val dimensionType get() = BuiltinDimensionTypes.END
        override fun skin(rules: HolderGetter<MaterialRule>) =
            MaterialRules.getRule(rules, EndMaterialRules.END)

        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(ownRock),
            seas = listOf(Sea.NONE),
            carvers = listOf(Carvers.SOLID),
            underground = Underground.NONE,
        )
            .withOptions(Aspect.SUN, SkyBodies.ABSENT.name, listOf(Parameter.TRUE))
            // **And nothing circles it either.** Silencing the sun alone left a full moon over the void,
            // because a cast nobody described falls back to vanilla's one. The nether needs no such line —
            // it is sealed, and a world shut overhead has nothing overhead whatever its cast says.
            .withOptions(Aspect.MOON, SkyBodies.ABSENT.name, listOf(Parameter.TRUE))
            .withOptions(Aspect.SKY, Atmosphere.SKY.name, listOf("black"))
            .withOptions(Aspect.AIR, Atmosphere.FOG.name, listOf("purple"))
    },
    ;

    /** The world this starts from. Built on demand, so no two Ages can share a mutable one. */
    abstract fun world(): AgeComposition

    /**
     * The landform that is **this world's rock** — [rock], as a page a writer can name under any template.
     * An Age that names no landform wears it.
     */
    abstract val ownRock: Terrain

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

    /**
     * **This world's own dimension type** — vanilla's, not a copy of it.
     *
     * An Age wearing this world's rock wears its type too, and that is the whole of how it gets the
     * nether's fog distances, its ambient light and its ceiling without anybody writing a number down. It
     * also gets its *height*: the nether's rock generates 0..128, and putting it in the −64..320 world our
     * own types declare left the roof at 127 with two hundred blocks of empty air over it.
     *
     * A getter rather than a stored field: reading `BuiltinDimensionTypes` stands `DimensionType`'s codec
     * up, and an enum constant that did it at construction would drag the registries into every check that
     * so much as names a template — several of which are registry-free on purpose.
     *
     * Ours are for the worlds vanilla has no type for — an Age with a field of its own, whose vertical band
     * is [co.voik.agesandtheart.worldgen.VerticalWindow.DEFAULT] rather than any of vanilla's.
     */
    abstract val dimensionType: ResourceKey<DimensionType>

    /**
     * **The dressing this world lays over ground of ours** — netherrack and soul soil over an infernal
     * Age's hills, where it used to be the overworld's dirt and grass whatever the book started from.
     *
     * Read only where the rock is a field of ours. Vanilla's own rock carries its own skin already, and
     * `AgeGeneration.vanillasRockFor` keeps it.
     *
     * A getter, like [dimensionType], though for a different reason than it used to be: 26.3 makes each
     * of these trees a datapack entry rather than a builder call, so what is named here is a key and the
     * getter is what resolves it.
     */
    abstract fun skin(rules: HolderGetter<MaterialRule>): MaterialRule

    /**
     * What this world paints **deep in the rock** rather than on its face, or null where it paints nothing
     * there. [skin] is laid only near the top of the column, so a cave biome's own stone would never be.
     */
    open fun beneathTheSkin(rules: HolderGetter<MaterialRule>, biomes: HolderGetter<Biome>): MaterialRule? = null


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

        /** The world whose rock [terrain] is, or null where it is a shape of ours. */
        fun ofRock(terrain: Terrain): AgeTemplate? = entries.firstOrNull { it.ownRock == terrain }

        /** The template [key] names, or null where it names none. */
        fun named(key: String): AgeTemplate? = entries.firstOrNull { it.key == key }
    }
}

/** One of the overworld's named material rules — `overworld/surface` — which vanilla keeps private keys for. */
private fun overworldRule(path: String): ResourceKey<MaterialRule> =
    ResourceKey.create(Registries.MATERIAL_RULE, Identifier.withDefaultNamespace("overworld/$path"))
