package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.generation.StructureWeathering
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * `porous` cuts holes through what it is said of, and only that: the features or a named one, the structures or a named
 * set, the rock. Laid bare it is the rock alone, and a structure's holes never take what holds something.
 */
@Tags(NEEDS_REGISTRIES)
class PorousCheck : FunSpec({

    fun porosityOf(aspect: Aspect, pages: List<String>): String? =
        resolved(SEED, *pages.toTypedArray()).composition.optionsFor(aspect).chosen["porosity"]?.joinToString()

    test("porous features and porous structures hole what they name, and nothing else") {
        check(porosityOf(Aspect.FEATURES, listOf("porous", "features")) != null) { "porous features holed no feature" }
        check(porosityOf(Aspect.STRUCTURES, listOf("porous", "features")) == null) { "porous features holed the structures" }
        check(porosityOf(Aspect.STRUCTURES, listOf("porous", "structures")) != null) { "porous structures holed no structure" }
        val villages = porosityOf(Aspect.STRUCTURES, listOf("porous", "villages")).orEmpty()
        check("for=minecraft:villages" in villages) { "porous villages holed '$villages' rather than the villages" }
    }

    test("porous surface pits the ground") {
        check(porosityOf(Aspect.SURFACE, listOf("porous", "surface")) != null) { "porous surface pitted nothing" }
        check(porosityOf(Aspect.FEATURES, listOf("porous", "surface")) == null) { "porous surface holed the features" }
    }

    test("porous landmass holes the land, and what porous is said of keeps the rock as it was") {
        check(porosityOf(Aspect.TERRAIN, listOf("porous", "landmass")) != null) { "porous landmass holed no land" }
        fun rockOf(vararg pages: String) = resolved(SEED, *pages).composition.carvers
        check(Carvers.POROUS !in rockOf("porous", "features")) { "porous features made the rock porous" }
        check(Carvers.POROUS !in rockOf("porous", "landmass")) { "porous landmass made the rock porous" }
        check(Carvers.POROUS in rockOf("porous", "rock")) { "porous rock was not porous" }
        check(Carvers.POROUS in rockOf("porous")) { "a bare porous left the rock whole" }
    }

    test("porous underground is the porous underground, and leaves the rock as it was") {
        val composition = resolved(SEED, "porous", "underground").composition
        check(composition.underground == Underground.POROUS) { "porous underground seated ${composition.underground}" }
        check(Carvers.POROUS !in composition.carvers) { "porous underground made the rock porous" }
    }

    test("porous laid bare is the rock alone") {
        check(porosityOf(Aspect.FEATURES, listOf("porous")) == null) { "a bare porous holed the features" }
        check(porosityOf(Aspect.STRUCTURES, listOf("porous")) == null) { "a bare porous holed the structures" }
        check(porosityOf(Aspect.SURFACE, listOf("porous")) == null) { "a bare porous pitted the ground" }
        check(porosityOf(Aspect.TERRAIN, listOf("porous")) == null) { "a bare porous holed the land" }
    }

    test("a porous obelisk carries its porosity in its own clause") {
        val grows = resolved(SEED, "porous", "obelisks").composition.optionsFor(Aspect.FEATURES).chosen[Features.PLACES.name].orEmpty()
        check(grows.any { it.startsWith("agesandtheart:obelisks") && "porosity=" in it }) { "porous obelisks grew $grows" }
    }

    test("a structure's holes never take what holds something") {
        val holed = StructureWeathering(crumble = 0.0, charred = false, porosity = 1.0, holesSeed = SEED)
        val walls = (0..<SPAN).flatMap { x -> (0..<SPAN).map { y -> BlockPos(x, y, 0) } }
        check(walls.any { holed.weathered(it, Blocks.STONE_BRICKS.defaultBlockState(), SEED) == null }) { "a porous wall had no holes" }
        check(walls.none { holed.weathered(it, Blocks.CHEST.defaultBlockState(), SEED) == null }) { "a hole took a chest" }
        check(Structures.POROSITY.name == "porosity")
    }
})

private const val SEED = 7L
private const val SPAN = 24
