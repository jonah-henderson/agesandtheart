package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.worldgen.biome.ClimateBias
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * What an Age looks and grows like: its biomes, and the blocks those biomes paint the shape in.
 *
 * The two travel together because a palette is written *against* a biome table — vanilla's surface
 * rules ask "which biome is this?" at nearly every turn, so [Palette.VANILLA_OVERWORLD] means nothing
 * over a world that is one biome everywhere, and [Palette.BARE_ROCK] would flatten a world that has
 * real ones. Letting a writer pair them freely would mostly produce grey.
 *
 * Structures ride here too, for now. They are closer to the *contents* slot the design describes (§3.1)
 * than to dressing, but that slot has nothing behind it yet, and villages plainly belong to the same
 * decision as "this Age has vanilla's biomes in it". When contents arrives they should move.
 */
enum class Dressing(override val key: String) : SlotPreset {
    /** Grey stone and andesite: the shape itself, with nothing growing to distract from it. */
    BARE_ROCK("bare_rock"),

    /** Our own green dressing, applied evenly — a world that looks alive without vanilla's biome table. */
    VERDANT("verdant"),

    /** Spire's green plasma sea, and bare rock above it. */
    PLASMA("plasma"),

    /**
     * Vanilla's own biomes, climate and palette: the sane default for an Age whose author has expressed
     * no preference, and the only dressing under which the world grows anything.
     */
    OVERWORLD("overworld"),
    ;

    override val slot = Slot.DRESSING

    /**
     * The climate knobs and the biome list ride on every dressing, but only [OVERWORLD] can honour them:
     * everything else is one fixed biome, and there is no table to bend or enrich.
     *
     * Declared everywhere anyway, deliberately. `unknownOptions` would otherwise report `dressing.hot` as a
     * *typo* on a bare-rock Age, which is the wrong complaint — the writer spelled a real knob that this
     * dressing cannot turn, and that is the same distinction [ignoresMaterial] draws for `stone`.
     */
    override val parameters: List<Parameter>
        get() = listOf(STONE, BIOMES) + ClimateAxis.entries.map { it.parameter } +
            if (this == OVERWORLD) listOf(SETTLEMENT) else emptyList()

    override fun getSerializedName(): String = key

    /**
     * Which of the knobs it declares this dressing can actually turn.
     *
     * The split is exact and not a coincidence: a dressing is either **one fixed biome painted our way**
     * (so it wears a material and has no table to enrich) or **vanilla's whole table** (so it holds biomes
     * and climate, and its rock is wired through sixty biome branches we do not own). Nothing is both.
     */
    override fun honours(parameter: Parameter): Boolean = when (parameter.name) {
        STONE.name -> !ignoresMaterial
        BIOMES.name -> !ignoresClimate
        in ClimateAxis.entries.map { it.key } -> !ignoresClimate
        else -> super.honours(parameter)
    }

    /**
     * The biomes this dressing lays over [landform].
     *
     * [OVERWORLD] grounds its climate in the Age's own rock rather than pinning it to the surface, which
     * is what reaches vanilla's underground biomes — dripstone and lush caves through the middle of the
     * rock, the deep dark at the very bottom. Everything else is one biome everywhere, because for those
     * Ages the shape *is* the subject.
     */
    fun biomes(server: MinecraftServer, landform: TerrainField, seed: Long, options: Options): BiomeSource = when (this) {
        OVERWORLD -> AgeBiomeSource.vanillaOverworld(server, seed)
            .told(climateIn(options), preferencesIn(options))
            .groundedIn(landform)
        PLASMA -> fixedBiome(server, ResourceKey.create(Registries.BIOME, AgeGeneration.PLASMA_BIOME))
        // Vanilla's barren `the_void`: a bright, neutral sky, and nothing in the way of the shape.
        BARE_ROCK, VERDANT -> fixedBiome(server, Biomes.THE_VOID)
    }

    /**
     * The blocks the shape gets repainted in, once it is laid down.
     *
     * A **material** (design §3.2) replaces the rock and leaves the dressing's own soil alone, so
     * `dressing=verdant dressing.stone=minecraft:blackstone` is a green world on black rock. It takes the
     * whole column rather than swapping one layer of a stack: the andesite crust and the deepslate floor
     * are themselves statements about what the rock *is*, and keeping them would say "this world is
     * blackstone" while showing three other stones.
     *
     * [OVERWORLD] cannot honour one and does not pretend to — see [ignoresMaterial].
     */
    fun palette(options: Options): SurfaceRules.RuleSource {
        val stones = materialsIn(options)
        return when (this) {
            OVERWORLD -> Palette.VANILLA_OVERWORLD
            VERDANT -> if (stones.isEmpty()) Palette.VERDANT else Palette.verdantOver(stones)
            BARE_ROCK, PLASMA -> if (stones.isEmpty()) Palette.BARE_ROCK else Palette.madeOf(stones)
        }
    }

    /**
     * Whether naming a material here would go unhonoured, so the resolver can charge for it (§3.3: a word
     * that does nothing must never do so in silence).
     *
     * True only for [OVERWORLD], and for a reason that is a fact about vanilla rather than a gap in ours:
     * its palette is `SurfaceRuleData.overworldLike`, a rule tree built by Minecraft with its stone wired
     * in throughout — sixty-odd biome branches, each naming its own rock. There is no seam to substitute
     * at short of reimplementing it, and reimplementing it would mean maintaining a copy of vanilla's
     * surface rules forever.
     */
    val ignoresMaterial: Boolean get() = this == OVERWORLD

