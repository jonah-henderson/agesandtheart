package co.voik.agesandtheart.age.aspect

/**
 * How hard the Age pulls everything down — one number that players, mobs, items, sand and arrows all fall by
 * (`co.voik.agesandtheart.age.consequence.StrangeGravity` applies it).
 *
 * **`pull`, the name the moon's tide already has** (`SkyBodies.PULL`), so `strong` and `weak` reach both and
 * the clause decides: `strong moon` is a high tide and `strong gravity` a heavy world.
 */
object Gravity {

    val PULL = Parameter.ranged(
        "pull",
        help = "How hard the world pulls things down.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a third of vanilla's"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(1.0, "twice vanilla's"),
        ),
    ).onlyWhenAimed()

    /**
     * The share of vanilla's gravity a pull of [pull] on the natural axis stands for: twice it at the top, a
     * third at the bottom, the same steps strange gravity climbs.
     */
    fun factorAt(pull: Double): Double = when {
        pull >= 0.0 -> ORDINARY + pull * HEAVIEST_EXTRA
        else -> ORDINARY / (ORDINARY - pull * LIGHTEST_EXTRA)
    }

    private const val ORDINARY = 1.0

    /** How much heavier than vanilla's the top of the axis is. */
    private const val HEAVIEST_EXTRA = 1.0

    /** How much heavier vanilla's is than the bottom of the axis. */
    private const val LIGHTEST_EXTRA = 2.0
}
