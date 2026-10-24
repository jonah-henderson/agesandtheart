package co.voik.agesandtheart.age.reward

/** A [Danger] scoring exactly what a check names, against lines of its own, so a check reads as a score. */
internal object ScoredDanger {
    val LINES = DangerTable.Lines(paysAt = 1.0, ruinsBelow = 1.0, little = 1.2, some = 1.5, much = 2.0, immense = 2.75)

    private val TABLE = DangerTable.NONE.copy(lines = LINES, combining = 1.0)

    fun of(score: Double, terminal: Double = 0.0): Danger = Danger(
        hazards = listOf(Danger.Hazard(Danger.Source.GROUND, "ground", score)).filter { it.value > 0.0 },
        terminal = terminal,
        table = TABLE,
    )
}
