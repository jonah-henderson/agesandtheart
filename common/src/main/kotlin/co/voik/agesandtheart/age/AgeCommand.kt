package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.age.consequence.Consequence
import net.minecraft.server.MinecraftServer
import co.voik.agesandtheart.age.consequence.Wounds
import net.minecraft.world.Difficulty
import net.minecraft.world.DifficultyInstance
import co.voik.agesandtheart.age.consequence.Tearing
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.age.phenomena.Meteors
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.worldgen.feature.SheerFace
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.AgeSpawner
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.AuroraGround
import co.voik.ephemeris.sky.Blending
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Rainbow
import co.voik.ephemeris.sky.Daylight
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.debug.LevelLookPreview
import co.voik.agesandtheart.sky.Skies
import net.minecraft.core.Direction
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.word.Resolver
import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelClock
import co.voik.ephemeris.sky.LevelLook
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.agesandtheart.age.phenomena.SandfallBehaviour
import co.voik.agesandtheart.age.phenomena.Tempest
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.platform.Services
import net.minecraft.commands.SharedSuggestionProvider
import co.voik.agesandtheart.age.word.DerivationRules
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.TerminalKind
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.location
import co.voik.agesandtheart.age.word.grammar.Sentence
import co.voik.agesandtheart.book.FoundBook
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.core.registries.Registries
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.MobCategory
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The `/age` debug command, until the player-facing books exist. Brigadier is vanilla, so the tree
 * lives in `common`; each loader hands us its [CommandDispatcher]. The preset named by `create` is an
 * [AgePreset].
 *
 * **Fenced because it is a usage synopsis**, where `[seed]` means an optional argument — outside a fence
 * KDoc reads every one of those as a link and reports it unresolved.
 *
 * ```
 * /age create <name> [seed]           — author a new Age (Spire preset) and persist it
 * /age create <preset> <name> [seed]  — the same, from any preset: hills, caverns, …
 * /age compose <name> [seed] <spec>   — author one out of aspects: terrain=hills sea=water
 * /age write <name> [seed] <words>    — author one out of *words*: beautiful floating riddled
 * /age words                          — the vocabulary the Art currently knows
 * /age pages [derived]                — a notebook of every word, for filling a desk to test writing with
 * /age forget [<word>]                — unlearn everything, or one word, so a device can be walked twice
 * /age tp <name>                      — travel to an Age
 * /age delete <name>|all              — discard an Age (or every Age), chunks and all
 * /age gen <name>                     — force-generate the spawn chunk and report what it made
 * /age bench <name> [radius]          — time generating the chunks around the origin (ms/chunk)
 * /age biomes <name> [radius]         — what share of the surface each biome covers (use a big radius)
 * /age locate <name> <preset>         — how far to the nearest territory of that terrain, from where you stand
 * /age book [seed]                    — a book the Art could have written, read back rather than given
 * /age draft <grammar> [seed]         — one expansion of a generation grammar: book, name, …
 * /age compare <a> <b> [radius]       — do two Ages generate the same world, block for block?
 * /age sky <name> [<spec>]            — read an Age's suns and moons, or preview different ones in it
 * /age strike [distance]              — call a bolt down where you are looking, to see a tempest land one
 * /age probe <name> <x> <z>           — what the generator thinks of a column: rock, sea, aquifer, dry
 * /age list                           — list known Ages (with their recipe)
 * ```
 */
