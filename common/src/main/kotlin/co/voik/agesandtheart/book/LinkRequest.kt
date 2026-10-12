package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.advancement.Link
import co.voik.agesandtheart.advancement.LinkedInto
import co.voik.agesandtheart.advancement.LinkedWith
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelAppearance
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level

/**
 * "Take me there" — the click on a book's linking panel.
 *
 * Carries only which hand held the book, because everything else is on the server already and a payload
 * naming an Age would be a request to go anywhere at all.
 */
data class LinkRequest(val hand: InteractionHand) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LinkRequest> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LinkRequest> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "link"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LinkRequest> =
            ByteBufCodecs.idMapper({ LinkRequest(InteractionHand.entries[it]) }, { it.hand.ordinal })
    }
}

/**
 * Linking: the act the whole mod is built around.
 *
 * **The book does not travel.** From a hand it is spent to the spot you were standing on, which is what
 * makes going somewhere a decision rather than a door — link away with no way back and you are stranded
 * until you find a star fissure (design §7.8), and that is intended. From a lectern it stays on the
 * lectern, which is what a lectern is for (§7.8.2).
 */
object Linking {

    fun handle(player: ServerPlayer, request: LinkRequest) {
        val stack = player.getItemInHand(request.hand)
        if (!LecternBooks.isOurs(stack)) return LinksUnderway.endedWithoutGoing(player)
        LinksUnderway.begin(player, LinksUnderway.Departure.FromHand(request.hand, stack.copy()))
    }

    /**
     * Where [stack] would take [player] from [level], worked out without anybody moving — or null, with the
     * reason said, where it will not. What [go] does too, so a link that waited is refused for exactly what
     * one that went at once would be.
     */
    fun journeyFor(player: ServerPlayer, level: ServerLevel, stack: ItemStack): Journey? = when {
        stack.item === AgeContent.DESCRIPTIVE_BOOK -> journeyToTheAge(player, level, stack)
        stack.item === AgeContent.LINKING_BOOK -> journeyToThePlace(player, level, stack)
        else -> null
    }

    /** A link worked out: the world it arrives in, the chunk it arrives at, and the going. */
    class Journey(
        val into: ServerLevel,
        val arrivingAt: ChunkPos,
        private val called: String,
        private val travel: (ServerPlayer) -> Unit,
    ) {
        fun take(player: ServerPlayer) {
            travel(player)
            player.sendSystemMessage(Component.translatable("book.agesandtheart.linked", called), true)
        }
    }

    /** Goes through the book in [hand] and spends it to the spot [player] stood on, answering whether they went. */
    fun link(player: ServerPlayer, level: ServerLevel, stack: ItemStack, hand: InteractionHand): Boolean {
        // Taken before going, which moves the player — otherwise the book would come to rest in the world
        // they went to. The book itself is copied after, so it carries whatever going stamped onto it.
        val leftAt = player.position().add(0.0, player.eyeHeight - HAND_BELOW_THE_EYES, 0.0)
        val facing = player.yRot
        if (!go(player, level, stack)) return false
        val left = stack.copy()
        player.setItemInHand(hand, ItemStack.EMPTY)
        BookEntity.leaveBehind(level, leftAt, facing, left)
        Constants.LOG.debug("{} linked, book left at {}", player.gameProfile.name, leftAt)
        return true
    }

    /**
     * Takes [player] wherever [stack] leads and leaves the stack where it was, answering whether they went.
     * What becomes of the book afterwards is the caller's: spent from a hand, kept by a lectern.
     */
    fun go(player: ServerPlayer, level: ServerLevel, stack: ItemStack): Boolean {
        // Asked before going: arriving marks the Age visited, and it does so inside the teleport.
        val firstVisit = destinationOf(stack)?.let { !AgeSavedData.get(level.server).hasBeenVisited(it) } ?: true
        val journey = journeyFor(player, level, stack) ?: return false
        journey.take(player)
        AgeTriggers.LINKED.trigger(player, linkAsLanded(player, stack, firstVisit))
        return true
    }

    /** The world [stack] leads to, or null for a descriptive book whose Age has not been made yet. */
    private fun destinationOf(stack: ItemStack): Identifier? =
        stack.get(AgeComponents.AGE_ID) ?: stack.get(AgeComponents.LINK_TARGET)?.dimension?.identifier()

