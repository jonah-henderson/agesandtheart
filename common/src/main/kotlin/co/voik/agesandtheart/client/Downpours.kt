package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.DelugePayload
import co.voik.ephemeris.Rgba
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * A deluge's rain, drawn heavier than any rain vanilla makes (design §5.2).
 *
 * Vanilla's rain is one gentle layer of streaks whatever the weather is doing, so a downpour that is
 * drowning the Age looked like a shower. Four things are laid over it, each graded by how heavy the deluge
 * is and — except the streaks, which are already only drawn where rain reaches — by how exposed you are:
 * the streaks fall twice as fast and twice as thick, the air greys and closes in, the ground splashes more,
 * and the rain is louder and lower.
 */
object Downpours {

    private var told: DelugePayload? = null

    /** How heavy the deluge falling in [level] is, nought to one, or null where no deluge is falling. */
    fun heavinessIn(level: ClientLevel): Double? {
        val heard = told ?: return null
        if (heard.age != level.dimension().identifier()) return null
        if (!heard.pouring || !level.isRaining) return null
        return heard.heaviness
    }

    fun remember(payload: DelugePayload) {
        told = payload
    }

    /** For a client leaving a server, whose Age ids mean nothing on the next. */
    fun forget() {
        told = null
    }

    /**
     * Thicken and hurry the rain [columns] vanilla has just laid out for this frame.
     *
     * Asked from `WeatherEffectRendererMixin` on the render thread. **Speed by a whole multiple on
     * purpose**: vanilla wraps each column's scroll at thirty-two texture lengths, and only a whole multiple
     * of a wrapped scroll wraps again where the texture repeats, so the streaks never jump.
     */
    @JvmStatic
    fun thicken(columns: MutableList<WeatherEffectRenderer.ColumnInstance>) {
        val level = Minecraft.getInstance().level ?: return
        heavinessIn(level) ?: return
        val laid = columns.toList()
        columns.clear()
        for (column in laid) {
            columns += column.copy(vOffset = column.vOffset * FASTER)
            // A second sheet through the same column, its streaks offset across and along so the two
            // never line up into one.
            columns += column.copy(uOffset = column.uOffset + HALF_ACROSS, vOffset = (column.vOffset + HALF_ALONG) * FASTER)
        }
    }

    private fun WeatherEffectRenderer.ColumnInstance.copy(
        uOffset: Float = uOffset(),
        vOffset: Float = vOffset(),
    ) = WeatherEffectRenderer.ColumnInstance(x(), z(), bottomY(), topY(), uOffset, vOffset, lightCoords())

    /**
     * The air greying and closing in under a deluge, laid over whatever the Age already paints.
     *
     * The same attribute layers `Storms` uses for a whiteout, and like them laid unconditionally and asking
     * about the rain at each sample, because this runs once when the level's attribute system is built.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, at, _ ->
            outInIt(level, at)?.let { ARGB.srgbLerp(greyingOf(it), was, STORM_GREY.rgb()) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, at, _ ->
            outInIt(level, at)?.let { ARGB.srgbLerp(greyingOf(it), was, STORM_GREY.rgb()) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { was * seenThrough(it) * BEGINS_AT } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { was * seenThrough(it) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { was * seenThrough(it) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { was * seenThrough(it) } ?: was
        }
        return layers
    }

    /**
     * How hard the deluge is on this sample, nought to one, or null where none reaches: its heaviness
     * lifted off the floor an ordinary one gives, times how much sky the place sees, times how far the rain
     * has come in.
     */
    private fun outInIt(level: ClientLevel, at: Vec3): Float? {
        val heaviness = heavinessIn(level) ?: return null
        val exposed = Blizzard.exposureAt(level, BlockPos.containing(at))
        if (exposed <= NONE) return null
        val raining = level.getRainLevel(WHOLE_TICK)
        return (ORDINARY_HARDNESS + (ALL_OF_IT - ORDINARY_HARDNESS) * heaviness.toFloat()) * exposed * raining
    }

    private fun greyingOf(hard: Float): Float = hard * MOST_GREYING

    /** The share of the ordinary view left at [hard] — never less than [NARROWEST], well short of a whiteout. */
    private fun seenThrough(hard: Float): Float = ALL_OF_IT - (ALL_OF_IT - NARROWEST) * hard

