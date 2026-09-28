package co.voik.agesandtheart.station

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * The machines behind the fine grades (design §7.1.2): the grinder serves the ink ladder and the pulper
 * the paper ladder. Each is one block, and runs only while [needs] stand beside it.
 */
enum class Station(
    val key: String,
    val recipeName: String,
    val needs: List<StationNeed>,
    val workTicks: Int,
    val finishSound: SoundEvent,
    val workingParticle: SimpleParticleType,
) {
    GRINDER(
        key = "grinder",
        recipeName = "grinding",
        needs = listOf(StationNeed.ARC_CRYSTAL, StationNeed.GRINDSTONE),
        workTicks = GRINDING_TICKS,
        finishSound = SoundEvents.GRINDSTONE_USE,
        workingParticle = ParticleTypes.ASH,
    ),
    PULPER(
        key = "pulper",
        recipeName = "pulping",
        needs = listOf(StationNeed.ARC_CRYSTAL, StationNeed.WATER_CAULDRON),
        workTicks = PULPING_TICKS,
        finishSound = SoundEvents.BREWING_STAND_BREW,
        workingParticle = ParticleTypes.SPLASH,
    ),
    ;

    /** What this station lacks, given the block on each face. */
    fun missingNeeds(neighbour: (Direction) -> BlockState): List<StationNeed> {
        fun isMetOnSomeFace(need: StationNeed) = Direction.entries.any { face -> need.isMetBy(neighbour(face)) }
        return needs.filterNot(::isMetOnSomeFace)
    }

    /** Charges whatever a finished run costs its neighbours — the pulper's cauldron loses a level. */
    fun spendNeeds(level: ServerLevel, pos: BlockPos) {
        for (need in needs) {
            val face = Direction.entries.firstOrNull { need.isMetBy(level.getBlockState(pos.relative(it))) } ?: continue
            need.spend(level, pos.relative(face))
        }
    }
}

/** A furnace's smelt, for a deretheni ground to dust. */
private const val GRINDING_TICKS = 200

/** Longer than grinding: wood is cooked down, not broken. */
private const val PULPING_TICKS = 300

/** A stack of deretheni, on average; a grindstone is cheap and the deretheni is not. */
private const val RUNS_A_GRINDSTONE_LASTS_ON_AVERAGE = 64

/** A block a station must have beside it before it will work. */
enum class StationNeed {
    /** The power, paid at construction and never metered. */
    ARC_CRYSTAL,

    /** The grinder's wheel, which wears out — see [RUNS_A_GRINDSTONE_LASTS_ON_AVERAGE]. */
    GRINDSTONE,

    /** The pulper's water, drained a level a run. */
    WATER_CAULDRON,
    ;

    fun isMetBy(state: BlockState): Boolean = when (this) {
        ARC_CRYSTAL -> state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)
        GRINDSTONE -> state.`is`(Blocks.GRINDSTONE)
        WATER_CAULDRON -> state.`is`(Blocks.WATER_CAULDRON)
    }

    fun spend(level: ServerLevel, at: BlockPos) {
        when (this) {
            ARC_CRYSTAL -> Unit
            GRINDSTONE -> if (level.random.nextInt(RUNS_A_GRINDSTONE_LASTS_ON_AVERAGE) == 0) level.destroyBlock(at, false)
            WATER_CAULDRON -> LayeredCauldronBlock.lowerFillLevel(level.getBlockState(at), level, at)
        }
    }
}

/** What a station is doing, as a block state so it reads across a room. */
enum class StationActivity(private val key: String) : StringRepresentable {
    /** Nothing to work on. */
    IDLE("idle"),

    WORKING("working"),

    /** Something to work on, and a neighbour missing or the output full. */
    STALLED("stalled"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val PROPERTY: EnumProperty<StationActivity> = EnumProperty.create("activity", StationActivity::class.java)
    }
}
