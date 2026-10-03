package co.voik.agesandtheart.page

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.ItemStack
import co.voik.agesandtheart.advancement.LearnedBy
import co.voik.agesandtheart.age.word.DerivedWords
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.learnedWords

/**
 * Learning a word by having handled the thing, or by standing where it is (design §8.3).
 *
 * **The second learning channel, and the only one that scales.** Discovery hands out authored words a
 * found page at a time; the derived corpus is ~1100 offline and more on a server, so meeting it that way
 * is a collection quest nobody should sign up for. Acquaintance turns it into a *possession* problem
 * instead — the blocks a pack has you gather become writable as you gather them — and possession is what
 * the game already has you doing.
 *
 * **Derived referents only**, which is load-bearing rather than tidy: authored structural words stay on
 * discovery, and grammatical words come *only* from generated books (§4.5). A device that reached either
 * would gut the discovery economy and the self-illustrating grammar at a stroke. [Vocabulary.isDerived]
 * is the check, so the boundary is one question rather than a list.
 *
 * Three devices share this because the difference between them is only which registry the referent comes
 * out of: the analysis machine is a station the sample is brought to, the surveying device is one carried
 * to the place and set down there, and the observation device studies a creature held in a cage. See
 * [Withheld] for the one thing none of them may name.
 */
object Acquaintance {

    /**
     * Why [referent] would teach nothing, or null where it would — **asked without granting anything**.
     *
     * The analysis machine destroys its sample, so it has to know the answer before it takes one: a block
     * eaten in exchange for "you already know that" is the one refusal that costs something.
     */
    fun refusalFor(player: ServerPlayer, referent: Identifier): Acquainted? =
        (lookUp(player, referent) as? Lookup.Refused)?.why

    /** The word [referent] would teach, or why it would teach nothing — the server and vocabulary asked once. */
    private fun lookUp(player: ServerPlayer, referent: Identifier): Lookup {
        val server = player.level().server
        val vocabulary = Vocabulary.of(server)
        val word = vocabulary.word(referent.toString()) ?: return Lookup.Refused(Acquainted.Unnameable)
        if (!vocabulary.isDerived(word)) return Lookup.Refused(Acquainted.Unnameable)
        if (Withheld.holdsBack(word, server.registryAccess())) return Lookup.Refused(Acquainted.HeldBack)
        if (player.learnedWords.knows(word.id)) return Lookup.Refused(Acquainted.AlreadyKnown(word))
        return Lookup.Teaches(word)
    }

    private sealed interface Lookup {
        data class Teaches(val word: Word) : Lookup

        data class Refused(val why: Acquainted) : Lookup
    }

    /**
     * What the ground at [at] is called — the spot the device was stood in, not the one the player is
     * standing in when they collect. A survey consumes nothing; the price was the travel and the wait.
     */
    fun withPlace(player: ServerPlayer, level: ServerLevel, at: BlockPos): Acquainted {
        val here = level.getBiome(at).unwrapKey().orElse(null) ?: return Acquainted.Unnameable
        return teach(player, here.identifier(), LearnedBy.SURVEY)
    }

    /**
     * The referent a stack names, or null for something that names nothing.
     *
     * **A bucket is asked what block its fluid becomes**, rather than for the fluid's own id.
     * [DerivedWords.materials] walks the *block* registry, so every derived word is named for a block, and
     * a fluid is a different registry with its own keys. Ours share the block's name as vanilla's do, so
     * the two now agree — but the indirection is still the correct question to ask, and is what makes this
     * hold for a fluid whose block is named something else.
     */
    fun substanceIn(sample: ItemStack): Identifier? = when (val item = sample.item) {
        is BlockItem -> BuiltInRegistries.BLOCK.getKey(item.block)
        is BucketItem -> BuiltInRegistries.BLOCK.getKey(item.content.defaultFluidState().createLegacyBlock().block)
        else -> null
    }

    /** The referent a creature names: its kind's id, which is what [DerivedWords.spawns] names words by. */
    fun kindOf(creature: Entity): Identifier = BuiltInRegistries.ENTITY_TYPE.getKey(creature.type)

    /** Learns the word for [referent], or says why not. */
    fun teach(player: ServerPlayer, referent: Identifier, by: LearnedBy): Acquainted =
        teachEach(player, listOf(referent), by).single()

    /**
     * Learns the word for each of [referents], or says why not, in the order asked. The words learned are
     * confirmed together, so a cage of several creatures raises one toast rather than a stack of them.
     */
    fun teachEach(player: ServerPlayer, referents: List<Identifier>, by: LearnedBy): List<Acquainted> {
        val lookups = referents.map { lookUp(player, it) }
        val teachable = lookups.filterIsInstance<Lookup.Teaches>().map { it.word.id }
        val learned = PageLearning.teach(player, teachable, by).toSet()
        return lookups.map { lookup ->
            when (lookup) {
                is Lookup.Refused -> lookup.why
                is Lookup.Teaches ->
                    if (lookup.word.id in learned) Acquainted.Learned(lookup.word) else Acquainted.AlreadyKnown(lookup.word)
            }
        }
    }

    /**
     * Says what happened. A word that was learned says so with the same toast a found page raises — one
     * channel, one confirmation — so only the three refusals need words of their own.
     */
    fun tell(player: ServerPlayer, outcome: Acquainted) {
        val said = when (outcome) {
            is Acquainted.Learned -> return
            is Acquainted.AlreadyKnown -> Component.translatable("device.agesandtheart.already_known", outcome.word.name)
            Acquainted.HeldBack -> Component.translatable("device.agesandtheart.held_back")
            Acquainted.Unnameable -> Component.translatable("device.agesandtheart.unnameable")
        }
        player.sendSystemMessage(said, true)
    }
}

/** What came of asking a device to name something. */
sealed interface Acquainted {
    data class Learned(val word: Word) : Acquainted

    data class AlreadyKnown(val word: Word) : Acquainted

    /** On the reproduction rung: a word the ladder refuses to grant early (§8.3, [Withheld]). */
    data object HeldBack : Acquainted

    /** Nothing here is a derived referent — an empty bucket, a tool, a word the pack never derived. */
    data object Unnameable : Acquainted
}
