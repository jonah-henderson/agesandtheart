package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.WaterTable
import com.mojang.serialization.Codec
import com.mojang.datafixers.util.Either
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.RegistryCodecs
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.chunk.ProtoChunk
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.Beardifier
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.DensityFunctions
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.NoiseSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.RandomSupport
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.WorldGenerationContext
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.carver.CarvingContext
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The Tier-A generator: it owns no shape of its own — it samples whatever [field] it is handed and
 * fills the empty space below with [ambient]. Archetypes are field *presets*, not subclasses.
 *
 * Unlike [SpireChunkGenerator] (a bespoke Tier-B preset kept alongside), this participates in the
 * vanilla decoration pipeline: it reports honest surface heights ([getBaseHeight]/[getBaseColumn])
 * and primes the world-surface heightmaps during fill, so inherited `applyBiomeDecoration` places
 * features and structures on the terrain rather than in the sea.
 *
 * Fill lays one solid block everywhere the field claims; [buildSurface] then repaints that shape
 * according to the Age's [Palette], and [applyCarvers] cuts caves and canyons back out of it. See
 * `notes/terrain-architecture.md` for how the three fit together.
 */
class FieldChunkGenerator(
    private val biomes: BiomeSource,
    private val field: TerrainField,
    private val ambient: AmbientMedium,
    private val surfaceRule: SurfaceRules.RuleSource = Palette.PLAIN_STONE,
    /**
     * What is cut back out of the rock, one set per subsurface — **and they all run**, except where a
     * subsurface asserting the rock is *uncut* holds the ground (design §3.4, and [uncarvedTerritories]).
     *
     * Carving has been three things in turn, and the two it stopped being were wrong in ways worth keeping
     * written down. It began as a selection **per chunk**, which was an accident of how this was written
     * rather than a decision, and it made naming two subsurfaces silently exclusive. It then became a plain
     * **union** — every set running everywhere — on the argument that carving is populative (§3.2), since
     * carvers cut air out of rock and share one [CarvingMask], so two sets simply yield both cave systems.
     *
     * The union is right, and it is what still happens between any two subsurfaces that *cut* something:
     * porosity leaving small holes through a colonnade another subsurface stripped out is two ideas
     * combining, which is what a union is for. Its one flaw is `solid`, which carries no carvers and so is
     * the union's *identity* rather than a member of it: `caves solid` was measured to differ from `caves`
     * by two blocks, both of them water, and the writer was told nothing. That is §3.3's silent drop, and it
     * left "caves here, solid ground there" — an entirely ordinary thing to want — unsayable.
     *
     * So the union was not too strong, it was applied to one claim that is not populative at all. See
     * [uncarvedTerritories] for the line, which is §3.2's own.
     *
     * What survives from the union argument either way is the part about **columns**: a carver is a stateful
     * walk, so there is no column at which to ask whether it may cut. [applyCarvers] therefore asks at the
     * walk's *origin*, which is the one position a walk has.
     */
    private val carvers: List<Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>> = listOf(emptyMap()),
    /**
     * Which subsurface owns which ground — read by **carving and hydrology alike**, so that the caves and
     * the water standing in them belong to the same territory rather than to two maps that nearly agree.
     */
    private val underground: RegionMap = RegionMap.whole(),
    /**
     * Where water stands, **one table per subsurface** — hydrology divides on the same [underground] map as
     * the carving it belongs to (design §3.4). Empty means "a flat table at the ambient sea", derived below.
     */
    private val waterTables: List<WaterTable> = emptyList(),
    private val structureSets: HolderSet<StructureSet> = HolderSet.direct(emptyList()),
) : ChunkGenerator(biomes) {

    /**
     * The same generator with one subsurface everywhere — what a Tier-B preset means, since a preset is
     * a whole hand-tuned world rather than an assembly of territories.
     */
    constructor(
        biomes: BiomeSource,
        field: TerrainField,
        ambient: AmbientMedium,
        surfaceRule: SurfaceRules.RuleSource = Palette.PLAIN_STONE,
        carvers: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>,
        waterTable: WaterTable? = null,
        structureSets: HolderSet<StructureSet> = HolderSet.direct(emptyList()),
    ) : this(
        biomes, field, ambient, surfaceRule, listOf(carvers), RegionMap.whole(),
        listOfNotNull(waterTable), structureSets,
    )

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    // A sea level of Int.MIN_VALUE means "no sea" (AmbientMedium.VOID); the machinery below wants a
    // real height, and for a void medium the value is inert anyway since nothing ever fills.
    private val seaLevel = ambient.level.coerceAtLeast(MIN_Y)

    /**
     * Our vertical layout in the shape vanilla's generation machinery expects. The router is inert
     * ([NoiseRouterData.none]) and aquifers are off, so nothing here describes terrain — the field tree
     * does that. Everything is derived from our own constants rather than borrowed from
     * `NoiseGeneratorSettings.dummy()`, so our layout and this description cannot silently drift apart.
     */
    private val generationSettings = NoiseGeneratorSettings(
        NoiseSettings.create(MIN_Y, GEN_HEIGHT, NOISE_CELLS_HORIZONTAL, NOISE_CELLS_VERTICAL),
        SOLID,
        ambient.representative,
        INERT_ROUTER,
        surfaceRule,
        emptyList(),
        seaLevel,
        /* disableMobGeneration = */ true,
        /* aquifersEnabled = */ false,
        /* oreVeinsEnabled = */ false,
        /* useLegacyRandomSource = */ false,
    )

    /**
     * A stand-in that exists solely to get past a type check. [CarvingContext] demands a concrete
     * [NoiseBasedChunkGenerator], but it never keeps the reference: it hands it straight to
     * [WorldGenerationContext], which reduces it to two integers — `getMinY()` and `getGenDepth()`,
     * both declared on plain `ChunkGenerator`. Built from [generationSettings], so those two integers
     * are *ours*. Composition rather than inheritance: we stay a peer of the noise generator instead of
     * a subclass inheriting behaviour we never asked for.
     */
    private val carvingStandIn = NoiseBasedChunkGenerator(biomes, Holder.direct(generationSettings))

    override fun fillFromNoise(
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ): CompletableFuture<ChunkAccess> {
        val chunkMinX = chunk.pos.minBlockX
        val chunkMinZ = chunk.pos.minBlockZ
        val oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        val worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
        val cursor = BlockPos.MutableBlockPos()
        // Null in almost every chunk, which is what makes asking it per block affordable.
        val adaptation = TerrainAdaptation.around(structureManager, chunk.pos)

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val spans = field.columnSpans(worldX, worldZ)
                // Once per column, not once per block: which territory a column is in costs a noise
                // sample per medium, and the answer cannot change as you go down it.
                val medium = ambient.blockAt(worldX, worldZ)

                for (y in MIN_Y..<TOP_Y) {
                    // The field decides, unless a structure standing here has an opinion of its own.
                    val isRock = adaptation?.verdictAt(worldX, y, worldZ) ?: spans.contains(y)
                    val state = when {
                        isRock -> SOLID
                        ambient.fillsAt(y) -> medium
                        else -> null
                    } ?: continue
                    chunk.setBlockState(cursor.set(worldX, y, worldZ), state, false)
                    oceanFloor.update(localX, y, localZ, state)
                    worldSurface.update(localX, y, localZ, state)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    // --- Surface height contract: honest answers so structures/features land on the terrain. ---

    /**
     * The first Y *above* the topmost block this column has that [type] counts as ground. Structures place
     * against this and nothing else, so it is answered exactly rather than approximated.
     *
     * Which blocks count is [type]'s own business and we ask it rather than guessing: a world-surface query
     * counts anything that is not air, so a sea reads as its own surface, while an ocean-floor or
     * motion-blocking query sees straight through the water to the rock below. Getting that wrong puts a
     * shipwreck on the seabed and a village underwater.
     *
     * Two details are load-bearing and neither is cosmetic. Spans reach far outside any real world
     * ([Spans.HIGHEST_Y]), because an unbounded shape like [co.voik.agesandtheart.worldgen.field.HalfSpace]
     * has to be expressible, so the answer is **clamped** to the height the caller actually has. And a
     * column holding nothing answers the world's floor — vanilla's own fallback — where the old
     * `?: ambient.level` handed back [VOID][co.voik.agesandtheart.worldgen.field.AmbientMedium.VOID]'s
     * `Int.MIN_VALUE`, which `getFirstOccupiedHeight` then decremented straight into overflow.
     */
    override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState): Int {
        val counts = type.isOpaque()
        // One below the world, so a column with nothing this query counts simply answers the floor.
        val nothing = level.minBuildHeight - 1
        val rockTop = if (counts.test(SOLID)) field.columnSpans(x, z).highestSolidY ?: nothing else nothing
        val mediumTop = if (counts.test(ambient.blockAt(x, z))) ambient.surfaceY ?: nothing else nothing
        return (maxOf(rockTop, mediumTop) + 1).coerceIn(level.minBuildHeight, level.maxBuildHeight)
    }

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val spans = field.columnSpans(x, z)
        val medium = ambient.blockAt(x, z)
        val column = Array(GEN_HEIGHT) { index ->
            val y = MIN_Y + index
            when {
                spans.contains(y) -> SOLID
                ambient.fillsAt(y) -> medium
                else -> AIR
            }
        }
        return NoiseColumn(MIN_Y, column)
    }

    /**
     * Repaints the shape the field laid down, according to [surfaceRule] — vanilla's own
     * [net.minecraft.world.level.levelgen.SurfaceSystem] doing the column walk for us (see [Palette]).
     *
     * The one piece it wants that a field Age has no use for is a [NoiseChunk]. We hand it one built on
     * `NoiseGeneratorSettings.dummy()`, whose router is inert and whose aquifers are off — the same
     * settings the game already used to build the [RandomState] it passes us, since this generator is
     * not a `NoiseBasedChunkGenerator`. So nothing here consults noise; it is an access toll, paid once
     * per chunk.
     */
    override fun buildSurface(level: WorldGenRegion, structureManager: StructureManager, randomState: RandomState, chunk: ChunkAccess) {
        randomState.surfaceSystem().buildSurface(
            randomState,
            level.biomeManager,
            level.registryAccess().registryOrThrow(Registries.BIOME),
            /* useLegacyRandomSource = */ false,
            WorldGenerationContext(this, level),
            chunk,
            noiseChunkFor(chunk, randomState, structureManager),
            surfaceRule,
        )
    }

    /**
     * Where water stands in this Age's rock. Defaults to a flat table at the ambient sea — flooded
     * below, dry above — which an Age can replace with a wandering one for dry deep caves and perched
     * pockets. It mints a fresh aquifer per carving pass, since that object carries state.
     */
    private val tables: List<WaterTable> =
        waterTables.ifEmpty { listOf(WaterTable.matching(ambient, seaLevel)) }

    /**
     * Every carving subsurface's carvers together, per step — the union described on [carvers].
     *
     * Built once rather than per chunk, and **in composition order**, which is not incidental: a carver is
     * seeded by its *index* in the list it is run from, so a stable order is what keeps an Age reproducible.
     * `distinct()` because two subsurfaces naming the same vanilla carver should run it once, not twice with
     * different seeds — that would double its density rather than combine two ideas.
     */
    private val carving: Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<*>>>> by lazy {
        GenerationStep.Carving.entries.associateWith { step ->
            carvers.flatMap { perSubsurface -> perSubsurface[step]?.toList().orEmpty() }.distinct()
        }
    }

    /**
     * The territories where **nothing starts a walk** — the subsurfaces that cut nothing anywhere.
     *
     * This is the whole of how a division and a union coexist, and the line it draws is §3.2's own, read one
     * level down at the preset instead of at the parameter. `caves`, `porous` and `weathered` each assert
     * that something *exists* underground, which is a populative claim, so they accumulate and their union is
     * the right answer: porosity leaving small holes through a colonnade that something else stripped out is
     * two ideas combining, not two ideas competing. `solid` asserts an *absence* — that the rock is uncut —
     * and an absence cannot accumulate with anything. It is predicative, so it contends, and what it contends
     * for is ground.
     *
     * Inferred rather than declared, and exactly rather than heuristically: a subsurface that cuts nothing at
     * any step *is* one asserting the rock is uncut, so a subsurface added by a datapack lands on the right
     * side of this without having to say anything.
     */
    private val uncarvedTerritories: Set<Int> by lazy {
        fun cutsNothing(subsurface: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>): Boolean =
            subsurface.values.all { step -> step.size() == 0 }
        carvers.indices.filter { territory -> cutsNothing(carvers[territory]) }.toSet()
    }

    // Only ever consulted by the NoiseChunk's own (disabled, unused) aquifer — carving uses [aquifer].
    private val ambientFluid =
        Aquifer.FluidPicker { x, _, z -> Aquifer.FluidStatus(seaLevel, ambient.blockAt(x, z)) }

    /** Cached on the chunk, so surfacing and carving share one — it is the access toll, paid once. */
    private fun noiseChunkFor(chunk: ChunkAccess, randomState: RandomState, structureManager: StructureManager): NoiseChunk =
        chunk.getOrCreateNoiseChunk { access ->
            NoiseChunk.forChunk(
                access,
                randomState,
                // The real beardifier rather than the inert marker: it is public, and it is what will
                // let structures flatten the ground around themselves once they are switched on.
                Beardifier.forStructuresInChunk(structureManager, access.pos),
                generationSettings,
                ambientFluid,
                Blender.empty(),
            )
        }

    /**
     * Cuts caves and canyons out of the shape the field laid down — the subtractive counterpart to the
     * field toolkit. Carvers are stateful random walks across a chunk *neighbourhood*, which is exactly
     * the winding, non-columnar form the analytic span contract cannot express, so this is not a
     * bolt-on: it covers the gap the fields structurally leave.
     *
     * Unlike vanilla this reads its carvers from the Age's own recipe rather than from the biome. Field
     * Ages sit on a barren biome that carries none, and an Age already describes its whole world as
     * replayable data, so its carvers belong there too.
     *
     * **Whether anything may start a walk is decided at the walk's origin** — the source chunk — rather than
     * per column, which is what makes uncut ground affordable at all (see [carvers]). Two consequences
     * follow, and both are wanted:
     *
     * - A tunnel starting outside keeps going across the boundary, up to [CARVE_REACH_CHUNKS] chunks. So the
     *   two meet as a **gradient rather than a wall**, and uncut ground is not perfectly uncut at its edge.
     *   A hard mask would instead shear tunnels off flat against an invisible line.
     * - Ground much narrower than that reach is **swamped by what bleeds into it**. The default territory is
     *   400 blocks across against a reach of 128, so an even division reads clearly and a scarce one fades —
     *   a real limit on how small an uncut territory can usefully be, and the reason [underground] is not
     *   simply handed the share ladder's 1% floor to work with.
     *
     * The generalisation this is the first case of: a territory carves the union unless it was asked to keep
     * ground of its own, and `solid` is the degenerate version where its own set is empty. Asking is the
     * grammar's `and` (§3.2), which does not exist yet, so nothing here reads a flag that nothing can set.
     */
    override fun applyCarvers(
        level: WorldGenRegion,
        seed: Long,
        randomState: RandomState,
        biomeManager: BiomeManager,
        structureManager: StructureManager,
        chunk: ChunkAccess,
        step: GenerationStep.Carving,
    ) {
        val stepCarvers = carving[step].orEmpty()
        if (stepCarvers.isEmpty()) return
        val protoChunk = chunk as? ProtoChunk ?: return

        // A biome manager reading this Age's own source rather than the level's, which a carver asks
        // per position to decide what it may cut through.
        val carvingBiomes = biomeManager.withDifferentSource { quartX, quartY, quartZ ->
            biomes.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler())
        }
        val noiseChunk = noiseChunkFor(chunk, randomState, structureManager)
        val context = CarvingContext(
            carvingStandIn,
            level.registryAccess(),
            chunk.heightAccessorForGeneration,
            noiseChunk,
            randomState,
            surfaceRule,
        )
        val carvingMask = protoChunk.getOrCreateCarvingMask(step)
        // Fresh per pass: it caches a column and tracks whether the water it just placed needs to
        // settle, so it must not be shared between chunk workers.
        val aquifer = WaterTable.aquiferFor(tables, field, underground)
        // Seeded per *source* chunk rather than per target, so one cave system crosses chunk borders
        // identically however the chunks happen to be generated. The reach matches vanilla's.
        val random = WorldgenRandom(LegacyRandomSource(RandomSupport.generateUniqueSeed()))

        for (offsetX in -CARVE_REACH_CHUNKS..CARVE_REACH_CHUNKS) {
            for (offsetZ in -CARVE_REACH_CHUNKS..CARVE_REACH_CHUNKS) {
                val source = ChunkPos(chunk.pos.x + offsetX, chunk.pos.z + offsetZ)
                // The source chunk's centre decides, so a chunk is wholly inside or outside the uncut ground
                // even where a boundary crosses it. [RegionMap.memberAt] has already frayed that boundary by
                // the Age's seam, so the two interlock at chunk grain without anything here saying so.
                val territory = underground.memberAt(source.middleBlockX, source.middleBlockZ)
                if (territory in uncarvedTerritories) continue
                stepCarvers.forEachIndexed { index, carver ->
                    random.setLargeFeatureSeed(seed + index, source.x, source.z)
                    if (carver.value().isStartChunk(random)) {
                        // Our own aquifer, not the NoiseChunk's: vanilla's reads noise from the
                        // RandomState's router, which is inert for a generator like ours.
                        carver.value().carve(context, chunk, carvingBiomes::getBiome, random, aquifer, source, carvingMask)
                    }
                }
            }
        }
    }

    /**
     * Which structures may be placed in this Age — named by its own recipe, and empty by default.
     *
     * Vanilla takes every structure set in the registry whose structures list a biome the source can
     * produce. That is fine for vanilla's terrain and wrong for ours: the moment real biomes arrive, so do
     * villages, in Ages whose terrain may be a field of pyramids or a flat plate. So structures are opt-in
     * per Age, the same way carvers already are, and an Age names the ones it wants in its own recipe.
     *
     * Vanilla builds this state two ways and neither is quite what an Age wants: `createForNormal` reads
     * the whole registry but seeds the concentric rings from the world seed, while `createForFlat` takes an
     * explicit set but nails that seed to zero — which would put every Age's strongholds at the same
     * bearings. Narrowing the *lookup* instead gets both: our set, and this Age's own rings.
     */
    override fun createState(
        structureSetLookup: HolderLookup<StructureSet>,
        randomState: RandomState,
        seed: Long,
    ): ChunkGeneratorStructureState =
        ChunkGeneratorStructureState.createForNormal(
            randomState,
            seed,
            biomes,
            structureSetLookup.restrictedTo(structureSets),
        )

    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    override fun getGenDepth(): Int = GEN_HEIGHT

    override fun getSeaLevel(): Int = ambient.level

    override fun getMinY(): Int = MIN_Y

    companion object {
        // Declared before CODEC, and it has to be: a companion initialises top to bottom, so CODEC
        // reading this from below would read a null. Cost us a server boot to find, because nothing
        // offline touches the generator's codec.
        private val CARVER_SETS: Codec<Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>> =
            Codec.unboundedMap(
                GenerationStep.Carving.CODEC,
                RegistryCodecs.homogeneousList(Registries.CONFIGURED_CARVER),
            )

        val CODEC: MapCodec<FieldChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                TerrainField.CODEC.fieldOf("field").forGetter { it.field },
                AmbientMedium.CODEC.forGetter { it.ambient },
                // Optional so field Ages serialised before palettes existed still load.
                SurfaceRules.RuleSource.CODEC.optionalFieldOf("surface_rule", Palette.PLAIN_STONE)
                    .forGetter { it.surfaceRule },
                // A list now that subsurface is set-valued, and still readable as the single map it
                // was: one carver set is exactly what an Age with one subsurface has.
                Codec.either(CARVER_SETS.listOf(), CARVER_SETS)
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .optionalFieldOf("carvers", listOf(emptyMap())).forGetter { it.carvers },
                RegionMap.MAP_CODEC.codec().optionalFieldOf("underground", RegionMap.whole())
                    .forGetter { it.underground },
                // Absent means "a flat table at the ambient sea", derived at construction. A list, since
                // hydrology divides with the carving it belongs to, and still readable as the single table
                // it was — one table is exactly what an Age with one subsurface has.
                Codec.either(WaterTable.CODEC.codec().listOf(), WaterTable.CODEC.codec())
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .optionalFieldOf("water_table", emptyList()).forGetter { it.waterTables },
                RegistryCodecs.homogeneousList(Registries.STRUCTURE_SET)
                    .optionalFieldOf("structure_sets", HolderSet.direct(emptyList()))
                    .forGetter { it.structureSets },
            ).apply(instance) { biomes, field, ambient, rule, carvers, underground, tables, structures ->
                FieldChunkGenerator(biomes, field, ambient, rule, carvers, underground, tables, structures)
            }
        }

        /** Half a chunk, so a chunk is judged by its middle rather than its corner. */
        private const val BLOCKS_PER_SECTION = 16

        // Vertical layout matches the agesandtheart:age dimension type (min_y -64, height 384).
        private const val MIN_Y = -64
        private const val GEN_HEIGHT = 384
        private const val TOP_Y = MIN_Y + GEN_HEIGHT

        /**
         * A noise router that describes nothing: every one of its density functions is zero. The field
         * tree is what shapes our terrain, so this exists only to fill a required slot — and it is
         * deliberately *inert* rather than merely unused, so nothing that consults it can be confidently
         * wrong about terrain that does not exist. (Vanilla's own `NoiseRouterData.none()` is protected.)
         */
        private val INERT_ROUTER: NoiseRouter = DensityFunctions.zero().let { nothing ->
            NoiseRouter(
                nothing, nothing, nothing, nothing, nothing,
                nothing, nothing, nothing, nothing, nothing,
                nothing, nothing, nothing, nothing, nothing,
            )
        }

        // Cell sizes for the layout description handed to vanilla's machinery; they match the
        // overworld's, which is the shape all of it is tuned around.
        private const val NOISE_CELLS_HORIZONTAL = 1
        private const val NOISE_CELLS_VERTICAL = 2

        // Carvers reach this many chunks out, so a cave system crosses borders. Vanilla's own figure.
        private const val CARVE_REACH_CHUNKS = 8

        // What the field lays down before the palette repaints it.
        private val SOLID: BlockState = Blocks.STONE.defaultBlockState()
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
    }
}

/**
 * The same lookup, showing only the sets in [allowed] — the seam that lets an Age name its structures
 * without giving up vanilla's own state builder (see [FieldChunkGenerator.createState] for why).
 *
 * Only [listElements] is narrowed for `createForNormal`'s sake; [get] is narrowed too so the view stays
 * honest for anything else that reads it, while tags pass through untouched — nothing consults them here,
 * and a half-filtered tag would be a worse answer than the real one.
 */
private fun HolderLookup<StructureSet>.restrictedTo(allowed: HolderSet<StructureSet>): HolderLookup<StructureSet> {
    val whole = this
    return object : HolderLookup<StructureSet> {
        override fun listElements(): Stream<Holder.Reference<StructureSet>> =
            whole.listElements().filter { allowed.contains(it) }

        override fun listTags(): Stream<HolderSet.Named<StructureSet>> = whole.listTags()

        override fun get(key: ResourceKey<StructureSet>): Optional<Holder.Reference<StructureSet>> =
            whole.get(key).filter { allowed.contains(it) }

        override fun get(tag: TagKey<StructureSet>): Optional<HolderSet.Named<StructureSet>> = whole.get(tag)
    }
}
