package co.voik.agesandtheart.command

import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.feature.SheerFace
import co.voik.agesandtheart.age.aspect.AgeSpawner
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Spans
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.MobCategory
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.Heightmap

internal object TerrainInstruments {

    /** Measuring the ground an Age was built on: what is where, how fast, and how two of them differ. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(generateSubcommand())
            .then(locateSubcommand())
            .then(biomeCensusSubcommand())
            .then(cliffSurveySubcommand())
            .then(benchmarkSubcommand())
            .then(compareSubcommand())
            .then(spawnsSubcommand())
            .then(probeSubcommand())
    }

    private const val PROBE_X = "x"

    private const val PROBE_Z = "z"

    /** Below any rift floor, so a probe covers the whole band a chasm could occupy. */
    private const val PROBE_FROM = 0

    /** As high as the blocks readout walks — past any landform's summit without printing empty sky. */
    private const val PROBE_TO = 200

    /** Wide enough for `y -64..-63`, so the block names line up down the readout. */
    private const val PROBE_RUN_COLUMN = 12

    private const val RADIUS_ARGUMENT = "radius"

    private const val FIRST_ARGUMENT = "first"

    private const val PRESET_ARGUMENT = "preset"

    private const val SECOND_ARGUMENT = "second"

    // Big enough to be dominated by generation rather than level-open overhead, small enough to run
    // on the server thread without tripping the watchdog.
    private const val DEFAULT_BENCHMARK_RADIUS = 8

    private const val MAX_BENCHMARK_RADIUS = 24

    private const val NANOS_PER_MILLISECOND = 1_000_000.0

    // A comparison reads every block of every chunk in range, so it stays small by default.
    private const val DEFAULT_COMPARE_RADIUS = 2

    private const val MAX_COMPARE_RADIUS = 8

    private const val MAX_REPORTED_DIFFERENCES = 3

    /**
     * How far `/age locate` looks, and how finely. The stride is far below the smallest territory a share
     * can produce, so it cannot step over one.
     */
    private const val LOCATE_RADIUS_BLOCKS = 20_000

    private const val LOCATE_STRIDE_BLOCKS = 64

