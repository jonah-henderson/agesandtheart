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
 * What studying an empty grammar guide takes (`art/grammar_study.json`): the points that fill one, what a page is
 * worth by its word's rarity bucket, and what anything else that may be studied is worth.
 */
data class GrammarStudy(val full: Int, val pages: Map<String, Int>, val items: Map<Item, Int>) {

    /** What one of [stack] is worth to a guide, or nothing where it cannot be studied. */
    fun worthOf(stack: ItemStack, vocabulary: Vocabulary): Int {
        items[stack.item]?.let { return it }
        if (!NotebookItem.isPage(stack)) return NOTHING
        val word = stack.get(AgeComponents.PAGE_WORD)?.let(vocabulary::word) ?: return NOTHING
        val bucket = vocabulary.bucketOf(word)?.name ?: COMMON
        return pages[bucket] ?: pages[COMMON] ?: NOTHING
    }

    companion object {
        private const val FILE = "art/grammar_study.json"
        private const val COMMON = "common"
        private const val NOTHING = 0
        private const val DEFAULT_FULL = 200

        /** What a server with no file, or a broken one, studies by: every page worth one. */
        private val ORDINARY = GrammarStudy(DEFAULT_FULL, mapOf(COMMON to 1), emptyMap())

        val CODEC: Codec<GrammarStudy> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("full").forGetter(GrammarStudy::full),
                Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("pages").forGetter(GrammarStudy::pages),
                Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT).optionalFieldOf("items", emptyMap())
                    .forGetter(GrammarStudy::items),
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