    /**
     * Splashes where the rain lands, over and above vanilla's own, and the rain's roar.
     *
     * Vanilla spawns about a fifth of a splash per column per tick in full rain; this adds as many again at
     * an ordinary deluge and several times that at the heaviest. Skipped where the ground is far above or
     * below you, as vanilla's are.
     */
    fun pour(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        val heaviness = heavinessIn(level) ?: return
        val raining = level.getRainLevel(WHOLE_TICK)
        val at = player.blockPosition()
        val random = level.random
        val splashes = (SPLASHES * (ALL_OF_IT + heaviness * MORE_SPLASHES) * raining).toInt()
        repeat(splashes) {
            val ground = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING,
                at.offset(random.nextInt(-AROUND, AROUND + 1), 0, random.nextInt(-AROUND, AROUND + 1)),
            ).below()
            if (ground.y !in at.y - WITHIN_HEIGHT..at.y + WITHIN_HEIGHT) return@repeat
            val state = level.getBlockState(ground)
            val x = random.nextDouble()
            val z = random.nextDouble()
            val top = maxOf(
                state.getCollisionShape(level, ground).max(Direction.Axis.Y, x, z),
                level.getFluidState(ground).getHeight(level, ground).toDouble(),
            )
            level.addParticle(ParticleTypes.RAIN, ground.x + x, ground.y + top, ground.z + z, 0.0, 0.0, 0.0)
        }
        roar(level, player.blockPosition(), heaviness, raining)
    }

    /** vanilla's own rain sound, played louder and lower than it plays it, muffled under cover. */
    private fun roar(level: ClientLevel, at: BlockPos, heaviness: Double, raining: Float) {
        if (level.random.nextInt(ROAR_EVERY) != 0) return
        val exposed = Blizzard.exposureAt(level, at)
        val loud = (QUIETEST_ROAR + (LOUDEST_ROAR - QUIETEST_ROAR) * heaviness.toFloat()) * raining
        val sound = if (exposed > HALF) SoundEvents.WEATHER_RAIN else SoundEvents.WEATHER_RAIN_ABOVE
        val volume = if (exposed > HALF) loud * exposed else loud * SHELTERED
        level.playLocalSound(at, sound, SoundSource.WEATHER, volume, LOW_PITCH, false)
    }

    private const val NONE = 0.0f
    private const val HALF = 0.5f
    private const val ALL_OF_IT = 1.0f
    private const val WHOLE_TICK = 1.0f

    /** How much faster the streaks fall than vanilla's. A whole number — see [thicken]. */
    private const val FASTER = 2.0f

    /** How far the second sheet is set across and along the first, in texture lengths. */
    private const val HALF_ACROSS = 0.5f
    private const val HALF_ALONG = 0.37f

    /** The air in a downpour: a cold, dark grey, the colour of rain with the sun behind a great deal of cloud. */
    private val STORM_GREY = Rgba.of(0xFF5A636Bu.toInt())

    /** How far toward [STORM_GREY] the heaviest deluge takes the air, fully exposed. */
    private const val MOST_GREYING = 0.7f

    /** How hard an ordinary deluge counts, before its heaviness adds the rest. */
    private const val ORDINARY_HARDNESS = 0.45f

    /** The share of the ordinary view the heaviest deluge leaves — about a quarter, where a blizzard leaves blocks. */
    private const val NARROWEST = 0.25f

    /** Where the grey begins, as a share of where it is total. */
    private const val BEGINS_AT = 0.2f

    /** Splashes a tick at an ordinary deluge, and how many times that the heaviest adds. */
    private const val SPLASHES = 45
    private const val MORE_SPLASHES = 3.0

    /** Vanilla's own reach for rain splashes, across and up and down. */
    private const val AROUND = 10
    private const val WITHIN_HEIGHT = 10

    /** One roar in this many ticks, and how loud at an ordinary and at the heaviest deluge. */
    private const val ROAR_EVERY = 3
    private const val QUIETEST_ROAR = 0.35f
    private const val LOUDEST_ROAR = 0.8f
    private const val SHELTERED = 0.2f
    private const val LOW_PITCH = 0.7f
}