    /** The link [player] has just made through [stack], read where they landed. */
    private fun linkAsLanded(player: ServerPlayer, stack: ItemStack, firstVisit: Boolean): Link {
        val arrivedIn = player.level()
        val into = when {
            Ages.recipeOf(arrivedIn) != null -> LinkedInto.AN_AGE
            arrivedIn.dimension() == Level.OVERWORLD -> LinkedInto.THE_OVERWORLD
            else -> LinkedInto.ELSEWHERE
        }
        val with = if (stack.item === AgeContent.LINKING_BOOK) LinkedWith.LINKING_BOOK else LinkedWith.DESCRIPTIVE_BOOK
        val carriesALinkingBook = player.inventory.nonEquipmentItems.any { it.item === AgeContent.LINKING_BOOK }
        return Link(into, with, carriesALinkingBook, firstVisit = into == LinkedInto.AN_AGE && firstVisit)
    }

    private fun journeyToTheAge(player: ServerPlayer, level: ServerLevel, stack: ItemStack): Journey? {
        // **A book is a door to somewhere else.** Carry one into the Age it describes — through a linking
        // book, say — and using it would spend the book on the room you are already standing in, which is
        // the same claim `journeyToThePlace` refuses and for the same reason.
        if (stack.get(AgeComponents.AGE_ID) == level.dimension().identifier()) return refuse(player, "same_world")
        // `BookAge` and not a second copy of this: the linking panel opens the same Age from the same
        // stack, and a book that previewed one world and sent you to another would be the worst fault
        // this could have.
        val isStillBeingRenamed = stack.get(AgeComponents.AGE_ID)?.let(AgeSavedData.get(level.server)::isReserved) == true
        if (isStillBeingRenamed) return refuse(player, "still_settling")
        val age = BookAge.of(level.server, stack) ?: return refuse(player, "failed")
        val called = stack.get(AgeComponents.BOOK_TITLE) ?: age.dimension().identifier().path
        return Journey(age, ChunkPos.containing(Ages.arrivalIn(age)), called) { Ages.teleport(it, age) }
    }

    private fun journeyToThePlace(player: ServerPlayer, level: ServerLevel, stack: ItemStack): Journey? {
        val target = stack.get(AgeComponents.LINK_TARGET) ?: return refuse(player, "no_destination")
        // Linking is travel *between* worlds. A book that moves you within one is not a linking book —
        // which is both the lore and, incidentally, what stops this being an overland taxi.
        if (target.dimension == level.dimension()) return refuse(player, "same_world")
        // A missing destination is an Age that was collected while this book survived, so the book puts
        // it back — same terrain, deterministically, and empty of whatever was built in it. Only a
        // vanilla dimension can still be genuinely unreachable, and none of those is ours to restore.
        val destination = destinationOf(target, level.server) ?: return refuse(player, "no_destination")
        return Journey(destination, ChunkPos.containing(BlockPos.containing(target.position)), target.name) { traveller ->
            // Before the move, not after: one stream carries both, so a sky sent first cannot arrive late.
            LevelAppearance.expecting(traveller, destination.dimension())
            traveller.teleportTo(
                destination,
                target.position.x,
                target.position.y,
                target.position.z,
                emptySet(),
                target.yaw,
                traveller.xRot,
                true,
            )
        }
    }

    /**
     * The world [target] is in, put back first if it is an Age that was collected while the book survived.
     * Shared with the panel, so what a book shows and where it sends you are one place.
     */
    fun destinationOf(target: LinkTarget, server: MinecraftServer): ServerLevel? =
        server.getLevel(target.dimension) ?: restore(target, server)

    /** Puts back an Age a linking book outlived, or null where there is nothing to put back. */
    private fun restore(target: LinkTarget, server: MinecraftServer): ServerLevel? {
        val recipe = target.recipe ?: return null
        Constants.LOG.info("Restoring '{}' from a linking book that outlived it", target.dimension.identifier())
        return Ages.ensure(server, target.dimension.identifier(), recipe)
    }

    /** Says why [player] is not going, and ends any wait a client is showing for it. */
    fun refuse(player: ServerPlayer, reason: String): Nothing? {
        player.sendSystemMessage(Component.translatable("book.agesandtheart.$reason"), true)
        LinksUnderway.endedWithoutGoing(player)
        return null
    }

    /** Where a dropped book leaves the hand, as vanilla drops an item: this far below the eyes. */
    private const val HAND_BELOW_THE_EYES = 0.3
}
