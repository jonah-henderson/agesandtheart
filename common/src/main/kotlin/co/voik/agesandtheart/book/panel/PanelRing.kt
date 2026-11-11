package co.voik.agesandtheart.book.panel

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

/**
 * The square of chunks a panel is made of — the geometry both sides have to agree on.
 *
 * Apart from the messages that carry it because it is not one: the server sizes its ticket from these, the
 * client sizes its chunk cache from them, and the fog ends at one of them.
 */
object PanelRing {

    /** How far a panel shows, and so where the fog ends. */
    const val SHOWN_RADIUS_CHUNKS = 3

    /**
     * How far the ring is streamed, one chunk further than [SHOWN_RADIUS_CHUNKS].
     *
     * `LevelRenderer.compileSections` will not compile a section it has never compiled unless
     * `hasAllNeighbors()`, so the outermost streamed ring can never draw. The spare ring exists only to let
     * the ring inside it mesh.
     */
    const val RADIUS_CHUNKS = SHOWN_RADIUS_CHUNKS + 1

    /**
     * How far chunks are held, one wider again.
     *
     * The server's ticket has to cover generating the streamed ring's own edge, and `ClientChunkCache`
     * refuses a chunk outside its radius.
     */
    const val HELD_RADIUS_CHUNKS = RADIUS_CHUNKS + 1

    val SIDE: Int get() = RADIUS_CHUNKS * 2 + 1

    val COUNT: Int get() = SIDE * SIDE

    /** The side of the shown ring, which is all a map needs: it meshes nothing, so it has no spare edge. */
    val SHOWN_SIDE: Int get() = SHOWN_RADIUS_CHUNKS * 2 + 1

    val SHOWN_COUNT: Int get() = SHOWN_SIDE * SHOWN_SIDE

    fun centreOf(arrival: BlockPos): ChunkPos = ChunkPos.containing(arrival)

    /** The shown ring around [centre], nearest first, as [around] orders the streamed one. */
    fun shownAround(centre: ChunkPos): List<ChunkPos> =
        ChunkPos.rangeClosed(centre, SHOWN_RADIUS_CHUNKS).toList().sortedBy { centre.getChessboardDistance(it) }

    /**
     * Every chunk of the ring around [centre], nearest first, so the picture assembles outwards from the
     * arrival rather than in a raster from one corner (design §7.8.1).
     *
     * Chessboard distance because the ring is a square and that is the order a square grows in.
     */
    fun around(centre: ChunkPos): List<ChunkPos> =
        ChunkPos.rangeClosed(centre, RADIUS_CHUNKS).toList().sortedBy { centre.getChessboardDistance(it) }
}
