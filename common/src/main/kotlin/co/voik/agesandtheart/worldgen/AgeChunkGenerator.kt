package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.TerrainFill
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
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.age.consequence.Consequence
import co.voik.agesandtheart.age.consequence.Tearing
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.DensityFunctions
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.NoiseSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.RandomSupport
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.carver.CarvingContext
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import com.google.common.base.Suppliers
import net.minecraft.world.level.biome.FeatureSorter
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.resources.Identifier

/**
 * The generator every composed Age runs on: **vanilla's noise generator, with four deliberate exits.**
 * Everything else — the stages, the surface system, decoration, structures, mob spawning — is inherited.
 *
 * 1. [fillFromNoise], the only irreducible one. Our shapes are *analytic*: a [TerrainField] answers
 *    `(x, z) -> Spans` where a density function answers `(x, y, z) -> double` on an interpolated cell
 *    grid, and interpolation is what rounds off a `Box`'s faces.
 * 2. [getBaseHeight] and 3. [getBaseColumn] — the same decision from two more angles. These must *agree*
 *    with the fill, since structures and features place against them.
 * 4. [applyCarvers], because an Age's carvers come from its **recipe**, not its biome. Otherwise vanilla's
 *    own algorithm, with one substitution: the source of the list.
 *
 * Not an exit: dividing the world by territory goes through a custom `RegionRule`, vanilla's own
 * registered extension point. See `notes/terrain-architecture.md` for how the stages fit together.
 */
