package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * Where a D'ni instrument is in its work — shared by both acquaintance devices (design §8.3).
 *
 * The two ask different questions and answer them the same way: take a referent, work for a while, hold an
 * answer until somebody comes for it. That shape is what makes them read as a *pair* of instruments rather
 * than two unrelated blocks, so it is stated once and they both wear it.
 */
enum class DeviceStage(private val key: String) : StringRepresentable {
    /** Waiting for something to work on. */
    IDLE("idle"),

    /** Working, and the wait is a scheduled tick. */
    WORKING("working"),

    /** Holding a word, until somebody asks for it. */
    READY("ready"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val PROPERTY: EnumProperty<DeviceStage> = EnumProperty.create("stage", DeviceStage::class.java)

        /** Long enough to be work, short enough to stand and wait out. */
        const val WORK_TICKS = 400
    }
}

/** The [DeviceStage.WORKING] stage as both acquaintance devices run it. */
object DeviceWork {

    /**
     * Says the device is still working, and sets it going again if nothing is coming for it.
     *
     * The stage is a block state and the wait is a scheduled tick, so anything that writes the one without
     * the other leaves a device running with nothing to finish it: `/setblock`, or a structure carrying one
     * mid-reading. Breaking it is *not* such a case — vanilla checks the block still matches before it ticks
     * — and neither is a piston, which cannot move either device at all.
     */
    fun keepWorking(level: ServerLevel, pos: BlockPos, block: Block, player: ServerPlayer, key: String) {
        if (!level.blockTicks.hasScheduledTick(pos, block)) level.scheduleTick(pos, block, DeviceStage.WORK_TICKS)
        say(player, key)
    }

    /** A translated line on [player]'s action bar. */
    fun say(player: ServerPlayer, key: String) {
        player.sendSystemMessage(Component.translatable(key), true)
    }

    /** The wait is over: a working device becomes ready, and chimes to say so. */
    fun finishWork(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.WORKING) return
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.READY), Block.UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, VOLUME, PITCH)
    }

    /** Enchanting motes rising off a working device. */
    fun workingParticles(level: Level, pos: BlockPos, random: RandomSource, state: BlockState) {
        if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.WORKING) return
        level.addParticle(
            ParticleTypes.ENCHANT,
            pos.x + random.nextDouble(),
            pos.y + ABOVE_THE_DEVICE,
            pos.z + random.nextDouble(),
            0.0,
            DRIFTING_UP,
            0.0,
        )
    }

    const val VOLUME = 1.0f
    const val PITCH = 1.0f

    private const val ABOVE_THE_DEVICE = 1.1
    private const val DRIFTING_UP = 0.04
}
