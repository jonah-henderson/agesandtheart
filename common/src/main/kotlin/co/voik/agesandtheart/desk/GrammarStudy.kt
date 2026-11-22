package co.voik.agesandtheart.desk

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * What studying a grammar guide takes (`art/grammar_study.json`): the points that fill an empty one, what a page is
 * worth by its word's rarity bucket, and what anything else that may be studied is worth; and what a full one asks to
 * study a new word of each tier.
 */
data class GrammarStudy(
    val full: Int,
    val pages: Map<String, Int>,
    val items: Map<Item, Int>,
    val trades: Map<StudyTier, GrammarTrade>,
) {

    /** What one of [stack] is worth to a guide, or nothing where it cannot be studied. */
    fun worthOf(stack: ItemStack, vocabulary: Vocabulary): Int {
        items[stack.item]?.let { return it }
        if (!NotebookItem.isPage(stack)) return NOTHING
        val word = stack.get(AgeComponents.PAGE_WORD)?.let(vocabulary::word) ?: return NOTHING
        val bucket = vocabulary.bucketOf(word)?.name ?: COMMON
        return pages[bucket] ?: pages[COMMON] ?: NOTHING
    }

    /** The tier a page counts as when trading: the one whose buckets hold its word's, else the lowest. */
    fun tierOf(page: ItemStack, vocabulary: Vocabulary): StudyTier {
        val bucket = page.get(AgeComponents.PAGE_WORD)?.let(vocabulary::word)?.let(vocabulary::bucketOf)?.name
        return trades.entries.firstOrNull { bucket in it.value.buckets }?.key ?: StudyTier.COMMON
    }

    companion object {
        private const val FILE = "art/grammar_study.json"
        private const val COMMON = "common"
        private const val NOTHING = 0
        private const val DEFAULT_FULL = 200

        private const val COMMON_PRICE = 20
        private const val UNCOMMON_PRICE = 50
        private const val RARE_PRICE = 30
        private const val COMMON_SECONDS = 120
        private const val UNCOMMON_SECONDS = 300
        private const val RARE_SECONDS = 600

        /** What a server with no file, or a broken one, trades by. */
        private val ORDINARY_TRADES = mapOf(
            StudyTier.COMMON to GrammarTrade(
                setOf(COMMON, "staple", "critical"), COMMON_PRICE, StudyTier.COMMON, COMMON_SECONDS, GrammarTrade.Experience(0, 2),
            ),
            StudyTier.UNCOMMON to GrammarTrade(
                setOf("uncommon"), UNCOMMON_PRICE, StudyTier.COMMON, UNCOMMON_SECONDS, GrammarTrade.Experience(2, 5),
            ),
            StudyTier.RARE to GrammarTrade(
                setOf("rare"), RARE_PRICE, StudyTier.UNCOMMON, RARE_SECONDS, GrammarTrade.Experience(3, 7),
            ),
        )

        /** What a server with no file, or a broken one, studies by: every page worth one. */
        private val ORDINARY = GrammarStudy(DEFAULT_FULL, mapOf(COMMON to 1), emptyMap(), ORDINARY_TRADES)

        val CODEC: Codec<GrammarStudy> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("full").forGetter(GrammarStudy::full),
                Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("pages").forGetter(GrammarStudy::pages),
                Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT).optionalFieldOf("items", emptyMap())
                    .forGetter(GrammarStudy::items),
                Codec.unboundedMap(StudyTier.CODEC, GrammarTrade.CODEC).optionalFieldOf("trades", ORDINARY_TRADES)
                    .forGetter(GrammarStudy::trades),
            ).apply(instance, ::GrammarStudy)
        }

        fun of(server: MinecraftServer): GrammarStudy = current.of(server)

        private val current = PerReload { server ->
            val resource = server.resourceManager.getResource(FILE.location()).orElse(null)
            if (resource == null) {
                ORDINARY
            } else {
                val problems = mutableListOf<String>()
                val read = ResourceParsing.parse(resource, FILE.location(), CODEC, problems)
                for (problem in problems) Constants.LOG.warn("Grammar study: {}", problem)
                read ?: ORDINARY
            }
        }
    }
}
