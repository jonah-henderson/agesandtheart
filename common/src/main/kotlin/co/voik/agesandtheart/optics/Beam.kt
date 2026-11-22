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

/** A beam arriving at the block at [pos], travelling [travelling], as [light]; [diffuse] where it is unfocused. */
data class BeamHit(val pos: BlockPos, val travelling: Direction, val light: Light, val diffuse: Boolean)

/** Where a beam went, branching at splitters, and the blocks its branches ended against. */
data class BeamPath(val segments: List<BeamSegment>, val hits: List<BeamHit>) {
    companion object {
        val DARK = BeamPath(emptyList(), emptyList())
    }
}

/** A block entity sending out a beam, kept on both sides to be drawn. */
interface ShinesABeam {
    val path: BeamPath
}

/** A block entity a beam can end at and give its light to. */
interface TakesBeams {
    /**
     * A beam from [source] reached it travelling [travelling], as [light], to count until the game time [until];
     * [diffuse] where it is unfocused.
     */
    fun takeBeam(source: BlockPos, travelling: Direction, light: Light, diffuse: Boolean, until: Long)
}

/**
 * Following a beam: on through air, what has no collision and glass, set to the colour of stained glass it passes,
 * turned by reflectors, divided by splitters, and stopped by anything else, which it gives its light to if that block
 * takes beams.
 */
object Beam {
    /** What a beam passes as it passes glass, besides what has no collision at all. */
    val LETS_BEAMS_THROUGH: TagKey<Block> = TagKey.create(Registries.BLOCK, "lets_beams_through".location())

    /** What stops a beam though it would otherwise pass: tinted glass, which is for keeping light out. */
    val STOPS_BEAMS: TagKey<Block> = TagKey.create(Registries.BLOCK, "stops_beams".location())

    /**
     * How long what a beam reached keeps its light, in ticks. Every beam is followed again each tick, and one tick more
     * covers a block that ticks before the source feeding it.
     */
    const val HELD_FOR = 1L

    private const val MOST_TURNS = 16

    /** How many times one beam may divide, however its splitters are laid, loops among them included. */
    private const val MOST_BRANCHES = 64
    private const val HALF = 0.5f

    /** The beam leaving [source] toward [facing] as [light], reaching [range] blocks along each of its branches. */
    fun trace(level: Level, source: BlockPos, facing: Direction, light: Light, range: Int, diffuse: Boolean): BeamPath {
        val tracing = Tracing(level, diffuse)
        tracing.follow(source, facing, HALF, light, range, MOST_TURNS)
        return BeamPath(tracing.segments, tracing.hits)
    }

    /** Gives the light of each of [path]'s branches from [source] to whatever it ended against, if that takes beams. */
    fun deliver(level: ServerLevel, source: BlockPos, path: BeamPath) {
        for (hit in path.hits) {
            val taker = level.getBlockEntity(hit.pos) as? TakesBeams ?: continue
            taker.takeBeam(source, hit.travelling, hit.light, hit.diffuse, level.gameTime + HELD_FOR)
        }
    }

    /** One beam being followed, and every branch it has put down so far. */
    private class Tracing(val level: Level, val diffuse: Boolean) {
        val segments = mutableListOf<BeamSegment>()
        val hits = mutableListOf<BeamHit>()
        private var branchesLeft = MOST_BRANCHES

        /**
         * One branch, from [shiftedStart] blocks past the centre of [from] toward [facing], until it ends, runs out of
         * [range], or divides at a splitter into two that are followed in turn.
         */
        fun follow(from: BlockPos, facing: Direction, shiftedStart: Float, light: Light, range: Int, turns: Int) {
            var direction = facing
            var segmentFrom = from
            var segmentShift = shiftedStart
            var lightNow = light
            var at = from
            var turnsLeft = turns

            fun close(endBlock: BlockPos, endShift: Float) {
                val blocksBetween = (endBlock.x - segmentFrom.x) * direction.stepX +
                    (endBlock.y - segmentFrom.y) * direction.stepY +
                    (endBlock.z - segmentFrom.z) * direction.stepZ
                val length = blocksBetween + endShift - segmentShift
                if (length > 0f) segments += BeamSegment(segmentFrom, direction, segmentShift, length, ARGB.opaque(lightNow.colour.tint), diffuse)
            }

            fun endAgainst(block: BlockPos) {
                close(block, -HALF)
                hits += BeamHit(block, direction, lightNow, diffuse)
            }

            for (step in 1..range) {
                val next = at.relative(direction)
                if (!level.isLoaded(next)) break
                val state = level.getBlockState(next)
                val block = state.block
                when {
                    block is ReflectorBlock -> {
                        val turnedTo = ReflectorBlock.turn(state, direction)
                        if (turnedTo == null || turnsLeft <= 0) return endAgainst(next)
                        close(next, 0f)
                        turnsLeft--
                        direction = turnedTo
                        segmentFrom = next
                        segmentShift = 0f
                    }
                    block is BeamSplitterBlock -> {
                        val turnedTo = BeamSplitterBlock.turnedOut(state, direction)
                        if (turnedTo == null || turnsLeft <= 0 || branchesLeft <= 0) return endAgainst(next)
                        close(next, 0f)
                        branchesLeft--
                        val half = lightNow.sharedBetween(2)
                        if (half.intensity <= 0) return
                        follow(next, direction, 0f, half, range - step, turnsLeft - 1)
                        follow(next, turnedTo, 0f, half, range - step, turnsLeft - 1)
                        return
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
                    !passes(state, level, next) -> return endAgainst(next)
                }
                at = next
            }
            close(at, HALF)
        }
    }

    private fun passes(state: BlockState, level: Level, pos: BlockPos): Boolean {
        if (state.`is`(STOPS_BEAMS)) return false
        return state.`is`(LETS_BEAMS_THROUGH) || state.getCollisionShape(level, pos).isEmpty
    }
}

/**
 * The beams reaching a block, each from its own source and direction (one beam split and brought back together arrives
 * twice), and each lasting until its time runs out.
 */
class IncomingBeams {
    private data class Arrival(val light: Light, val diffuse: Boolean, val until: Long)

    private val arriving = mutableMapOf<Pair<BlockPos, Direction>, Arrival>()

    fun take(source: BlockPos, travelling: Direction, light: Light, diffuse: Boolean, until: Long) {
        arriving[source to travelling] = Arrival(light, diffuse, until)
    }

    /** All the light still arriving at [now], added together, or null where none is. */
    fun lightAt(now: Long): Light? {
        forgetPast(now)
        return Light.together(arriving.values.map { it.light })
    }

    /** Whether every beam still arriving at [now] is unfocused. */
    fun isAllDiffuseAt(now: Long): Boolean {
        forgetPast(now)
        return arriving.values.all { it.diffuse }
    }

    private fun forgetPast(now: Long) {
        arriving.values.removeIf { it.until < now }
    }
}
