package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Biomes
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * The great halls' dark sky lifted where a hall lies open to the sky: a cleft cut down into them, where the
 * hall's biome still stands though nothing is overhead. There the sky overhead is the one outside — its colour,
 * and how far off the fog swallows it and the clouds — while the hall's own fog colour and distance stay, so
 * looking along the hall is still looking into the dark.
 *
 * How open is the sky light where the eye is, so it fades in over the light's own falloff from an opening; how
 * much of the hall is in the air is a share of the biomes round the eye, so climbing out does not snap. The
 * outside is the air at the top of the column, over every band an Age lays.
 *
 * **Laid outermost of the Age's painters.** The outside's air is read through all of them, so any laid after
 * this would be applied to it twice.
 */
object HallSkylight {

    private val GREAT_HALL = ResourceKey.create(Registries.BIOME, Biomes.GREAT_HALL_BIOME)

    /** Set while the outside's air is read, so reading it cannot come back here. */
    private val readingTheOutside = ThreadLocal.withInitial { false }

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, at, _ ->
            val opened = openingAt(level, at)
            if (opened <= 0.0f) was else Vector3f(was).lerp(outsideOf(level, EnvironmentAttributes.SKY_COLOR, at, was), opened)
        }
        for (distance in listOf(EnvironmentAttributes.SKY_FOG_END_DISTANCE, EnvironmentAttributes.CLOUD_FOG_END_DISTANCE)) {
            layers.addPositionalLayer(distance) { was, at, _ ->
                val opened = openingAt(level, at)
                if (opened <= 0.0f) was else Mth.lerp(opened, was, outsideOf(level, distance, at, was))
            }
        }
        return layers
    }

    /** How far the sky over [at] is the outside's, `0..1`: how open it is, by how much of a hall is about. */
    private fun openingAt(level: ClientLevel, at: Vec3): Float {
        if (readingTheOutside.get()) return 0.0f
        val eye = BlockPos.containing(at)
        val open = level.getBrightness(LightLayer.SKY, eye) / FULL_SKY_LIGHT
        if (open <= 0.0f) return 0.0f
        return open * hallShareAround(level, eye)
    }

    /** The share of the biomes in a small lattice round [eye] that are the great hall. */
    private fun hallShareAround(level: ClientLevel, eye: BlockPos): Float {
        fun isAHall(offset: BlockPos): Boolean {
            val sample = eye.offset(offset)
            return level.getNoiseBiome(sample.x shr QUART_SHIFT, sample.y shr QUART_SHIFT, sample.z shr QUART_SHIFT).`is`(GREAT_HALL)
        }
        return LATTICE.count(::isAHall).toFloat() / LATTICE.size
    }

    /** [attribute] at the top of [at]'s column, or [fallback] where the level will not say. */
    private fun <Value : Any> outsideOf(level: ClientLevel, attribute: EnvironmentAttribute<Value>, at: Vec3, fallback: Value): Value {
        readingTheOutside.set(true)
        return try {
            level.environmentAttributes().getValue(attribute, Vec3(at.x, (level.maxY - 1).toDouble(), at.z)) ?: fallback
        } finally {
            readingTheOutside.set(false)
        }
    }

    private const val FULL_SKY_LIGHT = 15.0f

    /** Biome cells are four blocks to a side. */
    private const val QUART_SHIFT = 2
    private const val CELL = 1 shl QUART_SHIFT

    /** Three cells to a side round the eye, a cell apart. */
    private val LATTICE: List<BlockPos> = (-1..1).flatMap { dx ->
        (-1..1).flatMap { dy -> (-1..1).map { dz -> BlockPos(dx * CELL, dy * CELL, dz * CELL) } }
    }
}
