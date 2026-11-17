package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.Payloads

/** Every payload the companion sends, for each loader to register; a clientbound one's handler is client code. */
object ImagingPayloads {
    val ROUTES: List<Payloads.Route<*>> = listOf(
        Payloads.Serverbound(PicturePiece.TYPE, PicturePiece.STREAM_CODEC) { player, piece -> Shots.pieceArrived(player, piece) },
        Payloads.Serverbound(PictureWanted.TYPE, PictureWanted.STREAM_CODEC) { player, wanted -> PictureFetch.wanted(player, wanted) },
        Payloads.Clientbound(PictureSent.TYPE, PictureSent.STREAM_CODEC),
    )
}