object AgeCommand {
    /**
     * Level 2, as it always was — but a named check now rather than an integer. Vanilla replaced numeric
     * permission levels with a [net.minecraft.server.permissions.PermissionSet], and `LEVEL_GAMEMASTERS`
     * is the one that used to be spelled `hasPermission(2)`.
     */
    private val OPERATOR_PERMISSION = Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS)
    private const val DISTANCE_ARGUMENT = "distance"
    private const val PROBE_X = "x"
    private const val PROBE_Z = "z"

    /** Below any rift floor, so a probe covers the whole band a chasm could occupy. */
    private const val PROBE_FROM = 0

    private const val WORD_ARGUMENT = "word"
    private const val NAME_ARGUMENT = "name"

    /** `/age decay <name> age <days>` — the literal, and how far back it may reach. */
    private const val AGED_LITERAL = "age"

    /** `/age danger here` — a literal rather than a bare executable, so the tree stays uniform. */
    private const val HERE_LITERAL = "here"

    /** `/age danger score <name>` — the recipe's worth, as against [HERE_LITERAL]'s worth of the ground. */
    private const val SCORE_LITERAL = "score"

    /** `/age weather blizzard <intensity>` — how hard, where one is ordinary and three is fully bought. */
    private const val INTENSITY_ARGUMENT = "intensity"
    private const val ORDINARY_STORM = 1.0
    private const val WILDEST_STORM = 5.0

    /** How far `/age wounds here` looks — a good deal further than a wound corrupts, so it can say "none". */
    private const val LOOKS_FOR_WOUNDS_WITHIN = 128.0

    /** Blocks to a chunk, as a shift. */
    private const val CHUNK_BITS = 4

    /** What one `/age showing <what>` branch is called — "stop waiting for it". */
    private const val NOW_LITERAL = "now"

    /** The one subcommand every phenomenon that is *seen rather than done* answers under. See [Seen]. */
    private const val SHOWING_LITERAL = "showing"

    /** How far ahead the report looks for the next night a curtain comes. */
    private const val NIGHTS_LOOKED_AHEAD = 60L

    /** Half a chunk out, which is the ring the client samples the ground over. */
    private const val GROUND_RING_BLOCKS = 24

    /** Below this nothing is on the screen — the same floor `AuroraPainter` declines to draw at. */
    private const val NOTHING_SHOWING = 0.0f

    private const val EVERY_NIGHT = 1.0f

    /**
     * How far vanilla darkens its sky between noon and midnight — `skyDarken` is `15 - skyLightLevel`, and
     * the overworld runs 15 by day to 4 by night.
     */
    private const val FULLY_DARKENED = 11.0f

    private const val PER_CENT = 100.0f

    /** Degrees in a turn, for naming the quarter a curtain crosses. */
    private const val WHOLE_COMPASS = 360

    /** Vanilla's chance that a mob arrives with anything on at all, before the multiplier scales it. */
    private const val ARMS_ANYTHING_AT_ALL = 0.15f
    private const val DAYS_ARGUMENT = "days"
    private const val MOST_DAYS = 100_000

    /** `/age decay <name> unstable <n>` — the index, set by hand rather than earned. */
    private const val UNSTABLE_LITERAL = "unstable"
    private const val INDEX_ARGUMENT = "index"
    private const val MOST_INSTABILITY = 10_000
    private const val RADIUS_ARGUMENT = "radius"
    private const val SEED_ARGUMENT = "seed"
    private const val SPECIFICATION_ARGUMENT = "spec"
    private const val SENTENCE_ARGUMENT = "words"

    /** `/age tags <aspect> [tag]`. */
    private const val ASPECT_ARGUMENT = "aspect"
    private const val REGISTRY_ARGUMENT = "registry"
    private const val TAGS_LITERAL = "tags"
    private const val TAG_ARGUMENT = "tag"

    /** The other half of the corpus: one word per block in the pack, and all of them materials. */
    private const val DERIVED_LITERAL = "derived"

    /** Vanilla's End arrival platform, which is where a portal would have put you. */
    private const val END_PLATFORM_X = 100.5
    private const val END_PLATFORM_Y = 49.0
    private const val END_PLATFORM_Z = 0.5
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

    /** Far enough that `/age strike` does not land on the caster, near enough to watch it land. */
    private const val DEFAULT_STRIKE_DISTANCE = 12
    private const val MAX_STRIKE_DISTANCE = 128

    /** Far enough out to watch one come, and inside what is loaded at an ordinary view distance. */
    private const val DEFAULT_SANDFALL_DISTANCE = 64

    /** Far enough that the sky reads as somewhere else, near enough to walk to before it falls. */
    private const val DEFAULT_STORM_DISTANCE = 90
    private const val MAX_STORM_DISTANCE = 512

    /** A body every this many ticks, matching what an ordinary written storm drops. */
    private const val STORM_EVERY = 5

    private const val LOOK_LITERAL = "look"

    /**
     * How steeply a storm may be asked to come in, in degrees off the horizontal.
     *
     * Wider than the ten to forty-five a storm draws for itself, deliberately: this is the instrument for
     * judging whether that range is the right one, and it cannot answer that from inside it.
     */
    /** Raises one as if nothing were drawing it, for telling a lure's doing from a storm's own. */
    private const val IGNORE_LURE_LITERAL = "ignore_lure"

    private const val DEGREES_ARGUMENT = "degrees"
    private const val SHALLOWEST_SLANT = 1
    private const val STEEPEST_SLANT = 90

    /** Which body's light `look` turns you to, and how far along its approach that light is. */
    private const val FIRST_BODY = 0
    private const val JUST_SIGHTED = 0.0f
    private const val MIDDLE_OF_A_BLOCK = 0.5
    private const val MAX_SANDFALL_DISTANCE = 256
    private const val SECONDS_ARGUMENT = "seconds"

    /** `/age sandfall <distance> <seconds> <fury>` — how far into what instability could buy, as a percent. */
    private const val FURY_ARGUMENT = "fury"
    private const val NO_FURY = 0
    private const val ALL_FURY = 100
    private const val MOST_SANDFALL_SECONDS = 3600
    private const val TICKS_PER_SECOND = 20

    /** What `/age sky`'s preview spec may name, and the prefix its parameters carry. */
    private const val SKY_ASPECT = "sky"

    /** What `/age sky` prefixes the Age's clock reading with, and what `SkyClockCheck` looks for. */
    const val CLOCK_LABEL = "clock"

    /** What `/age sky` prefixes the *mapped* hour with — the one every colour in the sky is keyed to. */
    const val LIT_AS_LABEL = "lit as"

    private const val VANILLA_DAY = 24000L

    /**
     * How far `/age locate` looks, and how finely. The stride is far below the smallest territory a share
     * can produce, so it cannot step over one.
     */
    private const val LOCATE_RADIUS_BLOCKS = 20_000
    private const val LOCATE_STRIDE_BLOCKS = 64

    /**
     * A landform to satisfy `AgeComposition.parse`, which refuses a composition without one. Read by
     * nothing.
     *
     * **Built from the aspect's own key** rather than spelled, because it was spelled and outlived the name
     * it spelled: renaming the aspect left this saying `terrain=hills` to a parser that had stopped knowing
     * the word, and every sky-preview check failed with it.
     */
    private val PREVIEW_SCAFFOLD = "${Aspect.TERRAIN.page}=${Terrain.HILLS.key}"

    // Brigadier command result codes.
    private const val SUCCESS = 1
    internal const val FAILURE = 0

    /**
     * A subcommand that can answer either way: as prose, or — written `/age <name> json …` — as one
     * structured document (see [Report]).
     *
     * **The literal goes immediately after the subcommand, not at the end**, and `/age write` is why: its
     * sentence is a greedy string, so anything after it is swallowed as another word. One position for all
     * of them beats a rule with an exception in it.
     *
     * [arguments] is called *twice* to build two independent subtrees. That is the point of taking a
     * builder rather than a node: a Brigadier node cannot be hung in two places, and writing the tree out
     * twice is the duplication that would drift.
     *
     * **Only commands that really build a document get one**, so a `json` that parses always means
     * structure exists behind it.
     */
    private fun reporting(
        name: String,
        arguments: (ReportFor) -> ArgumentBuilder<CommandSourceStack, *>,
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .then(arguments { context -> Report.prose(context.source) })
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .then(arguments { context -> Report.structured(context.source) }),
            )

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires(OPERATOR_PERMISSION)
                .then(createSubcommand())
                .then(composeSubcommand())
                .then(writeSubcommand())
                .then(vocabularySubcommand())
                .then(tagsSubcommand())
                .then(rulesSubcommand())
                .then(dimensionsSubcommand())
                .then(holdingsSubcommand())
                .then(pagesSubcommand())
                .then(forgetSubcommand())
                .then(weatherSubcommand())
                .then(showingSubcommand())
                .then(teleportSubcommand())
                .then(deleteSubcommand())
                .then(generateSubcommand())
                .then(locateSubcommand())
                .then(bookSubcommand())
                .then(bindSubcommand())
                .then(draftSubcommand())
                .then(biomeCensusSubcommand())
                .then(cliffSurveySubcommand())
                .then(benchmarkSubcommand())
                .then(compareSubcommand())
                .then(spawnsSubcommand())
                .then(skySubcommand())
                .then(strikeSubcommand())
                .then(sandfallSubcommand())
                .then(meteorsSubcommand())
                .then(probeSubcommand())
                .then(decaySubcommand())
                .then(dangerSubcommand())
                .then(woundsSubcommand())
                .then(listSubcommand()),
        )
    }

    /**
     * `/age create [<preset>] <name> [<seed>]` — one branch per [AgePreset], built from the enum, so a
     * new preset is offered the moment it exists. The bare form stays Spire.
     *
     * The optional seed is what makes `/age compare` worth anything: an Age otherwise seeds itself from
     * its own name, so naming the seed is the only way to write the same recipe twice.
     */
    private fun createSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val create = Commands.literal("create").then(namedAge(AgePreset.SPIRE))
        for (preset in AgePreset.entries) {
            create.then(Commands.literal(preset.key).then(namedAge(preset)))
        }
        return create
    }

    /** `<name> [<seed>]`, the tail every `create` branch ends in. */
    private fun namedAge(preset: AgePreset) =
        Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
            .executes { context -> runCreate(context, preset, seed = null) }
            .then(
                Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                    .executes { context -> runCreate(context, preset, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
            )

    /**
     * `/age compose <name> [<seed>] <spec>`, where the spec runs to the end of the line.
     *
     * The seed sits before the spec because a greedy argument can have nothing after it, and the seeded
     * tail is registered first so `compose age 42 …` reads the 42 as a seed.
     */
    private fun composeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("compose").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg()).then(
                        Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                            .executes { context -> runCompose(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
                    ),
                )
                .then(
                    Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context -> runCompose(context, seed = null) },
                ),
        )

    /**
     * `/age write <name> [<seed>] <words…>` — authors an Age out of words rather than aspect names.
     *
     * Shaped like `compose` so the two can be diffed: what this resolves to prints in `compose`'s own
     * spelling, and pasting that back with the same seed must give the same world.
     */
    private fun writeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("write") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg()).then(
                        Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                            .executes { context ->
                                val seed = LongArgumentType.getLong(context, SEED_ARGUMENT)
                                runWrite(context, seed, reportFor(context))
                            },
                    ),
                )
                .then(
                    Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context -> runWrite(context, seed = null, report = reportFor(context)) },
                )
        }

    private fun vocabularySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("words")
            .executes { context -> runVocabulary(context, Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runVocabulary(context, Report.structured(context.source)) },
            )

    /**
     * `/age tags <aspect> [tag]` — **what a vague word can actually find**, member by member.
     *
     * The instrument the tag pass needed and did not have (`notes/the-tag-layer.md` §7). Reading the rules
     * says what they were meant to claim; only asking a tag what carries it says what they do — and half
     * the layer is vanilla's own tags, which are bound on a server and nowhere else, so this is the one
     * place the whole picture exists.
     */
    /**
     * **Every dimension this server has**, ours and everyone else's.
     *
     * A book starts from a base dimension — one of vanilla's three today, because `AgeTemplate` names
     * their noise settings, biome sources and dimension types in code. Nothing about the mechanism is
     * vanilla-only: what a base supplies is a chunk generator and a dimension type, which a modded
     * dimension has as surely as the nether does.
     *
     * This is here so Scrivener can see what a server actually offers, ahead of anything being able
     * to use it. Reading the list is the cheap half; making a template out of one is the other half, and
     * it wants `AgeTemplate` to stop being an enum first.
     */
    private fun dimensionsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("dimensions") { reportFor ->
            Commands.literal("all").executes { context -> runDimensions(context, reportFor(context)) }
        }

    private fun runDimensions(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val server = context.source.server
        val ours = AgeSavedData.get(server).ages.map { it.toString() }.toSet()
        val stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
        val ids = stems.listElementIds().map { it.identifier().toString() }.toList().sorted()
        report.fact("dimensions", ids.size) { "This server has ${ids.size} dimension(s):" }
        for (id in ids) {
            report.entry("dimension", mapOf("id" to id, "ours" to (id in ours))) {
                "  $id${if (id in ours) "  (an Age)" else ""}"
            }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * **What a registry holds, and what its tags carry** — the other half of what only a running game
     * knows, and the one Scrivener needs to offer a list instead of a blank prompt.
     *
     * A pack's own placed features are datapack content and exist nowhere until a server has loaded them,
     * so a word that mints one could only ever be typed. Block tags are the same: `#minecraft:stone_ore_
     * replaceables` is a perfectly good pool for a formation to be made of and nothing offline can name it.
     *
     * General rather than one command per registry, because the next thing the tool wants to offer will be
     * a third registry and this already answers for it. Named the way a registry is — `minecraft:block`,
     * `minecraft:worldgen/placed_feature`.
     */
    private fun holdingsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("holdings") { reportFor ->
            // **An id argument, not a string one.** Brigadier's unquoted word stops at a colon and a
            // slash, so `minecraft:worldgen/placed_feature` had to be typed in quotes to parse at all.
            Commands.argument(REGISTRY_ARGUMENT, IdentifierArgument.id())
                .suggests { context, builder ->
                    SharedSuggestionProvider.suggest(registryNames(context.source.server), builder)
                }
                .executes { context -> runHoldings(context, tags = false, report = reportFor(context)) }
                .then(
                    Commands.literal(TAGS_LITERAL)
                        .executes { context -> runHoldings(context, tags = true, report = reportFor(context)) },
                )
        }

    private fun registryNames(server: MinecraftServer): List<String> =
        server.registryAccess().listRegistryKeys().map { it.identifier().toString() }.sorted().toList()

    private fun runHoldings(context: CommandContext<CommandSourceStack>, tags: Boolean, report: Report): Int {
        val server = context.source.server
        val id = IdentifierArgument.getId(context, REGISTRY_ARGUMENT)
        val named = id.toString()
        val registry = server.registryAccess().lookup(ResourceKey.createRegistryKey<Any>(id)).orElse(null)
        if (registry == null) {
            context.source.sendFailure(Component.literal("No registry called '$named'"))
            return FAILURE
        }
        if (tags) {
            val carried = registry.listTags()
                .map { it.key().location().toString() to it.size() }
                .toList()
                .sortedBy { it.first }
            report.fact("tags", carried.size) { "$named has ${carried.size} tag(s):" }
            for ((tag, carriers) in carried) {
                report.entry("tag", mapOf("id" to tag, "carriers" to carriers)) { "  $tag  ($carriers)" }
            }
        } else {
            val ids = registry.listElementIds().map { it.identifier().toString() }.toList().sorted()
            report.fact("holdings", ids.size) { "$named holds ${ids.size}:" }
            for (held in ids) report.entry("holding", mapOf("id" to held)) { "  $held" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * **What each derivation rule alone puts into a set** — the half of the tag layer an offline corpus
     * cannot see.
     *
     * Registry tags are bound by a running game, so 84 of the 142 rules match nothing without one. The
     * Scrivener asks this once through `--refresh` and remembers the answer, which is what lets it show
     * what fills `ore` on a screen with no server behind it (`notes/the-tag-layer.md` §4).
     */
    private fun rulesSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("rules") { reportFor ->
            Commands.literal("all").executes { context -> runRules(context, report = reportFor(context)) }
        }

    private fun runRules(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val server = context.source.server
        val vocabulary = Vocabulary.of(server)
        val registries = server.registryAccess()
        val rules = DerivationRules.rulesIn(vocabulary.derivation)
        report.fact("rules", rules.size) { "${rules.size} rules:" }
        for (rule in rules) {
            val caught = DerivationRules.catches(registries, rule)
            report.entry(
                "rule",
                mapOf(
                    "id" to rule.id,
                    "aspect" to rule.aspect.page,
                    "reads" to rule.key,
                    "byTag" to rule.byTag,
                    "caught" to caught,
                ),
            ) { "  ${rule.id} — ${caught.size}" }
        }
        report.finish()
        return SUCCESS
    }

    private fun tagsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("tags") { reportFor ->
            Commands.argument(ASPECT_ARGUMENT, StringArgumentType.word())
                .suggests { _, builder ->
                    SharedSuggestionProvider.suggest(Aspect.entries.map { it.page }, builder)
                }
                .executes { context -> runTags(context, tag = null, report = reportFor(context)) }
                .then(
                    Commands.argument(TAG_ARGUMENT, StringArgumentType.word())
                        .suggests { context, builder ->
                            SharedSuggestionProvider.suggest(
                                Vocabulary.of(context.source.server).carriedTags.sorted(),
                                builder,
                            )
                        }
                        .executes { context ->
                            runTags(context, StringArgumentType.getString(context, TAG_ARGUMENT), reportFor(context))
                        },
                )
        }

    /**
     * Every tag one aspect's members carry, or every member carrying one tag — the second being what a
     * hand-tuning pass reads.
     *
     * **Weights and all**, because a tag at a sixth and a tag at nine tenths are what the tiers tell apart,
     * and a member sitting just over a threshold is exactly the sort of thing worth seeing.
     */
    private fun runTags(context: CommandContext<CommandSourceStack>, tag: String?, report: Report): Int {
        val source = context.source
        val named = StringArgumentType.getString(context, ASPECT_ARGUMENT)
        val aspect = Aspect.entries.firstOrNull { it.page == named }
            ?: return report.fail("No aspect called '$named'. Try: ${Aspect.entries.joinToString(" ") { it.page }}")
        val vocabulary = Vocabulary.of(source.server)
        val reachable = vocabulary.askableIn(aspect)
        report.fact("aspect", aspect.page) { "${aspect.page}: ${reachable.size} reachable by description." }
        if (tag == null) {
            val counted = reachable.flatMap { vocabulary.tagsOf(it).keys }.groupingBy { it }.eachCount()
            for ((carried, many) in counted.entries.sortedByDescending { it.value }) {
                report.entry("tags", mapOf("tag" to carried, "carriers" to many)) { "  $carried — $many" }
            }
            report.finish()
            return SUCCESS
        }
        val carrying = reachable.mapNotNull { preset ->
            vocabulary.tagsOf(preset)[tag]?.let { preset to it }
        }.sortedByDescending { it.second }
        // **Two numbers, because only one of them is what a word finds.** Anything above nothing is
        // carried; only what clears a restrictive word's threshold is *reachable* by one, and a tail of
        // tenth-weight carriers otherwise reads as coverage it is not.
        val found = carrying.count { it.second >= Tier.RESTRICTIVE.threshold }
        report.fact("carriers", carrying.size) { "'$tag' is carried by ${carrying.size} of them:" }
        report.fact("found", found) { "  $found of those a restrictive word would keep." }
        for ((preset, weight) in carrying) {
            report.entry("carrying", mapOf("member" to preset.key, "weight" to weight)) {
                "  %-44s %.2f".format(preset.key, weight)
            }
        }
        report.finish()
        return SUCCESS
    }

    private fun teleportSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tp")
            // Literals resolve before arguments, so these never shadow an Age that happens to share a
            // name — and they tab-complete, which is the whole point of having them.
            .then(Commands.literal("overworld").executes { runVanillaTeleport(it, Level.OVERWORLD) })
            .then(Commands.literal("nether").executes { runVanillaTeleport(it, Level.NETHER) })
            .then(Commands.literal("end").executes { runVanillaTeleport(it, Level.END) })
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runTeleport))

    /**
     * `/age sky <name> [<spec>]` — read an Age's sky, or preview a different one in it.
     *
     * The spec form sends a sky to everyone standing in the Age and changes nothing about the Age, so
     * walking out and back in reverts it. Read by [AgeComposition.parse], so it is spelled like `compose`.
     */
    private fun skySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("sky").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runSkyReport(context, preview = null) }
                .then(
                    Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context ->
                            runSkyReport(context, StringArgumentType.getString(context, SPECIFICATION_ARGUMENT))
                        },
                ),
        )

    /**
     * `/age strike [<distance>]` — call a bolt down where you are looking, to see what a tempest does to
     * it without standing in the rain waiting.
     *
     * Aimed through [Tempest.callDown], so it is the *same* strike a tempest gets — including the rod.
     * The first version built a bolt at the position it was looking at and added it to the world, which
     * cratered correctly and could not be grounded by any amount of copper: vanilla moves a strike onto a
     * rod in the spawner, before a bolt exists, so a bolt made directly has already refused.
     */
    /**
     * `/age weather <clear|rain|thunder>` — set the weather of **the Age you are standing in**.
     *
     * Vanilla's `/weather` cannot reach one. An Age owns its own `WeatherData` so that its rain is its own
     * (`ServerLevelMixin`), and the command writes the *overworld's* — so standing in a burning Age and
     * asking for rain changed the weather somewhere else entirely, which is how this went unwalked.
     *
     * A debug affordance rather than a mechanic, and deliberately not a mixin on `WeatherCommand`: the
     * ability to make it rain on demand is worth exactly as much as the walk that needs it, and vanilla's
     * command is not wrong about the world it was aimed at.
     */
    private fun weatherSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("weather").apply {
            for ((name, wants) in AgeWeather.asked()) {
                val branch = Commands.literal(name)
                    .executes { context -> runWeather(context, name, wants, hardness = null) }
                // **Only the blizzard takes a strength**, because it is the only phenomenon whose weather
                // and whose fierceness are two different dials — everything else either wants a condition
                // or does not. One is ordinary and three is everything instability can buy.
                if (name == Phenomenon.BLIZZARD.key) {
                    branch.then(
                        Commands.argument(INTENSITY_ARGUMENT, DoubleArgumentType.doubleArg(ORDINARY_STORM, WILDEST_STORM))
                            .executes { context ->
                                runWeather(
                                    context,
                                    name,
                                    wants,
                                    DoubleArgumentType.getDouble(context, INTENSITY_ARGUMENT),
                                )
                            },
                    )
                }
                then(branch)
            }
        }

    private fun runWeather(
        context: CommandContext<CommandSourceStack>,
        name: String,
        wants: AgeWeather.Conditions,
        hardness: Double?,
    ): Int {
        val source = context.source
        val level = source.level
        val own = AgeWeather.of(level)
        if (own == null) {
            source.sendFailure(Component.translatable("commands.agesandtheart.weather.not_an_age"))
            return 0
        }
        // Asking for a plain blizzard puts the Age back on its own fierceness, so the override is never
        // something a walk can leave switched on without meaning to.
        if (name == Phenomenon.BLIZZARD.key) {
            if (hardness == null) Blizzard.release(level) else Blizzard.force(level, hardness)
        }
        AgeWeather.set(level, own, wants)
        source.sendSuccess({ Component.translatable("commands.agesandtheart.weather.set", name) }, true)
        return 1
    }

    /**
     * `/age showing aurora` — **why there is or is not a curtain in the sky right now**, and
     * `/age showing aurora now` to stop waiting for one.
     *
     * Written because an aurora is the first thing here that can be *correct and invisible*. Everything
     * about it is decided on the client from arithmetic, so nothing is logged, nothing is stored, and a
     * walk that sees no curtain cannot tell an Age that has none from a night it does not come from a
     * renderer that is broken. Three very different faults with one symptom, and no way to separate them
     * (Jonah, 2026-08-30, walked: "unable to see any auroras, even after waiting multiple days").
     *
     * **The server can answer all of it but the last.** Which nights a curtain comes is a pure function of
     * the spec, and the ground rule is a biome lookup — so this recomputes exactly what the client will,
     * from the same numbers, and says which factor is the one at nought. If it says a curtain should be
     * overhead and the sky is empty, the fault is in the drawing and nowhere else.
     */
    private fun showingSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun branchesUnder(parent: LiteralArgumentBuilder<CommandSourceStack>, reportFor: ReportFor) =
            Seen.entries.fold(parent) { tree, seen ->
                tree.then(
                    Commands.literal(seen.key)
                        .executes { context -> seen.reportInto(context, reportFor(context)) }
                        .then(Commands.literal(NOW_LITERAL).executes { context -> seen.bringOn(context) }),
                )
            }
        val prose = branchesUnder(Commands.literal(SHOWING_LITERAL)) { context -> Report.prose(context.source) }
        val structured = branchesUnder(Commands.literal(Report.STRUCTURED_LITERAL)) { context ->
            Report.structured(context.source)
        }
        return prose.then(structured)
    }

    /**
     * The phenomena that are **seen rather than done** — the ones `Happenings.befall` has nothing to run
     * for, because everything they do happens on the client from arithmetic it does for itself.
     *
     * That is exactly the set that needs a command like this, and the reason there is one command rather
     * than one per phenomenon. Each of these can be *correct and invisible*, for several different reasons
     * at once, and none of them logs anything: a walk that sees nothing cannot tell an Age that has none
     * from a day it does not come from a renderer that is broken. So the server recomputes what the client
     * will and says which factor is the one at nought.
     *
     * **Adding a phenomenon adds a constant here and nothing else.** `/age` gains no subcommand, the tree
     * is built by folding over these, and the third one to be seen rather than done costs a `key` and two
     * methods (Jonah, 2026-08-30: "we're not cluttering the `/age` namespace with every new phenomenon").
     */
    private enum class Seen(val key: String) {
        AURORA("aurora") {
            override fun reportInto(context: CommandContext<CommandSourceStack>, report: Report) =
                runAurora(context, report)

            override fun bringOn(context: CommandContext<CommandSourceStack>) = runAuroraNow(context)
        },

        RAINBOW("rainbow") {
            override fun reportInto(context: CommandContext<CommandSourceStack>, report: Report) =
                runRainbow(context, report)

            override fun bringOn(context: CommandContext<CommandSourceStack>) = runRainbowNow(context)
        },
        ;

        /** Why it is or is not in the sky right now, factor by factor. */
        abstract fun reportInto(context: CommandContext<CommandSourceStack>, report: Report): Int

        /** The Age's own, with whatever makes it *wait* taken off — a preview, so there is nothing to undo. */
        abstract fun bringOn(context: CommandContext<CommandSourceStack>): Int
    }

    private fun runAurora(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val aurora = auroraOf(level)
        if (aurora == null) {
            report.say { "Nothing hangs a curtain in ${level.dimension().identifier()}." }
            report.fact("hasAurora", false) {
                "  Write one with `auroral`, or describe one — `green aurora`. `beautiful` sometimes does too."
            }
            report.finish()
            return SUCCESS
        }

        val at = BlockPos.containing(source.position)
        val night = level.defaultClockTime / VANILLA_DAY
        val tonight = aurora.strengthOn(night)
        val starlit = starlitnessIn(level)
        val ground = groundShareFor(aurora, level, at)
        val raining = level.getRainLevel(1.0f)
        val showing = tonight * starlit * (1.0f - raining) * aurora.glow * ground

        report.say { "The curtain over ${level.dimension().identifier()}, on night $night:" }
        report.fact("colours", aurora.colours.size) {
            "  burns ${aurora.colours.size} colour(s), crown first, over ${aurora.ground.serializedName}"
        }
        report.fact("frequency", aurora.frequency) {
            "  comes on %.0f%% of nights, at glow %.2f, breadth %.2f, height %.2f"
                .format(aurora.frequency * PER_CENT, aurora.glow, aurora.breadth, aurora.height)
        }
        report.fact("tonight", tonight) {
            if (tonight > NOTHING_SHOWING) "  tonight is one of its nights, at %.2f".format(tonight)
            else "  tonight is not one of its nights"
        }
        report.fact("nextNight", nextNightAfter(aurora, night)) {
            "  the next night it comes is ${nextNightAfter(aurora, night) ?: "further off than $NIGHTS_LOOKED_AHEAD nights"}"
        }
        report.fact("starlit", starlit) { "  the sky is %.2f of the way to its darkest".format(starlit) }
        report.fact("ground", ground) { "  the ground within sight of you is %.2f cold enough".format(ground) }
        report.fact("rain", raining) { "  weather is hiding %.2f of it".format(raining) }
        report.fact("showing", showing) { "  so a client should be drawing it at %.3f".format(showing) }
        report.say { "  ${whyNotOf(tonight, starlit, ground, raining, showing)}" }
        report.finish()
        return SUCCESS
    }

    /** The one factor at nought, named — or what to conclude when none of them is. */
    private fun whyNotOf(tonight: Float, starlit: Float, ground: Float, raining: Float, showing: Float): String =
        when {
            showing > NOTHING_SHOWING ->
                "It is up. If the sky is empty, the fault is in the drawing — say so, it is not this."
            tonight <= NOTHING_SHOWING -> "Not tonight. `/age $SHOWING_LITERAL aurora $NOW_LITERAL` stops you waiting for it."
            starlit <= NOTHING_SHOWING -> "Too light. It keeps the hours its stars keep."
            ground <= NOTHING_SHOWING -> "Nowhere cold enough within sight. It stands where the snow lies."
            raining >= 1.0f -> "The weather has it."
            else -> "Every factor is above nothing but the product is not, which should not happen."
        }

    /**
     * `/age showing aurora now` — tonight's curtain, over any ground, until you walk out and back in.
     *
     * **The Age's own curtain rather than a demonstration one**, which is the whole difference from
     * `/age sky <name> aurora=ordinary`: what you see is what the book actually wrote, with the two things
     * that make it *wait* taken off. A preview is shown and never given ([LevelLookPreview]), so there is
     * no state to undo and nothing on the server believes any of it.
     */
    private fun runAuroraNow(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val look = Skies.lookOf(source.server, level.dimension())
        val aurora = look?.sky?.aurora
        if (look == null || aurora == null) {
            source.sendFailure(Component.literal("Nothing hangs a curtain here to bring on"))
            return FAILURE
        }
        val insisted = aurora.copy(frequency = EVERY_NIGHT, ground = AuroraGround.ANYWHERE)
        LevelLookPreview.show(level, look.copy(sky = look.sky.copy(aurora = insisted)))
        source.sendSuccess(
            { Component.literal("Tonight, and over any ground. Still needs darkness — it keeps its stars' hours.") },
            false,
        )
        // **Which way to look, because a band is not the whole sky.** It crosses about a hundred degrees
        // either side of its bearing, so a curtain that is up and behind you is indistinguishable from one
        // that is not up at all — which is exactly the confusion this command exists to end.
        source.sendSuccess({ Component.literal("  ${facingFor(insisted.bearingDegrees)}") }, false)
        return SUCCESS
    }

    /** Where to stand looking, given a curtain crossing the sky at [bearingDegrees]. */
    private fun facingFor(bearingDegrees: Float): String =
        "It crosses the sky about ${compassPointFor(bearingDegrees)} — " +
            "face that way and look well up, not at the horizon."

    /**
     * The nearest of the eight points to [bearingDegrees], where zero is north.
     *
     * Three things say a direction in prose and each phrases it differently, so what they share is the
     * arithmetic and not the sentence. **Note this is a bearing rather than a yaw** — Minecraft's zero
     * faces south, so anything reading an entity's rotation has half a turn to add first.
     */
    private fun compassPointFor(bearingDegrees: Float): String {
        val points = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        val step = (Math.round(bearingDegrees / (WHOLE_COMPASS / points.size)) % points.size + points.size) % points.size
        return points[step]
    }

    /**
     * `/age showing rainbow` — **why there is or is not a bow in the sky right now**, and
     * `/age showing rainbow now` to stop waiting for one.
     *
     * The aurora's problem, with one more way to have it. A bow can be correct and invisible because today
     * is not one of its days, because nothing has fallen, *or* because every light in the sky stands higher
     * than its own arc — and that last has no counterpart in a curtain and no symptom of its own.
     *
     * **The server can answer all of it but the wetness.** Which days a bow comes and where its light
     * stands are pure functions of the spec and the clock, so this recomputes exactly what the client will.
     * How long ago it rained is the one thing only the client remembers, so this says what is falling *now*
     * and names the gap rather than guessing across it.
     */
    private fun runRainbow(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val look = Skies.lookOf(source.server, level.dimension())
        val rainbow = look?.sky?.rainbow
        if (look == null || rainbow == null) {
            report.say { "Nothing writes a bow into ${level.dimension().identifier()}." }
            report.fact("hasRainbow", false) {
                "  Write one with `rainbows`, describe one — `red and yellow rainbow` — or `prismatic`."
            }
            report.finish()
            return SUCCESS
        }

        val clock = level.defaultClockTime
        val day = clock / VANILLA_DAY
        val today = rainbow.strengthOn(day)
        val raining = level.getRainLevel(1.0f)
        val lights = lightsIn(look)
        val highest = lights.maxOfOrNull { it.path.altitudeAt(clock) } ?: BELOW_EVERYTHING
        val cast = lights.maxOfOrNull { rainbow.castAt(it.path.altitudeAt(clock)) } ?: NOTHING_SHOWING

        report.say { "The bow over ${level.dimension().identifier()}, on day $day:" }
        report.fact("colours", rainbow.colours.size) {
            "  burns ${rainbow.colours.size} colour(s), outermost first, at radius %.0f°%s"
                .format(rainbow.radiusDegrees, if (rainbow.secondary) " with a second arc" else "")
        }
        report.fact("frequency", rainbow.frequency) {
            "  comes on %.0f%% of days, at glow %.2f, wanting %.0f%% rain"
                .format(rainbow.frequency * PER_CENT, rainbow.glow, rainbow.needsRain * PER_CENT)
        }
        report.fact("today", today) {
            if (today > NOTHING_SHOWING) "  today is one of its days, at %.2f".format(today)
            else "  today is not one of its days"
        }
        report.fact("nextDay", nextDayAfter(rainbow, day)) {
            "  the next day it comes is ${nextDayAfter(rainbow, day) ?: "further off than $NIGHTS_LOOKED_AHEAD days"}"
        }
        report.fact("lights", lights.size) { "  ${lights.size} light(s) in this sky could cast one" }
        report.fact("highest", highest) {
            "  the highest of them stands at %.0f°, and a bow needs one under %.0f°"
                .format(highest, rainbow.radiusDegrees)
        }
        report.fact("cast", cast) { "  so the geometry allows %.2f of a bow".format(cast) }
        report.fact("nextLow", nextLowEnough(rainbow, lights, clock)) {
            "  the next time a light is low enough is " +
                (nextLowEnough(rainbow, lights, clock)?.let { "in $it ticks" } ?: "not within a day")
        }
        report.fact("rain", raining) { "  %.2f is falling right now".format(raining) }
        report.fact("showing", today * cast) {
            "  so what the server can see comes to %.3f, before the air's own wetness".format(today * cast)
        }
        report.say { "  ${whyNoBow(rainbow, today, cast, highest, raining)}" }
        report.finish()
        return SUCCESS
    }

    /**
     * The one factor at nought, named — or what to conclude when none of them is.
     *
     * **Wetness is deliberately never the answer here**, because the server does not have it: the client
     * remembers the last of the rain for a couple of minutes and this cannot see that. So a dry reading is
     * reported as a maybe rather than as a cause, which is the honest thing and stops this command
     * confidently blaming the one factor it cannot measure.
     */
    private fun whyNoBow(
        rainbow: Rainbow,
        today: Float,
        cast: Float,
        highest: Float,
        raining: Float,
    ): String = when {
        today <= NOTHING_SHOWING -> "Not today. `/age $SHOWING_LITERAL rainbow $NOW_LITERAL` stops you waiting for it."
        cast <= NOTHING_SHOWING && highest >= rainbow.radiusDegrees ->
            "Every light is too high. A bow is a circle about the point opposite one, so a light above " +
                "%.0f° puts the whole arc underground. Wait for evening, or `/age $SHOWING_LITERAL rainbow $NOW_LITERAL`."
                    .format(rainbow.radiusDegrees)
        cast <= NOTHING_SHOWING -> "Nothing is up to light one."
        rainbow.needsRain > NOTHING_SHOWING && raining <= NOTHING_SHOWING ->
            "It should be up if it has rained in the last couple of minutes — the client remembers that " +
                "and this cannot. If it has not, that is why. `/age $SHOWING_LITERAL rainbow $NOW_LITERAL` takes the rain off."
        else -> "It is up. If the sky is empty, the fault is in the drawing — say so, it is not this."
    }

    /**
     * `/age showing rainbow now` — the Age's own bow, every day, needing no rain, and opened out far enough that
     * the light currently in the sky actually clears it.
     *
     * **The Age's own bow rather than a demonstration one**, which is the difference from
     * `/age sky <name> rainbow=ordinary`: what you see is what the book wrote, with the things that make it
     * *wait* taken off. A preview is shown and never given ([LevelLookPreview]).
     *
     * The radius is the part that cannot simply be insisted upon. A light standing above the arc's own
     * radius leaves no arc above the ground at all, and no amount of forcing the day or the weather changes
     * that — so this opens the radius until the crown clears, and says that it did.
     */
    private fun runRainbowNow(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val look = Skies.lookOf(source.server, level.dimension())
        val rainbow = look?.sky?.rainbow
        if (look == null || rainbow == null) {
            source.sendFailure(Component.literal("Nothing writes a bow here to bring on"))
            return FAILURE
        }
        val clock = level.defaultClockTime
        val lights = lightsIn(look)
        val leading = lights.maxByOrNull { it.path.altitudeAt(clock) }
        if (leading == null) {
            source.sendFailure(Component.literal("Nothing in this sky gives light, so nothing can cast a bow"))
            return FAILURE
        }
        val highest = leading.path.altitudeAt(clock)
        if (highest < BELOW_THE_HORIZON) {
            source.sendFailure(
                Component.literal("Every light here has set. A bow is bent sunlight; come back when one is up."),
            )
            return FAILURE
        }
        val opened = highest >= rainbow.radiusDegrees
        val insisted = rainbow.copy(
            frequency = EVERY_NIGHT,
            needsRain = NO_RAIN_WANTED,
            radiusDegrees = if (opened) (highest + CLEARS_THE_GROUND).coerceAtMost(WIDEST_FORCED) else rainbow.radiusDegrees,
        )
        LevelLookPreview.show(level, look.copy(sky = look.sky.copy(rainbow = insisted)))
        source.sendSuccess({ Component.literal("Today, and needing no rain. Until you walk out and back in.") }, false)
        if (opened) {
            source.sendSuccess(
                {
                    Component.literal(
                        "  Its light stands at %.0f°, over its own %.0f° arc, so the radius is opened to %.0f° to clear the ground. Not the Age's own shape."
                            .format(highest, rainbow.radiusDegrees, insisted.radiusDegrees),
                    )
                },
                false,
            )
        }
        // **Which way to look, because a bow is nowhere near its light.** It is a circle about the point
        // exactly opposite, so the one reliable instruction is to put the light at your back — and a bow
        // behind you is indistinguishable from one that is not there, which is what this command is for.
        val away = (leading.path.bearingAt(clock) + HALF_COMPASS) % WHOLE_COMPASS
        source.sendSuccess({ Component.literal("  ${lookingAwayFrom(away)}") }, false)
        return SUCCESS
    }

    /** Where to stand looking, given a bow centred on [bearingDegrees]. */
    private fun lookingAwayFrom(bearingDegrees: Float): String =
        "Put the light at your back and face ${compassPointFor(bearingDegrees)} — " +
            "the bow is centred there, low down."

    /** The bodies in [look] that give light, which are the ones that can cast a bow. */
    private fun lightsIn(look: LevelLook): List<CelestialBody> =
        look.sky.bodies.filter { it.blending == Blending.ADDS }

    /** The next day after [day] that [rainbow] comes, or null within [NIGHTS_LOOKED_AHEAD]. */
    private fun nextDayAfter(rainbow: Rainbow, day: Long): Long? =
        (day + 1..day + NIGHTS_LOOKED_AHEAD).firstOrNull { rainbow.strengthOn(it) > NOTHING_SHOWING }

    /**
     * How many ticks until some light is low enough to cast a bow, or null within a day.
     *
     * Walked rather than solved, for `CelestialPath.swing`'s reason: a light's altitude is a stack of
     * rotations and the tick it crosses a given angle has no closed form worth reading.
     */
    private fun nextLowEnough(rainbow: Rainbow, lights: List<CelestialBody>, clock: Long): Long? {
        if (lights.isEmpty()) return null
        var ahead = 0L
        while (ahead < VANILLA_DAY) {
            val at = clock + ahead
            if (lights.any { rainbow.castAt(it.path.altitudeAt(at)) > NOTHING_SHOWING }) return ahead
            ahead += LOOKING_AHEAD_STEP
        }
        return null
    }

    /** Nothing is up at all, in degrees — below any real altitude, so a sky with no lights sorts last. */
    private const val BELOW_EVERYTHING = -90.0f

    /** How far under the horizon still counts as a light that has set rather than one about to rise. */
    private const val BELOW_THE_HORIZON = -1.5f

    private const val NO_RAIN_WANTED = 0.0f

    /** How far above the horizon a forced bow's crown is put, so it is unmistakably there. */
    private const val CLEARS_THE_GROUND = 12.0f

    /** Past this the antisolar axis is near vertical and a bow stops being a shape anybody recognises. */
    private const val WIDEST_FORCED = 85.0f

    private const val HALF_COMPASS = 180.0f

    /** Coarse enough to be cheap and fine enough to be worth reading — a bow moves slowly. */
    private const val LOOKING_AHEAD_STEP = 100L

    /** The curtain this level wears, or null where it wears none. */
    private fun auroraOf(level: ServerLevel): Aurora? =
        Skies.lookOf(level.server, level.dimension())?.sky?.aurora

    /**
     * How dark the sky has gone, as the client will read it — the level's own suns where it has any of its
     * own, and vanilla's curve where it does not.
     */
    private fun starlitnessIn(level: ServerLevel): Float {
        val look = Skies.lookOf(level.server, level.dimension()) ?: return NOTHING_SHOWING
        look.air.starBrightness?.let { return it }
        if (!look.sky.isOrdinary && look.rules.daylight != Daylight.VANILLA_CLOCK) {
            return LevelDaylight.starlitnessOf(look.readAt(level.defaultClockTime), look.rules)
        }
        // Vanilla's own curve, read off the light rather than off the stars: `getStarBrightness` is the
        // client's and this is the same fact from the side the server has. `skyDarken` runs 0 by day to
        // [FULLY_DARKENED] by night, which is the scale the client's nightliness is already on.
        return (level.skyDarken.toFloat() / FULLY_DARKENED).coerceIn(NOTHING_SHOWING, 1.0f)
    }

    /**
     * What share of the ground around [at] answers the curtain's rule.
     *
     * The same ring the client samples, asked of the server's own level — so a disagreement between this
     * and the window is a disagreement about *drawing* and never about the rule.
     */
    private fun groundShareFor(aurora: Aurora, level: ServerLevel, at: BlockPos): Float {
        if (aurora.ground == AuroraGround.ANYWHERE) return 1.0f
        val around = listOf(at) + Direction.Plane.HORIZONTAL.map { at.relative(it, GROUND_RING_BLOCKS) }
        val cold = around.count { level.getBiome(it).value().coldEnoughToSnow(it, level.seaLevel) }
        return cold.toFloat() / around.size
    }

    /** The next night the curtain comes, or null where none of the next [NIGHTS_LOOKED_AHEAD] is one. */
    private fun nextNightAfter(aurora: Aurora, night: Long): Long? =
        (night + 1..night + NIGHTS_LOOKED_AHEAD).firstOrNull { aurora.strengthOn(it) > NOTHING_SHOWING }

    private fun strikeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("strike")
            .executes { context -> runStrike(context, DEFAULT_STRIKE_DISTANCE) }
            .then(
                Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_STRIKE_DISTANCE))
                    .executes { context ->
                        runStrike(context, IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT))
                    },
            )

    /**
     * `/age sandfall [<distance>]` — stands a column of sand up out in front of you, headed at you.
     *
     * The debug trigger, exactly as `/age strike` is one, and for the same reason: a sandfall arrives on a
     * timer measured in minutes, so nothing about it is observable in a walk without a way to ask for one.
     *
     * **Aimed rather than sited**, which is the difference from a real one — [Sandfall] chooses a bearing
     * and a spread so that a column usually passes near you and sometimes passes wide, where this puts one
     * exactly where you are looking and turns it around to come back. That is what you want of a debug
     * trigger and emphatically not what you want of the phenomenon.
     *
     * **The optional life is what makes the trail measurable**, because the widening ramp is a share of it:
     * an ordinary column spends its first minute or two opening, so anything measured inside that window is
     * measuring a column that is not yet the width it will be. A short one is at full width in seconds.
     */
    /**
     * `/age meteors [<distance>] [<seconds>] [<fury>] [look]` — gather a storm ahead of you, now.
     *
     * **A storm is thirty seconds of sky before it is anything at all**, which is exactly what makes it
     * unwalkable without this: waiting for one to happen by itself, in the right Age, close enough to see
     * and facing the right way, is several minutes a look. Here it is one line.
     *
     * `look` turns you to face it, which sounds like a convenience and is closer to the point: the
     * telegraph is *directional*, so a debug command that leaves you hunting the sky for it cannot show
     * you the thing it was written to show.
     *
     * `degrees` forces the angle a storm would otherwise draw for itself, which is the only way to put its
     * shallow and steep ends beside each other rather than waiting for the draw to offer them.
     */
    private fun meteorsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun runner(given: Boolean, slanted: Boolean) =
            { context: CommandContext<CommandSourceStack>, look: Boolean, ignoreLure: Boolean ->
                runMeteors(
                    context,
                    if (given) IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT) else null,
                    if (given) IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT) else null,
                    if (given) IntegerArgumentType.getInteger(context, FURY_ARGUMENT) else null,
                    if (slanted) IntegerArgumentType.getInteger(context, DEGREES_ARGUMENT) else null,
                    look,
                    ignoreLure,
                )
            }

        val asked = Commands.argument(FURY_ARGUMENT, IntegerArgumentType.integer(0, ALL_FURY.toInt()))
        flagged(asked, runner(given = true, slanted = false))
        val slanted = Commands.argument(
            DEGREES_ARGUMENT,
            IntegerArgumentType.integer(SHALLOWEST_SLANT, STEEPEST_SLANT),
        )
        flagged(slanted, runner(given = true, slanted = true))
        asked.then(slanted)

        val meteors = Commands.literal("meteors")
        flagged(meteors, runner(given = false, slanted = false))
        return meteors.then(
            Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_STORM_DISTANCE)).then(
                Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1, MOST_SANDFALL_SECONDS))
                    .then(asked),
            ),
        )
    }

    /**
     * Hangs `look` and `ignore_lure` off a node, in either order and in any combination.
     *
     * Written out rather than as two literals because a flag a player has to remember the position of is a
     * flag they will get wrong, and this command already carries four positional arguments.
     */
    private fun flagged(
        node: ArgumentBuilder<CommandSourceStack, *>,
        run: (CommandContext<CommandSourceStack>, Boolean, Boolean) -> Int,
        look: Boolean = false,
        ignoreLure: Boolean = false,
    ) {
        node.executes { run(it, look, ignoreLure) }
        if (!look) {
            val next = Commands.literal(LOOK_LITERAL)
            flagged(next, run, look = true, ignoreLure = ignoreLure)
            node.then(next)
        }
        if (!ignoreLure) {
            val next = Commands.literal(IGNORE_LURE_LITERAL)
            flagged(next, run, look = look, ignoreLure = true)
            node.then(next)
        }
    }

    /**
     * Stands a storm up [distance] blocks ahead and, if asked, turns the caller to face it.
     *
     * The height is the storm's own rather than the caller's: a storm hangs well above the ground it
     * falls on, and one gathered at eye level would drop its bodies from under your feet.
     */
    private fun runMeteors(
        context: CommandContext<CommandSourceStack>,
        distance: Int?,
        seconds: Int?,
        furyPercent: Int?,
        degrees: Int?,
        look: Boolean,
        ignoreLure: Boolean,
    ): Int {
        val source = context.source
        val level = source.level
        // **A bare `/age meteors` imitates what this Age would raise on its own** (Jonah), rather than
        // inventing a storm the game does not contain: the rung its book claimed and the fury its
        // instability actually bought. Anything passed is an override on top of that.
        val density = Happenings.claimFor(level, Phenomenon.METEORS)?.density ?: Rung.ORDINARY
        // toDouble FIRST: ALL_FURY is an Int, so dividing without it makes every fury under a hundred nought.
        val fury = furyPercent?.let { it.toDouble() / ALL_FURY } ?: Happenings.furyIn(level, Phenomenon.METEORS)
        val facing = Vec3.directionFromRotation(source.rotation)
        val ahead = source.position.add(facing.scale((distance ?: DEFAULT_STORM_DISTANCE).toDouble()))
        val spot = BlockPos.containing(ahead.x, source.position.y, ahead.z)
        // Lures are consulted exactly as written weather consults them, unless asked not to — the whole
        // point of this command is that it does what a storm does.
        val drawn = if (ignoreLure) null else Meteors.drawnNear(level, source.position)
        val slant = degrees?.let { Math.toRadians(it.toDouble()) }
        val storm = Meteors.raise(level, spot, density, fury, drawn, slant, seconds?.times(TICKS_PER_SECOND))
        // Turned to the *light*, which is thousands of blocks out along the storm's entry line and nowhere
        // near the storm itself. Facing the storm left a walk staring at empty sky (Jonah, walked).
        if (look) {
            source.player?.lookAt(EntityAnchorArgument.Anchor.EYES, storm.seenFrom(storm.flightOf(FIRST_BODY), JUST_SIGHTED))
        }
        // Says what it actually raised rather than what was asked for, because most of this now comes from
        // the Age: an operator has to be able to see whether a bare call read anything at all.
        Report.prose(source).say {
            val drawnIn = drawn?.let { "drawn in by ${it.blocks} aloft, ${storm.reach.toInt()} wide" }
                ?: "${storm.reach.toInt()} wide, nothing drawing it"
            "A storm gathers, $drawnIn, ${(fury * ALL_FURY).toInt()}% fierce, " +
                "${storm.bodies} bodies over ${storm.falling / TICKS_PER_SECOND}s. " +
                "It falls in ${MeteorStorm.APPROACHING / TICKS_PER_SECOND}s."
        }
        return SUCCESS
    }

    private fun sandfallSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("sandfall")
            .executes { context -> runSandfall(context, DEFAULT_SANDFALL_DISTANCE, null, NO_FURY) }
            .then(
                Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_SANDFALL_DISTANCE))
                    .executes { context ->
                        runSandfall(
                            context,
                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                            null,
                            NO_FURY,
                        )
                    }
                    .then(
                        Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1, MOST_SANDFALL_SECONDS))
                            .executes { context ->
                                runSandfall(
                                    context,
                                    IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                    IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                    NO_FURY,
                                )
                            }
                            .then(
                                Commands.argument(FURY_ARGUMENT, IntegerArgumentType.integer(0, ALL_FURY))
                                    .executes { context ->
                                        runSandfall(
                                            context,
                                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, FURY_ARGUMENT),
                                        )
                                    },
                            ),
                    ),
            )

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

    /**
     * `/age decay <name>` — how far an Age has come apart, and `/age decay <name> age <days>` to make it
     * older than it is.
     *
     * **Without the second form none of this is observable.** Worsening and collapse are read against the
     * Age's own age (design §5.4), so the mildest of them takes four of its days to open one more wound per
     * chunk and the gentlest collapse a fortnight to be worth looking at. Backdating rewrites the one field
     * the clock is measured from, which is the whole of what "wait a month" means to everything downstream
     * — no separate debug path, and nothing that could disagree with the real one.
     */
    private fun decaySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("decay") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runDecay(context, reportFor(context)) }
                .then(
                    Commands.literal(AGED_LITERAL).then(
                        Commands.argument(DAYS_ARGUMENT, IntegerArgumentType.integer(0, MOST_DAYS))
                            .executes { context ->
                                runBackdate(
                                    context,
                                    IntegerArgumentType.getInteger(context, DAYS_ARGUMENT),
                                    reportFor(context),
                                )
                            },
                    ),
                )
                .then(
                    Commands.literal(UNSTABLE_LITERAL).then(
                        Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(0, MOST_INSTABILITY))
                            .executes { context ->
                                runForceInstability(
                                    context,
                                    IntegerArgumentType.getInteger(context, INDEX_ARGUMENT),
                                    reportFor(context),
                                )
                            },
                    ),
                )
        }

    /**
     * **Two different questions that are both "how dangerous is this", kept in one branch** because a
     * reader looking for either would look here.
     *
     * `/age danger here` — what the ground you are standing on is worth, in vanilla's own terms.
     *
     * **Written because the register is otherwise unobservable** (Jonah, 2026-08-09, walked): a wound arms
     * what comes out of it by ageing the ground (§5.1), which is `DifficultyInstance` doing the work, and
     * `getSpecialMultiplier` is a probability rather than a visible state. A walk that sees no armoured
     * skeleton has learned nothing — the chance is a few per cent a mob — so this says the number instead.
     *
     * **F3 will not show this.** The debug screen computes local difficulty from the *client's* level, and
     * the seam a wound raises is on `ServerLevel.getCurrentDifficultyAt`, so the two legitimately disagree
     * and the client's is the one that is wrong.
     *
     * `/age danger score <name>` — what a written *recipe* is worth to §7.7's rewards, scored before the
     * Age exists rather than measured in it. That is what the geologic survey will read at the desk, and
     * what decides whether anything pays out at all.
     *
     * **Numbers, because this is an operator's tool.** §3.2 forbids showing a number to the *player*, and
     * the survey obeys that by saying "a trace" against "a great deal". Calibrating [DangerTable] means
     * seeing the arithmetic, so here it is spelled out.
     */
    private fun dangerSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun branchesUnder(parent: LiteralArgumentBuilder<CommandSourceStack>, reportFor: ReportFor) = parent
            .then(Commands.literal(HERE_LITERAL).executes { context -> runDanger(context, reportFor(context)) })
            .then(
                Commands.literal(SCORE_LITERAL).then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runAgeDanger(context, reportFor(context)) },
                ),
            )
        val prose = branchesUnder(Commands.literal("danger")) { context -> Report.prose(context.source) }
        val structured = branchesUnder(Commands.literal(Report.STRUCTURED_LITERAL)) { context ->
            Report.structured(context.source)
        }
        return prose.then(structured)
    }

    /**
     * `/age wounds here` — **what this ground should hold against what it does.**
     *
     * The one question a walk cannot answer by looking: a landscape with no wounds in it is either an Age
     * that never bought any, ground that has not caught up, or placement putting them somewhere nobody
     * goes — and those look identical from a hilltop. Wanted against held tells the three apart in a line.
     */
    private fun woundsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("wounds") { reportFor ->
            Commands.literal(HERE_LITERAL).executes { context -> runWounds(context, reportFor(context)) }
        }

    private fun runWounds(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val at = BlockPos.containing(source.position)
        val id = level.dimension().identifier()
        val saved = AgeSavedData.get(source.server)
        if (id !in saved.ages) {
            report.fail("Not standing in an Age — /age tp <name> first")
            return FAILURE
        }
        val recipe = saved.recipe(id)
        val spending = Spending.of(source.server, recipe)
        val days = recipe.ageAt(source.server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val here = ChunkPos(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)
        val wanted = Tearing.wantedIn(here, level.seed, density)
        val holds = Wounds.countIn(level, here)

        var within = 0
        var nearest: BlockPos? = null
        var nearestAway = Double.MAX_VALUE
        Wounds.eachNear(level, at.center, LOOKS_FOR_WOUNDS_WITHIN) { wound ->
            within++
            val away = at.center.distanceTo(wound.center)
            if (away < nearestAway) {
                nearestAway = away
                nearest = wound
            }
        }

        report.say { "Standing in ${'$'}id at ${'$'}{at.x}, ${'$'}{at.y}, ${'$'}{at.z}, ${'$'}days days on:" }
        report.fact("density", density) { "  the Age wants %.3f to a chunk".format(density) }
        report.fact("wantedHere", wanted) { "  this chunk wants ${'$'}wanted" }
        report.fact("holdsHere", holds) { "  this chunk holds ${'$'}holds" }
        report.fact("within", within) {
            "  ${'$'}within within ${'$'}{LOOKS_FOR_WOUNDS_WITHIN.toInt()} blocks"
        }
        val found = nearest
        if (found == null) {
            report.say { "  no wound in reach" }
        } else {
            report.fact("nearestX", found.x) { "" }
            report.fact("nearestY", found.y) { "" }
            report.fact("nearestZ", found.z) { "" }
            report.fact("nearestAway", nearestAway) {
                "  nearest at ${'$'}{found.x}, ${'$'}{found.y}, ${'$'}{found.z} — %.1f blocks".format(nearestAway)
            }
        }
        // The three readings a walk needs told apart, said outright rather than left to arithmetic.
        report.say {
            when {
                density <= Tearing.NONE -> "  → this Age bought no wounds at all"
                holds >= wanted -> "  → this chunk is up to date"
                perDay <= Tearing.NONE -> "  → behind, and nothing will fix it: it does not worsen, so nothing catches up"
                else -> "  → behind by ${'$'}{wanted - holds}; leave until it unloads and return, or stand here for the creep"
            }
        }
        return SUCCESS
    }

    private fun runDanger(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val at = BlockPos.containing(source.position)
        // Vanilla's own answer, taken before ours can raise it — `Hostility` reads the wound and returns a
        // floor, so the pair is what says whether the register did anything here at all.
        val plain = DifficultyInstance(
            level.difficulty,
            level.overworldClockTime,
            level.getChunk(at).inhabitedTime,
            level.getMoonBrightness(at),
        )
        val raised = level.getCurrentDifficultyAt(at)
        val corruption = Wounds.corruptionAt(level, at.center)

        report.say { "Standing at ${at.x}, ${at.y}, ${at.z} in ${level.dimension().identifier()}:" }
        report.fact("difficulty", level.difficulty.serializedName) { "  world difficulty: ${level.difficulty.serializedName}" }
        report.fact("corruption", corruption) { "  corruption here: %.3f".format(corruption) }
        report.fact("inhabited", level.getChunk(at).inhabitedTime) {
            "  chunk really lived in for ${level.getChunk(at).inhabitedTime} ticks"
        }
        report.fact("effectiveWithout", plain.effectiveDifficulty) {
            "  effective difficulty without the wound: %.2f".format(plain.effectiveDifficulty)
        }
        report.fact("effectiveWith", raised.effectiveDifficulty) {
            "  effective difficulty as the Age has it: %.2f".format(raised.effectiveDifficulty)
        }
        report.fact("specialMultiplier", raised.specialMultiplier) {
            "  special multiplier: %.2f".format(raised.specialMultiplier)
        }
        // The whole point of the readout: vanilla refuses to arm anything below 2.0, and on Easy the ceiling
        // is 1.5 however lived-in the ground is — so the register cannot show there at all.
        if (raised.specialMultiplier <= 0.0f) {
            report.say {
                "  → nothing will spawn armed here: vanilla arms nothing below effective 2.0" +
                    if (level.difficulty == Difficulty.EASY) ", and Easy tops out at 1.5" else ""
            }
        } else {
            val chance = ARMS_ANYTHING_AT_ALL * raised.specialMultiplier
            report.say { "  → about %.0f%% of what spawns here should arrive armed".format(chance * 100.0f) }
        }
        return SUCCESS
    }

    /**
     * What a written Age is worth to §7.7, contributor by contributor.
     *
     * **The parts as well as the total**, because one number cannot be calibrated: the question a tuning
     * session asks is *why* an Age scored what it did, and the answer is always which contributor carried
     * it. Reading the four beside the score is what turns `art/danger.json` from a guess into a dial.
     */
    private fun runAgeDanger(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        val saved = AgeSavedData.get(source.server)
        if (id !in saved.ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return FAILURE
        }
        val recipe = saved.recipe(id)
        val danger = Danger.of(source.server, recipe)

        report.say { "Age '$name':" }
        report.fact("materials", danger.materials) { "  what it is made of: %.3f".format(danger.materials) }
        report.fact("spawns", danger.spawns) { "  what lives in it:    %.3f".format(danger.spawns) }
        report.fact("phenomena", danger.phenomena) { "  what happens in it:  %.3f".format(danger.phenomena) }
        report.fact("lighting", danger.lighting) { "  how dark it is:      %.3f".format(danger.lighting) }
        report.fact("score", danger.score) { "  → danger %.3f".format(danger.score) }
        report.fact("authored", danger.authored) {
            if (danger.authored) "  written by a player" else "  not written by a player, so it can never pay"
        }
        report.fact("paysOut", danger.paysOut) {
            if (danger.paysOut) "  pays out" else "  pays nothing"
        }
        report.fact("allowsRuins", danger.allowsRuins) {
            if (danger.allowsRuins) "  quiet enough to hold ruins" else "  too dangerous to hold ruins"
        }
        // Exposed rather than folded into the score: §7.7's terminal multiplier is still an open question.
        report.fact("terminal", danger.terminal) {
            if (danger.terminal > 0.0) "  and it will not last: collapse reach %.2f".format(danger.terminal) else ""
        }
        report.finish()
        return SUCCESS
    }

    private fun runDecay(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return FAILURE
        }
        val recipe = AgeSavedData.get(source.server).recipe(id)
        val spending = Spending.of(source.server, recipe)
        val days = recipe.ageAt(source.server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val tears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE))

        report.say { "Age '$name' has stood $days days (instability ${recipe.instability.index}):" }
        report.fact("days", days) { "  days: $days" }
        report.fact("spending", spending.toString()) { "  bought: $spending" }
        report.fact("woundsWritten", written) { "  wounds the book tore: %.3f per chunk".format(written) }
        report.fact("woundsPerDay", perDay) { "  worsening: %.3f more per chunk each day".format(perDay) }
        report.fact("woundsNow", density) { "  wounds now: %.3f per chunk".format(density) }
        report.fact("collapseTears", tears) {
            if (tears <= Collapse.NONE) "  no tears in the floor" else "  $tears tear(s) to every 96 blocks, widening"
        }
        // Where to walk. One tear to a 512-block cell is not something anybody finds by looking.
        if (tears > Collapse.NONE) {
            val level = Ages.open(source.server, id)
            if (level != null) {
                val here = BlockPos.containing(source.position)
                val (originX, originZ) = Collapse.nearestOriginTo(level.seed, here.x, here.z, tears)
                report.fact("nearestTearX", originX) { "" }
                report.fact("nearestTearZ", originZ) { "" }
                report.say { "  nearest tear opened at $originX, $originZ — /age tp $name then go there" }
            }
        }
        return SUCCESS
    }

    /**
     * Make an Age older than it is, by moving the tick it was written on backwards.
     *
     * Generation reads the clock per chunk, so unvisited ground comes out at the new age immediately.
     * Ground that already exists is brought up by `Worsening`'s catch-up as its chunks reload — so unloading
     * and returning is what makes it agree, and an Age that does not worsen has no catch-up and keeps
     * what it was made with.
     */
    private fun runBackdate(context: CommandContext<CommandSourceStack>, days: Int, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val saved = AgeSavedData.get(source.server)
        val id = ageId(name)
        if (id !in saved.ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return FAILURE
        }
        val now = source.server.overworld().gameTime
        val aged = saved.recipe(id).copy(writtenAt = now - days * Tearing.TICKS_PER_DAY)
        saved.add(id, aged)
        // **And the live generator, or nothing changes until the Age is reopened** (walked 2026-08-09).
        // A generator is built once at open and keeps its own copy of the clock, so rewriting the recipe
        // alone left an Age reporting a month and generating as though it were new.
        retellTheGenerator(source.server, id, aged)
        report.say { "Age '$name' now reads as $days days old." }
        report.say { "  ground it has not generated comes out at the new age; ground that exists catches up as it reloads, if the Age worsens." }
        report.fact("days", days.toLong()) { "" }
        report.fact("writtenAt", aged.writtenAt) { "" }
        return SUCCESS
    }

    /**
     * Tell a running Age that its recipe changed, so generation stops answering from the one it opened with.
     *
     * Without this a rewritten recipe reaches every *report* and no *chunk*, which is exactly the shape of
     * bug that had collapse announcing a radius of 240 and generating a solid world (walked 2026-08-09).
     */
    private fun retellTheGenerator(server: MinecraftServer, id: Identifier, recipe: AgeRecipe) {
        val level = Ages.open(server, id) ?: return
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: return
        generator.rewriteConsequence(Consequence.of(server, recipe))
    }

    /**
     * Set an Age's instability outright, so the consequence registers can be tested without writing a book
     * that earns them.
     *
     * Reaching the worsening honestly takes an index near sixty-five and collapse near a hundred, which is two dozen
     * pages opposing two dozen different things — a great deal of fighting the vocabulary to exercise
     * arithmetic the vocabulary has nothing to do with. The index goes through the real price list from
     * here, so what it buys is exactly what a book of that index would have bought.
     */
    private fun runForceInstability(context: CommandContext<CommandSourceStack>, index: Int, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val saved = AgeSavedData.get(source.server)
        val id = ageId(name)
        if (id !in saved.ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return FAILURE
        }
        val forced = saved.recipe(id).copy(instability = Instability.forced(index))
        saved.add(id, forced)
        retellTheGenerator(source.server, id, forced)
        val spending = Spending.of(source.server, forced)
        report.say { "Age '$name' is now instability $index, which buys $spending." }
        report.fact("instability", index) { "" }
        report.fact("spending", spending.toString()) { "" }
        report.say { "  walk to ground it has not generated yet. What exists catches up as it reloads if this bought worsening wounds; without them, existing ground keeps what it was made with." }
        return SUCCESS
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
        report.say { "Column ($x, $z) as the generator sees it:" }
        report.fact("waterline", seaFill.level) {
            "  sea ${seaFill.blockAt(x, z).block.descriptionId} standing at y=${seaFill.level}"
        }
        report.fact("rock", said(rock)) { "  rock: ${said(rock)}" }
        report.fact("keptDry", said(dryness)) { "  kept dry: ${said(dryness)}" }
        report.fact("carriedWater", said(wetness)) { "  carried water: ${said(wetness)}" }
        // The aquifer's claim, and the one that hid a flooded rift: it is asked *before* the sea and
        // answers from the water table, so anything it claims is wet whatever keeps the sea out.
        val hollow = generator.hollows?.columnSpans(x, z) ?: Spans.EMPTY
        report.fact("aquifer", said(hollow)) { "  aquifer answers for: ${said(hollow)}" }
        // The verdict, block by block through the band the sea could reach, which is what a walk is looking at.
        val wet = (PROBE_FROM..seaFill.level).filter { y ->
            !rock.contains(y) && (hollow.contains(y) || seaFill.fillsAt(y, dryness, wetness))
        }
        report.fact("filled", wet.size) {
            if (wet.isEmpty()) "  nothing is filled here between y=$PROBE_FROM and the waterline"
            else "  filled y=${wet.first()}..${wet.last()} (${wet.size} blocks)"
        }
        // The contradiction that flooded every rift: a space kept dry that the aquifer also claims.
        report.only("dryAndAquifer", (PROBE_FROM..seaFill.level).count { dryness.contains(it) && hollow.contains(it) })
        report.finish()
        return SUCCESS
    }

    /** Spans as a reader can check against a coordinate, which is the whole use of a probe. */
    private fun said(spans: Spans): String =
        if (spans.ranges.isEmpty()) "nothing"
        else spans.ranges.joinToString { range -> "y=${range.first}..${range.last}" }

    private fun locateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("locate").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(PRESET_ARGUMENT, StringArgumentType.word()).executes(::runLocate),
            ),
        )

    private fun bookSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("book")
            .executes { context -> runBook(context, seed = context.source.level.gameTime) }
            .then(
                Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                    .executes { context -> runBook(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
            )

    /**
     * `/age bind <name>` — a Descriptive Book already bound to an Age that exists.
     *
     * **The instrument the panel had no way of reaching.** A book is bound by being written, at the desk
     * or in a loot chest, and both decide the Age for you — so there was no way to look at a *chosen* Age
     * through a panel, and in particular no way to look at one whose index had been set by hand with
     * `/age decay <name> unstable <n>`, which is how the panel's distortion is looked at at all.
     *
     * The book is a real one and not a shim: the pages carry the Age's own sentence, so it reads, links
     * and previews exactly as a written one does.
     */
    private fun bindSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("bind").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runBind),
        )

    private fun runBind(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val saved = AgeSavedData.get(source.server)
        val id = ageId(name)
        if (id !in saved.ages) {
            return Report.prose(source).fail("No Age named '$name' — create it with /age create $name")
        }

        val recipe = saved.recipe(id)
        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK)
        book.set(AgeContent.AGE_ID, id)
        book.set(AgeContent.BOOK_TITLE, name)
        // A recipe keeps the sentence as pages where a book keeps it as ids, and the path is the page.
        // Set even where the Age was made by hand and has no sentence, so that a book with nothing to say
        // is not taken for a blank one and written over as a found book on the next tick.
        book.set(AgeContent.BOOK_WORDS, recipe.words.map { it.location() })
        readingOf(source, recipe.words)?.let { book.set(AgeContent.BOOK_READING, it) }
        if (!player.addItem(book)) player.drop(book, false)

        source.sendSuccess({
            Component.literal("Bound a book to Age '$name' at instability ${recipe.instability.index}")
        }, false)
        return SUCCESS
    }

    /** What the pages say, or null where they say nothing the grammar can read. */
    private fun readingOf(source: CommandSourceStack, pages: List<String>): List<Said>? {
        if (pages.isEmpty()) return null
        val sentence = Grammar.read(Vocabulary.of(source.server), pages) ?: return null
        return Readout.columnsOf(sentence)
    }

    private fun draftSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("draft").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runDraft(context, seed = context.source.level.gameTime) }
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                        .executes { context -> runDraft(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
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

    /** `all` is a literal rather than a name, so it cannot collide with an Age actually called "all". */
    private fun deleteSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("delete")
            .then(Commands.literal("all").executes(::runDeleteAll))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runDelete))

    /**
     * `/age pages [derived]` — a notebook holding one page of every word, for testing the desk.
     *
     * A notebook rather than the pages themselves: sixty-odd words is more stacks than an inventory has
     * rows, and a notebook is uncapped and empties into the desk in one action, which is the route a
     * player takes anyway.
     */
    private fun pagesSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pages")
            .executes { context -> runPages(context, derived = false) }
            .then(Commands.literal(DERIVED_LITERAL).executes { context -> runPages(context, derived = true) })

    /**
     * `/age forget [<word>]` — unlearn everything, or one word.
     *
     * Purely an instrument, and it exists because the learning channels can only be walked *once* per
     * world: `/age pages derived` teaches the whole corpus, after which no device can be seen teaching
     * anything. Nothing in the game unlearns a word and nothing should.
     */
    private fun forgetSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("forget")
            .executes { context -> runForget(context, only = null) }
            .then(
                Commands.argument(WORD_ARGUMENT, StringArgumentType.word())
                    .suggests { context, builder ->
                        val known = context.source.player?.learnedWords?.words.orEmpty()
                        SharedSuggestionProvider.suggest(known.map { it.path }, builder)
                    }
                    .executes { context ->
                        runForget(context, only = StringArgumentType.getString(context, WORD_ARGUMENT))
                    },
            )

    private fun runForget(context: CommandContext<CommandSourceStack>, only: String?): Int {
        val source = context.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("Only a player knows any words"))
            return FAILURE
        }
        val learned = player.learnedWords
        val forgotten = if (only == null) {
            learned.words.toList().also { all -> all.forEach(learned::forget) }
        } else {
            learned.words.filter { it.path == only || it.toString() == only }.also { it.forEach(learned::forget) }
        }
        if (forgotten.isEmpty()) {
            source.sendFailure(Component.literal(only?.let { "You do not know '$it'" } ?: "You know nothing"))
            return FAILURE
        }
        Services.NETWORK.sendToPlayer(player, LearnedWordsPayload.whole(learned.words))
        source.sendSuccess({ Component.literal("Forgot ${forgotten.size} word(s)") }, true)
        return SUCCESS
    }

    private fun listSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("list")
            .executes { context -> runList(context, Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runList(context, Report.structured(context.source)) },
            )

    private fun ageId(name: String): Identifier =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

    private fun runCreate(context: CommandContext<CommandSourceStack>, preset: AgePreset, seed: Long?): Int =
        write(context, AgeRecipe.worldFor(preset), seed)

    /** `/age compose <name> [<seed>] <spec>` — writes an Age out of aspects instead of naming a preset. */
    private fun runCompose(context: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val specification = StringArgumentType.getString(context, SPECIFICATION_ARGUMENT)
        val written = CompositionSpelling.read(specification).getOrElse { problem ->
            context.source.sendFailure(Component.literal(problem.message ?: "Could not read '$specification'"))
            return FAILURE
        }
        return write(context, AgeWorld.Composed(written.composition), seed, written.template, written.instability)
    }

    /** Writes an Age down and opens it — the tail `create` and `compose` share. */
    private fun write(
        context: CommandContext<CommandSourceStack>,
        world: AgeWorld,
        seed: Long?,
        template: AgeTemplate = AgeTemplate.ORDINARY,
        instability: Instability = Instability.NONE,
    ): Int {
        val source = context.source
        val report = Report.prose(source)
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id, report)) return FAILURE
        val recipe = AgeRecipe.written(
            source.server,
            world,
            seed ?: AgeRecipe.seedFor(id),
            template,
            instability = instability,
        )
        return open(source, name, id, recipe, report)
    }

    /** Whether an Age called [name] can be written here at all — having said why, if not. */
    private fun canWrite(source: CommandSourceStack, name: String, id: Identifier, report: Report): Boolean {
        if (!Ages.isSupported()) {
            report.fail("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)")
            return false
        }
        if (id in AgeSavedData.get(source.server).ages) {
            report.fail("Age '$name' already exists")
            return false
        }
        return true
    }

    /** Persists a recipe and opens its dimension — the last step of every way of authoring an Age. */
    private fun open(source: CommandSourceStack, name: String, id: Identifier, recipe: AgeRecipe, report: Report): Int {
        val level = Ages.create(source.server, id, recipe)
        if (level == null) return report.fail("Could not create Age '$name'")
        report.say { "Created Age '$name' [$recipe] ($id). Travel with /age tp $name" }
        return SUCCESS
    }

    /**
     * `/age write <name> [<seed>] <words…>` — a sentence in, an Age out. [Resolver] does the work; this
     * reads the words and reports the composition, the cost, and every flaw with its reason.
     */
    private fun runWrite(context: CommandContext<CommandSourceStack>, seed: Long?, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id, report)) return FAILURE

        val vocabulary = Vocabulary.of(source.server)
        reportProblems(report, vocabulary)
        val pages = StringArgumentType.getString(context, SENTENCE_ARGUMENT)
            .split(' ').filter(String::isNotBlank)
        // **A page nobody recognises is not a thing that happens in play**: a player assembles a book from
        // pages, and every page carries a real word. Typing one here is a typo, so it fails the command
        // rather than quietly making a vaguer Age out of the rest.
        val unknown = pages.filter { vocabulary.word(it) == null && vocabulary.grammarWord(it) == null }
        if (unknown.isNotEmpty()) {
            return report.fail("The Art has never heard of ${unknown.joinToString(" ")}")
        }
        // The one refusal in the Art (§4.3.1): name the thing you are making, or there is no book. Without
        // it an empty book would be a free reroll on a random Age, which is what repair would hand back.
        val read = Grammar.read(vocabulary, pages)
            ?: return report.fail("A book opens with the Age page — write 'age' first, then what it is like")
        if (read.isEmpty) return report.fail("An Age needs at least one word the Art can read")
        reportParse(report, read)

        val chosenSeed = seed ?: AgeRecipe.seedFor(id)
        val resolution = Resolver.resolve(vocabulary, read, chosenSeed)
        val recipe = AgeRecipe.written(source.server, resolution, chosenSeed)
        val result = open(source, name, id, recipe, report)
        if (result == FAILURE) return FAILURE

        report.only("age", id)
        report.only("recipe", recipe.world)
        report.only("seed", chosenSeed)
        report.fact("cost", resolution.cost) { "  cost ${resolution.cost}, ${resolution.instability}" }
        report.only("instability", resolution.instability.index)
        for (flaw in resolution.instability.flaws) {
            val fields = mapOf(
                "register" to flaw.register.key,
                "words" to flaw.words,
                "aspect" to flaw.aspect?.key,
                "severity" to flaw.severity,
            )
            report.entry("flaws", fields) { "  ! $flaw" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * What the Art made of the book, said out loud before the Age is opened — the stand-in for the desk's
     * scratch mode (design §7.5) until it exists, and the only way the grammar's attachment is visible at
     * all, since a book carries no punctuation to show it.
     *
     * The prose is the readout proper; the lines under it are this command's own, because `/age write` is
     * a debug tool and *where* a word reaches is what an author of the vocabulary needs to see. A desk
     * shows the prose alone.
     */
    private fun reportParse(report: Report, read: Sentence) {
        report.fact("readout", Readout.of(read)) { "  “${Readout.of(read)}”" }
        for (said in read.constraints) {
            val aimed =
                if (said.word.tier.narrows) said.aimedAt.joinToString(" ") { it.page } else "everywhere"
            val joined = said.group?.let { " (joined)" } ?: ""
            // Marked rather than hidden: the Age is built from the Art's own pages too, so a reader owed a
            // diagnosis has to see them — and they were never in the book, so they must not read as though
            // the writer had laid them.
            val whose = when {
                said.latent -> " (the Art's own)"
                said.rehomed -> " (moved here)"
                else -> ""
            }
            val fields = mapOf(
                "word" to said.word.name,
                "reaches" to said.aimedAt.sortedBy { it.ordinal }.map { it.page },
                "polarity" to said.polarity.name.lowercase(),
                "density" to Rung.spelled(said.density),
                "joined" to (said.group != null),
                "latent" to said.latent,
                "rehomed" to said.rehomed,
            )
            report.entry("said", fields) { "    ${said.word.name} → $aimed$joined$whose" }
        }
        // A book that was not a sentence is repaired against one the Art draws for itself, and what it drew
        // is the natural course of a world nobody described that far. The book never shows it, so this is
        // the only place a writer can be told.
        val supplied = read.constraints.filter { it.latent }.map { it.word.name }
        report.only("supplied", supplied)
        if (supplied.isNotEmpty()) {
            report.styled {
                Component.literal("  your book was not a sentence, so the Art wrote the rest of it: ")
                    .append(Component.literal(supplied.joinToString(" ")).withStyle(ChatFormatting.GRAY))
            }
        }
        // The two ways a page can fail to be in the reading, said apart because they cost different things
        // (§4.3): one makes the Age vaguer and is charged nothing, the other is charged and charged dearly.
        // Both are struck through and said plainly, since the readout above renders only what parsed and
        // prose that quietly omitted a page would read as though it had worked (§4.3.1).
        report.only("unreadable", read.unreadable)
        if (read.unreadable.isNotEmpty()) {
            report.styled {
                val unread = Component.literal(read.unreadable.joinToString(" "))
                    .withStyle(ChatFormatting.STRIKETHROUGH)
                Component.literal("  unread, so the Age comes out vaguer: ").append(unread)
            }
        }
        report.only("impossible", read.impossible)
        if (read.impossible.isNotEmpty()) {
            report.styled {
                val nowhere = Component.literal(read.impossible.joinToString(" "))
                    .withStyle(ChatFormatting.STRIKETHROUGH)
                Component.literal("  no sentence has a place for: ").append(nowhere)
            }
        }
    }

    /**
     * `/age pages [derived]` — a notebook holding a page of every word there is to write with.
     *
     * The authored corpus and the structural words by default, which is everything a sentence is *built*
     * from; `derived` is the other half, and it is a separate notebook because there are eleven hundred of
     * them and they are all materials.
     *
     * The exclusions loot honours are honoured here too, so a debug command cannot hand out the one thing
     * §7.1.2 says must wait for the rung that grants it.
     */
    private fun runPages(context: CommandContext<CommandSourceStack>, derived: Boolean): Int {
        val source = context.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("Only a player can be handed a notebook"))
            return FAILURE
        }
        val vocabulary = Vocabulary.of(source.server)
        val registries = source.registryAccess()
        val words = (if (derived) vocabulary.derivedWords else vocabulary.authoredWords)
            .filterNot { Withheld.holdsBack(it, registries) }
        if (words.isEmpty()) return FAILURE.also { source.sendFailure(Component.literal("No words to write")) }

        val pages = words.map { word ->
            ItemStack(AgeContent.PAGE).also { it.set(AgeContent.PAGE_WORD, word.id) }
        }
        // The structural words go in beside them: `and`, `only` and the rungs are pages a writer lays like
        // any other, and a book cannot be tested for structure without them.
        val structural = if (derived) emptyList() else vocabulary.grammarWords.map { spelled ->
            ItemStack(AgeContent.PAGE).also { it.set(AgeContent.PAGE_WORD, spelled.id) }
        }
        val notebook = ItemStack(AgeContent.NOTEBOOK)
        NotebookItem.setPages(notebook, pages + structural)
        if (!player.inventory.add(notebook)) player.drop(notebook, false)
        source.sendSuccess({
            Component.literal("A notebook of ${pages.size + structural.size} pages. Tip it into the desk.")
        }, false)
        return SUCCESS
    }

    /** `/age words` — the whole vocabulary, with each word's tier and the aspects it may fill. */
    private fun runVocabulary(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        reportProblems(report, vocabulary)
        if (vocabulary.words.isEmpty()) {
            return report.fail("The Art knows no words at all — is the mod's data pack loaded?")
        }
        // Authored words are listed; derived ones are counted, since there is one per block in the pack.
        val authored = vocabulary.words.filter { it.id.namespace == Constants.MOD_ID }
        report.fact("words", vocabulary.words.size) { "The Art knows ${vocabulary.words.size} words." }
        report.fact("authored", authored.size) { "${authored.size} written by hand:" }
        for (word in authored) {
            val about = if (word.aspects.isEmpty()) "anywhere" else word.aspects.joinToString(" ") { it.page }
            // Every tag it asks for, wherever it asks — a global tilt and a keyed one read the same here.
            val asks = (word.wanted.sorted() + word.unwanted.sorted().map { "-$it" }).joinToString(" ")
            val fields = mapOf(
                "word" to word.name,
                "tier" to word.tier.key,
                "aspects" to word.aspects.map { it.page },
                "aims" to word.aims,
            )
            report.entry("authoredWords", fields) { "  ${word.name} — ${word.tier.key}, $about: $asks" }
        }
        val structural = vocabulary.grammarWords
        report.only("structural", structural.map { it.name })
        if (structural.isNotEmpty()) {
            report.say { "${structural.size} structural: ${structural.joinToString(" ") { it.name }}" }
        }
        // **What a vague word can actually reach**, per aspect — the tag layer measured where its tags are
        // bound, which offline is exactly where they are not (`notes/the-tag-layer.md` §4). A pool far
        // larger than the hand-authored table is the derivation having fired.
        for (aspect in Aspect.entries) {
            val reachable = vocabulary.askableIn(aspect).size
            if (reachable == 0) continue
            report.entry("reach", mapOf("aspect" to aspect.page, "reachable" to reachable)) {
                "  ${aspect.page}: $reachable reachable by description"
            }
        }
        // Per namespace, which says at a glance whether a mod's content reached the vocabulary (§8).
        val derivedByPack = vocabulary.words.filter { it.id.namespace != Constants.MOD_ID }
            .groupingBy { it.id.namespace }.eachCount().entries.sortedByDescending { it.value }
        report.only("derived", vocabulary.words.size - authored.size)
        if (derivedByPack.isNotEmpty()) {
            val counts = derivedByPack.joinToString(", ") { (pack, many) -> "$pack $many" }
            report.say { "${vocabulary.words.size - authored.size} derived — $counts" }
            report.say { "  say any block, biome or structure by name, e.g. 'copper_block', 'mansion'" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * Anything wrong with the loaded vocabulary. A word that could not be read must be reported, never
     * silently absent (design §3.3).
     */
    private fun reportProblems(report: Report, vocabulary: Vocabulary) {
        report.only("problems", vocabulary.problems)
        for (problem in vocabulary.problems) {
            report.say { "Vocabulary problem: $problem" }
        }
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
        val saved = AgeSavedData.get(source.server)
        val firstName = StringArgumentType.getString(context, FIRST_ARGUMENT)
        val secondName = StringArgumentType.getString(context, SECOND_ARGUMENT)

        val first = openNamedAge(source, firstName, report) ?: return FAILURE
        val second = openNamedAge(source, secondName, report) ?: return FAILURE

        val firstRecipe = saved.recipe(ageId(firstName))
        val secondRecipe = saved.recipe(ageId(secondName))
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

    /** The Age called [name], opened — or null, having already said why. */
    private fun openNamedAge(source: CommandSourceStack, name: String, report: Report): ServerLevel? {
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return null
        }
        val level = Ages.open(source.server, id)
        if (level == null) report.fail("Could not open Age '$name'")
        return level
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

    private fun runDelete(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        if (!Ages.delete(source.server, ageId(name))) {
            source.sendFailure(Component.literal("Could not delete Age '$name' — no such Age, or the loader can't"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Deleted Age '$name'") }, true)
        return SUCCESS
    }

    private fun runDeleteAll(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val deleted = Ages.deleteAll(source.server)
        if (deleted == 0) {
            source.sendFailure(Component.literal("No Ages to delete"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Deleted $deleted Age(s)") }, true)
        return SUCCESS
    }

    /**
     * Prints an Age's sky, and when [preview] is given, shows that one instead.
     *
     * **What clients were told, not what the recipe would say**, which is the only reading that can catch the
     * pipeline having gone quiet — a look never given is a sky nobody sees, and recomputing it here would
     * report one anyway.
     */
    private fun runSkyReport(context: CommandContext<CommandSourceStack>, preview: String?): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
        val recipe = AgeSavedData.get(source.server).recipe(ageId(name))

        // The Art's own words and the library's parameters are told apart by name, so one line can carry both:
        // `sun.size=0.9..1.0 path=epicycle` reads as a sky the words describe with one thing turned.
        val said = preview?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        val (parameters, words) = said.partition { SkyParameters.offers(it.substringBefore('=')) }

        // **A line with no words turns the Age's own sky**, and that is the only way a parameter reaches a sky
        // the words made: a preview spec resolves one sun and one moon whatever it says, bodies being
        // minted by clauses that a `sky.…` line cannot carry. Naming any word restates the sky in full, as
        // it always did — so `path=polar` alone tips the Age's own suns and `sky=plain path=polar` tips one.
        val asWritten = if (words.isEmpty()) {
            LevelAppearance.of(level.dimension())?.sky ?: run {
                source.sendFailure(Component.literal("Nothing has said what '$name' looks like"))
                return FAILURE
            }
        } else {
            previewSpec(source, words.joinToString(" "), recipe.seed) ?: return FAILURE
        }

        // **Described after the parameters, not before.** Reporting the sky as written while showing the client
        // the sky as turned is the one thing this instrument must not do: you would read an unchanged
        // description, look up at a changed sky, and conclude the feature was broken.
        val shown = if (preview == null) {
            null
        } else {
            SkyParameters.applyTo(asWritten, parameters).getOrElse { problem ->
                source.sendFailure(Component.literal(problem.message ?: "Could not read a parameter"))
                return FAILURE
            }
        }
        shown?.let { LevelLookPreview.show(level, it) }

        val heading = if (preview == null) "Age '$name' sky" else "Previewing in '$name' (reverts on re-entry)"
        source.sendSuccess({ Component.literal(heading) }, false)
        if (parameters.isNotEmpty()) {
            source.sendSuccess({ Component.literal("  turned ${parameters.joinToString(" ")}") }, false)
        }
        for (line in (shown?.sky ?: asWritten).described()) {
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
        // The Age's own clock — what every moving thing above reads, and the only way to tell an Age
        // following the overworld from one frozen at dawn.
        source.sendSuccess({ Component.literal("  $CLOCK_LABEL ${level.defaultClockTime}") }, false)
        // **And the hour it is *lit* as**, which is a different number and the one that decides every
        // colour: an Age's own suns move the whole timeline, and a sky with none is held at midnight. Not
        // having this said cost a walk — the sky was reported correct while the client showed a sunset
        // (Jonah, 2026-08-27).
        val showing = shown ?: LevelAppearance.of(level.dimension())
        val litAs = showing?.let { LevelClock.vanillaEquivalent(it, level.defaultClockTime) }
        if (litAs != null) {
            source.sendSuccess({ Component.literal("  $LIT_AS_LABEL ${Math.floorMod(litAs, VANILLA_DAY)}") }, false)
        }
        return SUCCESS
    }

    /**
     * The sky a preview spec asks for, or null having said why.
     *
     * Reuses `/age compose`'s parser, which refuses a composition with no terrain — so [PREVIEW_SCAFFOLD]
     * is prepended and ignored. Since that scaffold is invisible, naming any other aspect is refused
     * rather than silently overridden.
     */
    private fun previewSpec(source: CommandSourceStack, preview: String, seed: Long): SkySpec? {
        val strayAspects = preview.split(' ')
            .filter { token -> token.isNotBlank() }
            .map { token -> token.substringBefore('=') }
            .filterNot { named -> named == SKY_ASPECT || named.startsWith("$SKY_ASPECT.") }
        if (strayAspects.isNotEmpty()) {
            source.sendFailure(
                Component.literal(
                    "`/age sky` previews the sky only, but you named ${strayAspects.joinToString(" ")}. " +
                        "Write it as `sky=plain sun.size=0.6..1.0`, and use `/age compose` for the rest. " +
                        "The library's own parameters are ${SkyParameters.describeOffered()}.",
                ),
            )
            return null
        }
        // An unknown option value is refused here, where `/age compose` keeps it: a composition is a save
        // and must keep saying what it said, but an instrument that silently ignores `orbits=wilde` and
        // shows the default is worse than one that refuses.
        // **Everything overhead, not the vault alone.** The bodies are the sun's, the moon's and the
        // stars' aspects now, and a preview line still spells them all `sky.…` because it is one
        // instrument over one picture.
        // Which of the four owns each parameter, **first of them wins**: `rising` is the sun's and the moon's
        // alike, and a preview line naming one body means the sun. The moon's is reachable through
        // `/age compose moon.rising=…`, which spells the aspect out.
        val overhead = listOf(Aspect.SKY, Aspect.SUN, Aspect.MOON, Aspect.STARS)
        val ownerOfParameter = overhead
            .flatMap { aspect -> aspect.parameters.map { it.name to aspect } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, owners) -> owners.first() }
        val skyParameters = overhead.flatMap { it.parameters }.associateBy { parameter -> parameter.name }
        val unreadable = preview.split(' ')
            .filter { token -> token.isNotBlank() && token.startsWith("$SKY_ASPECT.") }
            .mapNotNull { token ->
                val name = token.substringBefore('=').removePrefix("$SKY_ASPECT.")
                val value = token.substringAfter('=', missingDelimiterValue = "")
                val parameter = skyParameters[name]
                    ?: return@mapNotNull "$name — no such sky parameter. Try: ${skyParameters.keys.joinToString(" ")}"
                if (parameter.accepts(value)) null
                else "$name=$value — try: ${parameter.options.joinToString(" ")}"
            }
        if (unreadable.isNotEmpty()) {
            source.sendFailure(Component.literal(unreadable.joinToString("; ")))
            return null
        }

        // **Aimed at the aspect that owns each parameter before it is read.** A preview spells everything
        // overhead `sky.…` because it is one instrument over one picture, but the bodies are the sun's, the
        // moon's and the stars' aspects — so a `sky.colour` left as written is stored on the vault and
        // looked for on the sun, which is to say accepted and then ignored.
        val aimed = preview.split(' ').filter(String::isNotBlank).joinToString(" ") { token ->
            val name = token.substringBefore('=').removePrefix("$SKY_ASPECT.")
            val owner = ownerOfParameter[name]
            if (!token.startsWith("$SKY_ASPECT.") || owner == null) token
            else "${owner.page}.$name=${token.substringAfter('=')}"
        }
        // The terrain here is scaffolding for the parser and is read by nothing.
        val composition = AgeComposition.parse("$PREVIEW_SCAFFOLD $aimed").getOrElse { problem ->
            source.sendFailure(Component.literal(problem.message ?: "Could not read '$preview'"))
            return null
        }
        // The Age's own seed, so a preview differs from the real sky only where the words differ.
        return composition.sky.specFor(composition, seed)
    }

    /**
     * Strikes the ground [distance] blocks along the caster's line of sight.
     *
     * The target is the surface under that point rather than the point itself, because a strike is a
     * column: aiming into the air would put the bolt in the air. `MOTION_BLOCKING` is the heightmap
     * vanilla's own targeting uses.
     */
    private fun runStrike(context: CommandContext<CommandSourceStack>, distance: Int): Int {
        val source = context.source
        val level = source.level
        val aimed = source.position.add(Vec3.directionFromRotation(source.rotation).scale(distance.toDouble()))
        val struck = Tempest.callDown(level, BlockPos.containing(aimed), EntitySpawnReason.COMMAND)
        source.sendSuccess({ Component.literal("Struck ${struck.x} ${struck.y} ${struck.z}") }, true)
        return SUCCESS
    }

    private fun runSandfall(
        context: CommandContext<CommandSourceStack>,
        distance: Int,
        seconds: Int?,
        furyPercent: Int,
    ): Int {
        val source = context.source
        val level = source.level
        val facing = Vec3.directionFromRotation(source.rotation)
        val at = source.position.add(facing.scale(distance.toDouble()))
        val whole = SandfallBehaviour.of(source.server)
        val behaviour = whole.column
        val fury = furyPercent.toDouble() / ALL_FURY
        val random = level.random
        val column = SandColumn.raise(
            level = level,
            atX = at.x,
            atZ = at.z,
            // Turned around to walk back at you, so a column stood up ahead is one you then have to answer.
            headingDegrees = source.rotation.y + HALF_COMPASS,
            speed = behaviour.slowestSpeed + random.nextDouble() * (behaviour.fastestSpeed - behaviour.slowestSpeed),
            fullHalfWidth = whole.narrowestHalfWidthAt(fury) +
                random.nextDouble() * (whole.widestHalfWidthAt(fury) - whole.narrowestHalfWidthAt(fury)),
            depth = whole.depthAt(fury),
            lifetime = seconds?.times(TICKS_PER_SECOND)
                ?: (
                    whole.shortestLifeAt(fury) +
                        random.nextInt(
                            (whole.longestLifeAt(fury) - whole.shortestLifeAt(fury)).coerceAtLeast(1),
                        )
                    ),
        )
        if (column == null) {
            // Two causes, and saying which is the difference between a one-line fix and an afternoon: the
            // ground can only be asked of a chunk that is already there, so the usual answer is to stand
            // closer or to wait for the world to catch up.
            val ground = SectionPos.blockToSectionCoord(Mth.floor(at.x)) to
                SectionPos.blockToSectionCoord(Mth.floor(at.z))
            source.sendFailure(
                Component.literal("No column: chunk ${ground.first} ${ground.second} is not ticking entities"),
            )
            return FAILURE
        }
        source.sendSuccess(
            {
                Component.literal(
                    "A sandfall ${"%.0f".format(column.fullHalfWidth * 2)} across at " +
                        "${column.blockX} ${column.blockZ}, walking " +
                        "${compassPointFor(column.yRot + HALF_COMPASS)} for " +
                        "${column.lifetime / TICKS_PER_SECOND}s",
                )
            },
            true,
        )
        return SUCCESS
    }

    private fun runTeleport(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
        Ages.teleport(player, level)
        source.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return SUCCESS
    }

    /**
     * `/age tp overworld|nether|end` — back out of an Age without hunting for a book.
     *
     * A testing convenience, and it lands differently per dimension because one rule would be wrong
     * somewhere: the Overworld has a respawn point worth using, the Nether's surface heightmap finds the
     * bedrock roof, and the End has no terrain at all until you reach an island.
     */
    private fun runVanillaTeleport(
        context: CommandContext<CommandSourceStack>,
        target: ResourceKey<Level>,
    ): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.server.getLevel(target)
            ?: return Report.prose(source).fail("This world has no ${target.identifier().path}")

        val landing = when (target) {
            Level.OVERWORLD -> {
                val respawn = source.server.respawnData.globalPos().pos()
                Vec3(respawn.x + 0.5, respawn.y.toDouble(), respawn.z + 0.5)
            }
            // The obsidian platform, which is where a portal would have put you.
            Level.END -> Vec3(END_PLATFORM_X, END_PLATFORM_Y, END_PLATFORM_Z)
            else -> standingRoom(level, player.blockX, player.blockZ)
        }
        player.teleportTo(
            level, landing.x, landing.y, landing.z,
            emptySet(), player.yRot, player.xRot, true,
        )
        source.sendSuccess({ Component.literal("Travelled to ${target.identifier().path}") }, true)
        return SUCCESS
    }

    /**
     * The lowest gap with solid ground under it, searched downward.
     *
     * Downward rather than off the heightmap because the Nether's ceiling *is* its surface — the
     * heightmap would land you on top of the world.
     */
    private fun standingRoom(level: ServerLevel, x: Int, z: Int): Vec3 {
        level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
        val cursor = BlockPos.MutableBlockPos()
        for (y in level.maxY - 1 downTo level.minY + 1) {
            cursor.set(x, y, z)
            val head = level.getBlockState(cursor).isAir
            val feet = level.getBlockState(cursor.setY(y - 1)).isAir
            val floor = !level.getBlockState(cursor.setY(y - 2)).isAir
            if (head && feet && floor) return Vec3(x + 0.5, (y - 1).toDouble(), z + 0.5)
        }
        return Vec3(x + 0.5, (level.minY + 1).toDouble(), z + 0.5)
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
     * What share of the surface each biome covers — the instrument for tuning biome weights, where
     * [surveyBiomes]'s "which biomes exist here" is the wrong question.
     *
     * Samples the biome source directly rather than generated chunks, so it measures the climate table
     * alone and costs no generation. Surface only.
     */
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
        val recipe = AgeSavedData.get(source.server).recipe(ageId(name))

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
     * `/age book [<seed>]` — a book the Art could have written, read back rather than handed over.
     *
     * **Deliberately not the item.** A real one is what `/give agesandtheart:descriptive_book` produces, so
     * what this is for is *looking at what the grammar writes* — pages and reading, at a seed you can name,
     * from a console that has nobody to hand anything to.
     */
    private fun runBook(context: CommandContext<CommandSourceStack>, seed: Long): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        val grammar = vocabulary.generation.grammar(FoundBook.GRAMMAR)
        if (grammar == null) {
            source.sendFailure(
                Component.literal("This pack ships no '${FoundBook.GRAMMAR}' grammar, so the Art writes none"),
            )
            return FAILURE
        }
        val pages = grammar.expand(Random(seed))
        source.sendSuccess({ Component.literal("A book at seed $seed, ${pages.size} pages:") }, false)
        source.sendSuccess({ Component.literal("  ${pages.joinToString(" ")}") }, false)
        // Said back through the readout, so what it *means* is visible beside what it says — which is the
        // only way to judge whether a generated book is a good one.
        // A generation grammar that forgot the Age page has written something no player could bind, and
        // saying so here is the whole point of drafting against it.
        val sentence = Grammar.read(vocabulary, pages)
        val reading = sentence?.let(Readout::of) ?: "nothing — this book has no Age page, so it is not a book"
        source.sendSuccess({ Component.literal("  reads as: $reading") }, false)
        source.sendSuccess({
            Component.literal("  write it with: /age write book$seed $seed ${pages.joinToString(" ")}")
        }, false)
        return SUCCESS
    }

    /**
     * `/age draft <grammar> [<seed>]` — one expansion of a generation grammar, for authoring them against.
     * `/reload` picks a rewritten grammar up, so this is the whole edit loop.
     */
    private fun runDraft(context: CommandContext<CommandSourceStack>, seed: Long): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val vocabulary = Vocabulary.of(source.server)
        val grammar = vocabulary.generation.grammar(name)
        if (grammar == null) {
            val known = vocabulary.generation.names.joinToString(" ").ifEmpty { "none" }
            source.sendFailure(Component.literal("No generation grammar called '$name'. Known: $known"))
            return FAILURE
        }
        val produced = grammar.expand(Random(seed))
        source.sendSuccess({ Component.literal("'$name' at seed $seed, ${produced.size} terminals:") }, false)
        source.sendSuccess({ Component.literal("  ${produced.joinToString(" ")}") }, false)
        // A word grammar is meant to produce a book, so it is judged the way a book is: by what the parser
        // makes of it, not by whether the words look plausible in a row.
        if (grammar.terminals == TerminalKind.WORD) {
            val sentence = Grammar.read(vocabulary, produced)
            val reading = sentence?.let(Readout::of) ?: "nothing — no Age page, so this is not a book"
            source.sendSuccess({ Component.literal("  reads as: $reading") }, false)
            if (sentence != null && sentence.dropped.isNotEmpty()) {
                source.sendSuccess({
                    Component.literal("  unread: ${sentence.dropped.joinToString(" ")}").withStyle(ChatFormatting.RED)
                }, false)
            }
        }
        return SUCCESS
    }

    /**
     * What grows where, as a share of the ground.
     *
     * **Use a large radius.** Vanilla's continentalness varies over something like a thousand blocks, so a
     * census of six chunks sits inside one band of it and reports that band as the whole world — six chunks
     * of a `craterlands` Age said 95% ocean where forty-eight said 20%. A small answer here is not a
     * measurement, it is one place.
     */
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
        val level = openNamedAge(source, name, report) ?: return FAILURE

        val generator = level.chunkSource.generator
        val structures = level.structureManager()
        val randomState = level.chunkSource.randomState()
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
                    val biome = level.getBiome(here)
                    for (pass in MobCategory.entries) {
                        val list = generator.getMobsAt(biome, structures, pass, here)
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
        val placing = AgeGeneration.spawnersFor(source.server, AgeSavedData.get(source.server).recipe(ageId(name)))
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
        val climate = randomState.sampler()
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
                val here = biomeName(biomes, climate, quartX, QuartPos.fromBlock(ground), quartZ)
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
            val share = PERCENT * count / sampled
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
        val climate = level.chunkSource.randomState().sampler()
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
                val top = biomeName(source, climate, quartX, highestQuartY, quartZ)
                everywhere += top
                var layered = false
                for (quartY in lowestQuartY..highestQuartY) {
                    val here = biomeName(source, climate, quartX, quartY, quartZ)
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

    private fun biomeName(source: BiomeSource, climate: Climate.Sampler, quartX: Int, quartY: Int, quartZ: Int): String =
        source.getNoiseBiome(quartX, quartY, quartZ, climate).unwrapKey()
            .map { it.identifier().toString() }
            .orElse("(unnamed)")

    /** Every fourth quart cell, i.e. one column per 16 blocks — dense enough to find small biomes. */
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

    private const val SURVEY_QUART_STRIDE = 4

    /** How far a census may reach. Larger than the benchmark's cap because this generates nothing. */
    private const val MAX_CENSUS_RADIUS = 512

    private const val PERCENT = 100.0
    private const val BLOCKS_PER_CHUNK = 16

    /** Wide enough to cross several biomes at vanilla's scale, and free since nothing is generated. */
    private const val SURVEY_RADIUS_CHUNKS = 64

    /**
     * Every Age and the recipe it is rebuilt from, one to a line. Unrecognised options are called out,
     * so a misspelt parameter is distinguishable from one that had no effect.
     */
    private fun runList(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val ages = saved.ages
        report.only("count", ages.size)
        if (ages.isEmpty()) {
            report.say { "No Ages yet — write one with /age create <name>" }
            report.finish()
            return SUCCESS
        }
        report.say { "Ages (${ages.size}):" }
        for (id in ages) {
            val recipe = saved.recipe(id)
            val unknown = recipe.composition?.unknownOptions.orEmpty()
            val fields = mapOf(
                "age" to id,
                "recipe" to recipe.world,
                "seed" to recipe.seed,
                "sentence" to recipe.words.joinToString(" "),
                "instability" to recipe.instability.index,
                // Which of the four pre-authored types it wears — invisible in game until you notice the
                // world is not dark, and the one thing `/age list` could not answer.
                "dimensionType" to AgeGeneration.dimensionType(recipe),
                "unrecognised" to unknown,
                // Told apart from the above: this one is spelled right and cannot be honoured here.
                "unhonoured" to recipe.unhonoured,
            )
            report.entry("ages", fields) { "  $id — $recipe" }
            if (unknown.isNotEmpty()) {
                report.say { "    (ignored, unrecognised: ${unknown.joinToString(" ")})" }
            }
            // Said apart from the above, because the remedy is: that one is misspelled, this one is spelled
            // perfectly and asks the world it was written over for something it has no way to give.
            for (limit in recipe.unhonoured) report.say { "    (the world it was written over: $limit)" }
        }
        report.finish()
        return SUCCESS
    }
}
