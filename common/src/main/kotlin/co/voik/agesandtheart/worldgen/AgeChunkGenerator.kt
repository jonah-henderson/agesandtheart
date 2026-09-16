package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.TerrainFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.WaterTable
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.RegistryCodecs
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Biomes
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
 *    with the fill, since structures and features place against them. **Which is why an [Overlay] is
 *    honoured by all three and not just the fill**: a cone raised only in the fill would have vanilla put
 *    the trees and the villages on the ground *inside* it.
 * 4. [applyCarvers], because an Age's carvers come from its **recipe**, not its biome. Otherwise vanilla's
 *    own algorithm, with one substitution: the source of the list.
 *
 * Not an exit: dividing the world by territory goes through a custom `RegionRule`, vanilla's own
 * registered extension point. See `notes/terrain-architecture.md` for how the stages fit together.
 */
class AgeChunkGenerator(
    private val biomes: BiomeSource,
    /** Who answers for the rock — the field tree, or vanilla's own router. See [AgeRock]. */
    val rock: AgeRock,
    private val writtenSea: SeaFill,
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
    private val lives: Spawns.Living? = null,
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
     * How many more open per chunk with each day the Age has stood — **the worsening** (design §5.2.1).
     *
     * Where [woundsPerChunk] is how holed the book made it, this is how holed it *becomes*. Unbounded on
     * purpose: a ceiling would promise the Age can be outlasted, and the only question the register asks
     * is how long you stay. Zero for every Age that is merely flawed rather than coming apart.
     */
    woundsPerDay: Double = NO_WOUNDS,
    /**
     * How many tears per cell this Age's floor is cut with — **collapse** (design §5.3). Zero for every
     * Age that is not ending; they widen themselves once cut.
     */
    collapseTears: Int = Collapse.NONE,
    /**
     * The overworld tick this Age was written on, so [woundsPerDay] has something to count from.
     *
     * **On the generator because a chunk generated late must come out as torn as its neighbours**, which is
     * §5.4's derived-clock escape: a chunk that has never existed has no blocks to be legible from, so the
     * generator and the fast-forward read the same function rather than one of them inferring.
     */
    writtenAt: Long = 0L,
    /**
     * Shape of ours laid over whatever rock this Age wears — see [Overlay].
     *
     * **Only the vanilla-rock path reads it here.** A landform of ours has already folded the same object
     * into its own field tree, where it costs nothing; this is the other half of that bargain, and what
     * stops a word like `volcano` being accepted and then ignored.
     */
    val overlay: Overlay = Overlay.NONE,
) : NoiseBasedChunkGenerator(
    biomes,
    when (rock) {
        is AgeRock.Vanillas -> rock.settings
        is AgeRock.Ours -> Holder.direct(settingsFor(writtenSea, surfaceRule, climate, fill, window, rock.landform, rock.hollows))
    },
) {

    /**
     * How far under the level it was written with this Age's sea currently stands, in blocks.
     *
     * **Zero for every Age but a drowning one**, and zero again until one begins — the phenomenon carries an
     * Age *above* the sea its recipe names rather than up to it (design §5.2), so the written sea is the
     * floor and this is how far over it the water now stands.
     *
     * `var`, and volatile, for the reason [overlay] is: a generator is built once when its Age is opened
     * and lives as long as the dimension does, so anything a phenomenon changes about it has to be settable
     * afterwards. [Deluge] sets it from the Age's counter on every tick of `Happenings`.
     */
    @Volatile
    private var risenBy: Int = 0

    @Volatile
    private var standingSea: SeaFill = writtenSea

    /**
     * The sea as it stands **now** — which is the written one in every Age that is not drowning.
     *
     * Everything that generates ground reads this rather than the written sea, so a chunk made halfway
     * through a deluge comes out at the level the rest of the Age is at. That coherence is the whole reason
     * §5.4 allows the deluge a stored number at all: a chunk generated three thousand blocks out has to
     * flood to the same line as the one you are standing in, and no sampler could promise that.
     */
    val seaFill: SeaFill get() = standingSea

    /**
     * Stand the sea [blocks] over what was written.
     *
     * Cached rather than copied per call: this is read once per column of every chunk generated, and a
     * `data class` copy there would allocate through the floor.
     */
    fun standAbove(blocks: Int) {
        val wanted = blocks.coerceAtLeast(0)
        if (wanted == risenBy) return
        risenBy = wanted
        // A sea of NONE has `Int.MIN_VALUE` for a level — a sentinel, not a height — and moving a sentinel
        // is how you get an Age with a sea at minus two billion.
        standingSea = if (wanted == 0 || writtenSea.surfaceY == null) {
            writtenSea
        } else {
            writtenSea.copy(level = writtenSea.level + wanted)
        }
    }

    /** The rock a cave system was cut out of, or null where there is none — see [AgeRock.Ours.hollows]. */
    val hollows: TerrainField? get() = (rock as? AgeRock.Ours)?.hollows

    /**
     * The preliminary surface vanilla's surface system is told at this column — read from the router as
     * `NoiseChunk` reads it, so this is the number the frozen-ocean icebergs stop at. See [PreliminarySurface].
     */
    fun preliminarySurfaceAt(worldX: Int, worldZ: Int): Int = Math.floor(
        generatorSettings().value().noiseRouter().preliminarySurfaceLevel()
            .compute(DensityFunction.SinglePointContext(worldX, 0, worldZ)),
    ).toInt()

    /** The surface the aquifer reads, which is the one vanilla's surface system reads — as vanilla's aquifer does. */
    private fun surfaceForTheAquifer(): WaterTable.SurfaceAt = WaterTable.SurfaceAt(::preliminarySurfaceAt)

    /**
     * Whether a point lies in deep dark, which vanilla's aquifer never floods. Vanilla asks its erosion and
     * depth; our depth is our own, so this asks the biome those two would have chosen.
     */
    private fun deepDarkIn(randomState: RandomState): WaterTable.DeepDarkAt = WaterTable.DeepDarkAt { worldX, worldY, worldZ ->
        biomeSource.getNoiseBiome(
            QuartPos.fromBlock(worldX),
            QuartPos.fromBlock(worldY),
            QuartPos.fromBlock(worldZ),
            randomState.sampler(),
        ).`is`(Biomes.DEEP_DARK)
    }

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
    var consequence: Consequence = Consequence(woundsPerChunk, woundsPerDay, collapseTears, writtenAt)
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
        val living = lives ?: return offered
        val world = structures.level as? Level
        return living.at(
            biome.unwrapKey().orElse(null)?.identifier(),
            category,
            Spawns.Situation(
                at = at,
                skyIsOpen = skyIsOpenAt(structures, at),
                brightness = world?.getMaxLocalRawBrightness(at) ?: FULLY_LIT,
            ),
            offered,
        )
    }

    /**
     * Whether an attempt at [at] is happening out under the sky rather than inside the rock — the gate for
     * the creatures vanilla never spawns and so never wrote a placement rule for (see `Spawning`).
     *
     * Asked of the Age's **own field** where there is one: the shape already knows where its ground stops,
     * and it is the same answer `getBaseHeight` gives a structure.
     *
     * **An Age wearing vanilla's rock asks the level instead, and must.** Answering `true` there made the
     * gate inert for every book that named no landform — which is every book the template's rock reaches —
     * so a written dragon was offered inside the rock as readily as over it. The level is the structure
     * manager's, which is the only route to one from here.
     */
    private fun skyIsOpenAt(structures: StructureManager, at: BlockPos): Boolean {
        val ours = rock as? AgeRock.Ours
            ?: return at.y >= structures.level.getHeight(Heightmap.Types.WORLD_SURFACE, at.x, at.z)
        val highestRock = ours.field.columnSpans(at.x, at.z).highestSolidY ?: return true
        return at.y > highestRock
    }

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    // A sea level of Int.MIN_VALUE means "no sea" (SeaFill.NONE); the machinery below wants a
    // real height, and for a void sea the value is inert anyway since nothing ever fills.
    private val seaLevel = writtenSea.level.coerceAtLeast(window.minY)

    /**
     * The topmost block the sea itself occupies — vanilla's own convention, where `seaLevel` is the level
     * water is poured *below*. What `DeepWater.seaAt` measures its unbroken span down from.
     */
    private val seaSurfaceY get() = seaFill.surfaceY ?: window.minY

    /**
     * The plane under which this Age's water is abyss — see `DeepWater.lineIn`.
     *
     * Below `window.minY` where the Age has no sea worth the name, which is the same as "never".
     */
    private val abyssLine get() = seaFill.surfaceY?.let(DeepWater::lineBelow) ?: (window.minY - 1)

    /**
     * Whether this column genuinely has an abyss over it — **the sea's own water standing at the line**.
     *
     * **The plane alone was not the rule, and taking it for one put deep water in ordinary Ages** (Jonah,
     * walked 2026-09-10). `abyssLine` is the waterline less eighty, which for a sea at the usual height is
     * somewhere around y=-18 — so every flooded cave below that came out abyssal, and anything waterlogged
     * down there came out holding deep water. A patch of glow lichen on a cave ceiling then poured a column
     * of it into the dark.
     *
     * The line says *where* an abyss would be and this says *whether there is one*: the sea has to reach
     * this column at that depth, which is exactly the eighty blocks of water the rule claims. It keeps the
     * walked finding it was written for — an aquifer pocket under a real sea floor is still abyss, because
     * the sea stands over that column at the line even though the pocket itself is sealed.
     */
    private fun abyssReachesAt(chunk: ChunkAccess, worldX: Int, worldZ: Int): Boolean {
        val ours = rock as? AgeRock.Ours ?: return false
        if (ours.field.columnSpans(worldX, worldZ).contains(abyssLine)) return false
        if (!seaFill.fillsAt(abyssLine, seaFill.drynessAt(worldX, worldZ), seaFill.wetnessAt(worldX, worldZ))) {
            return false
        }
        return abyssBelongsIn(chunk, ours, worldX, worldZ)
    }

    /**
     * **Whether this column is somewhere an abyss may stand at all**, which is the question the plane and
     * the water table together could not answer.
     *
     * The plane says *where* deep water would be and the table says *whether* any stands there. Neither can
     * tell the floor of a trench from a flooded cavern — the default table is flat, so every space the shape
     * cut below the waterline is wet, and the plane is one Y for the whole Age. In an Age with caves that
     * plane slices straight through the cave system and lays a horizontal sheet of deep water across
     * everything it meets, eighty blocks under a sea that is nowhere near (Jonah, walking V3, 2026-09-11:
     * *"the caves are just playing havoc with it"*).
     *
     * Two tests, and the first is the one that does the work:
     *
     * - **The column has to be seabed.** If this Age's own ground stands *above* the waterline here, you are
     *   under land and not under sea, and nothing below you is abyss however deep it goes. This keeps the
     *   walked finding it has to keep — a sealed aquifer pocket under a real sea floor is still abyss, since
     *   the ground there is below the waterline — while refusing every cave under a hill.
     * - **And the biome has to allow it** (`#agesandtheart:no_abyss`). Weaker, and deliberately kept as the
     *   tunable half: a pack adding a cave biome should be able to keep the abyss out without touching code.
     *   Asked at the abyss line rather than at the surface, because the biome that matters is the one down
     *   where the water would be.
     *
     * Read off the chunk, which is safe here and only here: `BIOMES` runs before `NOISE`, so the biomes are
     * settled by the time the fill asks.
     */
    private fun abyssBelongsIn(chunk: ChunkAccess, ours: AgeRock.Ours, worldX: Int, worldZ: Int): Boolean {
        // **The biome first, because it is an array read and the other is a whole field tree.** This runs
        // per column of the fill, and the landform of a volcanic Age is the most expensive thing in it.
        val biome = chunk.getNoiseBiome(
            QuartPos.fromBlock(worldX),
            QuartPos.fromBlock(abyssLine),
            QuartPos.fromBlock(worldZ),
        )
        if (biome.`is`(DeepWater.NO_ABYSS)) return false
        // The land rather than the whole rock, as `getBaseHeight` reads it: a lid over a sealed Age is not
        // a sea floor, and reading it here would call every column of such an Age dry land.
        val ground = ours.landform.columnSpans(worldX, worldZ).highestSolidY
        return ground == null || ground < seaSurfaceY
    }

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
        val ours = rock as? AgeRock.Ours
            // **Vanilla's rock, and then ours on top of it.** Chained rather than done afterwards so it
            // lands inside the noise stage, which is what gets it surfaced: `buildSurface` runs next and
            // paints whatever it finds, so a cone comes out with the biome's own grass or snow or
            // netherrack on it. A feature could not — it may write only one chunk past its own, and it
            // would arrive after the surface was already decided.
            ?: return super.fillFromNoise(blender, randomState, structureManager, chunk)
                .thenApply { filled -> filled.also { layOverlayInto(it) } }
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
        val band = ColumnBand(chunkMinX, chunkMinZ, ours.field, seaFill, ours.hollows)
        // A second cursor: `DeepWater.airOpenedOver` walks back down the column, and sharing `cursor` with
        // it would move the position the fill is about to write to.
        val reach = BlockPos.MutableBlockPos()
        // One per chunk, because the object carries a column memo — the same reason carving mints its own.
        val water = WaterTable.aquiferFor(tables, ours.field, surfaceForTheAquifer(), deepDarkIn(randomState), underground)

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val at = band.indexOf(localX, localZ)
                val spans = band.spans(at)
                val sea = seaFill.blockAt(worldX, worldZ)
                // **Whether an abyss stands over this column at all**, asked once here rather than of
                // every block. See [abyssReaches] — without it, any Age with a sea grew an abyss in its
                // deepest caves, eighty blocks under a waterline that never reached them.
                // The band answers the geometry and the water. Whether this column is *sea* at all is the
                // third question and neither of those can answer it — see [abyssBelongsIn], which is what
                // keeps the plane from slicing a sheet of deep water through an Age's caves.
                val abyssal = !spans.contains(abyssLine) && band.fills(at, abyssLine) &&
                    abyssBelongsIn(chunk, ours, worldX, worldZ)

                // **Whether any lava the shape carries is near enough to line**, asked once for the whole
                // column — see [MoltenLining]. False everywhere in an Age with no carried body, which is
                // what keeps the two questions below off the hot path of every ordinary world.
                val couldLine = band.carriesNear(localX, localZ)

                // Whether the block just below came out empty, so a fluid placed on top of nothing can be
                // told to fall. Nothing is below the window's floor, which is as good as open for this.
                var nothingBelow = true

                for (y in window.minY..<window.topY) {
                    // The field decides, unless a structure standing here has an opinion of its own.
                    val isRock = adaptation?.verdictAt(worldX, y, worldZ) ?: spans.contains(y)
                    val askedTheAquifer = !isRock && band.carried(at, y) == null && band.hollow(at, y)
                    val state = when {
                        // What the rock *is*, which is vanilla's `default_block` and now ours — the surface
                        // system paints its skin over this afterwards, exactly as it does for vanilla.
                        // **Unless it is holding lava in**, in which case it is obsidian: see [MoltenLining]
                        // for why a volcano that demolishes its own caldera used to leave the lake in the air.
                        isRock -> MoltenLining.rockAt(
                            moltenAbove = couldLine && MoltenLining.isMolten(band.carried(at, y + 1)),
                            moltenBeside = couldLine && band.moltenBeside(localX, localZ, y),
                            otherwise = fill.blockAt(worldX, y, worldZ),
                        )
                        // A body the shape carries, which answers before either of the two below it: a
                        // caldera's lava is neither groundwater nor the sea, and both of those would take
                        // the space and put the wrong substance in it.
                        band.carried(at, y) != null -> band.carried(at, y)
                        // Inside the rock a cave system opened: the table answers, not the waterline. Asked
                        // before the sea, since this space is under it and the sea would otherwise take it.
                        band.hollow(at, y) -> heldBackFrom(
                            water.computeSubstance(DensityFunction.SinglePointContext(worldX, y, worldZ), HOLLOW),
                            band,
                            localX,
                            localZ,
                            y,
                            worldX,
                            worldZ,
                        )
                        band.fills(at, y) -> sea
                        else -> null
                    }
                        // **Every water this column produced, not only the sea's.** The aquifer answers
                        // before the sea does, so a cave flooded from the water table under an abyss came
                        // out as ordinary water — which read as great pockets of plain sea scattered
                        // through the deep (Jonah, walked 2026-09-10). Wrapping the whole `when` is what
                        // makes the boundary one plane rather than one per source of water.
                        ?.let { if (abyssal) DeepWater.seaAt(y, abyssLine, it) else it }
                        ?.takeUnless { it.isAir }
                    if (state == null) {
                        // Air over the abyss takes the pressure out of the water under it — see
                        // `DeepWater.standsAt`. The fill runs bottom-up, so what this affects is already in
                        // the chunk and is rewritten rather than predicted.
                        if (abyssal && y <= abyssLine) DeepWater.airOpenedOver(chunk, reach, worldX, y, worldZ)
                        nothingBelow = true
                        continue
                    }
                    cursor.set(worldX, y, worldZ)
                    // Water the aquifer placed gets its first tick exactly where vanilla's would: where two of
                    // its cells meet with different water. **Anything else with anywhere to go is asked to go
                    // there.** Where a channel drops faster than its own surface does, the shape leaves water
                    // standing over a step or against a wall of open air — and no arrangement of *levels* can
                    // fix that, because the gap is where the water is moving. Marked for post-processing,
                    // vanilla gives the source its first tick on load and it finds its own way down.
                    val wantsToMove = if (askedTheAquifer) {
                        water.shouldScheduleFluidUpdate()
                    } else {
                        nothingBelow || band.openBeside(localX, localZ, y)
                    }
                    if (wantsToMove && !state.fluidState.isEmpty) chunk.markPosForPostprocessing(cursor)
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
        private val bodies = arrayOfNulls<List<Spans>>(SIDE * SIDE)

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
                    bodies[at] = seaFill.carriedAt(worldX, worldZ)
                }
            }
        }

        /** Whether this level is inside the rock a cave system was cut from — see [hollows]. */
        fun hollow(at: Int, y: Int): Boolean = hollowness[at]!!.contains(y)

        /** What a body the shape carries puts here, if one reaches — see [StandingFluid]. */
        fun carried(at: Int, y: Int): BlockState? = seaFill.carriedAt(y, bodies[at]!!)

        fun indexOf(localX: Int, localZ: Int): Int = (localX + MARGIN) * SIDE + (localZ + MARGIN)

        fun spans(at: Int): Spans = spans[at]!!

        fun fills(at: Int, y: Int): Boolean = seaFill.fillsAt(y, dryness[at]!!, wetness[at]!!)

        /**
         * Whether this column or any beside it carries a body at all — the gate that keeps the lining
         * questions off every column of every Age that has no lava in it, which is almost all of them.
         * Asked once per column rather than once per block.
         */
        fun carriesNear(localX: Int, localZ: Int): Boolean =
            bodies[indexOf(localX, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX - 1, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX + 1, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX, localZ - 1)]!!.isNotEmpty() ||
                bodies[indexOf(localX, localZ + 1)]!!.isNotEmpty()

        /** Whether a carried body puts lava at this level in any of the four columns beside this one. */
        fun moltenBeside(localX: Int, localZ: Int, y: Int): Boolean =
            isMolten(indexOf(localX - 1, localZ), y) || isMolten(indexOf(localX + 1, localZ), y) ||
                isMolten(indexOf(localX, localZ - 1), y) || isMolten(indexOf(localX, localZ + 1), y)

        private fun isMolten(at: Int, y: Int): Boolean = MoltenLining.isMolten(carried(at, y))

        /**
         * Whether the **sea** stands against this block — beside it, or over it.
         *
         * The sea's own space is what the aquifer does not own: not rock, not a hollow of ours, and under
         * the waterline. Above is checked as well as beside, because a sea lying on the roof of a dry cave
         * falls into it the moment the chunk is ticked.
         */
        fun seaTouching(localX: Int, localZ: Int, y: Int): Boolean =
            isSea(indexOf(localX, localZ), y + 1) ||
                isSea(indexOf(localX - 1, localZ), y) || isSea(indexOf(localX + 1, localZ), y) ||
                isSea(indexOf(localX, localZ - 1), y) || isSea(indexOf(localX, localZ + 1), y)

        private fun isSea(at: Int, y: Int): Boolean =
            !spans[at]!!.contains(y) && !hollow(at, y) && carried(at, y) == null && fills(at, y)

        /** Whether any of the four columns beside this one left this level open. */
        fun openBeside(localX: Int, localZ: Int, y: Int): Boolean =
            isOpen(indexOf(localX - 1, localZ), y) || isOpen(indexOf(localX + 1, localZ), y) ||
                isOpen(indexOf(localX, localZ - 1), y) || isOpen(indexOf(localX, localZ + 1), y)

        private fun isOpen(at: Int, y: Int): Boolean =
            !spans[at]!!.contains(y) && !fills(at, y) && carried(at, y) == null

        private companion object {
            const val MARGIN = 1
            const val SIDE = 16 + 2 * MARGIN
        }
    }

    // --- Surface height contract: honest answers so structures/features land on the terrain. ---

    /**
     * **A wall of rock where a dry cave meets the sea** — vanilla's aquifer barrier, in the shape this
     * generator's seams actually take.
     *
     * **Two authorities own the water, and the fault is on their border** (found with `/age probe`,
     * 2026-09-11). The aquifer owns every hollow of ours and may answer *dry*; the sea fills anything below
     * the waterline the aquifer does not own. Where one cave crosses that line, the cave side comes out a
     * pool at its own level — or nothing — and the other side comes out sea at the waterline, and the two
     * meet at a **face**. A walk finds that as a slab of sea jutting into a cave, or a curtain down the
     * middle of one, and then watches it pour: the fill marks a perched fluid for post-processing and
     * vanilla gives it its first tick on load.
     *
     * Three attempts inside the aquifer — per room, per cell, a continuous level — all missed, because none
     * of them is about the border.
     *
     * **Vanilla's answer is not to reconcile the two; it is to separate them.** Where two of its aquifers
     * disagree it computes a pressure between them and puts **stone** in the gap, so a player never sees
     * water standing against air — they see a wall, which is what a wall between two water tables looks
     * like. This is that rule with our own two authorities in place of two of its cells.
     *
     * **Only where the cave came out dry.** A cave the aquifer filled is already water and wants no wall;
     * what needs one is emptiness with a sea leaning on it. And only against the *sea* — a dry cave beside
     * another dry cave is just a cave.
     */
    private fun heldBackFrom(
        answer: BlockState?,
        band: ColumnBand,
        localX: Int,
        localZ: Int,
        y: Int,
        worldX: Int,
        worldZ: Int,
    ): BlockState? {
        // Null is the aquifer's own barrier between two levels of water, and the rock stays — as it does for
        // a carver handed the same answer.
        if (answer == null) return fill.blockAt(worldX, y, worldZ)
        // The aquifer put something here, so there is nothing to hold back.
        if (!answer.isAir) return answer
        if (!band.seaTouching(localX, localZ, y)) return answer
        return fill.blockAt(worldX, y, worldZ)
    }

    /**
     * Write this Age's [Overlay] into a chunk vanilla has just filled.
     *
     * **The column is done in one pass, top-level order: raise, hollow, pour.** Raising first means a
     * caldera can be cut out of the cone that was only just built, and pouring last means a lake stands in
     * whatever the other two left — which is the same order [Overlay.foldedInto] composes the fields in, so
     * the two paths cannot come to disagree.
     *
     * **Written through the chunk rather than the level**, as `Collapse.carveInto` is and for the same
     * reason: this runs while the chunk is still being built, where the neighbour and lighting bookkeeping
     * `setBlock` carries has nothing to do.
     *
     * **Vanilla's carvers run after this and may cut straight through a cone**, which is accepted (Jonah,
     * 2026-09-11): a volcano with a cave through it is a volcano with a cave through it.
     */
    private fun layOverlayInto(chunk: ChunkAccess) {
        if (overlay.isEmpty) return
        val rockHere = generationSettings.defaultBlock
        val oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        val worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
        val cursor = BlockPos.MutableBlockPos()
        val lowest = chunk.minY
        val highest = chunk.minY + chunk.height - 1
        // **A band with a column of margin, as the field path keeps**, and for the one reason that needs
        // it: lining a body of lava asks what stands *beside* a block as well as over it, and the columns
        // past a chunk's edge are outside it. One extra ring is 68 columns on 256 — see [MoltenLining].
        val around = Array(LINING_SIDE * LINING_SIDE) { index ->
            val bandX = index / LINING_SIDE
            val bandZ = index % LINING_SIDE
            overlay.at(
                chunk.pos.minBlockX + bandX - LINING_MARGIN,
                chunk.pos.minBlockZ + bandZ - LINING_MARGIN,
            )
        }
        fun columnAt(localX: Int, localZ: Int) =
            around[(localX + LINING_MARGIN) * LINING_SIDE + (localZ + LINING_MARGIN)]

        for (localX in 0..<BLOCKS_PER_SECTION) {
            for (localZ in 0..<BLOCKS_PER_SECTION) {
                val worldX = chunk.pos.minBlockX + localX
                val worldZ = chunk.pos.minBlockZ + localZ
                val column = columnAt(localX, localZ)
                if (column.saysNothing) continue
                val beside = listOf(
                    columnAt(localX - 1, localZ), columnAt(localX + 1, localZ),
                    columnAt(localX, localZ - 1), columnAt(localX, localZ + 1),
                )
                column.forEachBlock(rockHere) { y, state ->
                    if (y in lowest..highest) {
                        // The same rule the field path keeps, so a cone on vanilla's rock holds its lava in
                        // the same obsidian a cone on ours does.
                        val laid = if (state != rockHere) state else MoltenLining.rockAt(
                            moltenAbove = MoltenLining.isMolten(column.blockAt(y + 1, rockHere)),
                            moltenBeside = beside.any { MoltenLining.isMolten(it.blockAt(y, rockHere)) },
                            otherwise = state,
                        )
                        cursor.set(worldX, y, worldZ)
                        chunk.setBlockState(cursor, laid)
                        // Both heightmaps are re-derived by walking the chunk back down where a write
                        // *removes* ground, so cutting a caldera lowers the surface rather than leaving
                        // the cone's old summit standing in a structure's way. Told what was **laid**
                        // rather than what was asked for: the two agree today, obsidian being as solid as
                        // stone, and a lining that was ever anything else would silently disagree.
                        oceanFloor.update(localX, y, localZ, laid)
                        worldSurface.update(localX, y, localZ, laid)
                    }
                }
            }
        }
    }

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
        val ours = rock as? AgeRock.Ours ?: return overlaidBaseHeight(x, z, type, level, randomState)
        val counts = type.isOpaque()
        // One below the world, so a column with nothing this query counts simply answers the floor.
        val nothing = level.minY - 1
        // The land rather than the whole rock: a lid over a sealed Age is not somewhere to stand, and
        // reading it here would answer "the top of the world" for every column (see [AgeRock.Ours.ground]).
        val landform = ours.landform.columnSpans(x, z).highestSolidY
        val rockTop = if (counts.test(fill.representative)) landform ?: nothing else nothing
        // A river stands over the waterline, so its own surface is what a structure has to be told about.
        val mediumTop = if (!counts.test(seaFill.blockAt(x, z))) nothing else {
            maxOf(seaFill.surfaceY ?: nothing, seaFill.wetnessAt(x, z).highestSolidY ?: nothing)
        }
        // And so does a lake the shape carries, which is made of something else and asks on its own behalf.
        val carriedTop = seaFill.carriedSurfaceY(x, z) { counts.test(it) } ?: nothing
        return (maxOf(rockTop, maxOf(mediumTop, carriedTop)) + 1).coerceIn(level.minY, level.maxY + 1)
    }

    /**
     * Vanilla's own height, **with the overlay laid over it** — the second of the three exits that have to
     * agree about a cone (see [Overlay.Column]).
     *
     * Answered against vanilla's real column rather than the overlay alone, because the overlay only knows
     * what *it* put here: the ground a cone stands on is vanilla's, and so is the ground under a chamber
     * the overlay hollowed. Composing the two is the only way to get "the top of the mountain" rather than
     * "the top of the mountain, or the field under it, whichever we happened to ask".
     *
     * **The cheap case is the overwhelmingly common one.** An overlay whose highest opinion lies below the
     * ground vanilla already reported cannot have moved the surface, so it returns vanilla's answer without
     * building a column — which is every column of every Age that named no feature, and every column of a
     * volcanic one but the few hundred round a cone.
     */
    private fun overlaidBaseHeight(
        x: Int,
        z: Int,
        type: Heightmap.Types,
        level: LevelHeightAccessor,
        randomState: RandomState,
    ): Int {
        val vanillaHeight = super.getBaseHeight(x, z, type, level, randomState)
        if (overlay.isEmpty) return vanillaHeight
        val column = overlay.at(x, z)
        val overlayTop = column.topmostY ?: return vanillaHeight
        // `vanillaHeight` is one *above* the topmost block it counts, so the overlay is clear of the
        // surface only when it stops below that block — not merely below the height.
        if (overlayTop < vanillaHeight - 1) return vanillaHeight
        val counts = type.isOpaque()
        val rockHere = generationSettings.defaultBlock
        val vanilla = super.getBaseColumn(x, z, level, randomState)
        val from = minOf(level.maxY, maxOf(overlayTop, vanillaHeight - 1))
        for (y in from downTo level.minY) {
            if (counts.test(column.blockAt(y, rockHere) ?: vanilla.getBlock(y))) return y + 1
        }
        // What vanilla answers for a column holding nothing this query counts.
        return level.minY
    }

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val ours = rock as? AgeRock.Ours ?: return overlaidBaseColumn(x, z, level, randomState)
        val spans = ours.field.columnSpans(x, z)
        val sea = seaFill.blockAt(x, z)
        val dryness = seaFill.drynessAt(x, z)
        val wetness = seaFill.wetnessAt(x, z)
        val bodies = seaFill.carriedAt(x, z)
        val column = Array(window.height) { index ->
            val y = window.minY + index
            when {
                spans.contains(y) -> fill.blockAt(x, y, z)
                // The same abyss the chunk fill lays, so a heightmap query and the blocks agree.
                else -> DeepWater.seaAt(
                    y,
                    abyssLine,
                    seaFill.carriedAt(y, bodies) ?: if (seaFill.fillsAt(y, dryness, wetness)) sea else AIR,
                )
            }
        }
        return NoiseColumn(window.minY, column)
    }

    /**
     * And the third exit: vanilla's column with the overlay written through it.
     *
     * Rebuilt over the *level's* range rather than vanilla's own, which is the same range and is the one
     * thing reachable from here — `NoiseColumn` hands back air for any Y outside itself, so the two agree
     * where they overlap and nothing is invented where they do not.
     */
    private fun overlaidBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val vanilla = super.getBaseColumn(x, z, level, randomState)
        if (overlay.isEmpty) return vanilla
        val column = overlay.at(x, z)
        if (column.saysNothing) return vanilla
        val rockHere = generationSettings.defaultBlock
        val blocks = Array(level.height) { index ->
            val y = level.minY + index
            column.blockAt(y, rockHere) ?: vanilla.getBlock(y)
        }
        return NoiseColumn(level.minY, blocks)
    }

    /**
     * Where water stands in this Age's rock. Defaults to a flat table at the sea's own level — flooded
     * below, dry above — which an Age can replace with a wandering one for dry deep caves and perched
     * pockets. It mints a fresh aquifer per carving pass, since that object carries state.
     */
    private val tables: List<WaterTable> =
        waterTables.map { it.copy(carried = seaFill.carried) }
            .ifEmpty { listOf(WaterTable.matching(seaFill, seaLevel)) }

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
        Aquifer.FluidPicker { x, _, z -> Aquifer.FluidStatus(seaLevel, writtenSea.blockAt(x, z)) }

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
        // Vanilla's rock brings vanilla's caves with it: its carvers read the same router the shape came
        // out of, where ours would be cutting into a world they know nothing about.
        if (rock !is AgeRock.Ours) {
            return super.applyCarvers(level, seed, randomState, biomeManager, structureManager, chunk)
        }
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
        val aquifer = WaterTable.aquiferFor(tables, rock.field, surfaceForTheAquifer(), deepDarkIn(randomState), underground)
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
        tellOpenedLavaToMove(chunk)
    }

    /**
     * **Lava a carver has just opened is told it may move.**
     *
     * A carver runs *after* the fill and writes its air straight into the chunk, firing no neighbour
     * updates — generation never does. So a caldera whose wall a cave happened to cut through kept a level
     * sheet of lava standing over the hole, because a source block does nothing until something asks it to
     * (Jonah, walked 2026-09-11: *"it did leave some lava that should have been flowing suspended"*).
     *
     * **Marked rather than moved**, which is the same answer the fill already gives its own perched fluids:
     * `markPosForPostprocessing` has vanilla give the block its first tick when the chunk loads, and it then
     * finds its own way down. Nothing here decides where the lava goes.
     *
     * **Lava only, and that is what makes the sweep affordable.** It is rare — crater lakes and magma
     * chambers — so almost every section is skipped outright by the same `maybeHas` test
     * `DeepWater.settleTheAbyss` uses, and an Age with none pays one predicate per section. Water is left
     * alone deliberately: a sea is most of the volume of a wet Age, and vanilla's carvers already stop at
     * it rather than cutting it open.
     *
     * A neighbour outside this chunk is not looked at. It cannot be read reliably here, and the chunk it
     * belongs to runs this same sweep over its own side of the boundary.
     */
    private fun tellOpenedLavaToMove(chunk: ChunkAccess) {
        val at = BlockPos.MutableBlockPos()
        val beside = BlockPos.MutableBlockPos()
        val lowest = chunk.minY
        val highest = chunk.minY + chunk.height - 1
        for (index in chunk.minSectionY..chunk.maxSectionY) {
            val section = chunk.getSection(chunk.getSectionIndexFromSectionY(index))
            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { MoltenLining.isMolten(it) }) continue
            // The section's own *block* floor. `index` is already a section Y here, so this is the shift
            // and nothing else — round-tripping it through the index would hand back the section Y again
            // and scan sixteen blocks starting at y = -4.
            val floor = index shl SECTION_TO_BLOCKS
            for (y in maxOf(floor, lowest)..minOf(floor + BLOCKS_PER_SECTION - 1, highest)) {
                for (localX in 0..<BLOCKS_PER_SECTION) {
                    for (localZ in 0..<BLOCKS_PER_SECTION) {
                        at.set(chunk.pos.minBlockX + localX, y, chunk.pos.minBlockZ + localZ)
                        if (!MoltenLining.isMolten(chunk.getBlockState(at))) continue
                        if (opensOnto(chunk, beside, at, localX, localZ, lowest, highest)) {
                            chunk.markPosForPostprocessing(at)
                        }
                    }
                }
            }
        }
    }

    /** Whether any neighbour of [at] inside this chunk is open air for the lava to run into. */
    private fun opensOnto(
        chunk: ChunkAccess,
        cursor: BlockPos.MutableBlockPos,
        at: BlockPos,
        localX: Int,
        localZ: Int,
        lowest: Int,
        highest: Int,
    ): Boolean {
        if (at.y > lowest && chunk.getBlockState(cursor.setWithOffset(at, Direction.DOWN)).isAir) return true
        if (at.y < highest && chunk.getBlockState(cursor.setWithOffset(at, Direction.UP)).isAir) return true
        if (localX > 0 && chunk.getBlockState(cursor.setWithOffset(at, Direction.WEST)).isAir) return true
        if (localX < BLOCKS_PER_SECTION - 1 && chunk.getBlockState(cursor.setWithOffset(at, Direction.EAST)).isAir) {
            return true
        }
        if (localZ > 0 && chunk.getBlockState(cursor.setWithOffset(at, Direction.NORTH)).isAir) return true
        return localZ < BLOCKS_PER_SECTION - 1 &&
            chunk.getBlockState(cursor.setWithOffset(at, Direction.SOUTH)).isAir
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
        // **The abyss is put right first, and before the early return below.** A structure brings its own
        // ordinary water — an ocean monument most of all — and vegetation grows in that water in this same
        // stage, so there is nowhere to stand between the two. See `DeepWater.settleTheAbyss`.
        DeepWater.settleTheAbyss(level, chunk, abyssLine) { x, z -> abyssReachesAt(chunk, x, z) }
        val bought = consequence
        if (bought.isNothing) return
        // **The Age's age is read here rather than at open**, so a chunk generated after a week of worsening
        // comes out as torn as the ones beside it. Against the *overworld's* clock: an Age's own only runs
        // while somebody is in it, which is exactly when the worsening is not supposed to be waiting.
        val days = bought.daysBy(level.level.server.overworld().gameTime)
        // The floor giving way first: a column the Age has already swallowed is not somewhere to put a
        // wound, and carving after would take the wound straight back out again.
        Collapse.carveInto(level, chunk, level.getSeed(), bought.collapseTears)
        val density = Tearing.densityAt(bought.woundsPerChunk, bought.woundsPerDay, days)
        // Nobody to tell and nothing to update: the chunk has not been sent to a client and will not be
        // until it is finished, so a wound here is written into it rather than announced.
        Tearing.tearInto(
            level,
            chunk,
            level.getSeed(),
            Tearing.wantedIn(chunk.pos, level.getSeed(), density),
            alreadyRunning = false,
        )
    }

    /**
     * Nothing where the shape is ours — mob generation is disabled in [settingsFor], and the superclass
     * would otherwise consult its own [NoiseChunk] to decide. Vanilla's rock answers for itself: its
     * settings already say whether that world populates a fresh chunk, and the overworld's says it does.
     *
     * **And nothing where the Age says nothing lives here.** This pass is not the runtime spawner and does
     * not come through [getMobsAt]: `NaturalSpawner.spawnMobsForChunkGeneration` takes the biome holder and
     * reads its own mob settings, so the whole of `Spawns.LIVES` was invisible to it. An Age on vanilla
     * rock got vanilla's chunk-generation animals whatever its sentence said — `deserted` emptied the
     * spawner and left five horses standing where the chunk was made.
     */
    override fun spawnOriginalMobs(level: WorldGenRegion) {
        if (rock is AgeRock.Ours) return
        if (offersNoCreaturesIn(level)) return
        super.spawnOriginalMobs(level)
    }

    /**
     * Whether this Age would refuse every creature the chunk-generation pass could place.
     *
     * Asked of the same biome the superclass would use, so the question is the one vanilla is about to
     * answer. **Only emptiness is acted on**: a sentence that merely narrows still gets vanilla's own list
     * here, since the pass reads the biome directly and there is nowhere to hand it a shorter one.
     *
     * The situation is the surface in daylight because that is what this pass places — animals, out in the
     * open, before there is any lighting to ask about.
     */
    private fun offersNoCreaturesIn(level: WorldGenRegion): Boolean {
        val living = lives ?: return false
        val at = level.center.worldPosition.atY(level.maxY)
        val biome = level.getBiome(at)
        val offered = biome.value().mobSettings.getMobs(MobCategory.CREATURE)
        if (offered.isEmpty) return false
        val kept = living.at(
            biome.unwrapKey().orElse(null)?.identifier(),
            MobCategory.CREATURE,
            Spawns.Situation(at = at, skyIsOpen = true, brightness = FULLY_LIT),
            offered,
        )
        return kept.isEmpty
    }

    /** The superclass renders noise-router values in F3, which describe terrain a field Age does not have. */
    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) {
        if (rock !is AgeRock.Ours) super.addDebugScreenInfo(info, randomState, pos)
    }

    // getGenDepth / getSeaLevel / getMinY are deliberately NOT overridden: the superclass answers all three
    // from `generatorSettings()`, which is ours (see [settingsFor]). That makes `getSeaLevel` return the
    // *coerced* sea level, so a void sea answers the world floor rather than `Int.MIN_VALUE`.

    companion object {
        /** What a place is taken as when there is no level to ask — nothing is refused for its light. */
        private const val FULLY_LIT = 15

        // Declared before CODEC, and it must be: a companion initialises top to bottom, so CODEC reading
        // this from below would read a null.
        // One set per territory. It was a map keyed by `GenerationStep.Carving` until vanilla collapsed
        // its two carving passes into one; only the air half was ever populated, so nothing was lost.
        private val CARVER_SETS: Codec<HolderSet<ConfiguredWorldCarver<*>>> =
            RegistryCodecs.homogeneousList(Registries.CONFIGURED_CARVER)

        /** A coherent Age, which tears nowhere. */
        const val NO_WOUNDS = 0.0

        val CODEC: MapCodec<AgeChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                AgeRock.MAP_CODEC.forGetter { it.rock },
                SeaFill.CODEC.forGetter { it.writtenSea },
                // Optional so field Ages serialised before palettes existed still load.
                SurfaceRules.RuleSource.CODEC.optionalFieldOf("surface_rule", SurfacingStrategy.SUPPRESSED)
                    .forGetter { it.surfaceRule },
                // One carver set per carving, since carving is set-valued.
                CARVER_SETS.listOf()
                    .optionalFieldOf("carvers", listOf(HolderSet.direct())).forGetter { it.carvers },
                RegionMap.MAP_CODEC.codec().optionalFieldOf("underground", RegionMap.whole())
                    .forGetter { it.underground },
                // Absent means "a flat table at the sea's own level", derived at construction. A list, since
                // hydrology divides with the carving it belongs to.
                WaterTable.CODEC.codec().listOf()
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
                // Absent for every coherent Age, which is nearly all of them.
                Codec.DOUBLE.optionalFieldOf("wounds_per_chunk", NO_WOUNDS).forGetter { it.consequence.woundsPerChunk },
                // And absent for every Age that is merely flawed rather than coming apart.
                Codec.DOUBLE.optionalFieldOf("wounds_per_day", NO_WOUNDS).forGetter { it.consequence.woundsPerDay },
                // And absent for every Age that is not ending.
                Codec.INT.optionalFieldOf("collapse_tears", Collapse.NONE).forGetter { it.consequence.collapseTears },
                Codec.LONG.optionalFieldOf("written_at", 0L).forGetter { it.consequence.writtenAt },
                // Absent for every Age that asked for no shape of ours over its rock.
                Overlay.CODEC.codec().optionalFieldOf("overlay", Overlay.NONE).forGetter { it.overlay },
            ).apply(instance) { biomes, rock, seaFill, rule, carvers, underground, tables, structures, climate,
                                fill, window, wounds, worsening, collapse, writtenAt, overlay ->
                AgeChunkGenerator(
                    biomes, rock, seaFill, rule, carvers, underground, tables, structures,
                    climate.orElse(null), fill, window,
                    woundsPerChunk = wounds,
                    woundsPerDay = worsening,
                    collapseTears = collapse,
                    writtenAt = writtenAt,
                    overlay = overlay,
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
            uncut: TerrainField?,
        ) = NoiseGeneratorSettings(
            NoiseSettings.create(window.minY, window.height, NOISE_CELLS_HORIZONTAL, NOISE_CELLS_VERTICAL),
            // The Age's own material, not a constant, which is what makes a surface rule fire over it:
            // `SurfaceSystem` recognises rock by comparing against these settings' default block, so
            // laying blackstone while declaring stone paints no surface at all. One block for the whole
            // Age, so several materials are recognised over [TerrainFill.representative] only.
            fill.representative,
            seaFill.representative,
            routerFor(
                climate,
                PreliminarySurface(field, uncut, window.minY, window.topY - 1, QuartPos.toBlock(NOISE_CELLS_VERTICAL)),
            ),
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

        /** A section is sixteen blocks tall, so its Y shifted by four is its floor in block space. */
        private const val SECTION_TO_BLOCKS = 4

        /** One column of margin all round, which is what asking "what is beside this" costs. */
        private const val LINING_MARGIN = 1
        private const val LINING_SIDE = BLOCKS_PER_SECTION + 2 * LINING_MARGIN

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
         * **One exception, and it earns itself: `preliminarySurfaceLevel`.** `NoiseChunk` floors that slot
         * into a column's preliminary surface, which is how deep the surface system's frozen-ocean icebergs
         * reach and what vanilla's `abovePreliminarySurface` compares against. [PreliminarySurface] answers it
         * from the field tree — as a height, not a density, since a height is what the slot holds.
         */
        private fun routerFor(climate: Holder<NoiseGeneratorSettings>?, surface: DensityFunction): NoiseRouter {
            val vanilla = climate?.value()?.noiseRouter() ?: return inertRouterOver(surface)
            val nothing = DensityFunctions.zero()
            return NoiseRouter(
                nothing, nothing, nothing, nothing,
                vanilla.temperature(), vanilla.vegetation(), vanilla.continents(), vanilla.erosion(),
                /* depth = */ nothing,
                vanilla.ridges(),
                /* preliminarySurfaceLevel = */ surface,
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
