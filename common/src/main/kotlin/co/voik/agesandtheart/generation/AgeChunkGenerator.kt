package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.phenomena.Deluge
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
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.codec.RegistryCodecs
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.attribute.EnvironmentAttributes
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
import co.voik.agesandtheart.age.consequence.Consequence
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.WorldGenerationContext
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext
import net.minecraft.world.level.chunk.CarvingMask
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Util
import co.voik.agesandtheart.worldgen.carver.OpenGround
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.RandomSupport
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.carver.WorldCarver
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
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.FieldFill
import co.voik.agesandtheart.worldgen.MoltenLining
import co.voik.agesandtheart.worldgen.Overlay
import co.voik.agesandtheart.worldgen.TerrainAdaptation
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.settingsFor

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
    private val surfaceRule: MaterialRule = SurfacingStrategy.SUPPRESSED,
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
    private val carvers: List<HolderSet<WorldCarver>> = listOf(HolderSet.direct()),
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
     * single-biome demo preset is. Only the climate half of the named router is taken; see
     * [co.voik.agesandtheart.worldgen.settingsFor].
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
     * What this Age's instability bought (design §5.0) — **one object, because the four figures in it
     * change together or not at all**, and a generator that took them loose could be handed half an update.
     *
     * **On the generator because a chunk generated late must come out as torn as its neighbours**, which is
     * §5.4's derived-clock escape: a chunk that has never existed has no blocks to be legible from, so the
     * generator and the fast-forward read the same function rather than one of them inferring.
     *
     * Named apart from the [consequence] property it seeds, which is the mutable one: this is what was true
     * at open, and that is what is true now.
     */
    bought: Consequence = Consequence.NONE,
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

    /** The surface of the sea as it was written, or null where the Age has none. */
    val writtenSeaSurfaceY: Int? get() = writtenSea.surfaceY

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
     * `NoiseChunk` reads it, so this is the number the frozen-ocean icebergs stop at. See
     * [co.voik.agesandtheart.worldgen.PreliminarySurface].
     */
    fun preliminarySurfaceAt(worldX: Int, worldZ: Int): Int {
        // Always one of ours, and its own sampler: `routerFor` puts a `PreliminarySurface` in this slot
        // for every Age, so there is no compile context to build in order to read it.
        val surface = generatorSettings().value().noiseRouter().chunkSurfaceLevel() as DensitySampler
        return Math.floor(surface.sampleValue(SamplerContext.EMPTY_UNCACHED, worldX, 0, worldZ).toDouble()).toInt()
    }

    /** The surface the aquifer reads, which is the one vanilla's surface system reads — as vanilla's aquifer does. */
    private fun surfaceForTheAquifer(): WaterTable.SurfaceAt = WaterTable.SurfaceAt(::preliminarySurfaceAt)

    /**
     * This Age's water table over [field], built fresh for whoever asks.
     *
     * **Fresh every time, and that is not an economy to make**: the object memoises a column and tracks
     * whether the water it has just placed still has to settle, so the fill and the carving each need their
     * own and neither may share one between chunk workers.
     *
     * Spelled once because it was spelled twice, identically, in the two places that need it — six
     * arguments apiece, where one drifting in one of them would be silent.
     */
    private fun aquiferFor(randomState: RandomState, field: TerrainField) =
        WaterTable.aquiferFor(tables, field, surfaceForTheAquifer(), deepDarkIn(randomState), writtenSea, underground)

    /**
     * Whether a point lies in deep dark, which vanilla's aquifer never floods. Vanilla asks its erosion and
     * depth; our depth is our own, so this asks the biome those two would have chosen.
     */
    private fun deepDarkIn(randomState: RandomState): WaterTable.DeepDarkAt = WaterTable.DeepDarkAt { worldX, worldY, worldZ ->
        biomeSource.createUncachedResolver(randomState).getNoiseBiome(
            QuartPos.fromBlock(worldX),
            QuartPos.fromBlock(worldY),
            QuartPos.fromBlock(worldZ),
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
    var consequence: Consequence = bought
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
        level: Level,
        structures: StructureManager,
        category: MobCategory,
        at: BlockPos,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        val offered = super.getMobsAt(level, structures, category, at)
        val living = lives ?: return offered
        // The biome off the level rather than handed in: 26.3 passes the level, which is also what
        // retired the widener line that used to reach one through the structure manager.
        val biome = level.getBiome(at)
        return living.at(
            biome.unwrapKey().orElse(null)?.identifier(),
            category,
            Spawns.Situation(
                at = at,
                skyIsOpen = skyIsOpenAt(structures, at),
                brightness = level.getMaxLocalRawBrightness(at),
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
        rock as? AgeRock.Ours
            ?: return at.y >= structures.level.getHeight(Heightmap.Types.WORLD_SURFACE, at.x, at.z)
        val highestRock = openSky.highestSolidYAt(at.x, at.z) ?: return true
        return at.y > highestRock
    }

    /**
     * The field's own answer to [skyIsOpenAt], remembered — see [OpenSkyHeights] for why the spawner needs
     * a cache of its own and why it could not be the one generation already has.
     */
    private val openSky: OpenSkyHeights by lazy { OpenSkyHeights((rock as AgeRock.Ours).field) }

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
        return isAbyssal(
            ours,
            worldX,
            worldZ,
            rockHere = ours.field.columnSpans(worldX, worldZ),
            seaFillsTheLine = {
                seaFill.fillsAt(abyssLine, seaFill.drynessAt(worldX, worldZ), seaFill.wetnessAt(worldX, worldZ))
            },
            biomeAtTheLine = { biomeAtTheLineIn(chunk, worldX, worldZ) },
        )
    }

    /** The biome at the abyss line, off a chunk whose biomes are already laid. */
    private fun biomeAtTheLineIn(chunk: ChunkAccess, worldX: Int, worldZ: Int): Holder<Biome> =
        chunk.getNoiseBiome(QuartPos.fromBlock(worldX), QuartPos.fromBlock(abyssLine), QuartPos.fromBlock(worldZ))

    /**
     * **The one statement of whether a column is abyssal**, for the settling tick and [getBaseColumn] alike,
     * so the blocks a heightmap query is answered with are the blocks that were laid. `FieldFill` asks the
     * same three things of its own snapshot of the sea.
     *
     * The rock has to stop short of the line, the sea has to stand at it, and [abyssBelongsIn] has to agree —
     * in that order, cheapest first, which is why the last two arrive as lambdas.
     */
    private inline fun isAbyssal(
        ours: AgeRock.Ours,
        worldX: Int,
        worldZ: Int,
        rockHere: Spans,
        seaFillsTheLine: () -> Boolean,
        biomeAtTheLine: () -> Holder<Biome>,
    ): Boolean {
        if (rockHere.contains(abyssLine)) return false
        if (!seaFillsTheLine()) return false
        return abyssBelongsIn(biomeAtTheLine(), ours, worldX, worldZ)
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
     * [biomeAtTheLine] is the biome at the abyss line. The fill and the settling tick read it off the chunk,
     * which is safe there: `BIOMES` runs before `NOISE`, so the biomes are settled by the time either asks.
     */
    private fun abyssBelongsIn(biomeAtTheLine: Holder<Biome>, ours: AgeRock.Ours, worldX: Int, worldZ: Int): Boolean {
        // **The biome first, because it is an array read and the other is a whole field tree.** This runs
        // per column of the fill, and the landform of a volcanic Age is the most expensive thing in it.
        if (biomeAtTheLine.`is`(DeepWater.NO_ABYSS)) return false
        // The land rather than the whole rock, as `getBaseHeight` reads it: a lid over a sealed Age is not
        // a sea floor, and reading it here would call every column of such an Age dry land.
        val ground = ours.landform.columnSpans(worldX, worldZ).highestSolidY
        return ground == null || ground < seaSurfaceY
    }

    /**
     * The settings handed to the superclass, read back rather than kept twice — see
     * [co.voik.agesandtheart.worldgen.settingsFor]. Vanilla
     * reads the same object, so `getSeaLevel`/`getMinY`/`getGenDepth` need no overrides here.
     */
    private val generationSettings: NoiseGeneratorSettings = generatorSettings().value()

    /**
     * **Fill, then this Age's own relief, then the surface, then the carvers** — vanilla's own order with
     * one insertion, which is the whole reason this is overridden rather than inherited.
     *
     * 26.3 collapsed `fillFromNoise`, `buildSurface` and `applyCarvers` into this one call and made the
     * pipeline inside it private. The [Overlay] has to land between the fill and the surfacing: surfaced
     * after being raised, a cone comes out with the biome's own grass or snow or netherrack on it, and
     * surfaced before, it comes out as bare rock (stamp 33, walked). A feature could not do it either —
     * it may write only one chunk past its own, and it would arrive after the surface was decided.
     *
     * `doFill` and `generateCarvers` are widened for this. Reimplementing vanilla's fill was the
     * alternative and was declined: it drifts every version, and drift inside the terrain fill is
     * invisible until somebody walks it.
     */
    override fun buildTerrain(
        chunk: ChunkAccess,
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        biomeManager: BiomeManager,
        region: WorldGenRegion?,
        biomes: MutableSet<Holder<Biome>>,
    ): CompletableFuture<ChunkAccess> = CompletableFuture.supplyAsync({
        noiseChunkFor(chunk, randomState, structureManager).use { noiseChunk ->
            val ours = rock as? AgeRock.Ours
            if (ours == null) doFill(noiseChunk, chunk) else fillOurGround(ours, chunk, randomState, structureManager)
            // Only where the rock is vanilla's: an Age of ours folds its overlay into the field tree.
            if (ours == null) layOverlayInto(chunk)
            randomState.surfaceSystem().buildSurface(
                randomState,
                biomeManager,
                WorldGenerationContext(this, chunk.heightAccessorForGeneration),
                chunk,
                noiseChunk,
                surfaceRule,
                biomes,
            )
            if (ours == null) {
                region?.let { generateCarvers(chunk, blender, noiseChunk, randomState, biomeManager, it, surfaceRule) }
            } else {
                region?.let { carveOurGround(ours, chunk, randomState, biomeManager, noiseChunk, it) }
            }
        }
        chunk
    }, Util.backgroundExecutor())

    /** The field tree's own fill — [FieldFill], which knows nothing of density functions. */
    private fun fillOurGround(
        ours: AgeRock.Ours,
        chunk: ChunkAccess,
        randomState: RandomState,
        structureManager: StructureManager,
    ) {
        // Null in almost every chunk, which is what makes asking it per block affordable.
        val adaptation = TerrainAdaptation.around(structureManager, chunk.pos)
        // **One reading of the sea for the whole chunk** — see [FieldFill], which is built around it.
        // `ColumnBand` already took a single reading for everything it answers, so this finishes what that
        // started rather than striking a new bargain.
        val standing = seaFill
        FieldFill(
            rock = ours,
            fill = fill,
            window = window,
            sea = standing,
            abyssLine = standing.surfaceY?.let(DeepWater::lineBelow) ?: (window.minY - 1),
            // One per chunk, because the object carries a column memo — the same reason carving mints its own.
            water = aquiferFor(randomState, ours.field),
            adaptation = adaptation,
            // Reaches back for the *live* sea rather than the snapshot, which is what it did before the fill
            // moved out of here. [FieldFill]'s note says why that is left alone rather than tidied.
            abyssBelongsHere = { at, worldX, worldZ ->
                abyssBelongsIn(biomeAtTheLineIn(at, worldX, worldZ), ours, worldX, worldZ)
            },
        ).fillInto(chunk)
    }

    // --- Surface height contract: honest answers so structures/features land on the terrain. ---

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
        // The same abyss the chunk fill lays, so a heightmap query and the blocks agree.
        val abyssal = isAbyssal(
            ours,
            x,
            z,
            rockHere = spans,
            seaFillsTheLine = { seaFill.fillsAt(abyssLine, dryness, wetness) },
            biomeAtTheLine = {
                biomeSource.createUncachedResolver(randomState).getNoiseBiome(
                    QuartPos.fromBlock(x),
                    QuartPos.fromBlock(abyssLine),
                    QuartPos.fromBlock(z),
                )
            },
        )
        val column = Array(window.height) { index ->
            val y = window.minY + index
            when {
                spans.contains(y) -> fill.blockAt(x, y, z)
                else -> {
                    val open = seaFill.carriedAt(y, bodies) ?: if (seaFill.fillsAt(y, dryness, wetness)) sea else AIR
                    if (abyssal) DeepWater.seaAt(y, abyssLine, open) else open
                }
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
    private val tables: List<WaterTable> = waterTables.ifEmpty { listOf(WaterTable.matching(writtenSea, seaLevel)) }

    /**
     * Every carving's carvers together — the union described on [carvers]. Built once and **in composition
     * order**, because a carver is seeded by its *index* in the list it runs from, so a stable order is
     * what keeps an Age reproducible. `distinct()` so a carver named twice runs once, rather than twice at
     * different seeds, which would double its density.
     */
    private val carving: List<Holder<WorldCarver>> by lazy {
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

    /**
     * One per `buildTerrain`, closed when it is done — 26.3 stopped caching these on the chunk and made
     * them `AutoCloseable`, the samplers inside holding buffers worth giving back.
     */
    private fun noiseChunkFor(chunk: ChunkAccess, randomState: RandomState, structureManager: StructureManager): NoiseChunk {
        val settings = generationSettings.noiseSettings().clampToHeightAccessor(chunk.heightAccessorForGeneration)
        return NoiseChunk(
            randomState,
            // The real beardifier rather than the inert marker: it is public, and it is what will
            // let structures flatten the ground around themselves once they are switched on.
            Beardifier.forStructuresInChunk(structureManager, chunk.pos),
            generationSettings,
            ambientFluid,
            Blender.empty(),
            // Vanilla's own `chunkVolume`, which is private and is this: the chunk, as deep as the
            // settings say, anchored at its corner.
            DensityVolume(
                BLOCKS_ACROSS_A_CHUNK, settings.height(), BLOCKS_ACROSS_A_CHUNK,
                chunk.pos.minBlockX, settings.minY(), chunk.pos.minBlockZ,
            ),
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
    private fun carveOurGround(
        ours: AgeRock.Ours,
        chunk: ChunkAccess,
        randomState: RandomState,
        biomeManager: BiomeManager,
        noiseChunk: NoiseChunk,
        level: WorldGenRegion,
    ) {
        if (carving.isEmpty()) return
        val seed = level.seed

        // A biome manager reading this Age's own source rather than the level's, which the mask's own
        // application asks per position to decide what a cut block becomes.
        val carvingBiomes = biomeManager.withDifferentSource(
            biomes.createResolver(randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED)),
        )
        val context = WorldGenerationContext(this, chunk.heightAccessorForGeneration)
        // Ours to make and ours to apply: 26.3 has a carver write into a mask and lets the generator
        // decide afterwards what each marked block becomes. Vanilla's own application reads the aquifer
        // the NoiseChunk built, which for us is the disabled one — see [openGroundOf].
        // **`(minY, maxY)`, inclusive — it took `(height, minY)` before 26.3.** Two ints, so the swap
        // compiled and threw only when a chunk was actually carved: the mask sized itself
        // `maxY - minY + 1` = `-64 - 384 + 1` and asked for a BitSet of -114432 bits. What made it hard to
        // see is where the throw went — see `DelayedCrashMixin`.
        val carvingMask = CarvingMask(window.minY, window.highestBlockY)
        // Fresh per pass: it caches a column and tracks whether the water it just placed needs to
        // settle, so it must not be shared between chunk workers.
        val aquifer = aquiferFor(randomState, ours.field)
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
                        carver.value().carve(context, random, chunk.pos, source, openGroundOf(chunk, carvingMask))
                    }
                }
            }
        }
        applyOurCarvingMask(chunk, carvingMask, aquifer, randomState, noiseChunk, context, carvingBiomes::getBiome)
        MoltenLining.markLavaOpenedIn(chunk)
    }

    /**
     * The mask, plus the one question 26.3 took away from a carver: whether there is rock here at all.
     *
     * A carver is handed a write-only output now, so the air test that used to be made against the
     * `ChunkAccess` has nowhere to be asked from — and [co.voik.agesandtheart.worldgen.carver.Porosity]
     * runs the whole world column, most of which is sky. See [OpenGround].
     */
    private fun openGroundOf(chunk: ChunkAccess, mask: CarvingMask): OpenGround {
        val cursor = BlockPos.MutableBlockPos()
        return object : OpenGround {
            override fun carve(localX: Int, worldY: Int, localZ: Int) = mask.carve(localX, worldY, localZ)
            override fun minY(): Int = mask.minY()
            override fun maxY(): Int = mask.maxY()
            override fun holdsRockAt(worldX: Int, worldY: Int, worldZ: Int): Boolean =
                !chunk.getBlockState(cursor.set(worldX, worldY, worldZ)).isAir
        }
    }

    /**
     * What a carved block becomes — **vanilla's own `applyCarvingMask`, transcribed, with our aquifer in
     * place of the one the `NoiseChunk` holds.**
     *
     * That substitution is the whole reason it is transcribed rather than called. 26.3 moved the choice of
     * block out of the carver and into the generator, and vanilla's copy reads `noiseChunk.aquifer()` —
     * which for us is the disabled one, since the settings carry no `Aquifer.Config` on purpose. Handing it
     * the bare aquifer would lose the decorator that lets water the shape poured answer first, and a carver
     * cutting into a river would stop finding the river.
     *
     * The rest is vanilla's line for line, including the part that is easy to miss: a cut that takes a
     * grass block or mycelium re-dresses the dirt beneath it, which is what stops a carved hillside
     * showing a band of bare earth along its lip.
     */
    private fun applyOurCarvingMask(
        chunk: ChunkAccess,
        mask: CarvingMask,
        aquifer: Aquifer,
        randomState: RandomState,
        noiseChunk: NoiseChunk,
        context: WorldGenerationContext,
        biomeAt: (BlockPos) -> Holder<Biome>,
    ) {
        if (mask.isEmpty()) return
        val here = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()
        mask.visit { localX, localZ, lowY, highY ->
            val worldX = chunk.pos.getBlockX(localX)
            val worldZ = chunk.pos.getBlockZ(localZ)
            // Downward, as vanilla's own walk is: the aquifer's column cache is built top down.
            for (worldY in highY downTo lowY) {
            here.set(worldX, worldY, worldZ)
            val standing = chunk.getBlockState(here)
            if (!standing.`is`(BlockTags.UNCARVABLE)) {
                val wasTurf = standing.`is`(Blocks.GRASS_BLOCK) || standing.`is`(Blocks.MYCELIUM)
                val cut = aquifer.computeSubstance(worldX, worldY, worldZ, NO_CAVE_DENSITY)
                if (cut != null) {
                    chunk.setBlockState(here, cut)
                    if (aquifer.shouldScheduleFluidUpdate() && !cut.fluidState.isEmpty) {
                        chunk.markPosForPostProcessing(here)
                    }
                    if (wasTurf && chunk.getBlockState(below.setWithOffset(here, Direction.DOWN)).`is`(Blocks.DIRT)) {
                        randomState.surfaceSystem().topMaterial(
                            surfaceRule,
                            randomState,
                            context,
                            biomeAt,
                            chunk,
                            noiseChunk.cachingSamplers(),
                            below,
                            !cut.fluidState.isEmpty,
                        ).ifPresent { dressed -> chunk.setBlockState(below, dressed) }
                    }
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
                getOrigin(randomState),
                biomes,
                structureSetLookup.restrictedTo(structureSets),
            )
        }
        return ChunkGeneratorStructureState.createForFlat(
            randomState, seed, getOrigin(randomState), biomes, structureSets.stream(),
        )
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
        // And then whatever the Age's instability bought, which is the register's own pass rather than
        // this hook's: the order those steps run in is a fact about §5, not about chunk generation.
        consequence.writeInto(level, chunk)
    }

    /**
     * Nothing where the shape is ours — mob generation is disabled in
     * [co.voik.agesandtheart.worldgen.settingsFor], and the superclass
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
     * Asked the way vanilla's own spawner asks, so the question is the one it is about to answer.
     * **Only emptiness is acted on**: a sentence that merely narrows still gets vanilla's own list here,
     * since the pass reads the attribute directly and there is nowhere to hand it a shorter one.
     *
     * **26.3 moved a biome's spawners off the biome** and onto `NATURAL_MOB_SPAWNS`, an environment
     * attribute like the sky's colour or the fog's. Read positionally here because that is what the
     * attribute is — a layer may answer differently at two places in one biome.
     *
     * The situation is the surface in daylight because that is what this pass places — animals, out in the
     * open, before there is any lighting to ask about.
     */
    private fun offersNoCreaturesIn(level: WorldGenRegion): Boolean {
        val living = lives ?: return false
        val at = level.center.worldPosition.atY(level.maxY)
        val biome = level.getBiome(at)
        val offered = level.environmentAttributes()
            .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, at)
            .getMobsToSpawn(MobCategory.CREATURE)
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
    override fun addDebugScreenInfo(
        info: MutableList<String>,
        randomState: RandomState,
        pos: BlockPos,
        samplers: SamplerContext,
    ) {
        if (rock !is AgeRock.Ours) super.addDebugScreenInfo(info, randomState, pos, samplers)
    }

    // getGenDepth / getSeaLevel / getMinY are deliberately NOT overridden: the superclass answers all three
    // from `generatorSettings()`, which is ours (see `worldgen.settingsFor`). That makes `getSeaLevel` return the
    // *coerced* sea level, so a void sea answers the world floor rather than `Int.MIN_VALUE`.

    companion object {
        /** What a place is taken as when there is no level to ask — nothing is refused for its light. */
        private const val FULLY_LIT = 15

        // Declared before CODEC, and it must be: a companion initialises top to bottom, so CODEC reading
        // this from below would read a null.
        // One set per territory. It was a map keyed by `GenerationStep.Carving` until vanilla collapsed
        // its two carving passes into one; only the air half was ever populated, so nothing was lost.
        private val CARVER_SETS: Codec<HolderSet<WorldCarver>> =
            RegistryCodecs.holderSet(Registries.CARVER)

        val CODEC: MapCodec<AgeChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                AgeRock.MAP_CODEC.forGetter { it.rock },
                SeaFill.CODEC.forGetter { it.writtenSea },
                // Optional so field Ages serialised before palettes existed still load.
                MaterialRule.DIRECT_CODEC.optionalFieldOf("surface_rule", SurfacingStrategy.SUPPRESSED)
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
                // The same four optional keys, written by the object they belong to — see
                // [Consequence.MAP_CODEC]. Names, defaults and order are unchanged, so an Age serialised
                // before this reads back identically.
                Consequence.MAP_CODEC.forGetter { it.consequence },
                // Absent for every Age that asked for no shape of ours over its rock.
                Overlay.CODEC.codec().optionalFieldOf("overlay", Overlay.NONE).forGetter { it.overlay },
            ).apply(instance) { biomes, rock, seaFill, rule, carvers, underground, tables, structures, climate,
                                fill, window, bought, overlay ->
                AgeChunkGenerator(
                    biomes, rock, seaFill, rule, carvers, underground, tables, structures,
                    climate.orElse(null), fill, window,
                    bought = bought,
                    overlay = overlay,
                )
            }
        }

        /** Half a chunk, so a chunk is judged by its middle rather than its corner. */
        private const val BLOCKS_PER_SECTION = 16

        /** One column of margin all round, which is what asking "what is beside this" costs. */
        private const val LINING_MARGIN = 1
        private const val LINING_SIDE = BLOCKS_PER_SECTION + 2 * LINING_MARGIN

        // Carvers reach this many chunks out, so a cave system crosses borders. Vanilla's own figure.
        private const val CARVE_REACH_CHUNKS = 8

        private const val BLOCKS_ACROSS_A_CHUNK = 16

        /** What a carved block's density reads as: a cut is open, whatever the shape said. */
        private const val NO_CAVE_DENSITY = 0.0

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
