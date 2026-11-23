package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.Arcs
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Lasers armed by arc crystal (design §7.4): a pile of arc crystal joined to an emitter arms its laser to hurt every
 * creature it passes through, by how much crystal is in it ([hurtFor]), and a charged pile to burn through the blocks
 * it ends against as well, dropping what a pickaxe would. Either needs the light at full strength or more, so half a
 * split laser is harmless.
 *
 * Optical blocks and whatever else is in [WITHSTANDS_LASERS] are never burnt, so a beam can be aimed through a build
 * of them safely; tinted glass, which stops beams, is the backstop.
 */
object ArmedBeams {
    val WITHSTANDS_LASERS: TagKey<Block> = TagKey.create(Registries.BLOCK, "withstands_lasers".location())

    val LASER: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, "laser".location())

    /** How many blocks burnt cost one discharge of the crystal arming the laser: a bolt's charge burns over a hundred. */
    const val BURNT_FOR_A_CHARGE = 16

    /** Burnt blocks drop what this would mine from them. */
    private val MINED_AS_BY = Items.DIAMOND_PICKAXE

    /** How often an emitter counts the crystal joined to it, which is a walk through the pile. */
    const val COUNTED_EVERY = 10L

    /** What each block of crystal's worth hurts a hit: hits land every half second, so half a heart a second. */
    private const val HURTS_A_CRYSTAL = 0.5f

    /** The most worth a plain pile counts, which is the least a charged one does. */
    private const val PLAIN_COUNTS_TO = 8

    /** And the most a charged pile counts. */
    private const val CHARGED_COUNTS_TO = 16

    /** How long a laser at full strength takes to burn through a block, in ticks for each point of its hardness. */
    private const val TICKS_A_HARDNESS = 20f

    /** And at the least, however soft the block. */
    private const val FEWEST_TICKS = 4f

    private const val REACHES_PAST_ITS_LINE = 0.25
    private const val CRACK_STAGES = 10
    private const val NO_CRACK = -1
    private const val SMOKES_EVERY = 4L
    private const val SPARKS_A_BLOCK_A_TICK = 0.05f
    private const val HALF = 0.5

    /** The arc the pile of crystal joined to the emitter at [pos] gives its laser, or null where there is none. */
    fun arcFrom(level: Level, pos: BlockPos): Arc? {
        val pile = Arcs.supplyingCrystal(level, listOf(pos))
        if (pile.isEmpty()) return null
        val worth = Arcs.supplyOf(level, pile)
        val isCharged = worth > pile.size
        return Arc(burns = isCharged, hurts = hurtFor(worth, isCharged), armedBy = pos)
    }

    /**
     * What a laser armed by crystal of [worth] hurts a hit (a charged block is worth two): more for more crystal, and
     * never more from a plain pile than the least from a charged one.
     */
    fun hurtFor(worth: Int, isCharged: Boolean): Float {
        val counted = if (isCharged) worth.coerceIn(PLAIN_COUNTS_TO, CHARGED_COUNTS_TO) else worth.coerceAtMost(PLAIN_COUNTS_TO)
        return HURTS_A_CRYSTAL * counted
    }

    /** The blocks of arc crystal beside the emitter at [pos], which pay for what its laser burns. */
    fun crystalsBeside(level: Level, pos: BlockPos): List<BlockPos> =
        Direction.entries.map(pos::relative).filter { level.getBlockState(it).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) }

    /** Hurts every creature standing in an armed run of [path], by the crystal arming it. */
    fun harm(level: ServerLevel, path: BeamPath) {
        val armed = path.segments.filter { it.harm > 0 }
        if (armed.isEmpty()) return
        val source = DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(LASER))
        for (segment in armed) {
            val (start, end) = endsOf(segment)
            val struck = level.getEntitiesOfClass(LivingEntity::class.java, AABB(start, end).inflate(REACHES_PAST_ITS_LINE))
            for (creature in struck) creature.hurtServer(level, source, segment.harm)
        }
    }

    /** Sparks along the armed runs of [path], on the client, so an armed laser is told from one that is safe. */
    fun sparkAlong(level: Level, path: BeamPath, random: RandomSource) {
        for (segment in path.segments) {
            if (segment.harm <= 0) continue
            val expected = segment.length * SPARKS_A_BLOCK_A_TICK
            val sparks = expected.toInt() + if (random.nextFloat() < expected % 1f) 1 else 0
            if (sparks == 0) continue
            val (start, end) = endsOf(segment)
            repeat(sparks) {
                val at = start.lerp(end, random.nextDouble())
                val leap = Vec3(random.nextDouble() - HALF, random.nextDouble() - HALF, random.nextDouble() - HALF).scale(0.1)
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, leap.x, leap.y, leap.z)
            }
        }
    }

    private fun endsOf(segment: BeamSegment): Pair<Vec3, Vec3> {
        val along = segment.direction.unitVec3
        val start = Vec3.atCenterOf(segment.from).add(along.scale(segment.shiftedStart.toDouble()))
        return start to start.add(along.scale(segment.length.toDouble()))
    }

    /**
     * What one source's burning lasers have done so far to the blocks they end against, each block's burn kept until
     * the beam leaves it. Held by the block entity sending the beam, and never saved: a burn starts over on reload.
     */
    class Burning {
        private val burnt = mutableMapOf<BlockPos, Float>()

        /** Burns on at every block a branch of [path] ends against, burning away those burnt through. */
        fun tick(level: ServerLevel, path: BeamPath) {
            val burningNow = mutableMapOf<BlockPos, BeamHit>()
            for (hit in path.hits) if (hit.light.burns) burningNow.merge(hit.pos, hit) { kept, _ -> kept }
            for (left in burnt.keys - burningNow.keys) stop(level, left)
            for ((pos, hit) in burningNow) burnOn(level, pos, hit)
        }

        /** Every burn put out, as when the source is removed. */
        fun stopAll(level: Level) {
            for (pos in burnt.keys.toList()) stop(level, pos)
        }

        private fun burnOn(level: ServerLevel, pos: BlockPos, hit: BeamHit) {
            val state = level.getBlockState(pos)
            val hardness = state.getDestroySpeed(level, pos)
            if (!canBurn(level, pos, state, hardness)) return stop(level, pos)
            val needed = maxOf(FEWEST_TICKS, hardness * TICKS_A_HARDNESS)
            val done = burnt.getOrDefault(pos, 0f) + hit.light.intensity.toFloat() / Light.FULL
            if (done < needed) {
                burnt[pos] = done
                level.destroyBlockProgress(crackId(pos), pos, (done / needed * CRACK_STAGES).toInt().coerceAtMost(CRACK_STAGES - 1))
                if (level.gameTime % SMOKES_EVERY == 0L) smoke(level, pos, hit.travelling)
                return
            }
            stop(level, pos)
            Block.dropResources(state, level, pos, level.getBlockEntity(pos), null, ItemStack(MINED_AS_BY))
            level.destroyBlock(pos, false)
            hit.light.arc?.let { arc -> (level.getBlockEntity(arc.armedBy) as? AstriteEmitterBlockEntity)?.burntThrough(level) }
        }

        private fun stop(level: Level, pos: BlockPos) {
            if (burnt.remove(pos) != null) level.destroyBlockProgress(crackId(pos), pos, NO_CRACK)
        }

        private fun canBurn(level: ServerLevel, pos: BlockPos, state: BlockState, hardness: Float): Boolean =
            !state.isAir && hardness >= 0f && !state.`is`(WITHSTANDS_LASERS) && level.getBlockEntity(pos) !is TakesBeams

        private fun smoke(level: ServerLevel, pos: BlockPos, travelling: Direction) {
            val face = Vec3.atCenterOf(pos).subtract(travelling.unitVec3.scale(HALF + 0.05))
            level.sendParticles(ParticleTypes.SMOKE, face.x, face.y, face.z, 1, 0.1, 0.1, 0.1, 0.01)
        }

        /** One crack per block, whichever source burns it, kept apart from the ids of entities, which are not negative. */
        private fun crackId(pos: BlockPos): Int = -1 - (pos.asLong().hashCode() and Int.MAX_VALUE)
    }
}
