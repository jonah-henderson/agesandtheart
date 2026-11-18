package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.Payloads

/** Every payload the companion sends, for each loader to register; a clientbound one's handler is client code. */
object ImagingPayloads {
    val ROUTES: List<Payloads.Route<*>> = listOf(
        Payloads.Serverbound(PicturePiece.TYPE, PicturePiece.STREAM_CODEC) { player, piece -> Shots.pieceArrived(player, piece) },
        Payloads.Serverbound(PictureWanted.TYPE, PictureWanted.STREAM_CODEC) { player, wanted -> PictureFetch.wanted(player, wanted) },
        Payloads.Serverbound(TurnTheRecorder.TYPE, TurnTheRecorder.STREAM_CODEC) { player, turn -> ImagingTurns.asked(player, turn) },
        Payloads.Serverbound(AimTheRecorder.TYPE, AimTheRecorder.STREAM_CODEC) { player, aim -> ImagingTurns.aimed(player, aim) },
        Payloads.Serverbound(ZoomTheRecorder.TYPE, ZoomTheRecorder.STREAM_CODEC) { player, zoom -> ImagingTurns.zoomed(player, zoom) },
        Payloads.Serverbound(PhotographPiece.TYPE, PhotographPiece.STREAM_CODEC) { player, piece -> AutomaticShots.pieceArrived(player, piece) },
        Payloads.Clientbound(PictureSent.TYPE, PictureSent.STREAM_CODEC),
        Payloads.Clientbound(PhotographWanted.TYPE, PhotographWanted.STREAM_CODEC),
        Payloads.Clientbound(PhotographChunk.TYPE, PhotographChunk.STREAM_CODEC),
        Payloads.Clientbound(PhotographEntities.TYPE, PhotographEntities.STREAM_CODEC),
    )
}
