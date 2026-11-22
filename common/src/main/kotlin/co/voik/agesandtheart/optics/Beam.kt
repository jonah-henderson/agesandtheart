package co.voik.agesandtheart.optics

import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BeaconBeamBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * One straight run of a beam, in one colour: from [shiftedStart] blocks along [direction] past the centre of [from],
 * for [length] blocks. A [diffuse] one is a collector's unfocused light rather than a laser.
 */
data class BeamSegment(
    val from: BlockPos,
    val direction: Direction,
    val shiftedStart: Float,
    val length: Float,
    val colour: Int,
    val diffuse: Boolean = false,
) {
    companion object {
        val CODEC: Codec<BeamSegment> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("from").forGetter(BeamSegment::from),
                Direction.CODEC.fieldOf("direction").forGetter(BeamSegment::direction),
                Codec.FLOAT.fieldOf("shifted_start").forGetter(BeamSegment::shiftedStart),
                Codec.FLOAT.fieldOf("length").forGetter(BeamSegment::length),
                Codec.INT.fieldOf("colour").forGetter(BeamSegment::colour),
                Codec.BOOL.optionalFieldOf("diffuse", false).forGetter(BeamSegment::diffuse),
            ).apply(instance, ::BeamSegment)
        }
    }
}

/** A beam arriving at the block at [pos], travelling [travelling], as [light]. */
data class BeamHit(val pos: BlockPos, val travelling: Direction, val light: Light)

/** Where a beam went, and the block it ended against, if it ended against one. */
data class BeamPath(val segments: List<BeamSegment>, val hit: BeamHit?) {
    companion object {
        val DARK = BeamPath(emptyList(), null)
    }
}

/** A block entity sending out a beam, kept on both sides to be drawn. */
interface ShinesABeam {
    val path: BeamPath
}

/** A block entity a beam can end at and give its light to. */
interface TakesBeams {
    /** A beam from [source] reached it travelling [travelling], as [light], to count until the game time [until]. */
    fun takeBeam(source: BlockPos, travelling: Direction, light: Light, until: Long)
}

/**
 * Following a beam: on through air, what has no collision and glass, set to the colour of stained glass it passes,
 * turned by reflectors, and stopped by anything else, which it gives its light to if that block takes beams.
 */
object Beam {
    /** What a beam passes as it passes glass, besides what has no collision at all. */
    val LETS_BEAMS_THROUGH: TagKey<Block> = TagKey.create(Registries.BLOCK, "lets_beams_through".location())

    /** What stops a beam though it would otherwise pass: tinted glass, which is for keeping light out. */
    val STOPS_BEAMS: TagKey<Block> = TagKey.create(Registries.BLOCK, "stops_beams".location())

    /** How often every beam is followed again, in ticks, and how long what it reached keeps its light. */
    const val LOOKS_AGAIN_EVERY = 10L
    const val HELD_FOR = 12L

    private const val MOST_TURNS = 16
    private const val HALF = 0.5f

    fun isTimeToLook(level: Level): Boolean = level.gameTime % LOOKS_AGAIN_EVERY == 0L

    /** The beam leaving [source] toward [facing] as [light], reaching [range] blocks. */
    fun trace(level: Level, source: BlockPos, facing: Direction, light: Light, range: Int, diffuse: Boolean): BeamPath {
        val segments = mutableListOf<BeamSegment>()
        var direction = facing
        var segmentFrom = source
        var segmentShift = HALF
        var lightNow = light
        var at = source
        var turns = 0

        fun close(endBlock: BlockPos, endShift: Float) {
            val blocksBetween = (endBlock.x - segmentFrom.x) * direction.stepX +
                (endBlock.y - segmentFrom.y) * direction.stepY +
                (endBlock.z - segmentFrom.z) * direction.stepZ
            val length = blocksBetween + endShift - segmentShift
            if (length > 0f) segments += BeamSegment(segmentFrom, direction, segmentShift, length, ARGB.opaque(lightNow.colour.tint), diffuse)
        }

        fun endingAgainst(block: BlockPos): BeamPath {
            close(block, -HALF)
            return BeamPath(segments, BeamHit(block, direction, lightNow))
        }

        repeat(range) {
            val next = at.relative(direction)
            if (!level.isLoaded(next)) {
                close(at, HALF)
                return BeamPath(segments, null)
            }
            val state = level.getBlockState(next)
            val block = state.block
            when {
                block is ReflectorBlock -> {
                    val turnedTo = ReflectorBlock.turn(state, direction)
                    if (turnedTo == null || turns >= MOST_TURNS) return endingAgainst(next)
                    close(next, 0f)
                    turns++
                    direction = turnedTo
                    segmentFrom = next
                    segmentShift = 0f
                }
                block is BeaconBeamBlock && passes(state, level, next) -> {
                    val stained = Light.nearestRimeColour(block.color.textureDiffuseColor)
                    if (stained != lightNow.colour) {
                        close(next, -HALF)
                        lightNow = lightNow.copy(colour = stained)
                        segmentFrom = next
                        segmentShift = -HALF
                    }
                }
                !passes(state, level, next) -> return endingAgainst(next)
            }
            at = next
        }
        close(at, HALF)
        return BeamPath(segments, null)
    }

    /** Gives the light of [path]'s beam from [source] to whatever it ended against, if that takes beams. */
    fun deliver(level: ServerLevel, source: BlockPos, path: BeamPath) {
        val hit = path.hit ?: return
        val taker = level.getBlockEntity(hit.pos) as? TakesBeams ?: return
        taker.takeBeam(source, hit.travelling, hit.light, level.gameTime + HELD_FOR)
    }

    private fun passes(state: BlockState, level: Level, pos: BlockPos): Boolean {
        if (state.`is`(STOPS_BEAMS)) return false
        return state.`is`(LETS_BEAMS_THROUGH) || state.getCollisionShape(level, pos).isEmpty
    }
}

/** The beams reaching a block, each from its own source and each lasting until its time runs out. */
class IncomingBeams {
    private val arriving = mutableMapOf<BlockPos, Pair<Light, Long>>()

    fun take(source: BlockPos, light: Light, until: Long) {
        arriving[source] = light to until
    }

    /** All the light still arriving at [now], added together, or null where none is. */
    fun lightAt(now: Long): Light? {
        arriving.values.removeIf { (_, until) -> until < now }
        return Light.together(arriving.values.map { (light, _) -> light })
    }
}
