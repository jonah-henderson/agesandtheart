package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState

/** A crown wider than its trunk can hold is given branches until no leaf of it would decay. */
@Tags(NEEDS_REGISTRIES)
class BranchesCheck : FunSpec({

    /** A trunk three across and [TRUNK_HEIGHT] tall under a sphere of leaves [CROWN_RADIUS] round, every leaf marked held. */
    fun colossalTree(): MutableMap<BlockPos, BlockState> {
        MinecraftRegistries.ensureStoodUp()
        val tree = HashMap<BlockPos, BlockState>()
        val crownMiddle = BlockPos(0, TRUNK_HEIGHT, 0)
        for (x in -CROWN_RADIUS..CROWN_RADIUS) for (y in -CROWN_RADIUS..CROWN_RADIUS) for (z in -CROWN_RADIUS..CROWN_RADIUS) {
            if (x * x + y * y + z * z <= CROWN_RADIUS * CROWN_RADIUS) tree[crownMiddle.offset(x, y, z)] = LEAVES
        }
        for (x in -1..1) for (z in -1..1) for (y in 0..TRUNK_HEIGHT) tree[BlockPos(x, y, z)] = LOG
        return tree
    }

    fun unheld(tree: Map<BlockPos, BlockState>): Int {
        val held = Branches.distancesFromLogs(tree, ::isOak)
        return tree.count { (at, state) -> Branches.isLeaf(state) && at !in held }
    }

    test("a crown too wide for its trunk has leaves it cannot hold, so this tests something") {
        check(unheld(colossalTree()) > 0) { "the crown was held without branches" }
    }

    test("branches hold every leaf, and every leaf knows how far it is from a log") {
        val tree = colossalTree()
        Branches.grownInto(tree, { at -> tree[at]?.let(Branches::isLeaf) ?: true }, ::isOak)
        check(unheld(tree) == 0) { "${unheld(tree)} leaves would still decay" }
        val held = Branches.distancesFromLogs(tree, ::isOak)
        val misnumbered = tree.count { (at, state) -> Branches.isLeaf(state) && state.getValue(LeavesBlock.DISTANCE) != held[at] }
        check(misnumbered == 0) { "$misnumbered leaves carry a distance other than their own" }
    }

    test("a branch grows nowhere it may not") {
        val tree = colossalTree()
        val before = tree.toMap()
        Branches.grownInto(tree, { false }, ::isOak)
        val newLogs = tree.count { (at, state) -> isOak(state) && before[at]?.let(::isOak) != true }
        check(newLogs == 0) { "$newLogs logs grew where nothing might" }
    }
})

/** Offline no tag is bound, so the logs are named outright. */
private fun isOak(state: BlockState): Boolean = state.`is`(Blocks.OAK_LOG)

private const val TRUNK_HEIGHT = 24
private const val CROWN_RADIUS = 9
private val LOG: BlockState by lazy { Blocks.OAK_LOG.defaultBlockState() }
private val LEAVES: BlockState by lazy { Blocks.OAK_LEAVES.defaultBlockState() }
