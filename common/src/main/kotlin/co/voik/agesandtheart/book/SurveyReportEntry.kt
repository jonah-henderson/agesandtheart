package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.AgeName
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.SurveyReport
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.entries.SingleEntryContainerBase
import net.minecraft.world.level.storage.loot.functions.LootItemFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional
import java.util.function.Consumer

/**
 * A survey report and the descriptive book of the Age it turned down, dropped together and calling that Age
 * by one name drawn for this copy (design §7.6). One entry because the two must share the name, and two
 * entries in a loot table share nothing.
 *
 * ```json
 * { "type": "agesandtheart:survey_report", "survey": "sunlit_age_survey" }
 * ```
 */
class SurveyReportEntry private constructor(
    private val survey: SurveyReport,
    weight: Int,
    quality: Int,
    condition: Optional<Holder<LootItemCondition>>,
    modifier: Optional<Holder<LootItemFunction>>,
) : SingleEntryContainerBase(weight, quality, condition, modifier) {

    override fun codec(): MapCodec<SurveyReportEntry> = MAP_CODEC

    override fun createItemStack(output: Consumer<ItemStack>, context: LootContext) {
        val server = context.level.server
        val ageName = AgeName.drawn(Vocabulary.of(server), context.random.nextLong())?.read ?: survey.ageName
        val report = ItemStack(AgeContent.SURVEY_REPORTS.getValue(survey)).apply {
            set(AgeComponents.BOOK_TITLE, ageName)
            set(DataComponents.ITEM_NAME, Component.translatable(NAMED_KEY, ageName))
        }
        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK).also { FoundBook.writeSurveyed(it, server, survey, ageName) }
        output.accept(report)
        output.accept(book)
    }

    companion object {
        private const val NAMED_KEY = "item.agesandtheart.survey_report.named"

        private val SURVEY_CODEC: Codec<SurveyReport> = Codec.STRING.comapFlatMap(
            { path ->
                SurveyReport.entries.firstOrNull { it.path == path }?.let { DataResult.success(it) }
                    ?: DataResult.error { "no survey report is called '$path'" }
            },
            SurveyReport::path,
        )

        val MAP_CODEC: MapCodec<SurveyReportEntry> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(SURVEY_CODEC.fieldOf("survey").forGetter { it.survey })
                .and(uniformFields(instance))
                .apply(instance, ::SurveyReportEntry)
        }
    }
}
