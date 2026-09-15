package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.reward.Footing
import co.voik.agesandtheart.desk.ReadingSource
import co.voik.agesandtheart.desk.SeismographMenu
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * What the seismograph says, on its own panel.
 *
 * **A headline and a list, not a sentence** (Jonah, 2026-09-07). The headline is the one line the asset
 * pass replaces — the block gets an animation per [Footing], so a writer who has learned the three shapes
 * reads the state off the instrument across the room and opens this only for the specifics. The specifics
 * are therefore terse and itemised: a reader scanning a list wants nouns, and prose would make the panel
 * something to read rather than something to check.
 */
class SeismographScreen(menu: SeismographMenu, inventory: Inventory, title: Component) :
    ReadoutScreen<SeismographMenu>(
        menu, inventory, title,
        mostListedLines = Manifestation.entries.size,
        translationPrefix = "container.agesandtheart.seismograph",
    ) {

    /**
     * The headline, then one line per thing the instability bought.
     *
     * **The instrument is never idle**, so there is no "nothing to read" state to draw — but there are
     * four different calm things to say and they are not interchangeable (Jonah, 2026-09-09). A sentence
     * that buys nothing has no instability *yet*; a bare desk in the room has nothing to read at all and
     * saying the world is stable there answers a question nobody asked; an ordinary world was never
     * written; and a stable Age was written well, which is a compliment rather than a fact about physics.
     */
    override fun lines(): List<Component> {
        if (menu.source == ReadingSource.AN_IDLE_DESK) return listOf(translated("desk_idle"))
        val state = Footing.entries.getOrNull(menu.footing) ?: Footing.STABLE
        if (state != Footing.STABLE) return listOf(translated("headline_${state.key}")) + bought()
        val calm = when (menu.source) {
            ReadingSource.A_PLAIN_WORLD -> "world_stable"
            ReadingSource.AN_AGE -> "age_stable"
            ReadingSource.A_SENTENCE, ReadingSource.AN_IDLE_DESK -> "headline_stable"
        }
        return listOf(translated(calm))
    }

    private fun bought(): List<Component> =
        Manifestation.entries
            .filter { menu.bought and (1 shl it.ordinal) != 0 }
            .map { translated("bought", translated("manifest_${it.key}")) }
}
