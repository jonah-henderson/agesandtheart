package co.voik.agesandtheart.content

import co.voik.agesandtheart.client.SurveyReportScreenOpener
import co.voik.agesandtheart.location
import co.voik.agesandtheart.page.PageLearning
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.locale.Language
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * The three D'ni survey reports (design §7.6): each turns down a candidate Age for missing one of the
 * things a D'ni city needs, and teaches the words for it. They stand alone, read in any order: no surveyed
 * Age holds a word another report teaches.
 *
 * What each says is in `lang/`, one key per page, [pageCount] of them, with the surveyed Age's name as `%1$s`.
 * That name is drawn for each copy found and kept in [AgeComponents.BOOK_TITLE]; one made any other way goes
 * by [ageName]. The Age is written as [sentence] at [ageSeed], and its descriptive book is found with the report.
 */
enum class SurveyReport(
    val path: String,
    val pageCount: Int,
    teaches: List<String>,
    sentence: String,
    val ageSeed: Long,
) {
    SUNLIT_AGE(
        "sunlit_age_survey",
        pageCount = 1,
        teaches = listOf("subterranean"),
        sentence = "age gentle landmass teeming trees features",
        ageSeed = 0x7A1EE4L,
    ),
    LIGHTLESS_AGE(
        "lightless_age_survey",
        pageCount = 1,
        teaches = listOf("algae"),
        sentence = "age gentle landmass sunless sun",
        ageSeed = 0x90AA7L,
    ),
    CRAMPED_AGE(
        "cramped_age_survey",
        pageCount = 1,
        teaches = listOf("large", "chambered"),
        sentence = "age large fissured underground",
        ageSeed = 0x2E54A7L,
    ),
    ;

    /** The surveyed Age's pages, in order. */
    val sentence: List<String> = sentence.split(' ')

    val id: Identifier get() = path.location()

    val teaches: List<Identifier> = teaches.map { it.location() }

    fun pageKey(page: Int): String = "item.${id.namespace}.$path.page.${page + 1}"

    val ageNameKey: String get() = "item.${id.namespace}.$path.age_name"

    /** The name of a report found without one. Both loaders load a mod's `en_us` on a dedicated server too. */
    val ageName: String get() = Language.getInstance().getOrDefault(ageNameKey)

    /** The name of the Age [stack], a copy of this report, surveyed. */
    fun ageNameOf(stack: ItemStack): String = stack.get(AgeComponents.BOOK_TITLE) ?: ageName
}

/** A survey report: reading it opens it, and teaches its words. */
class SurveyReportItem(properties: Properties, val report: SurveyReport) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) SurveyReportScreenOpener.open(report, report.ageNameOf(player.getItemInHand(hand)))
        if (player is ServerPlayer) PageLearning.teach(player, report.teaches)
        return InteractionResult.SUCCESS
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(Component.translatable(AUTHOR_KEY).withStyle(ChatFormatting.GRAY))
        // Which of the set this is, in the order the three argue, so a reader knows how many to look for.
        builder.accept(
            Component.translatable(NUMBER_KEY, report.ordinal + 1, SurveyReport.entries.size).withStyle(ChatFormatting.GRAY),
        )
    }

    private companion object {
        const val AUTHOR_KEY = "item.agesandtheart.survey_report.author"
        const val NUMBER_KEY = "item.agesandtheart.survey_report.number"
    }
}
