package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.block.Blocks

/**
 * That a deposit is laid only where one is earned.
 *
 * **Asserted by identity rather than by looking at the ground**, which is the whole of what can be checked
 * without a server: an Age that earns nothing must get its biome settings back *untouched* — not rebuilt
 * into an equal copy — because `FeatureSorter` indexes the sorted feature list by identity and an
 * equal-but-new object is a lookup miss in the middle of generation.
 *
 * **Only the refusals are reachable here, and the reason is worth knowing.** Building a deposit touches
 * `AgeContent`, whose class initialiser constructs items — and `Bootstrap.bootStrap()` has already frozen
 * the item registry by the time a spec runs, so the intrusive holders those constructors want cannot be
 * made. `DeskStoresCheck`, `WriterProfessionCheck` and `StockedItemsCheck` all live with the same wall.
 * Every path asserted below returns before it reaches that line, which is what makes them askable.
 *
 * Where the veins actually land is `scripts/checks/danger.txt`.
 */
@Tags(NEEDS_REGISTRIES)
class DepositsCheck : FunSpec({

    test("an Age that earns nothing lays no layer, and so is handed its own settings back") {
        val safe = danger(materials = 0.0)
        check(Deposits.layer(safe, ROCK) == null) { "a safe Age was given a deposit" }
        // The identity this protects is now [Decoration]'s to keep: a reward that lays nothing contributes
        // no layer, and an empty list hands the base back rather than rebuilding it — which is what costs
        // every feature in it the identity `FeatureSorter` indexes by.
        check(Decoration.laidOver(BASE, emptyList()) === BASE) {
            "an Age owed nothing had its biome settings rebuilt"
        }
    }

    /**
     * The raid's trigger, asked of the same values [Deposits] reads.
     *
     * The deposit it produces cannot be built here — see the note above — so what is pinned is the decision
     * in front of it: a dangerous Age is not a doomed one, and any collapse at all is.
     */
    test("a dangerous Age is not a doomed one") {
        check(!danger(materials = 1.0).isTerminal) { "an ordinary hellish Age read as doomed" }
        check(danger(materials = 1.0, terminal = A_PART_OF_IT).isTerminal) {
            "an Age part of the way into collapse did not read as doomed"
        }
        check(danger(materials = 1.0, terminal = ALL_OF_IT).isTerminal) {
            "an Age that bought every step of collapse did not read as doomed"
        }
    }

    /** Past large amounts danger still pays more, but the ceiling is a collapse's: danger alone never reaches it. */
    test("danger alone never lays more than the least of a collapse's hoard, though it grows to meet it") {
        val scores = (0..FURTHEST_SCORE * STEPS_A_POINT).map { it.toDouble() / STEPS_A_POINT }
        val held = scores.map { Deposits.heldAt(danger(materials = it)) }
        check(held.max() <= Deposits.leastHoard) { "danger alone laid ${held.max()}, against a least hoard of ${Deposits.leastHoard}" }
        val atLargeAmounts = Deposits.heldAt(danger(materials = ScoredDanger.LINES.immense))
        check(held.last() > atLargeAmounts) { "a far more dangerous Age held no more than large amounts' $atLargeAmounts" }
        check(held.zipWithNext().none { (lower, higher) -> higher < lower }) { "a dearer Age held less than a cheaper one: $held" }
    }

    test("a quiet Age that collapses lays the doomed hoard, and holds no ruins") {
        val quietAndCollapsing = danger(materials = 0.0, terminal = A_PART_OF_IT)
        check(quietAndCollapsing.paysOut && !quietAndCollapsing.allowsRuins) { "a collapsing Age scoring nothing paid nothing" }
        check(Yield.of(quietAndCollapsing) == Yield.MASSIVE) { "a quiet collapsing Age read as ${Yield.of(quietAndCollapsing)}" }
        check(Deposits.hoardVeinsPerChunk(quietAndCollapsing) >= DOOMED_AT_LEAST) {
            "a quiet collapsing Age was offered ${Deposits.hoardVeinsPerChunk(quietAndCollapsing)} veins a chunk"
        }
    }
}) {
    companion object {
        /** What a doomed Age is offered at the least: one vein, multiplied. */
        private const val DOOMED_AT_LEAST = 40

        /** Well past where the ceiling falls. */
        private const val FURTHEST_SCORE = 30
        private const val STEPS_A_POINT = 20

        init {
            MinecraftRegistries.ensureStoodUp()
        }

        /** Never called: every assertion here is about which function comes back, not what it answers. */
        private val BASE: (Holder<Biome>) -> BiomeGenerationSettings =
            { error("the base settings should not be consulted") }

        private val ROCK = listOf(Blocks.STONE.defaultBlockState())

        private const val A_PART_OF_IT = 0.67
        private const val ALL_OF_IT = 1.0

        private fun danger(materials: Double, terminal: Double = 0.0) = ScoredDanger.of(materials, terminal)
    }
}
