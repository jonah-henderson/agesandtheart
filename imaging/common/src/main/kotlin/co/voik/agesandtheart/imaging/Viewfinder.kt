package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.util.UUID
import net.minecraft.world.phys.Vec3
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

    /**
     * A recorder takes plates from its own slots, then from the inventory as a bow takes arrows, and needs none where
     * nothing is spent.
     */
    fun hasAPlate(player: Player): Boolean {
        val held = heldSlots(player)
        val inTheInventory = player.inventory.contains(RecorderSlots::isAPlate)
        return player.hasInfiniteMaterials() || held?.hasAPlate == true || inTheInventory
    }

    /** The slots of the recorder in [player]'s hand, or null where they hold none. */
    private fun heldSlots(player: Player): RecorderSlots.Held? =
        handHoldingTheRecorder(player)?.let { RecorderSlots.Held(player.getItemInHand(it)) }

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

    /** What the photographer's client does as the shutter goes with no plate to take the picture on. */
    @Volatile
    var noPlateOnTheClient: (() -> Unit)? = null

    /** The click a recorder gives with no plate to take a picture on, held or set down: a dispenser's with nothing in it. */
    fun clickWithNoPlate(level: Level, x: Double, y: Double, z: Double, source: SoundSource) {
        level.playSound(null, x, y, z, SoundEvents.DISPENSER_FAIL, source, 1.0f, EMPTY_CLICK_PITCH)
    }

    private const val EMPTY_CLICK_PITCH = 1.2f

    /**
     * The second use, on the server: a plate spent, the recorded image handed over at once, the shot waiting for the
     * client's picture of it ([Shots]), and the recorder lowered.
     */
    fun shutter(player: ServerPlayer) {
        if (!spendAPlate(player)) {
            clickWithNoPlate(player.level(), player.x, player.eyeY, player.z, SoundSource.PLAYERS)
            return
        }
        val picture = Picture(UUID.randomUUID(), WordNames.placeName(player.level().dimension()), player.blockPosition())
        val recordedImage = ItemStack(ImagingContent.RECORDED_IMAGE).apply { set(ImagingComponents.PICTURE, picture) }
        handOver(player, recordedImage)
        Shots.taken(player, picture.id)
        // Heard and seen by everyone near but the photographer, whose own client played it the moment the shutter went.
        player.level().playSound(player, player.x, player.eyeY, player.z, ImagingContent.SHUTTER, SoundSource.PLAYERS, 1.0f, 1.0f)
        flashSeenByOthers(player)
        stopLooking(player)
    }

    /**
     * Vanilla's firework flash at the front of the recorder's viewfinder, which the photographer's eye is behind, for
     * everyone near enough to see it but the photographer.
     */
    private fun flashSeenByOthers(player: ServerPlayer) {
        val level = player.level()
        val up = Vec3.directionFromRotation(player.xRot - QUARTER_TURN, player.yRot)
        val lens = player.eyePosition.add(player.lookAngle.scale(FLASH_AHEAD)).add(up.scale(FLASH_ABOVE))
        val flash = ColorParticleOption.create(ParticleTypes.FLASH, FLASH_COLOUR)
        level.players().filter { it !== player && it.distanceToSqr(lens) < FLASH_SEEN_WITHIN * FLASH_SEEN_WITHIN }.forEach { onlooker ->
            level.sendParticles(onlooker, flash, true, true, lens.x, lens.y, lens.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** The viewfinder's front, ahead of and a little above the eye looking through it, as the recorder is held. */
    private const val FLASH_AHEAD = 0.4
    private const val FLASH_ABOVE = 0.1
    private const val QUARTER_TURN = 90.0f
    private const val FLASH_SEEN_WITHIN = 64.0
    private const val FLASH_COLOUR = 0xFFF4E0

    /** The recorded image into the inventory, or the recorder's own output where that is full, or else at their feet. */
    private fun handOver(player: ServerPlayer, recordedImage: ItemStack) {
        player.inventory.add(recordedImage)
        if (recordedImage.isEmpty) return
        if (heldSlots(player)?.keep(recordedImage) == true) return
        player.drop(recordedImage, false, Prediction.SERVER_ONLY)
    }

    private fun spendAPlate(player: Player): Boolean {
        if (player.hasInfiniteMaterials()) return true
        if (heldSlots(player)?.spendAPlate() == true) return true
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
        val mustLower = player.isShiftKeyDown || !isHoldingTheRecorder || !player.isAlive
        if (mustLower) stopLooking(player)
    }

    fun serverTick(server: MinecraftServer) {
        server.playerList.players.forEach(::tick)
    }
}
