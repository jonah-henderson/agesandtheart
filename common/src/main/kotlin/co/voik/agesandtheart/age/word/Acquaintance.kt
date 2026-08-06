package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.platform.Services
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.ItemStack

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
 * Two devices share this because the difference between them is only which registry the referent comes
 * out of: the analysis machine is a station and the sample is brought to it, the surveying device is a
 * tool and you go to the place. See [Withheld] for the one thing neither may name.
 */
object Acquaintance {

    /** What a sample the analyser was fed turned out to be worth. */
    fun withSubstance(player: ServerPlayer, sample: ItemStack): Acquainted {
        val substance = substanceIn(sample) ?: return Acquainted.Unnameable
        return grant(player, substance)
    }

    /** What the ground under [player] is called. A survey consumes nothing — the price was the travel. */
    fun withPlace(player: ServerPlayer): Acquainted {
        val level = player.level() as? ServerLevel ?: return Acquainted.Unnameable
        val here = level.getBiome(player.blockPosition()).unwrapKey().orElse(null) ?: return Acquainted.Unnameable
        return grant(player, here.identifier())
    }

    /**
     * The referent a stack names, or null for something that names nothing.
     *
     * A fluid and its block share an id throughout vanilla, and [DerivedWords.materials] derives one word
     * from the block for both — so a bucket resolves to the same word its source block does, which is why
     * "analyse a bucket or its block form" is one case rather than two.
     */
    private fun substanceIn(sample: ItemStack): Identifier? = when (val item = sample.item) {
        is BlockItem -> BuiltInRegistries.BLOCK.getKey(item.block)
        is BucketItem -> BuiltInRegistries.FLUID.getKey(item.content)
        else -> null
    }

    private fun grant(player: ServerPlayer, referent: Identifier): Acquainted {
        val server = (player.level() as? ServerLevel)?.server ?: return Acquainted.Unnameable
        val vocabulary = Vocabulary.of(server)
        val word = vocabulary.word(referent.toString()) ?: return Acquainted.Unnameable
        if (!vocabulary.isDerived(word)) return Acquainted.Unnameable
        if (Withheld.holdsBack(word, server.registryAccess())) return Acquainted.HeldBack
        if (!player.learnedWords.learn(word.id)) return Acquainted.AlreadyKnown(word)
        Services.NETWORK.sendToPlayer(player, LearnedWordsPayload.added(word.id))
        return Acquainted.Learned(word)
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
