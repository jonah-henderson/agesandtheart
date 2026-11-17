package co.voik.agesandtheart.imaging

import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player

/**
 * Looking through an image recorder, on both sides.
 *
 * Whether a player is looking is whether they carry the viewfinder's slowing: the modifier is the state, so the server
 * owns it and vanilla's attribute sync tells the client, which also sets it itself as the recorder is used. Both sides
 * apply the same rules each tick, so a client that guessed wrong is put right.
 */
object Viewfinder {

    private val SLOWED_ID = Imaging.id("viewfinder")

    /** As drawing a bow slows a walker, to a fifth. */
    private const val SLOWED_AS_A_BOW_DRAWS = -0.8

    private val SLOWED = AttributeModifier(SLOWED_ID, SLOWED_AS_A_BOW_DRAWS, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

    fun isLooking(player: Player): Boolean = player.getAttribute(Attributes.MOVEMENT_SPEED)?.hasModifier(SLOWED_ID) == true

    /** A recorder takes plates from the inventory as a bow takes arrows, and needs none where nothing is spent. */
    fun hasAPlate(player: Player): Boolean =
        player.hasInfiniteMaterials() || player.inventory.contains { it.`is`(ImagingContent.PHOTOGRAPHIC_PLATE) }

    fun lookThrough(player: Player) {
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        if (!speed.hasModifier(SLOWED_ID)) speed.addTransientModifier(SLOWED)
    }

    fun stopLooking(player: Player) {
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(SLOWED_ID)
    }

    /** The second use. Taking the picture is not built yet, so for now it only lowers the recorder. */
    fun shutter(player: Player) {
        stopLooking(player)
    }

    /** The hand holding an image recorder, the main hand first, or null where neither does. */
    fun handHoldingTheRecorder(player: Player): InteractionHand? = when {
        player.mainHandItem.`is`(ImagingContent.IMAGE_RECORDER) -> InteractionHand.MAIN_HAND
        player.offhandItem.`is`(ImagingContent.IMAGE_RECORDER) -> InteractionHand.OFF_HAND
        else -> null
    }

    /** Sneaking lowers the recorder, as it leaves a boat; so does the recorder leaving the hands, or the last plate. */
    fun tick(player: Player) {
        if (!isLooking(player)) return
        val isHoldingTheRecorder = handHoldingTheRecorder(player) != null
        val mustLower = player.isShiftKeyDown || !isHoldingTheRecorder || !hasAPlate(player) || !player.isAlive
        if (mustLower) stopLooking(player)
    }

    fun serverTick(server: MinecraftServer) {
        server.playerList.players.forEach(::tick)
    }
}
