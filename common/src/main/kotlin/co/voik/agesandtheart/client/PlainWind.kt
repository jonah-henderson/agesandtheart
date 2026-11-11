package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelWind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import kotlin.random.Random

/**
 * A written wind heard where no gale or blizzard blows: the gale's open and sheltered pair made quiet, crossfaded by the
 * sky over the player as [Gales] crossfades its own, and louder the harder the wind was written. The sheltered one
 * fades away deep under cover, where no wind is heard at all, and both rise and fall together on a long swell and a
 * short flutter. A gale or a blizzard is heard instead while either blows.
 */
object PlainWind {

    fun heard(client: Minecraft) {
        val level = client.level
        if (level == null || client.player == null || !isHeardIn(level)) return stop(client)
        if (playing.isNotEmpty() && playing.none(Breeze::isStopped)) return
        stop(client)
        playing = listOf(Breeze(AgeContent.WIND_EXPOSED, whenOpen = true), Breeze(AgeContent.WIND_SHELTERED, whenOpen = false))
        playing.forEach(client.soundManager::play)
    }

    fun forget() {
        playing = emptyList()
        openness = Easing()
    }

    private fun isHeardIn(level: ClientLevel): Boolean = Gales.blowingIn(level) == null && LevelWind.of(level).strength > 0.0f

    private fun stop(client: Minecraft) {
        playing.forEach(client.soundManager::stop)
        playing = emptyList()
    }

    private var playing: List<Breeze> = emptyList()

    /** How open the sky is over the player, eased so a doorway is a fade rather than a cut. */
    private var openness = Easing()

    /** One of the pair, looping while the wind is written and nothing louder blows. */
    private class Breeze(soundEvent: SoundEvent, private val whenOpen: Boolean) :
        AbstractTickableSoundInstance(soundEvent, SoundSource.WEATHER, RandomSource.create()) {

        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level
            val player = client.player
            if (level == null || player == null || !isHeardIn(level)) return stop()
            val open = openness.towards(Sampling.exposureAt(level, player.blockPosition()))
            val loudness = QUIETEST + (LOUDEST - QUIETEST) * LevelWind.of(level).strength
            val reachesIn = Mth.smoothstep((open / HEARD_DOWN_TO).coerceIn(0.0f, 1.0f))
            val share = if (whenOpen) open else (ALL_OF_IT - open) * reachesIn
            volume = if (player.isUnderWater) SILENT else loudness * share * swellAt(level.gameTime)
        }
    }

    /**
     * How loud the wind is now against how loud it was written, the same for both of the pair: a long swell and a short
     * flutter, each a smooth wander between loudnesses drawn at random.
     */
    private fun swellAt(gameTime: Long): Float {
        val swell = wanderAt(gameTime, SWELL_TICKS, SWELL_SALT) * SWELL
        val flutter = wanderAt(gameTime, FLUTTER_TICKS, FLUTTER_SALT) * FLUTTER
        return (ALL_OF_IT + swell + flutter).coerceAtLeast(SILENT)
    }

    /**
     * From -1 to 1, eased between values drawn about every [spanTicks] — about, since its clock runs faster and slower
     * on a wander of its own three times as slow, so no two cycles are the same length.
     */
    private fun wanderAt(gameTime: Long, spanTicks: Long, salt: Long): Float {
        val warp = valueAt(gameTime.toDouble(), spanTicks * WARP_SLOWER, salt xor WARP_SALT) * spanTicks * WARP_REACH
        return valueAt(gameTime + warp, spanTicks, salt)
    }

    /** Smooth noise from -1 to 1 over [time], a value drawn at random every [spanTicks] and eased between them. */
    private fun valueAt(time: Double, spanTicks: Long, salt: Long): Float {
        val knot = Math.floorDiv(time.toLong(), spanTicks)
        val between = ((time - knot * spanTicks) / spanTicks).coerceIn(0.0, 1.0)
        fun drawnAt(at: Long) = Random(salt xor (at * KNOT_STRIDE)).nextFloat() * 2.0f - 1.0f
        return Mth.lerp(Mth.smoothstep(between.toFloat()), drawnAt(knot), drawnAt(knot + 1))
    }

    /** Sky light's share under which the sheltered wind fades away, to none at all with no sky light left. */
    private const val HEARD_DOWN_TO = 6.0f / 15.0f

    private const val SWELL = 0.35f
    private const val SWELL_TICKS = 900L
    private const val FLUTTER = 0.15f
    private const val FLUTTER_TICKS = 80L
    private const val SWELL_SALT = 0x5357_454CL
    private const val FLUTTER_SALT = 0x464C_5554L
    private const val KNOT_STRIDE = 0x9E37_79B9L
    private const val WARP_SALT = 0x5741_5250L
    private const val WARP_SLOWER = 3L

    /** How far a cycle's clock may run ahead or behind, in cycles. */
    private const val WARP_REACH = 0.75

    /** A steady breeze, barely there, and the hardest wind a book writes: both well under a gale's quietest. */
    private const val QUIETEST = 0.05f
    private const val LOUDEST = 0.3f
    private const val SILENT = 0.0f
    private const val ALL_OF_IT = 1.0f
}
