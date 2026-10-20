package co.voik.agesandtheart.page

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.age.word.learnedWords
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition

/**
 * Passes while the player opening a container still lacks a word that could be found in the [rarity]
 * buckets named — so an entry can offer the critical words until they are all learned and then step aside.
 *
 * ```json
 * { "condition": "agesandtheart:finder_lacks", "rarity": "critical" }
 * ```
 *
 * A container opened with no player, by a hopper, has nobody to lack anything, and fails.
 */
class FinderLacksCondition(val rarity: Set<String>) : LootItemCondition {

    override fun test(context: LootContext): Boolean {
        val finder = context.getOptional(LootContextParams.THIS_ENTITY) as? ServerPlayer ?: return false
        val vocabulary = Vocabulary.of(context.level.server)
        val registries = context.level.registryAccess()
        val buckets = vocabulary.rarity.buckets.filter { it.name in rarity }
        fun isFindableHere(word: Word): Boolean =
            vocabulary.rarity.bucketOf(word) in buckets && PageWordFunction.canBeFound(word, vocabulary, registries)
        return vocabulary.words.any { word -> isFindableHere(word) && !finder.learnedWords.knows(word.id) }
    }

    override fun codec(): MapCodec<out LootItemCondition> = MAP_CODEC

    companion object {
        val MAP_CODEC: MapCodec<FinderLacksCondition> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                WordRarity.BUCKET_NAMES_CODEC.fieldOf("rarity").forGetter(FinderLacksCondition::rarity),
            ).apply(instance, ::FinderLacksCondition)
        }
    }
}