    /**
     * Whether this dressing has a climate table to bend or enrich at all.
     *
     * False for everything but [OVERWORLD], which is the exact mirror of [ignoresMaterial]: the dressings
     * that *can* wear a material are the ones that cannot hold biomes, and vice versa. That is not a
     * coincidence — one is a single fixed biome painted our way, the other is vanilla's whole table.
     */
    val ignoresClimate: Boolean get() = this != OVERWORLD

    /** What the writer said this Age is *like* — the vague half (design §3.2). */
    private fun climateIn(options: Options): ClimateBias =
        ClimateAxis.entries.fold(ClimateBias.NONE) { bias, axis ->
            val chosen = options.of(axis.parameter)
            if (chosen == ClimateAxis.NATURAL) bias else bias.with(axis, axis.shiftFor(chosen))
        }

    /**
     * The biomes the writer named — the exact half, and **never exclusive**.
     *
     * A mention adds or strengthens and removes nothing (Jonah's call): pinning the world to one biome is
     * what `only` is for, and `only` is a modifier the grammar will bring. So a bare list is all positive
     * weights, and the machinery for removal sits unused until there is a word that means it.
     */
    private fun preferencesIn(options: Options): List<BiomePreference> = options.allOf(BIOMES)
        .filter { it != Parameter.UNCHANGED }
        .mapNotNull(ResourceLocation::tryParse)
        .map { biome -> BiomePreference(biome, BiomePreference.WEIGHT_OF_A_MENTION) }

    /**
     * The blocks this dressing was told to be made of — empty where it was told nothing, which is the
     * signal to keep the preset's own layered rock.
     *
     * Several mingle rather than divide (design §3.2). A block this pack does not have is dropped with a
     * complaint rather than failing the Age: a mod removed since the Age was written must not stop the
     * world opening, and the rest of a mingling still reads.
     */
    private fun materialsIn(options: Options): List<BlockState> = options.allOf(STONE)
        .filter { it != Parameter.UNCHANGED }
        .mapNotNull { named ->
            val id = ResourceLocation.tryParse(named) ?: return@mapNotNull null
            BuiltInRegistries.BLOCK.getOptional(id)
                .map { block -> block.defaultBlockState() }
                .orElseGet {
                    Constants.LOG.warn("An Age is made of '{}', which no block in this pack is", id)
                    null
                }
        }

    /**
     * What vanilla is allowed to build here, named by the Age rather than inherited.
     *
     * Naming a set is not the same as placing it: the generator's state builder drops any set whose
     * structures want a biome this Age's biome source cannot produce, so an Age with no jungle gets no
     * jungle temples without anyone having to say so. Leaving the nether and end sets out is honesty
     * rather than filtering — no overworld biome could ever admit them.
     */
    fun structures(server: MinecraftServer, options: Options): HolderSet<StructureSet> {
        if (this != OVERWORLD || options.of(SETTLEMENT) == "none") return HolderSet.direct(emptyList())
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        return HolderSet.direct(OVERWORLD_STRUCTURE_SETS.map(sets::getOrThrow))
    }

    private fun fixedBiome(server: MinecraftServer, biome: ResourceKey<Biome>) =
        FixedBiomeSource(server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome))

    companion object {
        /** Whether anyone ever built here. Vanilla's whole overworld set, or an empty world. */
        val SETTLEMENT = Parameter("settlement", "none", "vanilla")

        /**
         * What the rock is made of — the first **material** (design §3.2), and the one that proves the hook.
         *
         * Offered by every dressing including [OVERWORLD], which cannot honour it. Declaring it there
         * anyway is deliberate: `unknownOptions` would otherwise report `dressing.stone` as a *typo* on an
         * overworld Age, which is the wrong complaint — the writer spelled a real knob that this dressing
         * cannot turn, and [ignoresMaterial] is how that gets said properly.
         */
        val STONE = Parameter.material("stone")

        /**
         * The biomes an Age was told to grow — **populative**, so naming one adds it and naming two adds
         * both (design §3.2, Jonah's call on inclusive lists).
         *
         * Deliberately not exclusive: pinning an Age to a single biome is what `only` will mean once the
         * grammar can say it, and a bare mention that quietly excluded everything else would make a
         * beginner's one-word Age monotonous.
         */
        val BIOMES = Parameter.population("biomes")

        private val OVERWORLD_STRUCTURE_SETS = listOf(
            BuiltinStructureSets.VILLAGES,
            BuiltinStructureSets.DESERT_PYRAMIDS,
            BuiltinStructureSets.IGLOOS,
            BuiltinStructureSets.JUNGLE_TEMPLES,
            BuiltinStructureSets.SWAMP_HUTS,
            BuiltinStructureSets.PILLAGER_OUTPOSTS,
            BuiltinStructureSets.OCEAN_MONUMENTS,
            BuiltinStructureSets.WOODLAND_MANSIONS,
            BuiltinStructureSets.BURIED_TREASURES,
            BuiltinStructureSets.MINESHAFTS,
            BuiltinStructureSets.RUINED_PORTALS,
            BuiltinStructureSets.SHIPWRECKS,
            BuiltinStructureSets.OCEAN_RUINS,
            BuiltinStructureSets.ANCIENT_CITIES,
            BuiltinStructureSets.STRONGHOLDS,
            BuiltinStructureSets.TRAIL_RUINS,
            BuiltinStructureSets.TRIAL_CHAMBERS,
        )
    }
}