    /**
     * `/age probe [<x> <z>]` — what the **generator** says about one column, as against what the world
     * happens to hold there.
     *
     * Written because reading a world back from outside it is unreliable in exactly the case worth
     * investigating: a probe run where no player stands loads no chunk, and every block reads `void_air`,
     * which matches nothing and looks like a clean answer. This asks the field and the sea fill directly,
     * so it answers the same whether anyone is standing there or not — and it says *why* a column is wet
     * rather than only that it is.
     */
    private fun probeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("probe") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(PROBE_X, IntegerArgumentType.integer()).then(
                    Commands.argument(PROBE_Z, IntegerArgumentType.integer()).executes { context ->
                        runProbe(
                            context,
                            IntegerArgumentType.getInteger(context, PROBE_X),
                            IntegerArgumentType.getInteger(context, PROBE_Z),
                            reportFor(context),
                        )
                    },
                ),
            )
        }

    private fun runProbe(
        context: CommandContext<CommandSourceStack>,
        x: Int,
        z: Int,
        report: Report,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, report) ?: return FAILURE
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: run {
            source.sendFailure(Component.literal("Age '$name' is not one of ours to probe"))
            return FAILURE
        }

        val ours = generator.rock as? AgeRock.Ours ?: run {
            source.sendFailure(Component.literal("Age '$name' wears vanilla's own rock, which has no field of ours to probe"))
            return FAILURE
        }

        val seaFill = generator.seaFill
        val rock = ours.field.columnSpans(x, z)
        val dryness = seaFill.drynessAt(x, z)
        val wetness = seaFill.wetnessAt(x, z)
        // This column's own: an Age's landforms may each keep a sea at a different height.
        val levelHere = seaFill.levelAt(x, z)
        report.say { "Column ($x, $z) as the generator sees it:" }
        report.fact("waterline", levelHere) {
            "  sea ${seaFill.blockAt(x, z).block.descriptionId} standing at y=$levelHere"
        }
        report.fact("rock", said(rock)) { "  rock: ${said(rock)}" }
        report.fact("keptDry", said(dryness)) { "  kept dry: ${said(dryness)}" }
        report.fact("carriedWater", said(wetness)) { "  carried water: ${said(wetness)}" }
        seaFill.carried.forEachIndexed { index, body ->
            val stands = body.where.columnSpans(x, z)
            report.fact("carried${index}", said(stands)) {
                "  carried ${body.fluid.block.descriptionId}: ${said(stands)}"
            }
        }
        // **And what is actually standing there**, which is the half this was missing and the half a
        // headless walk kept getting wrong (2026-09-11). Everything above is what the *fields* intend;
        // carvers run after them, and so does the abyss sweep, so the blocks can and do disagree.
        //
        // **`getChunk` rather than a `forceload` from the console.** A forceload only schedules generation,
        // and an ungenerated chunk answers **air** to every query — so probing one too early reports an
        // empty world and reads exactly like data. Two passes over the same Age disagreed about the same
        // block before anyone noticed. This blocks until the column is really there.
        //
        // Printed as runs, because a column is mostly repetition and its *shape* is the question: where
        // the water stops, how much air is over it, what the floor is made of.
        level.getChunk(x shr CHUNK_BITS, z shr CHUNK_BITS)
        report.say { "  and what actually stands there:" }
        var runFrom = PROBE_FROM
        var running = blockName(level, x, PROBE_FROM, z)
        fun sayRun(from: Int, to: Int, what: String) {
            val where = if (from == to) "y $from" else "y $from..$to"
            report.fact("laid", "$where $what") { "    ${where.padEnd(PROBE_RUN_COLUMN)} $what" }
        }
        for (y in (PROBE_FROM + 1)..PROBE_TO) {
            val here = blockName(level, x, y, z)
            if (here == running) continue
            sayRun(runFrom, y - 1, running)
            runFrom = y
            running = here
        }
        sayRun(runFrom, PROBE_TO, running)

        // The aquifer's claim, and the one that hid a flooded rift: it is asked *before* the sea and
        // answers from the water table, so anything it claims is wet whatever keeps the sea out.
        val hollow = generator.hollows?.columnSpans(x, z) ?: Spans.EMPTY
        report.fact("aquifer", said(hollow)) { "  aquifer answers for: ${said(hollow)}" }
        // What vanilla's surface system is told the surface is — how deep a frozen ocean's icebergs may reach.
        val preliminary = generator.preliminarySurfaceAt(x, z)
        report.fact("preliminarySurface", "$preliminary") { "  preliminary surface, as vanilla's surface system reads it: y=$preliminary" }
        // The verdict, block by block through the band the sea could reach, which is what a walk is looking
        // at.
        //
        // **In the fill's own order, which this had wrong.** It read "the aquifer claims this *or* the sea
        // fills it", where `fillFromNoise` asks the aquifer **first** and only falls through to the sea for
        // space the aquifer does not answer for. So a cave under a hill was predicted full of sea to the
        // waterline when what stands in it is the aquifer's own pool, fifty blocks lower — the instrument
        // disagreeing with the world by more than the bug being hunted (2026-09-11).
        val wet = (PROBE_FROM..levelHere).filter { y ->
            when {
                rock.contains(y) -> false
                // The aquifer owns every hollow of ours, and it is the one that may answer "dry".
                hollow.contains(y) -> true
                else -> seaFill.fillsAt(y, levelHere, dryness, wetness) && !seaFill.isWalledAt(x, z, y, levelHere)
            }
        }
        val walled = (PROBE_FROM..levelHere).count { seaFill.isWalledAt(x, z, it, levelHere) }
        report.fact("walled", walled) {
            if (walled == 0) "  no wall here" else "  a wall holding this sea back from a lower one ($walled blocks)"
        }
        report.fact("filled", wet.size) {
            if (wet.isEmpty()) "  nothing is filled here between y=$PROBE_FROM and the waterline"
            else "  filled y=${wet.first()}..${wet.last()} (${wet.size} blocks)"
        }
        // Where the sea turns into an abyss, which nothing else here can answer: `blockAt` is per column
        // and says only what the sea is *made* of, so a probe over a hundred blocks of water reported
        // plain water and left the one question this Age was written to settle unanswerable.
        val sea = seaFill.blockAt(x, z)
        val surface = seaFill.surfaceYAt(x, z) ?: PROBE_FROM
        val abyss = wet.filter { y -> DeepWater.seaAt(y, DeepWater.lineBelow(surface), sea) != sea }
        report.fact("deepWater", abyss.size) {
            if (abyss.isEmpty()) {
                "  no deep water: nothing here has ${DeepWater.DEEPEST_VANILLA_SEA} unbroken blocks over it"
            } else {
                "  deep water y=${abyss.first()}..${abyss.last()} (${abyss.size} blocks)"
            }
        }
        // The contradiction that flooded every rift: a space kept dry that the aquifer also claims.
        report.only("dryAndAquifer", (PROBE_FROM..levelHere).count { dryness.contains(it) && hollow.contains(it) })
        sayClimate(level, generator, x, rock.highestSolidY ?: seaFill.level, z, report)
        report.finish()
        return SUCCESS
    }

    /**
     * **The climate this Age reads at the column's surface, and what it picks there** — the numbers a
     * biome's place is decided by, which nothing printed until a palm beach came out patched with warm
     * ocean (2026-09-30). The pick is the table's at that quart; the level's own answer goes through vanilla's
     * fuzzy zoom first, so the two can differ near an edge, and both are said.
     */
    private fun sayClimate(level: ServerLevel, generator: AgeChunkGenerator, x: Int, y: Int, z: Int, report: Report) {
        val biomes = generator.biomeSource as? AgeBiomeSource ?: return
        val sampler = level.chunkSource.randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED)
        val point = biomes.climateAt(sampler, x, y, z)
        fun read(value: Long) = "%.3f".format(Climate.unquantizeCoord(value))
        val numbers = "T=${read(point.temperature())} H=${read(point.humidity())} C=${read(point.continentalness())} " +
            "E=${read(point.erosion())} D=${read(point.depth())} W=${read(point.weirdness())}"
        report.fact("climate", numbers) { "  climate at the surface (y=$y): $numbers" }
        val picked = biomes.createResolver(sampler)
            .getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
        val shown = level.getBiome(BlockPos(x, y, z))
        fun named(biome: Holder<Biome>) =
            biome.unwrapKey().map { it.identifier().toString() }.orElse("?")
        report.fact("biome", named(picked)) { "  the table picks ${named(picked)}; the level shows ${named(shown)}" }
    }

    private fun locateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("locate").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(PRESET_ARGUMENT, StringArgumentType.word()).executes(::runLocate),
            ),
        )

    private fun generateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("gen").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runGenerate),
        )

    /**
     * `/age cliffs <name> [radius]` — **how sheer this Age's ground is, and how that changes with height.**
     *
     * Written to answer one question with numbers rather than with an impression: a crystal that grows only
     * on sheer faces needs to know what a sheer face *is* in terrain that actually exists, and eyeballing a
     * mountain gives you the tallest thing you saw rather than the distribution.
     *
     * **Heightmaps, not chunks.** `getBaseHeight` samples the noise column without generating anything, so
     * a survey of forty thousand columns costs seconds and touches no region file. What it measures is the
     * drop from each column to its lowest neighbour, which is the height of the face standing there.
     */
    private fun cliffSurveySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("cliffs") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runCliffSurvey(context, DEFAULT_CLIFF_RADIUS, reportFor(context)) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_CLIFF_RADIUS))
                        .executes { context ->
                            runCliffSurvey(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT), reportFor(context))
                        },
                )
        }

    /**
     * What grows where, as a share of the ground.
     *
     * **Use a large radius.** Vanilla's continentalness varies over something like a thousand blocks, so a
     * census of six chunks sits inside one band of it and reports that band as the whole world — six chunks
     * of a `craterlands` Age said 95% ocean where forty-eight said 20%. A small answer here is not a
     * measurement, it is one place.
     */
    private fun biomeCensusSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("biomes") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runBiomeCensus(context, SURVEY_RADIUS_CHUNKS, reportFor(context)) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_CENSUS_RADIUS))
                        .executes { context ->
                            val radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)
                            runBiomeCensus(context, radius, reportFor(context))
                        },
                )
        }

    /**
     * `/age spawns <age> [radius]` — **what this Age offers a spawn attempt**, above ground and below.
     *
     * The instrument nothing had: a written creature that never arrives is failing at one of four places —
     * the sentence, the recipe, the list `getMobsAt` builds, or vanilla's own placement check — and only
     * `/age list` could see any of them. This asks the Age's own generator the question the spawner asks,
     * at real positions, and prints what comes back.
     */
    private fun spawnsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("spawns") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runSpawnCensus(context, SPAWN_SAMPLE_RADIUS, reportFor(context)) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0, MAX_CENSUS_RADIUS))
                        .executes { context ->
                            val radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)
                            runSpawnCensus(context, radius, reportFor(context))
                        },
                )
        }

    private fun benchmarkSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("bench").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runBenchmark(context, DEFAULT_BENCHMARK_RADIUS) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_BENCHMARK_RADIUS))
                        .executes { context ->
                            runBenchmark(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT))
                        },
                ),
        )

    private fun compareSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("compare") { reportFor ->
            Commands.argument(FIRST_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(SECOND_ARGUMENT, StringArgumentType.word())
                    .executes { context -> runCompare(context, DEFAULT_COMPARE_RADIUS, reportFor(context)) }
                    .then(
                        Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0, MAX_COMPARE_RADIUS))
                            .executes { context ->
                                val radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)
                                runCompare(context, radius, reportFor(context))
                            },
                    ),
            )
        }

    /**
     * Generates the same square of chunks in two Ages and compares them block for block — the instrument
     * for "an Age is its recipe". Write two with a shared seed and this says whether they agree.
     *
     * Answers for the generator, not the save format: both are generated in one server run. Reads every
     * block rather than sampling, because the differences worth catching are small.
     */
    private fun runCompare(context: CommandContext<CommandSourceStack>, radius: Int, report: Report): Int {
        val source = context.source
        val firstName = StringArgumentType.getString(context, FIRST_ARGUMENT)
        val secondName = StringArgumentType.getString(context, SECOND_ARGUMENT)

        val (firstId, firstRecipe) = namedAge(source, firstName, report) ?: return FAILURE
        val first = Ages.open(source.server, firstId)
        val (secondId, secondRecipe) = namedAge(source, secondName, report) ?: return FAILURE
        val second = Ages.open(source.server, secondId)

        report.say { "Comparing '$firstName' [$firstRecipe] with '$secondName' [$secondRecipe]" }

        // Each world generated whole before the other is touched, so this asks whether the recipe
        // reproduces rather than whether two interleaved worlds happen to agree.
        //
        // **These `getChunk` calls look serial and are not** — measured, because they are the whole cost of
        // this command and the obvious optimisation is wrong. A blocking `getChunk` pumps the server thread
        // while the chunk system generates that chunk's whole neighbourhood on its worker pool, so walking
        // a square already keeps the pool fed: asking for all 289 futures up front and waiting on the lot
        // changed radius 8 by nothing at all, at five to six cores busy throughout. The block-for-block
        // diff below is not the cost either — a second compare over loaded chunks answers in a second
        // against twenty-six. What is left is worldgen arithmetic, and the only lever on it is [radius].
        val chunks = (-radius..radius).flatMap { chunkX -> (-radius..radius).map { chunkZ -> chunkX to chunkZ } }
        for ((chunkX, chunkZ) in chunks) first.getChunk(chunkX, chunkZ)
        for ((chunkX, chunkZ) in chunks) second.getChunk(chunkX, chunkZ)

        val differences = chunks.map { (chunkX, chunkZ) -> compareChunk(first, second, chunkX, chunkZ) }
        val differingBlocks = differences.sumOf { it.blocks }
        val differingChunks = differences.count { it.blocks > 0 }

        // The prose says one of two sentences and the document always says the same three numbers: a
        // reader should not have to notice that "identical" is where the zero went.
        report.fact("differingBlocks", differingBlocks) {
            val verdict = if (differingBlocks == 0) {
                "identical: ${chunks.size} chunks agree block for block"
            } else {
                "$differingBlocks block(s) differ across $differingChunks of ${chunks.size} chunks"
            }
            "  $verdict"
        }
        report.only("differingChunks", differingChunks)
        report.only("chunks", chunks.size)
        report.only("identical", differingBlocks == 0)
        for (example in differences.flatMap { it.examples }.take(MAX_REPORTED_DIFFERENCES)) {
            report.entry("examples", mapOf("at" to example)) { "  $example" }
        }
        report.finish()
        return SUCCESS
    }

    /** A column of the spawn chunk, by its local position and how high it stands. */
    private data class Column(val x: Int, val z: Int, val height: Int)

    /** The spawn chunk's tallest column — which is what reveals instanced geometry above the ground. */
    private fun tallestColumn(level: ServerLevel): Column {
        var tallest = Column(0, 0, Int.MIN_VALUE)
        for (localX in 0..<BLOCKS_PER_CHUNK) {
            for (localZ in 0..<BLOCKS_PER_CHUNK) {
                val height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ)
                if (height > tallest.height) tallest = Column(localX, localZ, height)
            }
        }
        return tallest
    }

    /** How far apart one chunk is in two Ages: how many blocks, and a few of them named. */
    private data class ChunkDifference(val blocks: Int, val examples: List<String>)

    /** Every block of one chunk against the other's, keeping the first few disagreements. */
    private fun compareChunk(first: ServerLevel, second: ServerLevel, chunkX: Int, chunkZ: Int): ChunkDifference {
        val here = first.getChunk(chunkX, chunkZ)
        val there = second.getChunk(chunkX, chunkZ)
        val cursor = BlockPos.MutableBlockPos()
        val examples = mutableListOf<String>()
        var differences = 0

        for (localX in 0..<BLOCKS_PER_CHUNK) {
            for (localZ in 0..<BLOCKS_PER_CHUNK) {
                for (y in here.minY..here.maxY) {
                    cursor.set(chunkX * BLOCKS_PER_CHUNK + localX, y, chunkZ * BLOCKS_PER_CHUNK + localZ)
                    val mine = here.getBlockState(cursor)
                    val theirs = there.getBlockState(cursor)
                    if (mine == theirs) continue
                    differences++
                    if (examples.size < MAX_REPORTED_DIFFERENCES) {
                        examples += "at (${cursor.x}, ${cursor.y}, ${cursor.z}): " +
                            "${BuiltInRegistries.BLOCK.getKey(mine.block)} vs ${BuiltInRegistries.BLOCK.getKey(theirs.block)}"
                    }
                }
            }
        }
        return ChunkDifference(differences, examples)
    }

    /**
     * Force-generates the Age's spawn chunk by the real chunk-gen path and reports what it made — a
     * headless sanity check needing no player.
     */
    private fun runGenerate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
        level.getChunk(0, 0) // force full generation of the spawn chunk
        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)
        val surfaceBlock = blockName(level, 0, surfaceY - 1, 0)
        val peak = tallestColumn(level)
        val peakBlock = blockName(level, peak.x, peak.height - 1, peak.z)
        source.sendSuccess({
            Component.literal(
                "Age '$name' spawn chunk: origin surface y=$surfaceY ($surfaceBlock); " +
                    "tallest column y=${peak.height} at (${peak.x},${peak.z}) ($peakBlock)",
            )
        }, false)
        surveyBiomes(level, SURVEY_RADIUS_CHUNKS).forEach { line ->
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
        return SUCCESS
    }

    /**
     * Times full generation of the `(2·radius+1)²` chunks around the Age's origin — the expensive case,
     * since a density gradient packs the most instances there. Run it on a freshly created Age: chunks
     * already generated come from the cache and time nothing.
     */
    private fun runBenchmark(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
        val startedAt = System.nanoTime()
        for (chunkX in -radius..radius) {
            for (chunkZ in -radius..radius) {
                level.getChunk(chunkX, chunkZ)
            }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val chunkCount = (2 * radius + 1) * (2 * radius + 1)
        source.sendSuccess({
            Component.literal(
                "Age '$name': generated $chunkCount chunks in ${"%.0f".format(elapsedMillis)} ms " +
                    "(${"%.2f".format(elapsedMillis / chunkCount)} ms/chunk)",
            )
        }, false)
        return SUCCESS
    }

    private fun blockName(level: ServerLevel, x: Int, y: Int, z: Int): String =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).toString()

    /**
     * `/age locate <name> <preset>` — how far to the nearest territory of that terrain.
     *
     * The instrument for "is a rare share findable". Answered from [RegionMap] alone, which is a pure
     * function of the column, so this searches far beyond a `/locate` and generates nothing.
     */
    private fun runLocate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val wanted = StringArgumentType.getString(context, PRESET_ARGUMENT)
        val recipe = namedAge(source, name, Report.prose(source))?.recipe ?: return FAILURE

        val world = recipe.world
        if (world !is AgeWorld.Composed) {
            source.sendFailure(Component.literal("'$name' is a bespoke Age, so it has no territories to find."))
            return FAILURE
        }
        val composition = world.composition
        val member = composition.terrains.indexOfFirst { it.key == wanted }
        if (member < 0) {
            val present = composition.terrains.joinToString(" ") { it.key }
            source.sendFailure(Component.literal("'$name' has no '$wanted' territory. It has: $present"))
            return FAILURE
        }
        if (composition.terrains.size <= 1) {
            source.sendFailure(Component.literal("'$name' is undivided, so '$wanted' is everywhere."))
            return FAILURE
        }

        val map = recipe.character.mapFor(Aspect.TERRAIN, composition.spreadOf(Aspect.TERRAIN), recipe.seed)
        val from = BlockPos.containing(source.position)
        val found = nearestColumnOf(map, member, from.x, from.z)
        if (found == null) {
            source.sendFailure(
                Component.literal(
                    "No '$wanted' column within $LOCATE_RADIUS_BLOCKS blocks of you. That is a real answer " +
                        "about its share, not a failure to look.",
                ),
            )
            return FAILURE
        }
        val distance = Math.sqrt(
            ((found.x - from.x).toDouble() * (found.x - from.x) + (found.z - from.z).toDouble() * (found.z - from.z)),
        )
        source.sendSuccess({
            Component.literal(
                "Nearest '$wanted' in '$name': ${found.x}, ${found.z} — about %.0f blocks away".format(distance),
            )
        }, false)
        return SUCCESS
    }

    /**
     * The nearest column belonging to [member], or null within [LOCATE_RADIUS_BLOCKS].
     *
     * Rings outward on a stride rather than testing every column: a territory is hundreds of blocks
     * across, so a stride far below that cannot step over one, and it is the difference between a
     * millisecond and a minute.
     */
    private fun nearestColumnOf(map: RegionMap, member: Int, fromX: Int, fromZ: Int): BlockPos? {
        if (map.memberAt(fromX, fromZ) == member) return BlockPos(fromX, 0, fromZ)
        var ring = LOCATE_STRIDE_BLOCKS
        while (ring <= LOCATE_RADIUS_BLOCKS) {
            var nearest: BlockPos? = null
            var nearestDistance = Double.MAX_VALUE
            for (step in -ring..ring step LOCATE_STRIDE_BLOCKS) {
                for (candidate in ringColumnsAt(fromX, fromZ, ring, step)) {
                    if (map.memberAt(candidate.x, candidate.z) != member) continue
                    val away = (candidate.x - fromX).toDouble() * (candidate.x - fromX) +
                        (candidate.z - fromZ).toDouble() * (candidate.z - fromZ)
                    if (away < nearestDistance) {
                        nearestDistance = away
                        nearest = candidate
                    }
                }
            }
            if (nearest != null) return nearest
            ring += LOCATE_STRIDE_BLOCKS
        }
        return null
    }

    /** The four columns [step] along each side of the square ring at [ring] blocks out. */
    private fun ringColumnsAt(fromX: Int, fromZ: Int, ring: Int, step: Int): List<BlockPos> = listOf(
        BlockPos(fromX + step, 0, fromZ - ring),
        BlockPos(fromX + step, 0, fromZ + ring),
        BlockPos(fromX - ring, 0, fromZ + step),
        BlockPos(fromX + ring, 0, fromZ + step),
    )

    /**
     * Every creature the Age offers, by pass and by whether the sky is open — asked of the generator with
     * the same four arguments `NaturalSpawner` uses.
     */
    private fun runSpawnCensus(
        context: CommandContext<CommandSourceStack>,
        radiusChunks: Int,
        report: Report,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val age = namedAge(source, name, report) ?: return FAILURE
        val level = Ages.open(source.server, age.id)

        val generator = level.chunkSource.generator
        val structures = level.structureManager()
        report.only("generator", generator.javaClass.simpleName)

        val offered = sortedMapOf<String, MutableSet<String>>()
        val spread = radiusChunks * BLOCKS_PER_CHUNK
        for (blockX in -spread..spread step SPAWN_SAMPLE_STRIDE) {
            for (blockZ in -spread..spread step SPAWN_SAMPLE_STRIDE) {
                // **Generated first, and it is not ceremony.** The ground rule reads the level's own
                // heightmap, and an ungenerated chunk answers the world floor — so every position read as
                // out under the sky and the rule looked as though it were not there at all.
                level.getChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))
                val ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ)
                // The two heights a spawn attempt can be at, and the whole of what the ground rule reads:
                // vanilla draws its own uniformly between the world's floor and one above the surface.
                for ((where, blockY) in listOf("above" to ground, "below" to (ground + level.minY) / 2)) {
                    val here = BlockPos(blockX, blockY, blockZ)
                    for (pass in MobCategory.entries) {
                        val list = generator.getMobsAt(level, structures, pass, here)
                        for (entry in list.unwrap()) {
                            val creature = BuiltInRegistries.ENTITY_TYPE.getKey(entry.value().type())
                            offered.getOrPut("${pass.getName()} $where") { sortedSetOf() }
                                .add("$creature x${entry.weight()}")
                        }
                    }
                }
            }
        }

        // And what the Age places for itself, which vanilla's spawner never sees — counted by the gate that
        // refused it, since every one of them is doing its job and telling them apart is the diagnosis.
        val placing = AgeGeneration.spawnersFor(source.server, age.recipe)
            .filterIsInstance<AgeSpawner>()
            .firstOrNull()
        for (creature in placing?.placedCreatures.orEmpty()) {
            val outcomes = sortedMapOf<String, Int>()
            for (blockX in -spread..spread step SPAWN_SAMPLE_STRIDE) {
                for (blockZ in -spread..spread step SPAWN_SAMPLE_STRIDE) {
                    level.getChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))
                    // The creature's own heightmap, which is what the spawner picks columns on — a census
                    // that chose its own would reproduce a disagreement instead of reporting one.
                    val ground = level.getHeight(creature.surface, blockX, blockZ)
                    val outcome = checkNotNull(placing).tryAt(creature, level, BlockPos(blockX, ground, blockZ))
                    val said = if (outcome is AgeSpawner.Outcome.Standing) "would stand" else outcome.javaClass.simpleName
                    outcomes[said] = (outcomes[said] ?: 0) + 1
                }
            }
            val named = BuiltInRegistries.ENTITY_TYPE.getKey(creature.type)
            report.entry("places", mapOf("creature" to named.toString(), "outcomes" to outcomes)) {
                "  places $named (${creature.ground}, ${creature.spacing} apart): " +
                    outcomes.entries.joinToString(", ") { "${it.key} ${it.value}" }
            }
        }

        if (offered.isEmpty()) {
            report.fail("'$name' offers nothing at all, in any pass — which is not what an ordinary Age does")
            return FAILURE
        }
        for ((where, creatures) in offered) {
            report.entry("offered", mapOf("where" to where, "creatures" to creatures.toList())) {
                "  $where: ${creatures.joinToString(", ")}"
            }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * Every column's drop to its lowest neighbour, gathered into a distribution.
     *
     * Two questions, and the second is the one that decides whether "more of them higher up" is a rule the
     * terrain already keeps or one a feature has to impose: how *often* a face of each height occurs, and
     * where those faces sit in the column.
     */
    private fun runCliffSurvey(
        context: CommandContext<CommandSourceStack>,
        radiusChunks: Int,
        report: Report,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, report) ?: return FAILURE
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()

        val reach = radiusChunks * BLOCKS_PER_CHUNK
        val across = reach * 2 + 1
        val ground = Array(across) { x ->
            IntArray(across) { z ->
                generator.getBaseHeight(
                    x - reach,
                    z - reach,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    level,
                    randomState,
                )
            }
        }

        // The face standing at a column is how far the ground falls away beside it, so the lowest of the
        // four neighbours is the one that matters — a column on a ledge has a face even if three sides
        // are level with it.
        val faces = mutableListOf<Pair<Int, Int>>()
        for (x in 1..<across - 1) {
            for (z in 1..<across - 1) {
                val here = ground[x][z]
                val lowest = minOf(ground[x - 1][z], ground[x + 1][z], ground[x][z - 1], ground[x][z + 1])
                faces += (here - lowest) to here
            }
        }
        if (faces.isEmpty()) {
            report.fail("Nothing to measure in Age '$name'")
            return FAILURE
        }

        report.say { "Age '$name', ${faces.size} columns within $radiusChunks chunks:" }
        val tallest = faces.maxOf { it.first }
        report.fact("columns", faces.size) { "" }
        report.fact("tallest", tallest) { "  tallest face: $tallest blocks" }
        for (height in CLIFF_BANDS) {
            val standing = faces.filter { it.first >= height }
            val share = standing.size.toDouble() / faces.size
            report.entry(
                "face$height",
                mapOf(
                    "atLeast" to height,
                    "columns" to standing.size,
                    "share" to share,
                    "meanY" to standing.map { it.second }.average().takeIf { standing.isNotEmpty() },
                ),
            ) {
                val where = if (standing.isEmpty()) "" else ", mean y %.0f".format(standing.map { it.second }.average())
                "  a face of $height+ at %,d columns (%.3f%%)$where".format(standing.size, share * 100.0)
            }
        }
        // And the same question asked the other way round: within each band of the column, how much of the
        // ground there is standing at a sheer face. This is what says whether height already selects for it.
        val floor = faces.minOf { it.second }
        val ceiling = faces.maxOf { it.second }
        val step = ((ceiling - floor) / CLIFF_Y_BANDS).coerceAtLeast(1)
        var band = floor
        while (band <= ceiling) {
            val within = faces.filter { it.second >= band && it.second < band + step }
            if (within.isNotEmpty()) {
                val sheer = within.count { it.first >= CLIFF_WORTH_CALLING_ONE }
                val share = sheer.toDouble() / within.size
                report.entry(
                    "band$band",
                    mapOf("fromY" to band, "toY" to band + step, "columns" to within.size, "sheer" to share),
                ) {
                    "  y $band..${band + step - 1}: %.2f%% of %,d columns stand at a face of $CLIFF_WORTH_CALLING_ONE+"
                        .format(share * 100.0, within.size)
                }
            }
            band += step
        }
        // And what a thing growing only on sheer faces would actually yield here, so the ramp is tuned
        // against measured ground rather than against an impression of it.
        val sheer = faces.filter { it.first >= SheerFace.SHEER_BLOCKS }
        val expected = sheer.sumOf { SheerFace.likelihoodAt(it.second) }
        val chunks = faces.size.toDouble() / (BLOCKS_PER_CHUNK * BLOCKS_PER_CHUNK)
        report.fact("perChunk", expected / chunks) {
            "  → a thing growing on sheer faces would come to %.2f a chunk (%,d faces over %.0f chunks)"
                .format(expected / chunks, sheer.size, chunks)
        }
        report.finish()
        return SUCCESS
    }

    /**
     * What share of the surface each biome covers — the instrument for tuning biome weights, where
     * [surveyBiomes]'s "which biomes exist here" is the wrong question.
     *
     * Samples the biome source directly rather than generated chunks, so it measures the climate table
     * alone and costs no generation. Surface only.
     */
    private fun runBiomeCensus(
        context: CommandContext<CommandSourceStack>,
        radiusChunks: Int,
        report: Report,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, report) ?: return FAILURE

        val generator = level.chunkSource.generator
        val biomes = generator.biomeSource
        val randomState = level.chunkSource.randomState()
        val resolver = biomes.createUncachedResolver(randomState)
        val quartRadius = QuartPos.fromBlock(radiusChunks * BLOCKS_PER_CHUNK)

        val counts = mutableMapOf<String, Int>()
        for (quartX in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
            for (quartZ in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
                // **At the ground, which is what this command has always said it measures.** It asked at
                // `level.maxY` — the top of the world — and so reported the biome of the *sky*, which in an
                // Age is nearly always ocean and told a reader their world was drowned when it was not.
                // Biomes are three-dimensional here: `ClimateDepth` answers zero above the rock and rises
                // below it, so the height a census asks at is the whole of what it measures.
                val blockX = QuartPos.toBlock(quartX)
                val blockZ = QuartPos.toBlock(quartZ)
                val ground = generator.getBaseHeight(
                    blockX, blockZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState,
                )
                val here = biomeName(resolver, quartX, QuartPos.fromBlock(ground), quartZ)
                counts[here] = (counts[here] ?: 0) + 1
            }
        }
        val sampled = counts.values.sum()
        report.fact("sampled", sampled) {
            "Age '$name' surface biomes: $sampled samples within $radiusChunks chunks, ${counts.size} distinct"
        }
        report.only("distinct", counts.size)
        // Commonest first: the question is nearly always "did the thing I named take more ground".
        for ((biome, count) in counts.entries.sortedByDescending { it.value }) {
            val share = PER_CENT * count / sampled
            report.entry(
                "biomes",
                mapOf("biome" to biome, "samples" to count, "share" to share),
            ) { "  ${"%5.2f".format(share)}%  $biome ($count)" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * Which biomes an Age's source places over a wide area, and whether they change with depth — the
     * latter being down to [co.voik.agesandtheart.worldgen.biome.ClimateDepth] alone.
     */
    private fun surveyBiomes(level: ServerLevel, radiusChunks: Int): List<String> {
        val source = level.chunkSource.generator.biomeSource
        val resolver = source.createUncachedResolver(level.chunkSource.randomState())
        val lowestQuartY = QuartPos.fromBlock(level.minY)
        val highestQuartY = QuartPos.fromBlock(level.maxY)

        val everywhere = mutableSetOf<String>()
        val deepOnly = mutableSetOf<String>()
        var layeredColumns = 0
        var columns = 0

        val quartRadius = QuartPos.fromBlock(radiusChunks * BLOCKS_PER_CHUNK)
        for (quartX in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
            for (quartZ in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
                columns++
                val top = biomeName(resolver, quartX, highestQuartY, quartZ)
                everywhere += top
                var layered = false
                for (quartY in lowestQuartY..highestQuartY) {
                    val here = biomeName(resolver, quartX, quartY, quartZ)
                    everywhere += here
                    if (here != top) {
                        deepOnly += here
                        layered = true
                    }
                }
                if (layered) layeredColumns++
            }
        }
        return listOf(
            "$columns columns sampled within $radiusChunks chunks: ${everywhere.size} distinct biomes",
            "$layeredColumns of $columns columns change biome with depth",
            if (deepOnly.isEmpty()) "no below-surface biomes" else "below the surface: ${deepOnly.sorted().joinToString(", ")}",
        )
    }

    private fun biomeName(resolver: BiomeResolver, quartX: Int, quartY: Int, quartZ: Int): String =
        resolver.getNoiseBiome(quartX, quartY, quartZ).unwrapKey()
            .map { it.identifier().toString() }
            .orElse("(unnamed)")

    /** How wide a spawn census looks, and how coarsely — enough places to be sure, few enough to be quick. */
    private const val SPAWN_SAMPLE_RADIUS = 2

    private const val SPAWN_SAMPLE_STRIDE = 16

    /** Big enough to cross a mountain and small enough to answer in seconds. */
    private const val DEFAULT_CLIFF_RADIUS = 6

    private const val MAX_CLIFF_RADIUS = 16

    /** The face heights worth knowing the frequency of, from a step up to a wall. */
    private val CLIFF_BANDS = listOf(4, 8, 10, 12, 16, 24, 32)

    /** What the y-band readout counts as sheer, so the two halves of the survey are asked at one height. */
    private const val CLIFF_WORTH_CALLING_ONE = 10

    private const val CLIFF_Y_BANDS = 8

    /** Every fourth quart cell, i.e. one column per 16 blocks — dense enough to find small biomes. */
    private const val SURVEY_QUART_STRIDE = 4

    /** How far a census may reach. Larger than the benchmark's cap because this generates nothing. */
    private const val MAX_CENSUS_RADIUS = 512

    private const val BLOCKS_PER_CHUNK = 16

    /** Wide enough to cross several biomes at vanilla's scale, and free since nothing is generated. */
    private const val SURVEY_RADIUS_CHUNKS = 64

}
