package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.MotesPayload
import co.voik.agesandtheart.age.aspect.Drift
import co.voik.ephemeris.Rgba
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.data.AtlasIds
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Motes no air should hold, hung around whoever is in an Age whose instability bought them — `UncannyMotes`
 * says so, and how far each dial went. How they look and whether they rise or hang still is drawn here from the
 * Age's seed, so every client in one Age sees the same.
 *
 * Particles added straight to the engine, as the surf's are, rather than a registered type: nothing but this
 * ever makes one.
 */
object StrangeMotes {

    private var told: MotesPayload? = null

    fun remember(payload: MotesPayload) {
        told = payload
    }

    fun forget() {
        told = null
    }

    /** A few more motes around the camera, if the Age the player stands in has them. */
    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        if (minecraft.isPaused) return
        val heard = told?.takeIf { it.present && it.age == level.dimension().identifier() } ?: return
        val eye = minecraft.player?.eyePosition ?: return
        val kind = Kind.of(heard.seed)
        val size = (SMALLEST + heard.size * (LARGEST - SMALLEST)).toFloat()
        val spawned = SPARSEST + heard.density * (THICKEST - SPARSEST)
        val random = level.random
        val whole = spawned.toInt() + if (random.nextDouble() < spawned % 1.0) 1 else 0
        repeat(whole) {
            val x = eye.x + (random.nextDouble() * 2.0 - 1.0) * REACH
            val y = eye.y + (random.nextDouble() * 2.0 - 1.0) * REACH_UP_AND_DOWN
            val z = eye.z + (random.nextDouble() * 2.0 - 1.0) * REACH
            if (!level.getBlockState(BlockPos.containing(x, y, z)).isAir) return@repeat
            minecraft.particleEngine.add(Mote(level, x, y, z, kind, size))
        }
    }

    /** What an Age's motes are like, drawn once from its seed. */
    private data class Kind(val colour: Rgba, val alpha: Float, val glows: Boolean, val rises: Boolean) {
        companion object {
            fun of(seed: Long): Kind {
                val random = Random(seed xor MOTES_SALT)
                val rises = random.nextBoolean()
                return when (random.nextInt(LOOKS)) {
                    PALE -> Kind(Rgba(0.86f, 0.86f, 0.82f), 0.8f, glows = false, rises)
                    GLOWING -> Kind(Rgba(1.0f, 0.96f, 0.82f), 0.55f, glows = true, rises)
                    DARK -> Kind(Rgba(0.07f, 0.07f, 0.08f), 0.8f, glows = false, rises)
                    else -> Kind(Drift.paletteFor(seed).first(), 0.75f, glows = false, rises)
                }
            }

            private const val LOOKS = 4
            private const val PALE = 0
            private const val GLOWING = 1
            private const val DARK = 2
            private const val MOTES_SALT = 0x3A0F_E5C2_71D8_4B69L
        }
    }

    /** One mote: fades in, holds still or climbs slowly with a little sway, and fades out. */
    private class Mote(
        level: ClientLevel,
        x: Double,
        y: Double,
        z: Double,
        private val kind: Kind,
        size: Float,
    ) : SingleQuadParticle(level, x, y, z, sprite()) {

        private val swayPhase = random.nextDouble() * Mth.TWO_PI

        init {
            lifetime = LIFETIME_LEAST + random.nextInt(LIFETIME_SPREAD)
            quadSize = size * (SIZE_SPREAD_LEAST + random.nextFloat() * SIZE_SPREAD)
            hasPhysics = false
            gravity = 0.0f
            xd = 0.0
            yd = 0.0
            zd = 0.0
            setColor(kind.colour.red, kind.colour.green, kind.colour.blue)
            setAlpha(0.0f)
        }

        override fun tick() {
            xo = x
            yo = y
            zo = z
            if (age++ >= lifetime) {
                remove()
                return
            }
            if (kind.rises) {
                val turn = swayPhase + age * SWAY_RATE
                move(sin(turn) * SWAY, RISING, cos(turn) * SWAY)
            }
            setAlpha(kind.alpha * visibilityAt(age.toFloat() / lifetime))
        }

        private fun visibilityAt(through: Float): Float = when {
            through < FADES_IN_UNTIL -> through / FADES_IN_UNTIL
            through > FADES_OUT_FROM -> (1.0f - through) / (1.0f - FADES_OUT_FROM)
            else -> 1.0f
        }

        override fun getLightCoords(a: Float): Int = if (kind.glows) FULL_BRIGHT else super.getLightCoords(a)

        override fun getLayer(): Layer = Layer.TRANSLUCENT
    }

    private fun sprite() =
        Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(SPECK)

    private val SPECK: Identifier = Identifier.withDefaultNamespace("generic_0")

    /** How many motes are added a tick at the first step and bought in full — about a hundred, then a few hundred, alive. */
    private const val SPARSEST = 0.8
    private const val THICKEST = 4.0

    /** A mote's size at the first step and bought in full. */
    private const val SMALLEST = 0.035
    private const val LARGEST = 0.11

    private const val REACH = 20.0
    private const val REACH_UP_AND_DOWN = 10.0

    private const val LIFETIME_LEAST = 100
    private const val LIFETIME_SPREAD = 80
    private const val SIZE_SPREAD_LEAST = 0.7f
    private const val SIZE_SPREAD = 0.6f
    private const val FADES_IN_UNTIL = 0.15f
    private const val FADES_OUT_FROM = 0.75f
    private const val RISING = 0.02
    private const val SWAY = 0.006
    private const val SWAY_RATE = 0.05

    /** Block light and sky light both at fifteen, packed as `LightCoordsUtil` reads them. */
    private const val FULL_BRIGHT = 0xF000F0
}
