package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.PlasmaField
import co.voik.agesandtheart.content.PlasmaSea
import co.voik.agesandtheart.location
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Our post effects over the whole picture: the heat haze, over a plasma sea ([PlasmaHaze]) or under an asteroid's
 * burning sky ([Impacts.hazeLevelFor]), and an asteroid's flash ([Impacts.flashLevelFor]).
 *
 * Each is one shader at fixed strengths (`post_effect/heat_haze_<n>`, `post_effect/flash_<n>`), since a post pass's
 * uniforms are fixed in its file. They ride the player's own post effects, which the renderer applies every frame,
 * and whatever else the server put there is left alone.
 */
object ScreenEffects {

    /** One a block from the plasma haze's first reach in to the field's edge, and the field itself. */
    const val HAZE_LEVELS = PlasmaField.SCORCHING_REACH - PlasmaSea.FIELD_REACH + 1

    const val FLASH_LEVELS = 16

    private val HAZES: List<Identifier> = (1..HAZE_LEVELS).map { level -> "heat_haze_$level".location() }
    private val FLASHES: List<Identifier> = (1..FLASH_LEVELS).map { level -> "flash_$level".location() }
    private val OURS: Set<Identifier> = (HAZES + FLASHES).toSet()

    /** Called from `ClientSetup.clientTick`. */
    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val haze = listOfNotNull(PlasmaHaze.levelUnder(player), Impacts.hazeLevelFor(player)).maxOrNull()
        val flash = Impacts.flashLevelFor(player)
        val ours = listOfNotNull(haze?.let { HAZES[it - 1] }, flash?.let { FLASHES[it - 1] })
        val wanted = player.activePostEffects.filterNot { it in OURS } + ours
        if (wanted != player.activePostEffects) player.setActivePostEffects(wanted)
    }
}
