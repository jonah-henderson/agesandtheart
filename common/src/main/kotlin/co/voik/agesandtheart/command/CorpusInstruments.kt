package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Report
import net.minecraft.server.MinecraftServer
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.grammar.Said
import net.minecraft.commands.SharedSuggestionProvider
import co.voik.agesandtheart.age.word.DerivationRules
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.TerminalKind
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.location
import co.voik.agesandtheart.book.FoundBook
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import kotlin.random.Random
import net.minecraft.network.chat.Component

internal object CorpusInstruments {

    /** What the Art currently knows, and what the registries hold for it to draw on. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(tagsSubcommand())
            .then(rulesSubcommand())
            .then(dimensionsSubcommand())
            .then(holdingsSubcommand())
            .then(bookSubcommand())
            .then(draftSubcommand())
    }

    /** `/age tags <aspect> [tag]`. */
    private const val ASPECT_ARGUMENT = "aspect"

    private const val REGISTRY_ARGUMENT = "registry"

    private const val TAGS_LITERAL = "tags"

    private const val TAG_ARGUMENT = "tag"

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

    /**
     * `/age tags <aspect> [tag]` — **what a vague word can actually find**, member by member.
     *
     * The instrument the tag pass needed and did not have (`notes/the-tag-layer.md` §7). Reading the rules
     * says what they were meant to claim; only asking a tag what carries it says what they do — and half
     * the layer is vanilla's own tags, which are bound on a server and nowhere else, so this is the one
     * place the whole picture exists.
     */
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
        val aspect = Aspect.byPage(named)
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

    private fun bookSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("book")
            .executes { context -> runBook(context, seed = context.source.level.gameTime) }
            .then(
                Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                    .executes { context -> runBook(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
            )

    private fun draftSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("draft").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runDraft(context, seed = context.source.level.gameTime) }
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                        .executes { context -> runDraft(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
                ),
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

}
