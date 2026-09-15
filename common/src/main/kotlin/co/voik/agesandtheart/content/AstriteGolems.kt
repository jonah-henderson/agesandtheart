package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.pattern.BlockInWorld
import net.minecraft.world.level.block.state.pattern.BlockPattern
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate
import net.minecraft.world.level.gameevent.GameEvent

/**
 * Standing a golem up out of blocks, which is what a carved pumpkin finishing the shape means.
 *
 * **The same shape as an iron golem, in astrite** — a T of four blocks with the pumpkin for a head. It is
 * the arrangement every player already knows, and knowing it is most of why the golem is built this way
 * rather than crafted into an item.
 *
 * **Neither loader has an event for this.** NeoForge has no golem hook at all (checked: nothing across its
 * event classes) and Fabric has none either, and vanilla's own spawning is a private method reached from
 * `CarvedPumpkinBlock.onPlace`. So the seam is a Mixin, and this is the Kotlin it calls into.
 */
object AstriteGolems {

    /**
     * Stand one up if [pumpkin] just completed the shape, and answer whether one was.
     *
     * The pattern is cached because building it allocates and this runs on every carved pumpkin anybody
     * ever places.
     */
    fun tryAssemble(level: Level, pumpkin: BlockPos): Boolean {
        val match = shape.find(level, pumpkin) ?: return false
        clearTheBlocks(level, match)
        val golem = AgeContent.ASTRITE_GOLEM.create(level, EntitySpawnReason.TRIGGERED) ?: return false
        val stands = match.getBlock(MIDDLE, FOOT, ONLY_LAYER).pos
        golem.snapTo(stands.x + MIDDLE_OF_A_BLOCK, stands.y + JUST_ABOVE, stands.z + MIDDLE_OF_A_BLOCK, FACING, FACING)
        // Whoever was standing there when it came together owns it. The seam this is called from is a
        // block being placed and carries no player, so the nearest one is as close as we can get — which
        // in practice is the person who placed the pumpkin, since nobody else is that close by accident.
        level.getNearestPlayer(stands.x + MIDDLE_OF_A_BLOCK, stands.y.toDouble(), stands.z + MIDDLE_OF_A_BLOCK, WITHIN_REACH, false)
            ?.let(golem::tame)
        level.addFreshEntity(golem)
        level.gameEvent(golem, GameEvent.ENTITY_PLACE, stands)
        return true
    }

    private fun clearTheBlocks(level: Level, match: BlockPattern.BlockPatternMatch) {
        for (across in 0..<match.width) {
            for (up in 0..<match.height) {
                val was = match.getBlock(across, up, ONLY_LAYER)
                level.setBlock(was.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                level.levelEvent(BREAKING_PARTICLES, was.pos, Block.getId(was.state))
            }
        }
    }

    /**
     * The pattern, built once. `by lazy` rather than a hand-rolled null check on a `var`: mutable state in
     * an `object` is what `CLAUDE.md` names as an anti-pattern, and this one was reached from a Mixin on
     * the server thread with no synchronisation of its own.
     */
    private val shape: BlockPattern by lazy { build() }

    private fun build(): BlockPattern = BlockPatternBuilder.start()
        .aisle(HEAD, ARMS, BODY)
        .where(PUMPKIN, BlockInWorld.hasState(BlockStatePredicate.forBlock(Blocks.CARVED_PUMPKIN)))
        .where(ASTRITE, BlockInWorld.hasState(BlockStatePredicate.forBlock(AgeContent.ASTRITE_BLOCK_BLOCK)))
        .where(EMPTY, BlockInWorld.hasState(BlockBehaviour.BlockStateBase::isAir))
        .build()

    /** Read downwards: the head, the arms and the block it stands on. */
    private const val HEAD = "~^~"
    private const val ARMS = "###"
    private const val BODY = "~#~"

    private const val PUMPKIN = '^'
    private const val ASTRITE = '#'
    private const val EMPTY = '~'

    /** Where in that shape the golem itself stands. */
    private const val MIDDLE = 1
    private const val FOOT = 2
    private const val ONLY_LAYER = 0

    private const val MIDDLE_OF_A_BLOCK = 0.5
    private const val JUST_ABOVE = 0.05
    private const val FACING = 0.0f

    /** How far off its maker may be and still be counted as its maker, in blocks. */
    private const val WITHIN_REACH = 8.0

    private const val BREAKING_PARTICLES = 2001
}
