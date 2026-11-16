package co.voik.agesandtheart.client.panel

import net.minecraft.core.BlockPos

/** What a panel is showing an Age from: a live [PreviewLevel], a [PanelMap] of it, or nothing but its end. */
sealed interface PanelSource {

    /** When it was opened, which the mist clears from ([PanelComposite.composeLive]). */
    val openedAtNanos: Long

    /** How much of it has arrived. */
    val load: RingLoad
}

/** An Age an end has struck, which a panel shows as its end ([PanelPicture.strokesOfAnEnd]): nothing comes to load. */
class EndedAgeView : PanelSource {
    override val openedAtNanos: Long = System.nanoTime()
    override val load: RingLoad = RingLoad(BlockPos.ZERO, expected = NOTHING_TO_LOAD)

    private companion object {
        const val NOTHING_TO_LOAD = 0
    }
}
