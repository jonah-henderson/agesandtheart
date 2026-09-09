package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * What a block of arc crystal is powering, and how hard — the reckoning behind every charged machine
 * (design §7.1.2).
 *
 * **Two shapes, and the difference is a simplification we chose on purpose** (Jonah, 2026-09-08). Copper
 * conducts through its **whole connected mass**, which is what a wire does. Iron and gold are read as
 * **lines**, each measured on its own, which is not what a magnet does and is much easier to build with
 * and reason about: a player laying a bar can see the field it will make, where a flood-filled magnet
 * would depend on rock they cannot see behind a wall.
 *
 * **Force is crystal per metal block**, always. More metal buys reach and more crystal buys strength, so a
 * long array needs proportionally more crystal to stay strong — which is what makes the material's sink
 * *building* rather than fuel.
 *
 * **Only purely unweathered copper conducts.** Oxide is an insulator, and that hands us the decay for
 * nothing: a run greens over and stops until it is waxed or a bolt scours it clean
 * (`LightningBolt.clearCopperOnLightningStrike` walks a connected run doing exactly that).
 */
object Arcs {

    /** One run of metal a crystal is powering: which way it lies, how long, and how well fed. */
    data class Run(
        val along: Direction,
        val blocks: List<BlockPos>,
        val crystal: Int,
    ) {
        /** How far the field reaches, which is the run's own length — the shape you build is the shape you get. */
        val reach: Int get() = blocks.size

        /**
         * Crystal per metal block, and so how hard it pulls, pushes or bites.
         *
         * One and one is the anchor every other number is read against: an ordinary array, noticeably
         * better than a snow golem and no more.
         */
        val force: Double get() = if (blocks.isEmpty()) 0.0 else crystal.toDouble() / blocks.size
    }

    /**
     * The lines of [metal] leading away from the crystal block [at] — **one per direction, measured
     * separately**, and never a mass.
     *
     * A run stops at the first block that is not this metal, so a corner is two runs rather than one bent
     * one. That is the simplification: what a player sees laid out is what they get.
     */
    fun runsFrom(level: BlockGetter, at: BlockPos, metal: (BlockState) -> Boolean): List<Run> =
        Direction.entries.mapNotNull { heading ->
            val blocks = lineFrom(level, at, heading, metal)
            if (blocks.isEmpty()) null else Run(heading, blocks, crystalAround(level, blocks))
        }

    private fun lineFrom(
        level: BlockGetter,
        at: BlockPos,
        heading: Direction,
        metal: (BlockState) -> Boolean,
    ): List<BlockPos> {
        val found = ArrayList<BlockPos>()
        var walking = at.relative(heading)
        while (found.size < LONGEST_RUN && metal(level.getBlockState(walking))) {
            found += walking
            walking = walking.relative(heading)
        }
        return found
    }

    /**
     * The connected mass of unweathered copper touching [at], six ways, up to [MOST_IN_A_MASS].
     *
     * Capped for the reason [LavaTubes] caps its own: a mass is walked from a block change, and a player
     * who has run copper across a continent should not be able to make that walk the tick that finds it.
     */
    fun copperAround(level: BlockGetter, at: BlockPos): Set<BlockPos> {
        val found = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        Direction.entries.forEach { queue.addLast(at.relative(it)) }
        while (queue.isNotEmpty() && found.size < MOST_IN_A_MASS) {
            val next = queue.removeFirst()
            if (next in found) continue
            if (!conducts(level.getBlockState(next))) continue
            found += next
            Direction.entries.forEach { queue.addLast(next.relative(it)) }
        }
        return found
    }

    /** How many blocks of arc crystal touch any part of [blocks] — the supply, wherever it was stacked. */
    fun crystalAround(level: BlockGetter, blocks: Collection<BlockPos>): Int {
        val counted = HashSet<BlockPos>()
        for (block in blocks) {
            for (heading in Direction.entries) {
                val beside = block.relative(heading)
                if (beside in counted) continue
                if (level.getBlockState(beside).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)) counted += beside
            }
        }
        return counted.size
    }

    /**
     * **The three currents, as tags of ours** — what pulls, what pushes, and what bites.
     *
     * Tags rather than a list in code, so a pack can add a metal without touching the jar and the three
     * read as one family in the data rather than as three arms of a `when`. What they must *not* be built
     * on is `minecraft:copper_blocks`, which would drag every oxidised stage in with it: which stage
     * conducts is the mechanic here, not an implementation detail, and the tag spells the bare ones out.
     */
    val ATTRACTIVE: TagKey<Block> = TagKey.create(Registries.BLOCK, "carries_attractive_current".location())
    val REPULSIVE: TagKey<Block> = TagKey.create(Registries.BLOCK, "carries_repulsive_current".location())
    val ELECTRIC: TagKey<Block> = TagKey.create(Registries.BLOCK, "carries_electric_current".location())

    fun conducts(state: BlockState): Boolean = state.`is`(ELECTRIC)

    fun attracts(state: BlockState): Boolean = state.`is`(ATTRACTIVE)

    fun repels(state: BlockState): Boolean = state.`is`(REPULSIVE)

    /** How far one run of iron or gold may reach. A long array is a build, not a bug. */
    private const val LONGEST_RUN = 32

    /** And how much copper one charge may run through, for the reason [LavaTubes] caps a mass. */
    private const val MOST_IN_A_MASS = 256
}
