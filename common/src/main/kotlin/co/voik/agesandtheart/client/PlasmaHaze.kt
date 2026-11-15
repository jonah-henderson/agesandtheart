package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.Plasma
import co.voik.agesandtheart.content.PlasmaField
import co.voik.agesandtheart.content.PlasmaSea
import net.minecraft.client.player.LocalPlayer

/**
 * The air shimmering over a plasma sea (design §7.1.2): from [PlasmaField.SCORCHING_REACH] blocks above it
 * the haze begins, thickening a step a block, and within [PlasmaSea.FIELD_REACH] it is at its worst.
 * [ScreenEffects] draws it.
 */
object PlasmaHaze {

    /** How hot the air is where [player] stands, from one to [ScreenEffects.HAZE_LEVELS]; null with no sea in reach. */
    fun levelUnder(player: LocalPlayer): Int? {
        val feet = player.blockPosition()
        val height = (1..PlasmaField.SCORCHING_REACH).firstOrNull { below ->
            player.level().getBlockState(feet.below(below)).`is`(Plasma.SEA)
        } ?: return null
        return if (height <= PlasmaSea.FIELD_REACH) ScreenEffects.HAZE_LEVELS else PlasmaField.SCORCHING_REACH + 1 - height
    }
}
