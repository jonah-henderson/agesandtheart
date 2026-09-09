package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import kotlin.math.roundToInt
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

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
        /**
         * Which way it lies, or **null for copper**, which conducts as a mass and so has no direction.
         *
         * The two shapes share this type because they share everything that is read off it — how far the
         * charge carries, and how well fed it is — and splitting them would have bought a field nobody
         * asks for.
         */
        val along: Direction?,
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
            if (blocks.isEmpty()) null else Run(heading, blocks, supplyAround(level, blocks))
        }

    /**
     * The runs this crystal is the one to drive — **the same runs, minus the ones somebody else is already
     * driving**, which is how two crystals on one bar stop counting it twice.
     *
     * `crystal — iron — iron — iron — crystal` is one bar and two walks find it, once from each end. Both
     * see the same three blocks and both see both crystals, so both come out at the same force — and left
     * alone the bar would pull twice as hard as anything a player could read off it. Naming one driver per
     * run is the whole fix, and it needs no ownership bookkeeping: **the run picks its own driver** from
     * the crystals already touching it, so nothing distant can change the answer.
     *
     * The lowest position wins, which is arbitrary and has to be — what matters is only that every crystal
     * on a run agrees, and a position sorts the same way from either end.
     */
    fun runsDrivenFrom(level: BlockGetter, at: BlockPos, metal: (BlockState) -> Boolean): List<Run> =
        runsFrom(level, at, metal).filter { drives(level, it, at) }

    private fun drives(level: BlockGetter, run: Run, crystal: BlockPos): Boolean =
        crystalsAround(level, run.blocks).minWithOrNull(POSITION_ORDER) == crystal

    /**
     * The strongest of [runs] acting on one thing, **never their sum**.
     *
     * Two runs that overlap are two ways of describing the same charge reaching the same place, so adding
     * them would pay a player twice for one field. It also means an underfed long array cannot be topped
     * up by laying a short one across it: what the ground feels is the best single run over it, which is
     * the number the builder can see.
     *
     * Runs from *different* crystals are pooled by whatever is applying them; this is the rule they pool by.
     */
    fun strongest(runs: Collection<Run>): Run? = runs.maxByOrNull { it.force }

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
     * The mass of copper this crystal is the one to drive, or null where it drives none.
     *
     * [runsDrivenFrom]'s rule, applied to the shape that has no direction: a mass with two crystals on it
     * is one machine, and left alone both walks would find the same copper and bite twice.
     */
    fun massDrivenFrom(level: BlockGetter, at: BlockPos): Run? {
        val mass = copperAround(level, at)
        if (mass.isEmpty()) return null
        val touching = crystalsAround(level, mass)
        if (touching.minWithOrNull(POSITION_ORDER) != at) return null
        return Run(along = null, blocks = mass.toList(), crystal = supplyAround(level, mass))
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

    /**
     * What the arc crystal touching any part of [blocks] is worth — the supply, wherever it was stacked.
     *
     * **A worth rather than a count, because a charged block is worth two** ([ArcCrystalBlock]). Reading
     * the lightning here is what lets it reach the pull, the push and the bite at once: none of them knows
     * about storms, and all three double when one lands.
     */
    fun supplyAround(level: BlockGetter, blocks: Collection<BlockPos>): Int =
        crystalsAround(level, blocks).sumOf { worthOf(level.getBlockState(it)) }

    /** What one block of crystal is worth to a machine — two while a bolt's charge is still in it. */
    fun worthOf(state: BlockState): Int = when {
        !state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) -> NOTHING_AT_ALL
        state.getValue(ArcCrystalBlock.CHARGE) > ArcCrystalBlock.FLAT -> ArcCrystalBlock.CHARGED_IS_WORTH
        else -> ArcCrystalBlock.ORDINARY_IS_WORTH
    }

    /** And which they are, which is what [runsDrivenFrom] needs to pick one of them. */
    fun crystalsAround(level: BlockGetter, blocks: Collection<BlockPos>): Set<BlockPos> {
        val found = LinkedHashSet<BlockPos>()
        for (block in blocks) {
            for (heading in Direction.entries) {
                val beside = block.relative(heading)
                if (beside in found) continue
                if (level.getBlockState(beside).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)) found += beside
            }
        }
        return found
    }

    /**
     * The arc crystal wired to [at] — **the pile a bolt fills**, walked through the crystal and through
     * anything that conducts.
     *
     * A rod that drew a strike stands on the pile rather than in it, and a mast is several blocks before
     * the crystal starts, so a walk that only knew about crystal would charge nothing in exactly the
     * arrangement the design tells a player to build. Conductors carry it because that is what conductors
     * do; the bolt reaches whatever the charge could have reached anyway.
     */
    fun pileConnectedTo(level: BlockGetter, at: BlockPos): Set<BlockPos> {
        val crystal = LinkedHashSet<BlockPos>()
        val walked = HashSet<BlockPos>()
        val queue = ArrayDeque(listOf(at) + Direction.entries.map { at.relative(it) })
        while (queue.isNotEmpty() && walked.size < MOST_IN_A_PILE) {
            val next = queue.removeFirst()
            if (!walked.add(next)) continue
            val state = level.getBlockState(next)
            val isCrystal = state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)
            if (!isCrystal && !conducts(state)) continue
            if (isCrystal) crystal += next
            Direction.entries.forEach { queue.addLast(next.relative(it)) }
        }
        return crystal
    }

    /**
     * The lightning rods in [mass] — **the mast it throws from**.
     *
     * A filter rather than a walk of its own, because **a lightning rod is copper** and so is already part
     * of the mass [copperAround] found. That one fact is what makes a rod stood on a pile of arc crystal
     * need no rule: it is a conducting mass of one block, driven by the crystal under it, and everything
     * about what it is worth was already written.
     */
    fun rodsOn(level: BlockGetter, mass: Collection<BlockPos>): Set<BlockPos> =
        mass.filterTo(LinkedHashSet()) { level.getBlockState(it).`is`(Blocks.LIGHTNING_ROD) }

    /**
     * How far a charged mass bites past itself, given the rods on it — nothing at all without one.
     *
     * The first rod is worth four blocks and each after it one more, so a mast is worth building and worth
     * stopping: thirteen rods reach [FURTHEST_A_MAST_THROWS] and a fourteenth buys nothing.
     */
    fun reachOfAMast(rods: Int): Double = when {
        rods <= 0 -> BY_CONTACT
        else -> (FIRST_ROD_REACHES + (rods - 1)).coerceAtMost(FURTHEST_A_MAST_THROWS).toDouble()
    }

    /**
     * Every block of arc crystal within [within] of [around] — **found by scanning, and not indexed**.
     *
     * [Lures] carries the whole argument and it holds here for the same reason: a section's palette says
     * whether it holds any at all, so all but a handful are dismissed without a single block being read,
     * and there is nothing to keep up to date, nothing to go stale when a chunk unloads, and no
     * bookkeeping to get wrong. An index would have wanted a block class, two block-change hooks and a
     * per-level map to buy back a cost that is not being paid.
     */
    fun crystalsNear(level: Level, around: Vec3, within: Double): List<BlockPos> {
        val found = mutableListOf<BlockPos>()
        val middle = BlockPos.containing(around)
        val reach = within.roundToInt()
        val fromX = SectionPos.blockToSectionCoord(middle.x - reach)
        val toX = SectionPos.blockToSectionCoord(middle.x + reach)
        val fromZ = SectionPos.blockToSectionCoord(middle.z - reach)
        val toZ = SectionPos.blockToSectionCoord(middle.z + reach)
        val holds = { state: BlockState -> state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) }
        for (chunkX in fromX..toX) {
            for (chunkZ in fromZ..toZ) {
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (index in chunk.sections.indices) {
                    val bottom = chunk.getSectionYFromSectionIndex(index) * SECTION
                    // Bounded up and down as well as sideways. A column is two dozen sections and all but
                    // a few are nowhere near the asker; skipping them costs a comparison where reading a
                    // palette costs a lookup.
                    if (bottom + SECTION < middle.y - reach || bottom > middle.y + reach) continue
                    val states = chunk.sections[index]
                    if (states.hasOnlyAir() || !states.maybeHas(holds)) continue
                    for (x in 0..<SECTION) for (y in 0..<SECTION) for (z in 0..<SECTION) {
                        if (!holds(states.getBlockState(x, y, z))) continue
                        val at = BlockPos(chunk.pos.minBlockX + x, bottom + y, chunk.pos.minBlockZ + z)
                        if (at.distToCenterSqr(around) <= within * within) found += at
                    }
                }
            }
        }
        return found
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

    /** Any total order will do — see [runsDrivenFrom] for why only the agreeing matters. */
    private val POSITION_ORDER = compareBy<BlockPos>({ it.x }, { it.y }, { it.z })

    private const val NOTHING_AT_ALL = 0

    /** How far one run of iron or gold may reach. A long array is a build, not a bug. */
    const val LONGEST_RUN = 32

    /** And how much copper one charge may run through, for the reason [LavaTubes] caps a mass. */
    private const val MOST_IN_A_MASS = 256

    /** And how much of a pile one bolt charges, for the reason a mass is capped. */
    private const val MOST_IN_A_PILE = 512

    private const val BY_CONTACT = 0.0
    private const val FIRST_ROD_REACHES = 4
    private const val FURTHEST_A_MAST_THROWS = 16

    private const val SECTION = 16
}
