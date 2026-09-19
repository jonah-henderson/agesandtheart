package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.desk.PageArchive
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import net.minecraft.core.component.DataComponentType
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * What a stack of ours carries — the data components, and the list each loader registers them from.
 *
 * **Apart from [AgeContent] because none of these holds an `Item` or a `Block`.** That object builds every
 * item and block eagerly, so anything wanting only a component type had to stand up the whole content
 * table to reach it, and could not be touched at all once the registries had frozen. Reading a page's word
 * is not a reason to construct forty items.
 *
 * Registered before the items on both loaders, which is the order a stack's own components need.
 */
object AgeComponents {
    /**
     * Data component stored on a Descriptive Book stack: the id of the Age it links to.
     * `persistent` = saved to disk; `networkSynchronized` = sent to the client.
     */
    val AGE_ID: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
        .persistent(Identifier.CODEC)
        .networkSynchronized(Identifier.STREAM_CODEC)
        .build()

    /** The word written on a page. Rolled when the page is generated, never when it is read. */
    val PAGE_WORD: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
        .persistent(Identifier.CODEC)
        .networkSynchronized(Identifier.STREAM_CODEC)
        .build()

    /**
     * The stock a page or notebook is still to be drawn from — **an unwritten page, not a blank one**.
     *
     * A villager's offer is built once and bought many times, so anything a trade writes into the item is
     * written once: a page whose word was rolled when the offer was made hands out that same word for ever.
     * Carrying the *pool* instead defers the draw to the moment somebody takes the item
     * (`MerchantResultSlotMixin`), so every purchase is a different word.
     *
     * This is the one place §8's rule that a page's word is "rolled when the page is generated, never when
     * it is read" is bent, and it is bent rather than broken: a found page is still rolled where it is
     * found. What is deferred here is the roll for a page that has not been handed to anybody yet.
     */
    val STOCKED_FROM: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
        .persistent(Identifier.CODEC)
        .networkSynchronized(Identifier.STREAM_CODEC)
        .build()

    /** Where a Linking Book goes. Absent means blank — see [co.voik.agesandtheart.book.LinkingBookItem]. */
    val LINK_TARGET: DataComponentType<LinkTarget> = DataComponentType.builder<LinkTarget>()
        .persistent(LinkTarget.CODEC)
        .networkSynchronized(LinkTarget.STREAM_CODEC)
        .build()

    /**
     * The pages a notebook holds, oldest first and **uncapped**.
     *
     * Not `BUNDLE_CONTENTS`: a bundle's capacity is enforced in a private weight check, which would cap a
     * notebook at sixty-four pages — a pocket rather than a catalogue.
     *
     * **Read and written only through [NotebookItem]**, which is not a style preference: this was set as
     * vanilla's `CONTAINER` in one place and asked for here in every other, so a found notebook held its
     * pages where nothing could see them and opened empty.
     */
    val NOTEBOOK_PAGES: DataComponentType<List<ItemStack>> = DataComponentType.builder<List<ItemStack>>()
        .persistent(ItemStack.CODEC.listOf())
        .networkSynchronized(ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    /**
     * The pages an archive holds, carried on the item when the block is broken — a shulker box's
     * bargain, because an unbounded store emptied onto the floor is a lag spike rather than a courtesy.
     */
    val ARCHIVE_PAGES: DataComponentType<PageArchive> = DataComponentType.builder<PageArchive>()
        .persistent(PageArchive.CODEC)
        .networkSynchronized(PageArchive.STREAM_CODEC)
        .build()

    /** The sentence a Descriptive Book carries, in order — page order is word order. */
    val BOOK_WORDS: DataComponentType<List<Identifier>> = DataComponentType.builder<List<Identifier>>()
        .persistent(Identifier.CODEC.listOf())
        .networkSynchronized(Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    /** What its writer called the Age. */
    val BOOK_TITLE: DataComponentType<String> = DataComponentType.builder<String>()
        .persistent(Codec.STRING)
        .networkSynchronized(ByteBufCodecs.STRING_UTF8)
        .build()

    /**
     * The seed the Age this book makes will be written at — chosen at the **desk**, not when the book is
     * first opened (see [co.voik.agesandtheart.desk.WritingSeedHolder]).
     *
     * On the book rather than only on its writer, so what the desk showed and what the Age turns out to be
     * cannot drift apart: a book changes hands, waits in a chest, and is opened by somebody else.
     *
     * Absent on a found book or one bound before this existed, and `DescriptiveBookRecipe` falls back to
     * seeding from the Age's id there — which is what every book did until now.
     */
    val BOOK_SEED: DataComponentType<Long> = DataComponentType.builder<Long>()
        .persistent(Codec.LONG)
        .networkSynchronized(ByteBufCodecs.VAR_LONG)
        .build()

    /**
     * What the book **says**, column by column — [Readout][co.voik.agesandtheart.age.word.grammar.Readout]'s
     * reading, with the particles a writer was spared for being inferable from position.
     *
     * Written down rather than derived, because reading a sentence takes the whole corpus and a client has
     * none. Column by column rather than as prose, because a book sets the script over its reading **word
     * for word**, and running them together would leave nothing to line up.
     */
    val BOOK_READING: DataComponentType<List<Said>> = DataComponentType.builder<List<Said>>()
        .persistent(Said.CODEC.listOf())
        .networkSynchronized(Said.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    /**
     * That this book was bound at a desk by a player, rather than found already written (design §7.7) —
     * what [co.voik.agesandtheart.age.AgeRecipe.authored] is set from when the Age is first made.
     *
     * **Stated rather than inferred, though it could be inferred today.** A found book happens to carry no
     * [BOOK_SEED] and a bound one always does, so the two are already distinguishable — but that is a
     * coincidence of two write paths rather than a claim either of them makes, and the moment a found book
     * gains a seed the reward economy would quietly open to the loot table. The fact worth recording is
     * *who wrote this*, so it is recorded.
     *
     * Absent on a found book, on a book bound before this existed, and on any hand-built stack — all of
     * which read as not authored, which is the safe way round.
     */
    val BOOK_AUTHORED: DataComponentType<Boolean> = DataComponentType.builder<Boolean>()
        .persistent(Codec.BOOL)
        .networkSynchronized(ByteBufCodecs.BOOL)
        .build()

    val components: List<Pair<Identifier, DataComponentType<*>>> = listOf(
        "stocked_from".location() to STOCKED_FROM,
        "age_id".location() to AGE_ID,
        "page_word".location() to PAGE_WORD,
        "book_words".location() to BOOK_WORDS,
        "book_title".location() to BOOK_TITLE,
        "book_seed".location() to BOOK_SEED,
        "book_reading".location() to BOOK_READING,
        "book_authored".location() to BOOK_AUTHORED,
        "link_target".location() to LINK_TARGET,
        "notebook_pages".location() to NOTEBOOK_PAGES,
        "archive_pages".location() to ARCHIVE_PAGES,
    )
}
