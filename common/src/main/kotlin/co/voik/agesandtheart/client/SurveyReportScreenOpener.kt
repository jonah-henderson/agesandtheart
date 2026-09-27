package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.SurveyReport
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component

/** Opens a survey report on vanilla's book screen, kept apart so the item never names a client class. */
object SurveyReportScreenOpener {
    fun open(report: SurveyReport) {
        val pages = (0..<report.pageCount).map { Component.translatable(report.pageKey(it)) }
        Minecraft.getInstance().setScreenAndShow(BookViewScreen(BookViewScreen.BookAccess(pages)))
    }
}
