package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.phenomena.DelugePayload
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.desk.ArchiveSyncPayload
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.desk.TunerProposalPayload
import co.voik.agesandtheart.worldgen.fissure.FallResumedPayload
import net.minecraft.client.Minecraft
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
        Receiver(DelugePayload.TYPE) { Downpours.remember(it) },
        Receiver(LearnedWordsPayload.TYPE) { KnownWords.remember(it) },
        Receiver(DeskSyncPayload.TYPE) { DeskModel.remember(it) },
        Receiver(DeskNoticePayload.TYPE) { DeskModel.remember(it) },
        Receiver(TunerProposalPayload.TYPE) { FrequencyTunerScreen.remember(it) },
        Receiver(ArchiveSyncPayload.TYPE) { ArchiveScreen.remember(it) },
        // The drop carries on from where it left off: where they are and how fast they were going came
        // back with them, and this is the flag that says the ground is still not holding them.
        Receiver(FallResumedPayload.TYPE) { Minecraft.getInstance().player?.noPhysics = true },
        Receiver(PanelLevelPayload.TYPE) { LinkingPanel.accept(it) },
        Receiver(PanelChunkPayload.TYPE) { LinkingPanel.accept(it) },
    )
}
