package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.TreeFoliage
import co.voik.agesandtheart.age.consequence.StrangeGround
import co.voik.agesandtheart.worldgen.feature.FlagTrees
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.levelgen.feature.Feature
import java.util.function.BooleanSupplier

/**
 * A sapling grown in an Age whose book grew its trees of a block (`diamond_block trees`), grown of it too — asked by
 * `TreeGrowerMixin` as the sapling becomes its tree, and answered off the Age's own generator.
 */
object GrownTrees {

    /**
     * Grows [tree] through [grow], with whatever its Age grew such trees of in place of their leaves, and bent as the
     * wind bends the trees where it stands.
     */
    @JvmStatic
    fun grown(level: WorldGenLevel, tree: Feature, grow: BooleanSupplier): Boolean {
        val server = level as? ServerLevel ?: return grow.asBoolean
        val generator = server.chunkSource.generator as? AgeChunkGenerator ?: return grow.asBoolean
        return FlagTrees.sweeping(generator.windBendingTrees) { grownOf(generator.treesGrownWith, server, tree, grow) }
    }

    private fun grownOf(written: TreeFoliage, server: ServerLevel, tree: Feature, grow: BooleanSupplier): Boolean {
        if (written.isNothing) return grow.asBoolean
        val block = written.ofSapling(tree, server.registryAccess()) ?: return grow.asBoolean
        return StrangeGround.growingWith(block) { grow.asBoolean }
    }
}
