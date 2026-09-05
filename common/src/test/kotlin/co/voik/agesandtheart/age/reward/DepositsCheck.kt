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
 * Where the veins actually land is `scripts/checks/danger.txt` for the ordinary case and item F of the
 * visual backlog for the terminal one.
 */
@Tags(NEEDS_REGISTRIES)
class DepositsCheck : FunSpec({

    test("an Age that earns nothing is handed its own settings back") {
        val safe = danger(materials = 0.0, authored = true)
        check(Deposits.laidOver(BASE, safe, ROCK) === BASE) {
            "a safe Age had its biome settings rebuilt, which costs every feature in it its identity"
        }
    }

    test("an Age nobody wrote is handed its own settings back, however dangerous") {
        val found = danger(materials = 1.0, authored = false)
        check(Deposits.laidOver(BASE, found, ROCK) === BASE) { "a found Age was given a deposit" }
    }

    /**
     * The raid's trigger, asked of the same values [Deposits] reads.
     *
     * The deposit it produces cannot be built here — see the note above — so what is pinned is the decision
     * in front of it: a dangerous Age is not a doomed one, and only a full reach of collapse is.
     */
    test("a dangerous Age is not a doomed one") {
        check(!danger(materials = 1.0, authored = true).isTerminal) { "an ordinary hellish Age read as doomed" }
        check(!danger(materials = 1.0, authored = true, terminal = A_PART_OF_IT).isTerminal) {
            "an Age part of the way into collapse read as doomed"
        }
        check(danger(materials = 1.0, authored = true, terminal = ALL_OF_IT).isTerminal) {
            "an Age that bought every step of collapse did not read as doomed"
        }
    }
}) {
    companion object {
        init {
            MinecraftRegistries.ensureStoodUp()
        }

        /** Never called: every assertion here is about which function comes back, not what it answers. */
        private val BASE: (Holder<Biome>) -> BiomeGenerationSettings =
            { error("the base settings should not be consulted") }

        private val ROCK = listOf(Blocks.STONE.defaultBlockState())

        private const val A_PART_OF_IT = 0.67
        private const val ALL_OF_IT = 1.0

        private val WEIGHTS = DangerTable.Weights(materials = 1.0, spawns = 0.0, phenomena = 0.0, lighting = 0.0)

        private fun danger(materials: Double, authored: Boolean, terminal: Double = 0.0) = Danger(
            materials = materials,
            spawns = 0.0,
            phenomena = 0.0,
            lighting = 0.0,
            terminal = terminal,
            authored = authored,
            weights = WEIGHTS,
            paysAbove = 0.2,
        )
    }
}
