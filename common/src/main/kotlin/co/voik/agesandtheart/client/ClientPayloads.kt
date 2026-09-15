package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * What the client does with each payload the server sends — one receiver for every `Payloads.Clientbound`
 * route, which `PayloadsCheck` holds. Each loader's client entrypoint registers these, and every one runs on
 * the client thread.
 */
object ClientPayloads {
    class Receiver<T : CustomPacketPayload>(val type: CustomPacketPayload.Type<T>, val receive: (T) -> Unit)

    val RECEIVERS: List<Receiver<*>> = listOf(
        Receiver(LexiconPayload.TYPE) { KnownWords.remember(it) },
        Receiver(BlizzardPayload.TYPE) { Storms.remember(it) },
        Receiver(LearnedWordsPayload.TYPE) { KnownWords.remember(it) },
        Receiver(DeskSyncPayload.TYPE) { DeskModel.remember(it) },
        Receiver(DeskPricePayload.TYPE) { DeskModel.remember(it) },
        Receiver(DeskNoticePayload.TYPE) { DeskModel.remember(it) },
        Receiver(PanelLevelPayload.TYPE) { LinkingPanel.accept(it) },
        Receiver(PanelChunkPayload.TYPE) { LinkingPanel.accept(it) },
    )
}
