package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.Blast
import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.agesandtheart.age.cataclysm.ImpactSounds
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource

/**
 * What an asteroid's end is heard by while it lasts (design §5.3 "The asteroid"), as against the moments [Impacts]
 * plays once: the drone of the approach's last minutes, the roar of its burning, the blast's wind, the firestorm, the wall of ash
 * coming on, the dust and the winter after.
 *
 * Each loop's loudness is worked out every tick from the schedule, so each swells and dies with its part of the end,
 * and a loop stops itself once its part is over. Out of the open they are muffled rather than silenced, as a gale's
 * gust is, and under water nearly gone.
 */
object ImpactLoops {

    /** One loop: its [sound], and how loud it is at a moment of the end, or null where it is not heard at all. */
    private class Loop(val sound: SoundEvent, val loudnessAt: (Doom, ClientLevel, Double) -> Float?)

    private val LOOPS = listOf(
        Loop(ImpactSounds.APPROACH) { doom, _, time ->
            when (doom.phaseAt(time.toLong())) {
                ImpactPhase.APPROACH -> {
                    val heardFrom = doom.startOf(ImpactPhase.ENTRY) - APPROACH_HEARD_FOR
                    val swelling = ((time - heardFrom) / APPROACH_HEARD_FOR).coerceIn(0.0, 1.0)
                    if (swelling <= 0.0) null else APPROACH_LOUDEST * squared(swelling)
                }
                ImpactPhase.ENTRY -> APPROACH_LOUDEST * (1.0f - doom.progressThrough(ImpactPhase.ENTRY, time).toFloat())
                else -> null
            }
        },
        Loop(ImpactSounds.BURNING) { doom, _, time ->
            if (doom.phaseAt(time.toLong()) != ImpactPhase.ENTRY) null
            else BURNING_LOUDEST * doom.progressThrough(ImpactPhase.ENTRY, time).toFloat()
        },
        Loop(ImpactSounds.BLAST_WIND) { doom, level, time ->
            Blast.blowingAt(doom, time)?.let { blowing ->
                val fury = (blowing.fury / FURY_AT_ITS_LOUDEST).coerceAtMost(1.0)
                (blowing.lying * fury).toFloat() * openness(level)
            }
        },
        Loop(ImpactSounds.FIRESTORM) { doom, level, time ->
            when (doom.phaseAt(time.toLong())) {
                ImpactPhase.HEAT, ImpactPhase.DUST -> Impacts.heatAt(doom, time) * openness(level)
                else -> null
            }
        },
        Loop(ImpactSounds.ASH_WALL) { doom, _, time ->
            val reached = doom.ashReachedAt(time)
            if (reached <= 0.0 || reached >= 1.0) null else ASH_WALL_LOUDEST * reached.toFloat() * reached.toFloat()
        },
        Loop(ImpactSounds.DUST) { doom, _, time ->
            if (doom.phaseAt(time.toLong()) != ImpactPhase.DUST) null else doom.chokeAt(time)
        },
        Loop(ImpactSounds.WINTER) { doom, _, time ->
            if (doom.phaseAt(time.toLong()) != ImpactPhase.AFTER) null else WINTER_LOUDNESS
        },
    )

    private val playing = mutableMapOf<SoundEvent, Playing>()

    /** Starts whichever loops [level]'s end now calls for and are not already going. Each keeps itself after that. */
    fun hear(minecraft: Minecraft, level: ClientLevel) {
        val doom = Impacts.comingTo(level) ?: return
        val time = level.gameTime.toDouble()
        for (loop in LOOPS) {
            val isCalledFor = loop.loudnessAt(doom, level, time) != null
            val isGoing = playing[loop.sound]?.isStopped == false
            if (isCalledFor && !isGoing) playing[loop.sound] = Playing(loop).also(minecraft.soundManager::play)
        }
    }

    /** How much of a loop is heard where the player stands: all of it in the open, some through a roof, little under water. */
    private fun openness(level: ClientLevel): Float {
        val player = Minecraft.getInstance().player ?: return MUFFLED
        if (player.isUnderWater) return UNDER_WATER
        return MUFFLED + (1.0f - MUFFLED) * Sampling.exposureAt(level, player.blockPosition())
    }

    private fun squared(value: Double) = (value * value).toFloat()

    /** One loop playing, as loud each tick as the end says, and stopping itself when the end says it is over. */
    private class Playing(private val loop: Loop) :
        AbstractTickableSoundInstance(loop.sound, SoundSource.AMBIENT, RandomSource.create()) {

        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val level = Minecraft.getInstance().level ?: return stop()
            val doom = Impacts.comingTo(level) ?: return stop()
            val loudness = loop.loudnessAt(doom, level, level.gameTime.toDouble()) ?: return stop()
            val underWater = if (Minecraft.getInstance().player?.isUnderWater == true) UNDER_WATER else 1.0f
            volume = loudness.coerceIn(SILENT, 1.0f) * underWater
        }
    }

    /** For a client leaving a server: every loop goes with it. */
    fun forget() {
        playing.clear()
    }

    private const val SILENT = 0.0f
    private const val MUFFLED = 0.3f
    private const val UNDER_WATER = 0.15f
    private const val APPROACH_LOUDEST = 0.8f

    /** How long before its entry the asteroid's drone begins, in ticks: the last two minutes, however long the warning. */
    private const val APPROACH_HEARD_FOR = 2_400.0
    private const val BURNING_LOUDEST = 1.0f
    private const val ASH_WALL_LOUDEST = 1.0f
    private const val WINTER_LOUDNESS = 0.5f

    /** How furious the blast is when its wind is at its loudest: well past a gale's whole. */
    private const val FURY_AT_ITS_LOUDEST = 2.0
}
