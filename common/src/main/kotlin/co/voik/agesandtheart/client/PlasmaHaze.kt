package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.Plasma
import co.voik.agesandtheart.content.PlasmaField
import co.voik.agesandtheart.content.PlasmaSea
import co.voik.agesandtheart.location
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.resources.Identifier

/**
 * The air shimmering over a plasma sea (design §7.1.2): from [PlasmaField.SCORCHING_REACH] blocks above it
 * the haze begins, thickening a step a block, and within [PlasmaSea.FIELD_REACH] it is at its worst.
 *
 * One shader at [LEVELS] fixed strengths (`post_effect/heat_haze_<n>`), since a post pass's uniforms are
 * fixed in its file. It rides the player's own post effects, which the renderer applies every frame, and
 * leaves alone whatever else the server put there.
 */
object PlasmaHaze {

    /** One a block from the haze's first reach in to the field's edge, and the field itself. */
    private const val LEVELS = PlasmaField.SCORCHING_REACH - PlasmaSea.FIELD_REACH + 1

    private val HAZES: List<Identifier> = (1..LEVELS).map { level -> "heat_haze_$level".location() }

    /** Called from `ClientSetup.clientTick`. */
    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val theirs = player.activePostEffects.filterNot { it in HAZES }
        val ours = levelUnder(player)?.let { level -> listOf(HAZES[level - 1]) }.orEmpty()
        val wanted = theirs + ours
        if (wanted != player.activePostEffects) player.setActivePostEffects(wanted)
    }

    /** How hot the air is where the player stands, counted down the column to the sea; null with none in reach. */
    private fun levelUnder(player: LocalPlayer): Int? {
        val feet = player.blockPosition()
        val height = (1..PlasmaField.SCORCHING_REACH).firstOrNull { below ->
            player.level().getBlockState(feet.below(below)).`is`(Plasma.SEA)
        } ?: return null
        return if (height <= PlasmaSea.FIELD_REACH) LEVELS else PlasmaField.SCORCHING_REACH + 1 - height
    }
}
