package co.voik.agesandtheart.portal

import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelAppearance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Relative
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3

/**
 * Linking portals in a level: finding them, lighting them from a receptacle, and where one leads.
 *
 * **The book is the whole state.** A lit portal remembers nothing; where it goes is asked of the receptacle on
 * its frame each time something crosses, so taking the book out or breaking the receptacle needs nothing
 * kept in step.
 */
object LinkingPortals {

    enum class Opening { OPENED, NO_FRAME, SAME_WORLD }

    fun isBoundLinkingBook(stack: ItemStack): Boolean =
        stack.item === AgeContent.LINKING_BOOK && stack.get(AgeComponents.LINK_TARGET) != null

    fun footingIn(level: BlockGetter): (BlockPos) -> PortalFooting = { position ->
        val state = level.getBlockState(position)
        when {
            state.`is`(AgeContent.PHASMIUM_BLOCK_BLOCK) -> PortalFooting.FRAME
            state.`is`(AgeContent.LINKING_PORTAL) -> PortalFooting.LIT
            state.isAir -> PortalFooting.OPEN
            else -> PortalFooting.BLOCKED
        }
    }

    /** Lights every unlit portal the receptacle at [receptacle] sits on, given the [book] it now holds. */
    fun open(level: ServerLevel, receptacle: BlockPos, book: ItemStack): Opening {
        val target = book.get(AgeComponents.LINK_TARGET) ?: return Opening.NO_FRAME
        if (target.dimension == level.dimension()) return Opening.SAME_WORLD
        val unlit = portalsUnder(level, receptacle).filter { it.isUnlit }
        unlit.forEach { light(level, it) }
        return if (unlit.isEmpty()) Opening.NO_FRAME else Opening.OPENED
    }

    /** Puts out every portal the receptacle at [receptacle] sat on that no other receptacle still holds open. */
    fun closeAround(level: ServerLevel, receptacle: BlockPos) {
        portalsUnder(level, receptacle)
            .filter { it.isLit && bookHolding(level, it) == null }
            .forEach { darken(level, it) }
    }

    /**
     * The lit portal whose opening holds [position], or null. The block there already says which plane it
     * stands in, so only that one is searched.
     */
    fun portalAt(level: BlockGetter, position: BlockPos, axis: Direction.Axis): LinkingPortalShape? =
        LinkingPortalShape.find(footingIn(level), level.minY, position, axis)?.takeIf { it.isLit }

    /** The book in a receptacle on [portal]'s frame, where there is one. */
    fun bookHolding(level: BlockGetter, portal: LinkingPortalShape): ItemStack? =
        portal.frame.asSequence()
            .flatMap { frameBlock -> Direction.entries.asSequence().map(frameBlock::relative) }
            .mapNotNull { level.getBlockEntity(it) as? LinkingBookReceptacleBlockEntity }
            .map { it.book }
            .firstOrNull(::isBoundLinkingBook)

    /**
     * Where [entity] arrives through the portal at [entry]: the book's own spot, facing the book's way, still
     * moving as it was. Null where there is nothing to go to, which vanilla takes as staying put.
     */
    fun destination(level: ServerLevel, entity: Entity, entry: BlockPos, axis: Direction.Axis): TeleportTransition? {
        val portal = portalAt(level, entry, axis) ?: return null
        val target = bookHolding(level, portal)?.get(AgeComponents.LINK_TARGET) ?: return null
        if (target.dimension == level.dimension()) return null
        val arrival = Linking.destinationOf(target, level.server) ?: return null
        // Told before the move, as a book in the hand does, so the sky cannot arrive after the player.
        if (entity is ServerPlayer) LevelAppearance.expecting(entity, arrival.dimension())
        return TeleportTransition(
            arrival,
            target.position,
            Vec3.ZERO,
            target.yaw,
            KEEP_THE_PITCH,
            Relative.union(Relative.DELTA, setOf(Relative.X_ROT)),
            TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET),
        )
    }

    /**
     * Every portal with a frame block beside [receptacle]. A frame only counts where it is that portal's own:
     * a phasmium block standing *in front of* an opening frames nothing.
     */
    private fun portalsUnder(level: Level, receptacle: BlockPos): List<LinkingPortalShape> {
        val footing = footingIn(level)
        val frameBlocks = Direction.entries
            .map(receptacle::relative)
            .filter { footing(it) == PortalFooting.FRAME }
        fun portalsFramedBy(frameBlock: BlockPos): List<LinkingPortalShape> =
            Direction.entries.flatMap { toward ->
                PORTAL_PLANES.mapNotNull { axis ->
                    LinkingPortalShape.find(footing, level.minY, frameBlock.relative(toward), axis)
                }
            }.filter { it.isFramedBy(frameBlock) }
        return frameBlocks.flatMap(::portalsFramedBy).distinctBy { it.bottomLeft to it.axis }
    }

    private fun light(level: Level, portal: LinkingPortalShape) {
        val lit = AgeContent.LINKING_PORTAL.defaultBlockState().setValue(LinkingPortalBlock.AXIS, portal.axis)
        // Vanilla's own flags for a portal going in: sent to clients, and no neighbour updates, which would
        // otherwise have each new block check a portal that is still half built.
        portal.opening.forEach { level.setBlock(it, lit, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE) }
    }

    private fun darken(level: Level, portal: LinkingPortalShape) {
        portal.opening.forEach { level.removeBlock(it, false) }
    }

    private val PORTAL_PLANES = listOf(Direction.Axis.X, Direction.Axis.Z)

    /** Added to the entity's own pitch, [Relative.X_ROT] being relative. */
    private const val KEEP_THE_PITCH = 0.0f
}
