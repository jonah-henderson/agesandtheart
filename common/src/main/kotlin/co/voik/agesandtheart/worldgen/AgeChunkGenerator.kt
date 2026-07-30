package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Substance
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
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.carver.CarvingContext
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The generator every composed Age runs on: **vanilla's noise generator, with four deliberate exits.**
 *
 * The governing rule, and the thing to hold on to when changing this file (Jonah, 2026-07-28): *reuse
 * vanilla's pipeline wherever we can; where we cannot reuse, match it as closely as we can; and where we
 * must do something vanilla cannot express, take a **narrow, named** step out and come straight back.*
 * Being a subclass rather than a peer is what makes "reuse" the default instead of an aspiration — the
 * stages, the surface system, decoration, structures and mob spawning are all simply inherited.
 *
 * **The four exits, and why each is one.** Everything else here is vanilla's.
 *
 * 1. [fillFromNoise] — the only irreducible one. Our shapes are *analytic*: a [TerrainField] answers
 *    `(x, z) -> Spans`, where a density function answers `(x, y, z) -> double` sampled on a cell grid and
 *    **interpolated**. Interpolation is what rounds off a `Box`'s faces and a `Cylinder`'s rim, and the
 *    span form is what buys honest heightmaps and `subtract`. So the shape is ours and cannot be data.
 * 2. [getBaseHeight] and 3. [getBaseColumn] — not separate decisions, but the same one seen from two more
 *    angles. Whatever the fill did, these must *agree* with it, since structures and features place against
 *    them. A height contract that disagrees with the fill is precisely the bug that once crashed `plains`.
 * 4. [applyCarvers] — because an Age's carvers come from its **recipe**, not from its biome. Vanilla keys
 *    carving on the biome, which cannot express `carvers=caves,solid` over one dressing: it would need a
 *    pre-authored biome per dressing-and-carving pair, and the registry freezes before a writer says
 *    anything. This method is otherwise vanilla's own algorithm — one shared mask, carvers seeded by list
 *    index, the decision taken at a walk's origin — with exactly one substitution, the source of the list.
 *
 * Notably *not* an exit: dividing the world by territory is done with a custom [RegionRule], which is
 * vanilla's own registered extension point. Painting is `SurfaceRules`; biomes are a `BiomeSource`; both ride
 * the pipeline unmodified. (A `RegionBiomeSource` divided them too, until biomes stopped dividing at all —
 * see [co.voik.agesandtheart.age.aspect.Biomes].)
 *
 * [SpireChunkGenerator] stays a bespoke Tier-B peer alongside this, deliberately.
 * See `notes/terrain-architecture.md` for how the stages fit together.
 */
