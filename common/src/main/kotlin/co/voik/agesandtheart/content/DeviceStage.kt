package co.voik.agesandtheart.content

import net.minecraft.util.StringRepresentable
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
