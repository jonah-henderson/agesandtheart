package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.client.BookStandOpener
import co.voik.agesandtheart.client.LinkTransitOpener
import co.voik.agesandtheart.page.PageLearning
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Where a book lying open on a stand of ours lies, as a lectern holds one: read from the [facing] side, its middle at
 * [middle]. A lectern's own book has its middle [LecternBookPlane.CENTRE_HEIGHT] above the lectern's corner.
 */
data class OpenBookPose(val facing: Direction, val middle: Vec3) {
    /** Where a lectern's corner would be for its book to lie so. */
    val corner: Vec3 get() = middle.subtract(HALF, LecternBookPlane.CENTRE_HEIGHT, HALF)

    private companion object {
        const val HALF = 0.5
    }
}

/** What a reader asks of a book lying open on a stand, from its screen. */
enum class StandAsked { TAKE, CLOSE }

/**
 * A block entity of ours that keeps books as high-capacity lecterns do (Jonah, 2026-11-19): the book wheels, the
 * book lift and the book finder. Each book is in a numbered place of its own; one of ours is shut until somebody
 * opens it, and lying open it is read and linked from as a lectern's is, by everybody near, until it is shut, taken with
 * its screen's Take Book, or left unwatched for [LecternBooks.TICKS_BETWEEN_LOOKS_ROUND].
 */
interface BookStand {
    /** The book lying open at [place], or null where none does. */
    fun openBookAt(place: Int): ItemStack?

    /** Every place with a book lying open. */
    fun openPlaces(): Collection<Int>

    /**
     * The open books that may take up the panel of the nearest open book (Jonah, 2026-11-19): every one where a player
     * could be reading it, and none where they could not — a wheel's books away from its front.
     */
    fun panelCandidates(): Collection<Int> = openPlaces()

    /** Where the book at [place] lies open while the stand is still. */
    fun openBookPose(place: Int): OpenBookPose

    /** [asked] of the book open at [place] by [player], who is in reach of it. */
    fun answer(player: ServerPlayer, place: Int, asked: StandAsked)
}

/** What every stand does alike: a click on a book, a reader's question, and whose panel may be shown. */
object BookStands {

    /** A book that is not ours but has pages to turn: a written book, or one still being written. */
    fun isReadable(book: ItemStack): Boolean = book.`is`(Items.WRITTEN_BOOK) || book.`is`(Items.WRITABLE_BOOK)

    /**
     * [player] using [book] at [place] on the [stand] at [pos], as a lectern is used: a book of ours shut is opened, and
     * open, links from its panel, opens its screen anywhere else, and with sneaking and an empty hand is shut. A written
     * book opens its pages, and any other book is taken, there being nothing in it to read.
     */
    fun use(
        level: Level,
        stand: BlockEntity,
        place: Int,
        book: ItemStack,
        player: Player,
        hit: BlockHitResult,
        open: (ServerLevel) -> Unit,
        close: () -> Unit,
        take: () -> Unit,
    ): InteractionResult {
        stand as BookStand
        val pos = stand.blockPos
        val panelPage = LecternBooks.panelPageOf(book)
        val isOpen = stand.openBookAt(place) != null
        when {
            panelPage == null && isReadable(book) -> if (level.isClientSide) BookStandOpener.readWritten(book, pos, place)
            panelPage == null -> if (!level.isClientSide) take()
            player.isSecondaryUseActive && player.mainHandItem.isEmpty -> if (isOpen && !level.isClientSide) close()
            !isOpen -> if (level is ServerLevel) {
                // Written where it is first opened, as on a lectern, since a stand never runs an inventory tick.
                DescriptiveBookItem.writeIfBlank(book, level)
                LinkingBookItem.clearStrayWriting(book)
                open(level)
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0f, 1.0f)
            }
            else -> {
                val pose = stand.openBookPose(place)
                val spot = LecternBookPlane.spotLookedAt(pose.facing, pose.corner, BookBinding.of(book), player.eyePosition, hit.location)
                val onThePanel = spot != null && spot.page == panelPage && spot.withinThePanel
                val here = BookBeingRead.OnAStand(pos, place)
                if (onThePanel) {
                    // Goes, and leaves the book lying open for the next reader, as a lectern does.
                    if (level.isClientSide) LinkTransitOpener.begin(here)
                    if (level is ServerLevel && player is ServerPlayer) {
                        if (Linking.go(player, level, book)) stand.setChanged() else Linking.endedWithoutGoing(player)
                    }
                } else {
                    if (level.isClientSide) BookStandOpener.readOurs(book, here)
                    if (player is ServerPlayer) PageLearning.study(player, book)
                }
            }
        }
        return InteractionResult.SUCCESS
    }

    /** The book lying open at [place] of the stand at [pos], where [viewer] may see its panel, or null. */
    fun openBookSeenBy(viewer: Player, pos: BlockPos, place: Int): ItemStack? {
        val stand = standInReach(viewer, pos) ?: return null
        return stand.openBookAt(place)?.takeIf(LecternBooks::isOurs)
    }

    /** Whether anybody is near enough to the stand at [pos] to be reading it, which keeps its books open. */
    fun anybodyNear(level: ServerLevel, pos: BlockPos): Boolean {
        val centre = Vec3.atCenterOf(pos)
        return level.players().any { !it.isSpectator && it.position().closerThan(centre, LecternBooks.REACH_BLOCKS + REACH_PAST_THE_BLOCK) }
    }

    /** Whether it is time a stand with books open looks round for somebody reading them. */
    fun isTimeToLookRound(level: Level): Boolean = level.gameTime % LecternBooks.TICKS_BETWEEN_LOOKS_ROUND == 0L

    fun handleAsked(player: ServerPlayer, payload: BookStandAskPayload) {
        val stand = standInReach(player, payload.stand) ?: return
        if (stand.openBookAt(payload.place) == null && payload.asked == StandAsked.CLOSE) return
        if (payload.asked == StandAsked.TAKE && !player.mayBuild()) return
        stand.answer(player, payload.place, payload.asked)
    }

    private fun standInReach(viewer: Player, pos: BlockPos): BookStand? {
        val level = viewer.level()
        if (!level.isLoaded(pos) || !viewer.position().closerThan(Vec3.atCenterOf(pos), LecternBooks.PANEL_HELD_WITHIN_BLOCKS + REACH_PAST_THE_BLOCK)) return null
        return level.getBlockEntity(pos) as? BookStand
    }

    /** A stand's books reach a little way past the block holding them: a book lift's up its chain. */
    private const val REACH_PAST_THE_BLOCK = 24.0
}

/** What a reader asks of the book lying open at [place] on the stand at [stand], from its screen. */
data class BookStandAskPayload(val stand: BlockPos, val place: Int, val asked: StandAsked) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookStandAskPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookStandAskPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "book_stand_ask"))

        private val ASKED: StreamCodec<ByteBuf, StandAsked> = ByteBufCodecs.VAR_INT.map({ StandAsked.entries[it] }, StandAsked::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, BookStandAskPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BookStandAskPayload::stand,
            ByteBufCodecs.VAR_INT, BookStandAskPayload::place,
            ASKED, BookStandAskPayload::asked,
            ::BookStandAskPayload,
        )

        fun handle(player: ServerPlayer, payload: BookStandAskPayload) = BookStands.handleAsked(player, payload)
    }
}
