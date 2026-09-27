package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.SurveyReport
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/**
 * Writes the descriptive book of the Age a survey report turned down, so the book and the report are
 * found together (design §7.6).
 *
 * ```json
 * { "function": "agesandtheart:write_surveyed_book", "survey": "sunlit_age_survey" }
 * ```
 */
class WriteSurveyedBookFunction(
    predicate: Optional<Holder<LootItemCondition>>,
    private val survey: SurveyReport,
) : LootItemConditionalFunction(predicate) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        FoundBook.writeSurveyed(itemStack, context.level.server, survey)
        return itemStack
    }

    companion object {
        private val SURVEY_CODEC: Codec<SurveyReport> = Codec.STRING.comapFlatMap(
            { path ->
                SurveyReport.entries.firstOrNull { it.path == path }?.let { DataResult.success(it) }
                    ?: DataResult.error { "no survey report is called '$path'" }
            },
            SurveyReport::path,
        )

        val MAP_CODEC: MapCodec<WriteSurveyedBookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(SURVEY_CODEC.fieldOf("survey").forGetter { it.survey })
                .apply(instance, ::WriteSurveyedBookFunction)
        }
    }
}
