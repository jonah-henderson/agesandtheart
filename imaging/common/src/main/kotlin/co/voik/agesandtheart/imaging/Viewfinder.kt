package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.util.UUID
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

    /**
     * What the photographer's client does as the shutter goes: set by its client entrypoint, since the frame to take is
     * the client's, and left unset on a dedicated server.
     */
    @Volatile
    var shutterOnTheClient: (() -> Unit)? = null

    /**
     * The second use, on the server: a plate spent, the recorded image handed over at once, the shot waiting for the
     * client's picture of it ([Shots]), and the recorder lowered.
     */
    fun shutter(player: ServerPlayer) {
        if (!spendAPlate(player)) {
            stopLooking(player)
            return
        }
        val picture = Picture(UUID.randomUUID(), WordNames.placeName(player.level().dimension()), player.blockPosition())
        val recordedImage = ItemStack(ImagingContent.RECORDED_IMAGE).apply { set(ImagingComponents.PICTURE, picture) }
        player.inventory.placeItemBackInInventory(recordedImage, Prediction.SERVER_ONLY)
        Shots.taken(player, picture.id)
        // Heard by everyone near but the photographer, whose own client played it the moment the shutter went.
        player.level().playSound(player, player.x, player.eyeY, player.z, ImagingContent.SHUTTER, SoundSource.PLAYERS, 1.0f, 1.0f)
        stopLooking(player)
    }

    private fun spendAPlate(player: Player): Boolean {
        if (player.hasInfiniteMaterials()) return true
        val inventory = player.inventory
        val slot = (0..<inventory.containerSize).firstOrNull { inventory.getItem(it).`is`(ImagingContent.PHOTOGRAPHIC_PLATE) }
            ?: return false
        inventory.getItem(slot).shrink(1)
        return true
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
