package co.voik.agesandtheart.worldgen

import io.kotest.core.spec.style.FunSpec

/**
 * **A world shut overhead is shut** — the categorical half of [CeilingField], which is the half that can
 * fail without anybody noticing.
 *
 * A template's roof is part of its rock, so a book that names a landform used to take the roof away with
 * it and leave `sealed=always` saying only what the dimension type says. Nothing offline could tell:
 * `has_ceiling` is a game rule and builds nothing, and the walk found it by flying up.
 *
 * **Deliberately not `NEEDS_LANDFORMS`.** That tag is earned by asserting the *emergent shape* of layered
 * noise, which drifts whenever a landform is tuned. These are yes-or-no questions about a lid — is it
 * there, is it one piece, does it leave room to stand under — and none of them moves when the relief or
 * the octaves do. A few hundred columns, closed form apart from one noise sample each.
 */
class CeilingCheck : FunSpec({
    val window = VerticalWindow.DEFAULT
    val lid = CeilingField.over(window, salt = 4242L)

    /** A spread wide enough to cross several swells of the vault, at a stride that is not the noise's. */
    val sampled = (-12..12).flatMap { alongX -> (-12..12).map { alongZ -> alongX * 137 to alongZ * 149 } }

    test("every column of a lid reaches the top of the world") {
        val open = sampled.filter { (x, z) -> lid.columnSpans(x, z).highestSolidY != window.topY }
        check(open.isEmpty()) {
            "${open.size} of ${sampled.size} columns do not reach ${window.topY}, so the sky is not shut: " +
                open.take(3).joinToString { (x, z) -> "($x, $z) tops at ${lid.columnSpans(x, z).highestSolidY}" }
        }
    }

    /**
     * **And it leaves somewhere to stand.** A lid that reached the ground would be a solid world, and one
     * that hung just over it would be a crawlspace. The bound is loose on purpose — where exactly the roof
     * sits is tuning, and that it stays clear of the landforms is not.
     *
     * Measured against the **lowest** rock in the column rather than the vault's own underside, so a
     * pendant that came adrift is caught by the same reading. Coming adrift is allowed: a mass of rock
     * hanging over you unattached is a thing the nether does, and a ceiling with no loose pieces in it
     * reads as a moulding.
     */
    test("nothing in a lid comes down into the ground our landforms build on") {
        val lowest = sampled.map { (x, z) -> lid.columnSpans(x, z).ranges.first().first }
        check(lowest.min() > HIGHEST_LANDFORM_CROWN) {
            "the roof comes down to ${lowest.min()}, which is into the ground our landforms build"
        }
        check(lowest.max() < window.topY) {
            "the roof has no underside at all — it is solid from ${lowest.max()} to the top"
        }
    }
})

/**
 * About as high as a landform of ours crowns before it is asked to be a mountain — the pyramids, the hills
 * and the cliffs all sit well under this. A shape that goes higher meets the roof and stands as a column,
 * which is wanted; a roof that comes down to *here* would be burying the ordinary ones.
 */
private const val HIGHEST_LANDFORM_CROWN = 110