class AgeChunkGenerator(
    private val biomes: BiomeSource,
    private val field: TerrainField,
    private val seaFill: SeaFill,
    private val surfaceRule: SurfaceRules.RuleSource = Palette.PLAIN_STONE,
    /**
     * What is cut back out of the rock, one set per carving — **and they all run**, except where a
     * carving asserting the rock is *uncut* holds the ground (design §3.4, and [uncarvedTerritories]).
     *
     * Carving has been three things in turn, and the two it stopped being were wrong in ways worth keeping
     * written down. It began as a selection **per chunk**, which was an accident of how this was written
     * rather than a decision, and it made naming two carving silently exclusive. It then became a plain
     * **union** — every set running everywhere — on the argument that carving is populative (§3.2), since
     * carvers cut air out of rock and share one [CarvingMask], so two sets simply yield both cave systems.
     *
     * The union is right, and it is what still happens between any two carving that *cut* something:
     * porosity leaving small holes through a colonnade another carving stripped out is two ideas
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
     * Which structure sets this Age offers vanilla — a plain list rather than a `HolderSet`, because some of
     * them may be **ours**.
     *
     * A writer who emphasises a set rescales its placement, which forces the set to be rebuilt
     * ([co.voik.agesandtheart.worldgen.structure.StructureDensity]), and a rebuilt set is a *direct*
     * holder that no registry has heard of. `RegistryCodecs.homogeneousList` can only write keys, so the field
     * carries `StructureSet.CODEC` — which writes a key for a registered set and the set itself for one of
     * ours — and [createState] branches on whether any survived.
     */
    private val structureSets: List<Holder<StructureSet>> = emptyList(),
    /**
     * Where this Age's **climate** comes from — vanilla's overworld noise settings, normally — or null for
     * an Age that has no climate at all, which is what a single-biome demo preset is.
     *
     * Only the climate half of the named router is taken; see [routerFor]. Last in the list and defaulted so
     * the Tier-B presets, which are all one biome, need say nothing.
     */
    private val climate: Holder<NoiseGeneratorSettings>? = null,
    /**
     * What the rock is made of — vanilla's `default_block`, per territory of the **terrain's** map.
     *
     * Last and defaulted, so every Age that names no material is stone and byte-identical to before. See
     * [Substance] for why this is a fill rather than a surface rule, and why strata are the other way round.
     */
    private val substance: Substance = Substance.PLAIN,
    /**
     * The band of world this Age generates into — see [VerticalWindow] for why it is per-Age rather than one
     * constant. Last and defaulted, so every Age that does not care is laid out exactly as before.
     */
    private val window: VerticalWindow = VerticalWindow.DEFAULT,
) : NoiseBasedChunkGenerator(biomes, Holder.direct(settingsFor(seaFill, surfaceRule, climate, substance, window))) {

    /**
     * The same generator with one carving everywhere — what a Tier-B preset means, since a preset is
     * a whole hand-tuned world rather than an assembly of territories.
     */
    constructor(
        biomes: BiomeSource,
        field: TerrainField,
        seaFill: SeaFill,
        surfaceRule: SurfaceRules.RuleSource = Palette.PLAIN_STONE,
        carvers: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>,
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
     * The settings we handed the superclass, read back rather than kept twice — see [settingsFor].
     *
     * There is exactly one of these now, and vanilla reads the same object we do: it is where our sea
     * level, default fluid and surface rule live, and where `getSeaLevel`/`getMinY`/`getGenDepth` are
     * answered from. That is four overrides this file used to carry and no longer needs.
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

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val spans = field.columnSpans(worldX, worldZ)
                // Once per column, not once per block: which territory a column is in costs a noise
                // sample per sea, and the answer cannot change as you go down it.
                val sea = seaFill.blockAt(worldX, worldZ)

                for (y in window.minY..<window.topY) {
                    // The field decides, unless a structure standing here has an opinion of its own.
                    val isRock = adaptation?.verdictAt(worldX, y, worldZ) ?: spans.contains(y)
                    val state = when {
                        // What the rock *is*, which is vanilla's `default_block` and now ours — the surface
                        // system paints its skin over this afterwards, exactly as it does for vanilla.
                        isRock -> substance.blockAt(worldX, y, worldZ)
                        seaFill.fillsAt(y) -> sea
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
     * `?: seaFill.level` handed back [VOID][co.voik.agesandtheart.worldgen.field.SeaFill.NONE]'s
     * `Int.MIN_VALUE`, which `getFirstOccupiedHeight` then decremented straight into overflow.
     */
    override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState): Int {
        val counts = type.isOpaque()
        // One below the world, so a column with nothing this query counts simply answers the floor.
        val nothing = level.minBuildHeight - 1
        val rockTop = if (counts.test(substance.representative)) field.columnSpans(x, z).highestSolidY ?: nothing else nothing
        val mediumTop = if (counts.test(seaFill.blockAt(x, z))) seaFill.surfaceY ?: nothing else nothing
        return (maxOf(rockTop, mediumTop) + 1).coerceIn(level.minBuildHeight, level.maxBuildHeight)
    }

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val spans = field.columnSpans(x, z)
        val sea = seaFill.blockAt(x, z)
        val column = Array(window.height) { index ->
            val y = window.minY + index
            when {
                spans.contains(y) -> substance.blockAt(x, y, z)
                seaFill.fillsAt(y) -> sea
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
     * Every carving carving's carvers together, per step — the union described on [carvers].
     *
     * Built once rather than per chunk, and **in composition order**, which is not incidental: a carver is
     * seeded by its *index* in the list it is run from, so a stable order is what keeps an Age reproducible.
     * `distinct()` because two carving naming the same vanilla carver should run it once, not twice with
     * different seeds — that would double its density rather than combine two ideas.
     */
    private val carving: Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<*>>>> by lazy {
        GenerationStep.Carving.entries.associateWith { step ->
            carvers.flatMap { perSubsurface -> perSubsurface[step]?.toList().orEmpty() }.distinct()
        }
    }

    /**
     * The territories where **nothing starts a walk** — the carving that cut nothing anywhere.
     *
     * This is the whole of how a division and a union coexist, and the line it draws is §3.2's own, read one
     * level down at the preset instead of at the parameter. `caves`, `porous` and `weathered` each assert
     * that something *exists* underground, which is a populative claim, so they accumulate and their union is
     * the right answer: porosity leaving small holes through a colonnade that something else stripped out is
     * two ideas combining, not two ideas competing. `solid` asserts an *absence* — that the rock is uncut —
     * and an absence cannot accumulate with anything. It is predicative, so it contends, and what it contends
     * for is ground.
     *
     * Inferred rather than declared, and exactly rather than heuristically: a carving that cuts nothing at
     * any step *is* one asserting the rock is uncut, so a carving added by a datapack lands on the right
     * side of this without having to say anything.
     */
    private val uncarvedTerritories: Set<Int> by lazy {
        fun cutsNothing(carving: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>): Boolean =
            carving.values.all { step -> step.size() == 0 }
        carvers.indices.filter { territory -> cutsNothing(carvers[territory]) }.toSet()
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
            // Ourselves. [CarvingContext] demands a concrete [NoiseBasedChunkGenerator], which we now are —
            // this used to be a whole second generator built for no reason but to satisfy the type.
            this,
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
     * explicit list but nails that seed to zero — which would put every Age's strongholds at the same
     * bearings. Narrowing the *lookup* gets both: our sets, and this Age's own rings.
     *
     * **So both are used, and what decides is whether the Age changed a set's density.** A lookup can only ever
     * show `Holder.Reference`s, and asking for more villages than usual means a new placement, so a new
     * [StructureSet] object, which no registry has heard of — see
     * [co.voik.agesandtheart.worldgen.structure.StructureDensity]. `createForFlat` takes holders directly and so
     * accepts ours.
     *
     * The cost lands only on the Ages that used the capability: **one that rescaled a set gets vanilla's
     * superflat ring bearings** for any concentric placement it kept, which in practice means its strongholds
     * sit where every other such Age's do. Adding, excluding or singling out sets moves no placement at all, so
     * every Age that only does that — including every Age written before density existed — takes the first
     * branch untouched, which is also what keeps the parity set byte-identical.
     *
     * **Measured, rather than reasoned about** (2026-07-29): two Ages on one recipe and seed, one of them with a
     * rescaled set, put their nearest stronghold at `[1008, 912]` and `[-128, -1584]`.
     *
     * **Accepted deliberately, and here is the upgrade if it ever grates** (Jonah, 2026-07-29 — *"if that's how
     * they work in super flat worlds, what we're doing is not too terribly different"*). The whole branch
     * disappears if a runtime-built set can be made to look registered: `Holder.Reference.createStandAlone` is
     * public, so forging one needs `Holder.Reference.bindValue` widened, and this function then collapses back
     * to a single `createForNormal` call with [restrictedTo] *injecting* our holders instead of filtering. Two
     * things to know before doing it: `bindTags` is package-private and a forged reference leaves `tags` unbound,
     * so anything calling `is(TagKey)` on it throws — that wants checking rather than assuming; and it is a
     * reach into the registry's own binding machinery, deeper than anything else this mod widens.
     *
     * **It is a pure optimisation, invisible to the design** — same words, same recipes, same worlds, only the
     * ring bearings differ — so it can be done any time, or never.
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
    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    /** The superclass renders noise-router values in F3, which describe terrain a field Age does not have. */
    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    // getGenDepth / getSeaLevel / getMinY are deliberately NOT overridden: the superclass answers all three
    // from `generatorSettings()`, which is ours (see [settingsFor]). Note this makes `getSeaLevel` return the
    // *coerced* sea level, so a VOID sea answers the world floor rather than handing out `Int.MIN_VALUE`
    // — the sentinel-escaping-into-arithmetic bug that `getBaseHeight` documents having already caused once.

    companion object {
        // Declared before CODEC, and it has to be: a companion initialises top to bottom, so CODEC
        // reading this from below would read a null. Cost us a server boot to find, because nothing
        // offline touches the generator's codec.
        private val CARVER_SETS: Codec<Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>>> =
            Codec.unboundedMap(
                GenerationStep.Carving.CODEC,
                RegistryCodecs.homogeneousList(Registries.CONFIGURED_CARVER),
            )

        val CODEC: MapCodec<AgeChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                TerrainField.CODEC.fieldOf("field").forGetter { it.field },
                SeaFill.CODEC.forGetter { it.seaFill },
                // Optional so field Ages serialised before palettes existed still load.
                SurfaceRules.RuleSource.CODEC.optionalFieldOf("surface_rule", Palette.PLAIN_STONE)
                    .forGetter { it.surfaceRule },
                // A list now that carving is set-valued, and still readable as the single map it
                // was: one carver set is exactly what an Age with one carving has.
                Codec.either(CARVER_SETS.listOf(), CARVER_SETS)
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .optionalFieldOf("carvers", listOf(emptyMap())).forGetter { it.carvers },
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
                // `StructureSet.CODEC` rather than a homogeneous list, because an Age that emphasised or
                // excluded one structure out of a set carries a set of *ours*: this writes a key for one of
                // vanilla's and the whole set inline for one of ours, which a registry list cannot do.
                StructureSet.CODEC.listOf()
                    .optionalFieldOf("structure_sets", emptyList())
                    .forGetter { it.structureSets },
                // Absent for a one-biome Age, which has no climate to describe.
                NoiseGeneratorSettings.CODEC.optionalFieldOf("climate")
                    .forGetter { Optional.ofNullable(it.climate) },
                Substance.CODEC.optionalFieldOf("substance", Substance.PLAIN).forGetter { it.substance },
                // Absent means the layout every Age had before the band became a choice — see [VerticalWindow].
                VerticalWindow.CODEC.optionalFieldOf("window", VerticalWindow.DEFAULT).forGetter { it.window },
            ).apply(instance) { biomes, field, seaFill, rule, carvers, underground, tables, structures, climate,
                                substance, window ->
                AgeChunkGenerator(
                    biomes, field, seaFill, rule, carvers, underground, tables, structures,
                    climate.orElse(null), substance, window,
                )
            }
        }

        /**
         * Our vertical layout, in the shape vanilla's machinery expects — and, now that we *are* a
         * [NoiseBasedChunkGenerator], the settings the game itself reads to build our [RandomState].
         *
         * That is the whole reason the subclass is worth its access-widener line: `ChunkMap` branches on
         * `instanceof NoiseBasedChunkGenerator` to decide whether to build a [RandomState] from the
         * generator's own settings or from `NoiseGeneratorSettings.dummy()`. A peer can never be handed a
         * real one, and everything that wants real climate has to be built privately around that fact.
         *
         * The router is still [INERT_ROUTER]: nothing here describes terrain, because [fillFromNoise] does.
         * Everything else is our own constants rather than borrowed from `dummy()`, so our layout and this
         * description cannot silently drift apart.
         *
         * A companion function, not a property, because a superclass constructor call cannot see the
         * instance being built. It takes exactly what it needs and stays pure.
         */
        private fun settingsFor(
            seaFill: SeaFill,
            surfaceRule: SurfaceRules.RuleSource,
            climate: Holder<NoiseGeneratorSettings>?,
            substance: Substance,
            window: VerticalWindow,
        ) = NoiseGeneratorSettings(
            NoiseSettings.create(window.minY, window.height, NOISE_CELLS_HORIZONTAL, NOISE_CELLS_VERTICAL),
            // **The Age's own material, not a constant, and this is what makes a surface rule fire over it.**
            // `SurfaceSystem` recognises rock by comparing a block against these settings' default block, so
            // laying blackstone while declaring stone makes it see a column of something-else and paint no
            // surface at all — measured: `dressing=overworld` on blackstone came out bare blackstone where it
            // should have taken vanilla's grass. One block for the whole Age, since settings are one object, so
            // an Age of several materials is recognised over its [Substance.representative] only.
            substance.representative,
            seaFill.representative,
            routerFor(climate),
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
         * A noise router that describes nothing: every one of its density functions is zero. The field
         * tree is what shapes our terrain, so this exists only to fill a required aspect — and it is
         * deliberately *inert* rather than merely unused, so nothing that consults it can be confidently
         * wrong about terrain that does not exist. (Vanilla's own `NoiseRouterData.none()` is protected.)
         */
        /**
         * **The climate half of a named router, and nothing else** — the point of being a subclass.
         *
         * `ChunkMap` builds the level's [RandomState] from these settings, so what goes in here is what
         * every consumer is handed: `applyCarvers`, the inherited `createBiomes`, `/age biomes`. Putting
         * vanilla's real climate functions here means that sampler describes a real climate, seeded with
         * this Age's own seed — Fantasy sets the dimension seed from the recipe, so the wiring lands on the
         * same number [co.voik.agesandtheart.worldgen.biome.AgeBiomeSource] has been using privately.
         *
         * **The terrain half stays zero, deliberately.** Our shape is the field tree's, so any density a
         * consumer read here would describe a world that does not exist — the existing [INERT_ROUTER]
         * reasoning, kept exactly where it still earns its keep and dropped where it does not.
         *
         * **`depth` stays zero too**, which is the one that looks inconsistent and is not: depth is *ours*
         * ([co.voik.agesandtheart.worldgen.biome.ClimateDepth]), measured against our terrain rather than
         * vanilla's, and vanilla's own depth function describes vanilla's relief. So it is a terrain
         * function wearing a climate name.
         */
        private fun routerFor(climate: Holder<NoiseGeneratorSettings>?): NoiseRouter {
            val vanilla = climate?.value()?.noiseRouter() ?: return INERT_ROUTER
            val nothing = DensityFunctions.zero()
            return NoiseRouter(
                nothing, nothing, nothing, nothing,
                vanilla.temperature(), vanilla.vegetation(), vanilla.continents(), vanilla.erosion(),
                /* depth = */ nothing,
                vanilla.ridges(),
                nothing, nothing, nothing, nothing, nothing,
            )
        }

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
 * without giving up vanilla's own state builder (see [AgeChunkGenerator.createState] for why).
 *
 * Only [listElements] is narrowed for `createForNormal`'s sake; [get] is narrowed too so the view stays
 * honest for anything else that reads it, while tags pass through untouched — nothing consults them here,
 * and a half-filtered tag would be a worse answer than the real one.
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
