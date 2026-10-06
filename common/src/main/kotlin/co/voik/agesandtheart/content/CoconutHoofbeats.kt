package co.voik.agesandtheart.content

import co.voik.agesandtheart.AgeConfig
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState

/** A player walking with a coconut half in each hand sounds like a horse. */
object CoconutHoofbeats {

    fun isClipClopping(player: Player): Boolean {
        fun holdsAHalfIn(hand: InteractionHand) = player.getItemInHand(hand).`is`(PalmWood.COCONUT_HALF)
        val holdsAHalfInEachHand = holdsAHalfIn(InteractionHand.MAIN_HAND) && holdsAHalfIn(InteractionHand.OFF_HAND)
        return holdsAHalfInEachHand && !player.isInWater && AgeConfig.coconutHoofbeats.get()
    }

    /** As a horse's own step sounds: a gallop when sprinting, the hollow knock on wood, the plain step elsewhere. */
    fun clipClop(player: Player, ground: BlockState) {
        val soundType = ground.soundType
        val sound = when {
            player.isSprinting -> SoundEvents.HORSE_GALLOP
            soundType in WOODEN -> SoundEvents.HORSE_STEP_WOOD
            else -> SoundEvents.HORSE_STEP
        }
        player.playSound(sound, soundType.volume * STEP_VOLUME, soundType.pitch)
    }

    private val WOODEN = setOf(SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.STEM, SoundType.CHERRY_WOOD, SoundType.BAMBOO_WOOD)

    private const val STEP_VOLUME = 0.15f
}
