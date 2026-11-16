package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.worldgen.fissure.TheFall
import net.minecraft.server.level.ServerPlayer
import java.util.Collections
import java.util.WeakHashMap

/**
 * A player who logged out in an Age before its end struck and logs back in after (design §5.3 "The dead link"): put
 * out into the overworld as a fall through a star fissure puts one out, rather than made to meet a strike already
 * past. When they left is the game's time at their last save, kept with them.
 */
object AwayForTheEnd {

    /** What the time a player was last saved is kept under in their save. */
    const val SAVE_KEY = "agesandtheart:saved_at"

    /** Where nothing was kept, as in a save from before this. */
    const val NOT_KEPT = -1L

    private val leftAt: MutableMap<ServerPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    /** The time to keep in [player]'s save. */
    @JvmStatic
    fun savingAt(player: ServerPlayer): Long = player.level().server.overworld().gameTime

    /** What [player]'s save said, read as they are loaded and before they join. */
    @JvmStatic
    fun loaded(player: ServerPlayer, savedAt: Long) {
        if (savedAt != NOT_KEPT) leftAt[player] = savedAt
    }

    /** As [player] joins: out of an Age whose end struck while they were away. */
    fun joined(player: ServerPlayer) {
        val savedAt = leftAt.remove(player) ?: return
        val level = player.level()
        val recipe = Ages.recipeOf(level) ?: return
        val doom = Cataclysms.of(level.server, recipe) ?: return
        val now = level.server.overworld().gameTime
        val struckWhileAway = savedAt < doom.impactAt && doom.hasStruckBy(now)
        if (struckWhileAway) TheFall.putOut(player)
    }
}
