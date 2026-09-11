package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.VerticalWindow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * **What the water actually is, in a real hills Age, drawn as a cross-section.**
 *
 * Written because two fixes to the aquifer in a row failed to change what a walk saw, and the third
 * report was the useful one: the water *"does seem to correspond very heavily to the location of the seas
 * on the surface"* (Jonah, 2026-09-11). That is a different accusation from the one being answered — it
 * points at the **sea** branch rather than the perched one — and no amount of reading the code settles
 * which, because the branches are chosen by a noise.
 *
 * So this prints rather than asserts. It builds the landform a book actually gets, cuts the caves that
 * book actually cuts, and walks a slice of it saying which branch put every block there:
 *
 * ```
 * #  rock          ~  the sea's own fill, over the terrain
 * S  aquifer: at the sea's level        (the `columnWaterY` branch)
 * P  aquifer: a perched pool            (the `perchedLevel` branch)
 * .  aquifer: dry                       (bone dry)
 * ```
 *
 * A curtain of `S` down the middle of a cave says the sea branch is deciding per column and stepping at
 * the waterline. A stack of `P` sheets says the perched branch is banding. They are different bugs.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class AquiferSectionCheck : FunSpec({

    /**
     * **The column-by-column reading at a place a walk found water standing against air** — Jonah gave the
     * block: `-192, 60, 182` on seed 4242.
     *
     * The suspicion this settles: `columnSubmerged` is `columnSurface < columnWaterY`, a hard per-column
     * binary. Under a submerged column the aquifer floods for [dryingDepth] blocks down; one column over,
     * with its surface a hair above the waterline, it is judged by the deep thresholds and comes out dry.
     * Two neighbours, opposite answers, all the way down — which seen from the side is a wall of water.
     *
     * Hills is the landform that shows it because hills is the landform that *hovers* around the waterline.
     */
    test("what the aquifer says either side of the block Jonah found, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)
        val sea = SeaFill.of(Blocks.WATER.defaultBlockState(), SEA_LEVEL)
        val aquifer = WaterTable.matching(sea, SEA_LEVEL, SALT).aquiferFor(ground.shape)

        println("  seed $SALT, z=$FOUND_Z, y=$FOUND_Y — the block Jonah found is x=$FOUND_X")
        println("     x  surface  submerged  aquifer says")
        for (worldX in (FOUND_X - 12)..(FOUND_X + 12)) {
            val surface = ground.shape.columnSpans(worldX, FOUND_Z).highestSolidY
            val submerged = (surface ?: SEA_LEVEL) < SEA_LEVEL
            val put = aquifer.computeSubstance(
                DensityFunction.SinglePointContext(worldX, FOUND_Y, FOUND_Z),
                -1.0,
            )
            val says = when {
                put == null -> "(solid)"
                put.fluidState.isEmpty -> "dry"
                else -> "WATER"
            }
            val mark = if (worldX == FOUND_X) " <-- here" else ""
            println("  ${worldX.toString().padStart(5)}  ${(surface ?: -999).toString().padStart(7)}" +
                "  ${submerged.toString().padStart(9)}  $says$mark")
        }
    }

    test("a slice through a hills Age's caves, for reading") {
        MinecraftRegistries.ensureStoodUp()
        val window = VerticalWindow.DEFAULT
        val options = AgeComposition(terrains = listOf(Terrain.HILLS)).optionsFor(Aspect.TERRAIN, 0)
        val ground = Terrain.HILLS.ground(Underground.NOISE_CAVES, options, options, window, SALT)

        val water = Blocks.WATER.defaultBlockState()
        val sea = SeaFill.of(water, SEA_LEVEL)
        val table = WaterTable.matching(sea, SEA_LEVEL, SALT)
        val aquifer = table.aquiferFor(ground.shape)

        val uncut = ground.hollows ?: error("noise caves should leave the uncut rock as its hollows")

        println("  a hills Age at seed $SALT, sea level $SEA_LEVEL — z=$ACROSS_Z, x from $FROM_X:")
        var seaBranch = 0
        var perchedBranch = 0
        for (y in HIGHEST downTo LOWEST) {
            val row = StringBuilder()
            for (step in 0..<WIDE) {
                val worldX = FROM_X + step * STRIDE
                val rock = ground.shape.columnSpans(worldX, ACROSS_Z)
                when {
                    rock.contains(y) -> row.append('#')
                    // Inside the rock the caving removed: the aquifer decides, as the fill asks it to.
                    uncut.columnSpans(worldX, ACROSS_Z).contains(y) -> {
                        val put = aquifer.computeSubstance(DensityFunction.SinglePointContext(worldX, y, ACROSS_Z), -1.0)
                        if (put == null || put.fluidState.isEmpty) {
                            row.append('.')
                        } else {
                            // Which branch: at the sea's own level, or a pool perched above it.
                            if (y < SEA_LEVEL) seaBranch++ else perchedBranch++
                            row.append(if (y < SEA_LEVEL) 'S' else 'P')
                        }
                    }
                    y < SEA_LEVEL -> row.append('~')
                    else -> row.append(' ')
                }
            }
            if (row.any { it != ' ' }) println("  ${y.toString().padStart(4)} $row")
        }
        println("  wet blocks in cave: $seaBranch below the waterline, $perchedBranch above it")
    }
}) {
    private companion object {
        private const val SALT = 4242L
        private const val SEA_LEVEL = 63

        /** A slice wide enough to cross a coast, which is where the reported curtain stands. */
        private const val FROM_X = -256
        private const val WIDE = 128
        private const val STRIDE = 4
        private const val ACROSS_Z = 0

        /** The block a walk found water standing against air on, seed 4242. */
        private const val FOUND_X = -192
        private const val FOUND_Y = 60
        private const val FOUND_Z = 182

        private const val LOWEST = -60
        private const val HIGHEST = 150
    }
}
