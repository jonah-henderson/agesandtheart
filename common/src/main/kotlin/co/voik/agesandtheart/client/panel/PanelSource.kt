package co.voik.agesandtheart.client.panel

/** What a panel is showing an Age from: a live [PreviewLevel], or a [PanelMap] of it. */
sealed interface PanelSource {

    /** When it was opened, which the mist clears from ([PanelComposite.composeLive]). */
    val openedAtNanos: Long

    /** How much of it has arrived. */
    val load: RingLoad
}