class AgeChunkGenerator(
    private val biomes: BiomeSource,
    val field: TerrainField,
    val seaFill: SeaFill,
    private val surfaceRule: SurfaceRules.RuleSource = SurfacingStrategy.SUPPRESSED,
    /**
     * What is cut back out of the rock, one set per carving — **and they all run**, except where a carving
     * asserting the rock is *uncut* holds the ground (§3.4, and [uncarvedTerritories]).
     *
     * The union is right between any two carvings that *cut* something. `solid` is the exception, carrying
     * no carvers and so being the union's identity rather than a member of it — which left "caves here,
     * solid ground there" unsayable.
     *
     * A carver is a stateful walk, so there is no column at which to ask whether it may cut; [applyCarvers]
     * asks at the walk's *origin*, the one position a walk has.
     */
    private val carvers: List<HolderSet<ConfiguredWorldCarver<*>>> = listOf(HolderSet.direct()),
    /**
     * Which carving owns which ground — read by **carving and hydrology alike**, so that the caves and
     * the water standing in them belong to the same territory rather than to two maps that nearly agree.
     */
    private val underground: RegionMap = RegionMap.whole(),
    /**
     * Where water stands, **one table per carving** — hydrology divides on the same [underground] map as
     * the carving it belongs to (design §3.4). Empty means "a flat table at the sea's own level", derived below.
     */
    private val waterTables: List<WaterTable> = emptyList(),
    /**
     * Which structure sets this Age offers vanilla — a plain list rather than a `HolderSet`, because a
     * rescaled set ([co.voik.agesandtheart.worldgen.structure.StructureDensity]) is a *direct* holder no
     * registry has heard of, and `RegistryCodecs.homogeneousList` can only write keys.
     */
    private val structureSets: List<Holder<StructureSet>> = emptyList(),
    /**
     * Where this Age's **climate** comes from, or null for an Age that has none — which is what a
     * single-biome demo preset is. Only the climate half of the named router is taken; see [routerFor].
     */
    private val climate: Holder<NoiseGeneratorSettings>? = null,
    /**
     * What the rock is made of — vanilla's `default_block`, per territory of the **terrain's** map. See
     * [TerrainFill] for why this is a fill rather than a surface rule.
     */
    private val fill: TerrainFill = TerrainFill.PLAIN,
    /** The band of world this Age generates into — see [VerticalWindow] for why it is per-Age. */
    private val window: VerticalWindow = VerticalWindow.DEFAULT,
    /**
     * The rock a cave system was cut out of, or null for an Age with no such caves.
     *
     * **Space inside this is the aquifer's to answer for, not the waterline's.** A carved cave meets
     * [WaterTable] on its way out of the rock; one that is part of the *shape* never does, so a flat sea
     * fills it to the roof. Handing the fill the volume the terrain would have occupied is what lets the
     * same three-way table decide there too — bone dry deep down, a perched pocket sometimes, and flooded
     * only where the sea genuinely reaches.
     */
    val hollows: TerrainField? = null,
    /**
     * What this Age places, as the function vanilla itself parameterises decoration with — see [Features].
     * Null leaves every biome's own list exactly as the pack wrote it.
     *
     * **Not serialised**, and it is the one input that is not. A settings function is code, where a recipe
     * holds data; the Age rebuilds it from its claims on every open, which is the same trip every other
     * aspect makes and the reason nothing here has to be a registry object.
     */
    places: ((Holder<Biome>) -> BiomeGenerationSettings)? = null,
    /**
     * What lives here, narrowing the weighted list vanilla resolved — see [Spawns]. Null leaves every
     * biome's own creatures exactly as the pack wrote them.
     *
     * **Not serialised**, like the feature policy beside it and for the same reason: a recipe holds data
     * and this is code, rebuilt from the Age's claims on every open.
     */
    private val lives: ((Identifier?, WeightedList<MobSpawnSettings.SpawnerData>) ->
    WeightedList<MobSpawnSettings.SpawnerData>)? = null,
    /**
     * How many wounds open per chunk (design §5.1, §5.0) — an **expected count**, so a half is half the
     * chunks getting one and a hundred is a hundred in every chunk.
     *
     * Per chunk rather than per Age, because a count for a whole dimension is a handful nobody ever walks
     * past. **And it climbs steeply**: writing an unstable Age should be something you *know*, met as
     * wounds you come across regularly rather than as a curiosity somewhere (Jonah, 2026-08-07). A badly
     * torn Age is holed through, not lightly freckled.
     *
     * Zero for every coherent Age, which is nearly all of them.
     */
    woundsPerChunk: Double = NO_WOUNDS,
    /**
     * How many more open per chunk with each day the Age has stood — **blight** (design §5.2.1).
     *
     * Where [woundsPerChunk] is how holed the book made it, this is how holed it *becomes*. Unbounded on
     * purpose: a ceiling would promise the Age can be outlasted, and the only question the register asks
     * is how long you stay. Zero for every Age that is merely flawed rather than coming apart.
     */
    blightPerDay: Double = NO_WOUNDS,
    /**
     * How many tears per cell this Age's floor is cut with — **collapse** (design §5.3). Zero for every
     * Age that is not ending; they widen themselves once cut.
     */
    collapseTears: Int = Collapse.NONE,
    /**
     * The overworld tick this Age was written on, so [blightPerDay] has something to count from.
     *
     * **On the generator because a chunk generated late must come out as torn as its neighbours**, which is
     * §5.4's derived-clock escape: a chunk that has never existed has no blocks to be legible from, so the
     * generator and the fast-forward read the same function rather than one of them inferring.
     */
    writtenAt: Long = 0L,
) : NoiseBasedChunkGenerator(biomes, Holder.direct(settingsFor(seaFill, surfaceRule, climate, fill, window, field))) {

    /**
     * What this Age's instability bought — **`var`, and volatile, because it can be rewritten under a
     * generator that already exists** (Jonah, 2026-08-09, walked: collapse reported a radius and generated
     * nothing).
     *
     * A generator is built once when its Age is opened and lives as long as the dimension does, so anything
     * captured in the constructor is what was true at *open*. `/age decay` rewrites the recipe — which every
     * report reads, and which is right on the next open — but decoration kept reading the stale copy, so an
     * Age would say it had stood a month and generate as though it were new.
     *
     * Volatile rather than read from the recipe per chunk: decoration runs on chunk-generation threads, and
     * `AgeSavedData` is a plain map on the overworld's storage that has no business being touched from one.
     * One reference written on the server thread and read on the workers is the whole of what is needed —
     * and it is one reference rather than four fields precisely so a reader cannot catch half an update.
     */
    @Volatile
    var consequence: Consequence = Consequence(woundsPerChunk, blightPerDay, collapseTears, writtenAt)
        private set

    /** Tell a running Age that what it is has changed, so generation stops answering from the old one. */
    fun rewriteConsequence(to: Consequence) {
        consequence = to
    }

    init {
        // **The seam vanilla offers, and both halves of it.** `ChunkGenerator` takes this function in its
        // two-argument constructor; `NoiseBasedChunkGenerator` calls the one-argument form, so the fields
        // are widened and assigned here instead.
        //
        // Both, because the constructor captures the **parameter** rather than the field when it builds
        // `featuresPerStep`. Assigning only the getter moves what decoration looks up and leaves the
        // sorted list it looks up *in* built from the biome defaults — and that list is indexed by
        // identity, so a feature this Age added comes back as -1 in the middle of generation.
        places?.let { settings ->
            generationSettingsGetter = java.util.function.Function(settings)
            featuresPerStep = Suppliers.memoize {
                FeatureSorter.buildFeaturesPerStep(
                    biomeSource.possibleBiomes().toList(),
                    { biome -> settings(biome).features() },
                    true,
                )
            }
        }
    }

    /**
     * The creatures this Age offers vanilla, narrowed by whatever the sentence said (design §3.1).
     *
     * **A plain override, and the whole seam.** `super` resolves a structure's own spawn overrides before
     * the biome's, so narrowing *its* answer means one sentence covers a fortress and the open field alike
     * — and it needs no widener, unlike the feature seam beside it.
     */
    override fun getMobsAt(
        biome: Holder<Biome>,
        structures: StructureManager,
        category: MobCategory,
        at: BlockPos,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        val offered = super.getMobsAt(biome, structures, category, at)
        return lives?.invoke(biome.unwrapKey().orElse(null)?.identifier(), offered) ?: offered
    }

    /** The same generator with one carving everywhere — what a Tier-B preset means. */
    constructor(
        biomes: BiomeSource,
        field: TerrainField,
        seaFill: SeaFill,
        surfaceRule: SurfaceRules.RuleSource = SurfacingStrategy.SUPPRESSED,
        carvers: HolderSet<ConfiguredWorldCarver<*>>,
        waterTable: WaterTable? = null,
        structureSets: List<Holder<StructureSet>> = emptyList(),
    ) : this(
        biomes, field, seaFill, surfaceRule, listOf(carvers), RegionMap.whole(),
        listOfNotNull(waterTable), structureSets,
    )

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    // A sea level of Int.MIN_VALUE means "no sea" (SeaFill.NONE); the machinery below wants a
    // real height, and for a void sea the value is inert anyway since nothing ever fills.
    private val seaLevel = seaFill.level.coerceAtLeast(window.minY)

    /**
     * The settings handed to the superclass, read back rather than kept twice — see [settingsFor]. Vanilla
     * reads the same object, so `getSeaLevel`/`getMinY`/`getGenDepth` need no overrides here.
     */
    private val generationSettings: NoiseGeneratorSettings = generatorSettings().value()

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

        // **A band, not a chunk.** A fluid can only be told to move if we know whether its neighbours left
        // it anywhere to go, and the columns beside a chunk's edge are outside it — so this reads one
        // column of margin all round. Measured at +24% of the field's own cost against a budget in which
        // that field is a few milliseconds, which is what made it worth having over letting the walls
        // stand. Read once per column and not once per block: the answer cannot change going down one.
        val band = ColumnBand(chunkMinX, chunkMinZ, field, seaFill, hollows)
        // One per chunk, because the object carries a column memo — the same reason carving mints its own.
        val water = WaterTable.aquiferFor(tables, field, underground)

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val at = band.indexOf(localX, localZ)
                val spans = band.spans(at)
                val sea = seaFill.blockAt(worldX, worldZ)

                // Whether the block just below came out empty, so a fluid placed on top of nothing can be
                // told to fall. Nothing is below the window's floor, which is as good as open for this.
                var nothingBelow = true

                for (y in window.minY..<window.topY) {
                    // The field decides, unless a structure standing here has an opinion of its own.
                    val isRock = adaptation?.verdictAt(worldX, y, worldZ) ?: spans.contains(y)
                    val state = when {
                        // What the rock *is*, which is vanilla's `default_block` and now ours — the surface
                        // system paints its skin over this afterwards, exactly as it does for vanilla.
                        isRock -> fill.blockAt(worldX, y, worldZ)
                        // Inside the rock a cave system opened: the table answers, not the waterline. Asked
                        // before the sea, since this space is under it and the sea would otherwise take it.
                        band.hollow(at, y) -> water.computeSubstance(
                            DensityFunction.SinglePointContext(worldX, y, worldZ),
                            HOLLOW,
                        )
                        band.fills(at, y) -> sea
                        else -> null
                    }?.takeUnless { it.isAir }
                    if (state == null) {
                        nothingBelow = true
                        continue
                    }
                    cursor.set(worldX, y, worldZ)
                    // **A fluid with anywhere to go is asked to go there.** Where a channel drops faster
                    // than its own surface does, the shape leaves water standing over a step or against a
                    // wall of open air — and no arrangement of *levels* can fix that, because the gap is
                    // where the water is moving. Marked for post-processing, vanilla gives the source its
                    // first tick on load and it finds its own way down, which is a waterfall.
                    val perched = nothingBelow || band.openBeside(localX, localZ, y)
                    if (perched && !state.fluidState.isEmpty) chunk.markPosForPostprocessing(cursor)
                    nothingBelow = false
                    chunk.setBlockState(cursor, state)
                    oceanFloor.update(localX, y, localZ, state)
                    worldSurface.update(localX, y, localZ, state)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    /**
     * A chunk's columns and one of margin all round, read once.
     *
     * The margin is the whole point: whether a fluid has somewhere to go is a question about its
     * *neighbours*, and a sixteenth of a chunk's columns have neighbours outside it. Reading a band costs a
     * quarter more than reading a chunk, where asking four extra columns per block would cost five times.
     *
     * Structure adaptation is deliberately not consulted for a neighbour. It is a local override on one
     * chunk's own rock, and letting it decide whether a river spills would make a village change the water
     * two chunks away.
     */
    private class ColumnBand(
        chunkMinX: Int,
        chunkMinZ: Int,
        field: TerrainField,
        private val seaFill: SeaFill,
        hollows: TerrainField?,
    ) {
        private val spans = arrayOfNulls<Spans>(SIDE * SIDE)
        private val dryness = arrayOfNulls<Spans>(SIDE * SIDE)
        private val wetness = arrayOfNulls<Spans>(SIDE * SIDE)
        private val hollowness = arrayOfNulls<Spans>(SIDE * SIDE)

        init {
            for (bandX in 0..<SIDE) {
                for (bandZ in 0..<SIDE) {
                    val worldX = chunkMinX + bandX - MARGIN
                    val worldZ = chunkMinZ + bandZ - MARGIN
                    val at = bandX * SIDE + bandZ
                    spans[at] = field.columnSpans(worldX, worldZ)
                    dryness[at] = seaFill.drynessAt(worldX, worldZ)
                    wetness[at] = seaFill.wetnessAt(worldX, worldZ)
                    hollowness[at] = hollows?.columnSpans(worldX, worldZ) ?: Spans.EMPTY
                }
            }
        }

        /** Whether this level is inside the rock a cave system was cut from — see [hollows]. */
        fun hollow(at: Int, y: Int): Boolean = hollowness[at]!!.contains(y)

        fun indexOf(localX: Int, localZ: Int): Int = (localX + MARGIN) * SIDE + (localZ + MARGIN)

        fun spans(at: Int): Spans = spans[at]!!

        fun fills(at: Int, y: Int): Boolean = seaFill.fillsAt(y, dryness[at]!!, wetness[at]!!)

        /** Whether any of the four columns beside this one left this level open. */
        fun openBeside(localX: Int, localZ: Int, y: Int): Boolean =
            isOpen(indexOf(localX - 1, localZ), y) || isOpen(indexOf(localX + 1, localZ), y) ||
                isOpen(indexOf(localX, localZ - 1), y) || isOpen(indexOf(localX, localZ + 1), y)

        private fun isOpen(at: Int, y: Int): Boolean = !spans[at]!!.contains(y) && !fills(at, y)

        private companion object {
            const val MARGIN = 1
            const val SIDE = 16 + 2 * MARGIN
        }
    }

    // --- Surface height contract: honest answers so structures/features land on the terrain. ---

    /**
     * The first Y *above* the topmost block this column has that [type] counts as ground. Structures place
     * against this and nothing else, so it is answered exactly rather than approximated. Which blocks
     * count is [type]'s business and is asked rather than guessed — getting it wrong puts a shipwreck on
     * the seabed and a village underwater.
     *
     * Two load-bearing details: spans reach far outside any real world (`Spans.HIGHEST_Y`), so the answer
     * is **clamped** to the height the caller has; and a column holding nothing answers the world's floor
     * rather than the sea level, which for a void sea was `Int.MIN_VALUE` and overflowed.
     */
    override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState): Int {
        val counts = type.isOpaque()
        // One below the world, so a column with nothing this query counts simply answers the floor.
        val nothing = level.minY - 1
        val rockTop = if (counts.test(fill.representative)) field.columnSpans(x, z).highestSolidY ?: nothing else nothing
        // A river stands over the waterline, so its own surface is what a structure has to be told about.
        val mediumTop = if (!counts.test(seaFill.blockAt(x, z))) nothing else {
            maxOf(seaFill.surfaceY ?: nothing, seaFill.wetnessAt(x, z).highestSolidY ?: nothing)
        }
        return (maxOf(rockTop, mediumTop) + 1).coerceIn(level.minY, level.maxY + 1)
    }

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val spans = field.columnSpans(x, z)
        val sea = seaFill.blockAt(x, z)
        val dryness = seaFill.drynessAt(x, z)
        val wetness = seaFill.wetnessAt(x, z)
        val column = Array(window.height) { index ->
            val y = window.minY + index
            when {
                spans.contains(y) -> fill.blockAt(x, y, z)
                seaFill.fillsAt(y, dryness, wetness) -> sea
                else -> AIR
            }
        }
        return NoiseColumn(window.minY, column)
    }

    /**
     * Where water stands in this Age's rock. Defaults to a flat table at the sea's own level — flooded
     * below, dry above — which an Age can replace with a wandering one for dry deep caves and perched
     * pockets. It mints a fresh aquifer per carving pass, since that object carries state.
     */
    private val tables: List<WaterTable> =
        waterTables.ifEmpty { listOf(WaterTable.matching(seaFill, seaLevel)) }

    /**
     * Every carving's carvers together — the union described on [carvers]. Built once and **in composition
     * order**, because a carver is seeded by its *index* in the list it runs from, so a stable order is
     * what keeps an Age reproducible. `distinct()` so a carver named twice runs once, rather than twice at
     * different seeds, which would double its density.
     */
    private val carving: List<Holder<ConfiguredWorldCarver<*>>> by lazy {
        carvers.flatMap { perSubsurface -> perSubsurface.toList() }.distinct()
    }

    /**
     * The territories where **nothing starts a walk** — the carving that cut nothing anywhere. §3.2's line,
     * read one level down: `caves` and `porous` assert something *exists* underground and so accumulate,
     * where `solid` asserts an absence, which cannot accumulate and so contends for ground.
     *
     * Inferred rather than declared, and exactly: a carving that cuts nothing *is* one asserting the rock
     * is uncut, so a datapack's carving lands on the right side without saying anything.
     */
    private val uncarvedTerritories: Set<Int> by lazy {
        carvers.indices.filter { territory -> carvers[territory].size() == 0 }.toSet()
    }

    // Only ever consulted by the NoiseChunk's own (disabled, unused) aquifer — carving uses [aquifer].
    private val ambientFluid =
        Aquifer.FluidPicker { x, _, z -> Aquifer.FluidStatus(seaLevel, seaFill.blockAt(x, z)) }

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
     * Cuts caves and canyons out of the shape the field laid down. Carvers are stateful random walks across
     * a chunk *neighbourhood*, which is the winding, non-columnar form the analytic span contract cannot
     * express. Reads its carvers from the Age's recipe rather than from the biome.
     *
     * **Whether anything may start a walk is decided at the walk's origin**, the source chunk, rather than
     * per column (see [carvers]). Two consequences, both wanted:
     *
     * - A tunnel starting outside keeps going across the boundary, up to [CARVE_REACH_CHUNKS] chunks, so
     *   uncut ground meets carved as a gradient rather than a wall.
     * - Ground much narrower than that reach is swamped by what bleeds into it — 400-block territories
     *   against a reach of 128, so an even division reads clearly and a scarce one fades.
     */
    override fun applyCarvers(
        level: WorldGenRegion,
        seed: Long,
        randomState: RandomState,
        biomeManager: BiomeManager,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ) {
        if (carving.isEmpty()) return
        val protoChunk = chunk as? ProtoChunk ?: return

        // A biome manager reading this Age's own source rather than the level's, which a carver asks
        // per position to decide what it may cut through.
        val carvingBiomes = biomeManager.withDifferentSource { quartX, quartY, quartZ ->
            biomes.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler())
        }
        val noiseChunk = noiseChunkFor(chunk, randomState, structureManager)
        val context = CarvingContext(
            // Ourselves: [CarvingContext] demands a concrete [NoiseBasedChunkGenerator], which we are.
            this,
            level.registryAccess(),
            chunk.heightAccessorForGeneration,
            noiseChunk,
            randomState,
            surfaceRule,
        )
        val carvingMask = protoChunk.getOrCreateCarvingMask()
        // Fresh per pass: it caches a column and tracks whether the water it just placed needs to
        // settle, so it must not be shared between chunk workers.
        val aquifer = WaterTable.aquiferFor(tables, field, underground)
        // Seeded per *source* chunk rather than per target, so one cave system crosses chunk borders
        // identically however the chunks happen to be generated. The reach matches vanilla's.
        val random = WorldgenRandom(LegacyRandomSource(RandomSupport.generateUniqueSeed()))

        for (offsetX in -CARVE_REACH_CHUNKS..CARVE_REACH_CHUNKS) {
            for (offsetZ in -CARVE_REACH_CHUNKS..CARVE_REACH_CHUNKS) {
                val source = ChunkPos(chunk.pos.x + offsetX, chunk.pos.z + offsetZ)
                // The source chunk's centre decides, so a chunk is wholly inside or outside the uncut
                // ground. [RegionMap.memberAt] has already frayed that boundary by the Age's seam.
                val territory = underground.memberAt(source.middleBlockX, source.middleBlockZ)
                if (territory in uncarvedTerritories) continue
                carving.forEachIndexed { index, carver ->
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
     * Which structures may be placed in this Age — named by its own recipe, and empty by default, since
     * vanilla would otherwise put villages in a field of pyramids.
     *
     * Two branches, and what decides is whether the Age rescaled a set's density. `createForNormal` reads
     * the registry and seeds the concentric rings from the world seed; `createForFlat` takes explicit
     * holders but pins that seed to zero. A rescaled set is a direct holder no lookup can show
     * ([co.voik.agesandtheart.worldgen.structure.StructureDensity]), so it has to take the second branch
     * and gets vanilla's superflat ring bearings.
     */
    override fun createState(
        structureSetLookup: HolderLookup<StructureSet>,
        randomState: RandomState,
        seed: Long,
    ): ChunkGeneratorStructureState {
        // Registered means "came out of the registry untouched". Anything we rescaled is a direct holder.
        val everyOneIsRegistered = structureSets.all { it.kind() == Holder.Kind.REFERENCE }
        if (everyOneIsRegistered) {
            return ChunkGeneratorStructureState.createForNormal(
                randomState,
                seed,
                biomes,
                structureSetLookup.restrictedTo(structureSets),
            )
        }
        return ChunkGeneratorStructureState.createForFlat(randomState, seed, biomes, structureSets.stream())
    }

    /**
     * Kept as an exit, narrowly: mob generation is disabled in [settingsFor], and the superclass would
     * otherwise consult its own [NoiseChunk] to decide. Nothing to inherit here that we want.
     */
    /**
     * Vanilla's decoration, and then whatever this Age's instability tore open (design §5.1).
     *
     * **After the features, not before.** A wound is not part of the world's furniture — it is what the
     * Age could not hold — so it goes in last, over whatever grew there, the way the tear in Riven sits in
     * a room somebody built rather than in a space left for it.
     *
     * Seeded off the chunk so an Age rebuilds identically on every open: this runs per chunk, and a chunk
     * generated today and the same chunk generated next year must agree about whether it holds one.
     */
    override fun applyBiomeDecoration(level: WorldGenLevel, chunk: ChunkAccess, structures: StructureManager) {
        super.applyBiomeDecoration(level, chunk, structures)
        val bought = consequence
        if (bought.isNothing) return
        // **The Age's age is read here rather than at open**, so a chunk generated after a week of blight
        // comes out as torn as the ones beside it. Against the *overworld's* clock: an Age's own only runs
        // while somebody is in it, which is exactly when blight is not supposed to be waiting.
        val days = bought.daysBy(level.level.server.overworld().gameTime)
        // The floor giving way first: a column the Age has already swallowed is not somewhere to put a
        // wound, and carving after would take the wound straight back out again.
        Collapse.carveInto(level, chunk, level.getSeed(), bought.collapseTears)
        val density = Tearing.densityAt(bought.woundsPerChunk, bought.blightPerDay, days)
        Tearing.tearInto(level, chunk, level.getSeed(), Tearing.wantedIn(chunk.pos, level.getSeed(), density))
    }

    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    /** The superclass renders noise-router values in F3, which describe terrain a field Age does not have. */
    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    // getGenDepth / getSeaLevel / getMinY are deliberately NOT overridden: the superclass answers all three
    // from `generatorSettings()`, which is ours (see [settingsFor]). That makes `getSeaLevel` return the
    // *coerced* sea level, so a void sea answers the world floor rather than `Int.MIN_VALUE`.

    companion object {
        // Declared before CODEC, and it must be: a companion initialises top to bottom, so CODEC reading
        // this from below would read a null.
        // One set per territory. It was a map keyed by `GenerationStep.Carving` until vanilla collapsed
        // its two carving passes into one; only the air half was ever populated, so nothing was lost.
        private val CARVER_SETS: Codec<HolderSet<ConfiguredWorldCarver<*>>> =
            RegistryCodecs.homogeneousList(Registries.CONFIGURED_CARVER)

        /** A coherent Age, which tears nowhere. */
        const val NO_WOUNDS = 0.0

        /** So wounds are decorrelated from everything else the world seed drives. */
        private const val WOUND_SALT = 0x0D_15_EA5EL

        private const val SECTION = 16

        /**
         * How often a wound opens above the ground rather than under it.
         *
         * **Three in five**, so the common case is the one worth having: a tear hanging in the open where
         * somebody walks. The rest wait in the rock and the caves to be mined into.
         */
        private const val ABOVE_GROUND = 0.6

        /**
         * How high above the ground one may hang.
         *
         * Small on purpose: eye level and a little over. A wound floating dozens of blocks above a field
         * reads as something somebody placed, where one at head height reads as the world having failed.
         */
        private const val OVERHEAD = 6

        val CODEC: MapCodec<AgeChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                TerrainField.CODEC.fieldOf("field").forGetter { it.field },
                SeaFill.CODEC.forGetter { it.seaFill },
                // Optional so field Ages serialised before palettes existed still load.
                SurfaceRules.RuleSource.CODEC.optionalFieldOf("surface_rule", SurfacingStrategy.SUPPRESSED)
                    .forGetter { it.surfaceRule },
                // A list now that carving is set-valued, and still readable as the single map it
                // was: one carver set is exactly what an Age with one carving has.
                Codec.either(CARVER_SETS.listOf(), CARVER_SETS)
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .optionalFieldOf("carvers", listOf(HolderSet.direct())).forGetter { it.carvers },
                RegionMap.MAP_CODEC.codec().optionalFieldOf("underground", RegionMap.whole())
                    .forGetter { it.underground },
                // Absent means "a flat table at the sea's own level", derived at construction. A list, since
                // hydrology divides with the carving it belongs to, and still readable as the single table
                // it was — one table is exactly what an Age with one carving has.
                Codec.either(WaterTable.CODEC.codec().listOf(), WaterTable.CODEC.codec())
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .optionalFieldOf("water_table", emptyList()).forGetter { it.waterTables },
                // `StructureSet.CODEC` rather than a homogeneous list: this writes a key for one of
                // vanilla's and the whole set inline for one of ours, which a registry list cannot do.
                StructureSet.CODEC.listOf()
                    .optionalFieldOf("structure_sets", emptyList())
                    .forGetter { it.structureSets },
                // Absent for a one-biome Age, which has no climate to describe.
                NoiseGeneratorSettings.CODEC.optionalFieldOf("climate")
                    .forGetter { Optional.ofNullable(it.climate) },
                TerrainFill.CODEC.optionalFieldOf("terrain_fill", TerrainFill.PLAIN).forGetter { it.fill },
                // Absent means the layout every Age had before the band became a choice — see [VerticalWindow].
                VerticalWindow.CODEC.optionalFieldOf("window", VerticalWindow.DEFAULT).forGetter { it.window },
                // Absent for every Age without shape-cut caves, which is almost all of them.
                TerrainField.CODEC.optionalFieldOf("hollows").forGetter { Optional.ofNullable(it.hollows) },
                // Absent for every coherent Age, which is nearly all of them.
                Codec.DOUBLE.optionalFieldOf("wounds_per_chunk", NO_WOUNDS).forGetter { it.consequence.woundsPerChunk },
                // And absent for every Age that is merely flawed rather than coming apart.
                Codec.DOUBLE.optionalFieldOf("blight_per_day", NO_WOUNDS).forGetter { it.consequence.blightPerDay },
                // And absent for every Age that is not ending.
                Codec.INT.optionalFieldOf("collapse_tears", Collapse.NONE).forGetter { it.consequence.collapseTears },
                Codec.LONG.optionalFieldOf("written_at", 0L).forGetter { it.consequence.writtenAt },
            ).apply(instance) { biomes, field, seaFill, rule, carvers, underground, tables, structures, climate,
                                fill, window, hollows, wounds, blight, collapse, writtenAt ->
                AgeChunkGenerator(
                    biomes, field, seaFill, rule, carvers, underground, tables, structures,
                    climate.orElse(null), fill, window, hollows.orElse(null),
                    woundsPerChunk = wounds,
                    blightPerDay = blight,
                    collapseTears = collapse,
                    writtenAt = writtenAt,
                )
            }
        }

        /**
         * Our vertical layout in the shape vanilla's machinery expects — and, being a
         * [NoiseBasedChunkGenerator], the settings the game reads to build our [RandomState].
         *
         * That is what the subclass buys: `ChunkMap` branches on `instanceof NoiseBasedChunkGenerator` to
         * decide whether to build a [RandomState] from the generator's own settings or from
         * `NoiseGeneratorSettings.dummy()`, so a peer can never be handed a real climate sampler.
         *
         * A companion function rather than a property, because a superclass constructor call cannot see
         * the instance being built.
         */
        private fun settingsFor(
            seaFill: SeaFill,
            surfaceRule: SurfaceRules.RuleSource,
            climate: Holder<NoiseGeneratorSettings>?,
            fill: TerrainFill,
            window: VerticalWindow,
            field: TerrainField,
        ) = NoiseGeneratorSettings(
            NoiseSettings.create(window.minY, window.height, NOISE_CELLS_HORIZONTAL, NOISE_CELLS_VERTICAL),
            // The Age's own material, not a constant, which is what makes a surface rule fire over it:
            // `SurfaceSystem` recognises rock by comparing against these settings' default block, so
            // laying blackstone while declaring stone paints no surface at all. One block for the whole
            // Age, so several materials are recognised over [TerrainFill.representative] only.
            fill.representative,
            seaFill.representative,
            routerFor(climate, field),
            surfaceRule,
            emptyList(),
            // Coerced, because VOID's level is a sentinel rather than a height and this one is read as a
            // height by the superclass, by features and by the surface system.
            seaFill.level.coerceAtLeast(window.minY),
            /* disableMobGeneration = */ true,
            /* aquifersEnabled = */ false,
            /* oreVeinsEnabled = */ false,
            /* useLegacyRandomSource = */ false,
        )

        /** Half a chunk, so a chunk is judged by its middle rather than its corner. */
        private const val BLOCKS_PER_SECTION = 16

        /**
         * **The climate half of a named router, and nothing else.** `ChunkMap` builds the level's
         * [RandomState] from these settings, so this is what every consumer is handed — `applyCarvers`,
         * the inherited `createBiomes`, `/age biomes`.
         *
         * **The terrain half stays zero**, since our shape is the field tree's and any density read here
         * would describe a world that does not exist. **`depth` stays zero too**, which only looks
         * inconsistent: depth is ours ([co.voik.agesandtheart.worldgen.biome.ClimateDepth]), and vanilla's
         * own depth function describes vanilla's relief — a terrain function wearing a climate name.
         *
         * **One exception, and it earns itself: `initialDensityWithoutJaggedness`.** `NoiseChunk` reads that
         * one slot and nothing else to find a column's preliminary surface, which is what
         * `abovePreliminarySurface` — and so vanilla's whole rule for *not* dressing a cave floor as ground —
         * is built on. [RockDensity] answers it from the field tree. Left at zero it is not merely unused but
         * actively wrong, and the cost of that was grass growing underground.
         */
        private fun routerFor(climate: Holder<NoiseGeneratorSettings>?, field: TerrainField): NoiseRouter {
            val surface = RockDensity(field)
            val vanilla = climate?.value()?.noiseRouter() ?: return inertRouterOver(surface)
            val nothing = DensityFunctions.zero()
            return NoiseRouter(
                nothing, nothing, nothing, nothing,
                vanilla.temperature(), vanilla.vegetation(), vanilla.continents(), vanilla.erosion(),
                /* depth = */ nothing,
                vanilla.ridges(),
                /* initialDensityWithoutJaggedness = */ surface,
                nothing, nothing, nothing, nothing,
            )
        }

        /** A router describing nothing but where the rock stands — what an Age with no climate gets. */
        private fun inertRouterOver(surface: DensityFunction): NoiseRouter = DensityFunctions.zero().let { nothing ->
            NoiseRouter(
                nothing, nothing, nothing, nothing, nothing,
                nothing, nothing, nothing, nothing, nothing,
                surface, nothing, nothing, nothing, nothing,
            )
        }

        // Cell sizes for the layout description handed to vanilla's machinery; they match the
        // overworld's, which is the shape all of it is tuned around.
        private const val NOISE_CELLS_HORIZONTAL = 1
        private const val NOISE_CELLS_VERTICAL = 2

        // Carvers reach this many chunks out, so a cave system crosses borders. Vanilla's own figure.
        private const val CARVE_REACH_CHUNKS = 8

        /** What an aquifer is told about a block being *removed*, so it answers with a fluid or with air. */
        private const val HOLLOW = -1.0

        // What the field lays down before the palette repaints it.
        private val SOLID: BlockState = Blocks.STONE.defaultBlockState()
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
    }
}

/**
 * The same lookup, showing only the sets in [allowed] — what lets an Age name its structures without
 * giving up vanilla's own state builder. Tags pass through untouched, nothing consulting them here.
 */
private fun HolderLookup<StructureSet>.restrictedTo(allowed: List<Holder<StructureSet>>): HolderLookup<StructureSet> {
    val whole = this
    return object : HolderLookup<StructureSet> {
        override fun listElements(): Stream<Holder.Reference<StructureSet>> =
            whole.listElements().filter { it in allowed }

        override fun listTags(): Stream<HolderSet.Named<StructureSet>> = whole.listTags()

        override fun get(key: ResourceKey<StructureSet>): Optional<Holder.Reference<StructureSet>> =
            whole.get(key).filter { it in allowed }

        override fun get(tag: TagKey<StructureSet>): Optional<HolderSet.Named<StructureSet>> = whole.get(tag)
    }
}
