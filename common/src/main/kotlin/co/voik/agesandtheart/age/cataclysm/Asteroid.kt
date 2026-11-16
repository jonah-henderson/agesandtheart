package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

/**
 * What an asteroid's end does on the server (design §5.3 "The asteroid"): the sky, the flash and the dust are the
 * client's, drawn off the schedule; what is left here is **the fragments** falling round players as the debris
 * arrives, **the heat**, which kills whatever the glowing sky can see and sets what it shines on alight, and **the
 * scorch** behind the dust.
 */
object Asteroid {

    fun befall(level: ServerLevel, doom: Doom) {
        throwFragments(level, doom)
        when (doom.phaseAt(level.gameTime)) {
            ImpactPhase.HEAT -> {
                if (level.gameTime % BURNING_EVERY == 0L) burnWhatTheSkySees(level)
                setTheGroundAlight(level)
            }
            ImpactPhase.DUST -> if (doom.hidesTheScorchAt(level.gameTime)) Scorch.some(level, CHUNKS_IN_THE_DUST)
            ImpactPhase.AFTER -> Scorch.some(level, CHUNKS_AFTER)
            ImpactPhase.APPROACH, ImpactPhase.ENTRY, ImpactPhase.TRAVEL, null -> Unit
        }
    }

    /** Fragments coming down round each player, from late in the debris' travel and through the heat. */
    private fun throwFragments(level: ServerLevel, doom: Doom) {
        val perSecond = fragmentsPerSecondAt(doom, level.gameTime.toDouble())
        if (perSecond <= 0.0) return
        for (player in level.players()) {
            if (player.isSpectator) continue
            val isThrown = level.random.nextDouble() < perSecond / ImpactTimeline.TICKS_PER_SECOND
            if (isThrown) Ejecta.throwNear(level, player, doom.bearingDegrees)
        }
    }

    /** How many fragments a second fall round a player: gathering through the end of the travel, and steady in the heat. */
    fun fragmentsPerSecondAt(doom: Doom, time: Double): Double = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL -> {
            val gathering = (doom.progressThrough(ImpactPhase.TRAVEL, time) - FRAGMENTS_FROM) / (1.0 - FRAGMENTS_FROM)
            if (gathering <= 0.0) 0.0 else FIRST_FRAGMENTS + (FRAGMENTS_IN_THE_HEAT - FIRST_FRAGMENTS) * gathering
        }
        ImpactPhase.HEAT -> FRAGMENTS_IN_THE_HEAT
        ImpactPhase.APPROACH, ImpactPhase.ENTRY, ImpactPhase.DUST, ImpactPhase.AFTER, null -> 0.0
    }

    /**
     * Burns everything living that the sky can see, players and creatures alike. Under water is sheltered, as
     * what lived in the sea was; under a roof is sheltered for as long as the roof stands.
     *
     * Its own damage rather than fire's, so neither a potion nor a creature's own fire immunity is an answer.
     */
    private fun burnWhatTheSkySees(level: ServerLevel) {
        val heat = heatIn(level)
        // Gathered first: what burns to death leaves the level's entities while they are being walked.
        val exposed = level.allEntities.filterIsInstance<LivingEntity>().filter { it.isAlive && isUnderTheSky(level, it) }
        for (living in exposed) {
            if (living is Player && (living.isCreative || living.isSpectator)) continue
            living.igniteForSeconds(BURNS_FOR_SECONDS)
            living.hurtServer(level, heat, BURN_DAMAGE)
        }
    }

    private fun isUnderTheSky(level: ServerLevel, living: LivingEntity): Boolean {
        val isUnderWater = living.isEyeInFluid(FluidTags.WATER)
        val seesTheSky = level.canSeeSky(BlockPos.containing(living.eyePosition))
        return seesTheSky && !isUnderWater
    }

    /**
     * Lights fires on what the sky shines on near each player, a few columns a tick — so a canopy goes up and a
     * wooden roof burns off, and whoever sheltered under it is under the sky again.
     */
    private fun setTheGroundAlight(level: ServerLevel) {
        val random = level.random
        for (player in level.players()) {
            repeat(COLUMNS_LIT_PER_PLAYER) {
                val x = player.blockX + random.nextInt(LIT_ACROSS) - LIT_ACROSS / 2
                val z = player.blockZ + random.nextInt(LIT_ACROSS) - LIT_ACROSS / 2
                if (!level.isLoaded(BlockPos(x, 0, z))) return@repeat
                // Not under anyone's feet: whoever stands in the open burns anyway, and a fire in the eyes hides the end.
                val isUnderSomebody = level.players().any { abs(it.blockX - x) <= 1 && abs(it.blockZ - z) <= 1 }
                if (isUnderSomebody) return@repeat
                val above = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z)
                val top = above.below()
                val isLeaves = level.getBlockState(top).`is`(BlockTags.LEAVES)
                if (isLeaves && random.nextFloat() < LEAVES_BURNED_STRAIGHT_AWAY) {
                    level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
                    return@repeat
                }
                val catches = level.getBlockState(above).isAir && BaseFireBlock.canBePlacedAt(level, above, Direction.UP)
                if (catches) level.setBlock(above, BaseFireBlock.getState(level, above), Block.UPDATE_ALL)
            }
        }
    }

    private fun heatIn(level: ServerLevel): DamageSource =
        DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(FALLING_SKY))

    val FALLING_SKY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, "falling_sky".location())

    private const val BURNING_EVERY = 10L
    /** Enough that nothing lasts a few seconds under the glowing sky, armoured or not. */
    private const val BURN_DAMAGE = 5.0f
    private const val BURNS_FOR_SECONDS = 4.0f

    private const val COLUMNS_LIT_PER_PLAYER = 24
    private const val LIT_ACROSS = 96
    private const val LEAVES_BURNED_STRAIGHT_AWAY = 0.5f

    /** How far through the travel the first fragments fall, and how many a second then and through the heat. */
    private const val FRAGMENTS_FROM = 0.6
    private const val FIRST_FRAGMENTS = 0.1
    private const val FRAGMENTS_IN_THE_HEAT = 0.5

    /** How many chunks the scorch works through a tick: quickly behind the dust, and steadily after it. */
    private const val CHUNKS_IN_THE_DUST = 2
    private const val CHUNKS_AFTER = 1
}
