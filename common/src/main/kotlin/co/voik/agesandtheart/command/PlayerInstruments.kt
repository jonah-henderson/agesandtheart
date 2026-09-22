package co.voik.agesandtheart.command

import net.minecraft.util.Prediction
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.world.item.ItemStack
import co.voik.agesandtheart.platform.Services
import net.minecraft.commands.SharedSuggestionProvider
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.location
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import co.voik.agesandtheart.content.PageItem

internal object PlayerInstruments {

    /** The debug affordances: a bound book in hand, and the words a player knows. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(bindSubcommand())
            .then(pagesSubcommand())
            .then(forgetSubcommand())
    }

    private const val WORD_ARGUMENT = "word"

    /** The other half of the corpus: one word per block in the pack, and all of them materials. */
    private const val DERIVED_LITERAL = "derived"

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
        val (id, recipe) = namedAge(source, name, Report.prose(source)) ?: return FAILURE

        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK)
        book.set(AgeComponents.AGE_ID, id)
        book.set(AgeComponents.BOOK_TITLE, name)
        // A recipe keeps the sentence as pages where a book keeps it as ids, and the path is the page.
        // Set even where the Age was made by hand and has no sentence, so that a book with nothing to say
        // is not taken for a blank one and written over as a found book on the next tick.
        book.set(AgeComponents.BOOK_WORDS, recipe.words.map { it.location() })
        readingOf(source, recipe.words)?.let { book.set(AgeComponents.BOOK_READING, it) }
        player.inventory.placeItemBackInInventory(book, Prediction.SERVER_ONLY)

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

    /**
     * `/age pages [derived]` — a notebook holding a page of every word there is to write with.
     *
     * A notebook rather than the pages themselves: sixty-odd words is more stacks than an inventory has
     * rows, and a notebook is uncapped and empties into the desk in one action, which is the route a
     * player takes anyway. The authored corpus and the structural words by default, which is everything a
     * sentence is *built* from; `derived` is the other half, a separate notebook because there are eleven
     * hundred of them and they are all materials.
     *
     * The exclusions loot honours are honoured here too, so a debug command cannot hand out the one thing
     * §7.1.2 says must wait for the rung that grants it.
     */
    private fun pagesSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pages")
            .executes { context -> runPages(context, derived = false) }
            .then(Commands.literal(DERIVED_LITERAL).executes { context -> runPages(context, derived = true) })

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
            PageItem.writtenWith(word.id)
        }
        // The structural words go in beside them: `and`, `only` and the rungs are pages a writer lays like
        // any other, and a book cannot be tested for structure without them.
        val structural = if (derived) emptyList() else vocabulary.grammarWords.map { spelled ->
            PageItem.writtenWith(spelled.id)
        }
        val notebook = ItemStack(AgeContent.NOTEBOOK)
        NotebookItem.setPages(notebook, pages + structural)
        player.inventory.placeItemBackInInventory(notebook, Prediction.SERVER_ONLY)
        source.sendSuccess({
            Component.literal("A notebook of ${pages.size + structural.size} pages. Tip it into the desk.")
        }, false)
        return SUCCESS
    }

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

}
