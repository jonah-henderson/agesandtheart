package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BeaconBeamBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * One straight run of a beam, in one colour: from [shiftedStart] blocks along [direction] past the centre of [from],
 * for [length] blocks.
 */
data class BeamSegment(val from: BlockPos, val direction: Direction, val shiftedStart: Float, val length: Float, val colour: Int) {
    companion object {
        val CODEC: Codec<BeamSegment> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("from").forGetter(BeamSegment::from),
                Direction.CODEC.fieldOf("direction").forGetter(BeamSegment::direction),
                Codec.FLOAT.fieldOf("shifted_start").forGetter(BeamSegment::shiftedStart),
                Codec.FLOAT.fieldOf("length").forGetter(BeamSegment::length),
                Codec.INT.fieldOf("colour").forGetter(BeamSegment::colour),
            ).apply(instance, ::BeamSegment)
        }
    }
}

/** Where a beam went, and the receivers it reached, with the colour it reached each in. */
data class BeamPath(val segments: List<BeamSegment>, val reached: List<Pair<BlockPos, Int>>) {
    companion object {
        val DARK = BeamPath(emptyList(), emptyList())
    }
}

/**
 * Following a beam out of an emitter: on through air, what has no collision and glass, coloured by stained glass as a
 * beacon's is, turned by reflectors, stopped by anything else, and ending at a receiver.
 */
object Beam {
    /** What a beam passes as it passes glass, besides what has no collision at all. */
    val LETS_BEAMS_THROUGH: TagKey<Block> = TagKey.create(Registries.BLOCK, "lets_beams_through".location())

    /** What stops a beam though it would otherwise pass: tinted glass, which is for keeping light out. */
    val STOPS_BEAMS: TagKey<Block> = TagKey.create(Registries.BLOCK, "stops_beams".location())

    const val WHITE: Int = -0x1
    private const val MOST_TURNS = 16
    private const val HALF = 0.5f

    /** The beam an emitter at [emitter] facing [facing] sends [range] blocks, starting in [colour]. */
    fun trace(level: Level, emitter: BlockPos, facing: Direction, colour: Int, range: Int): BeamPath {
        val segments = mutableListOf<BeamSegment>()
        val reached = mutableListOf<Pair<BlockPos, Int>>()
        var direction = facing
        var segmentFrom = emitter
        var segmentShift = HALF
        var colourNow = colour
        var stainedYet = colour != WHITE
        var at = emitter
        var turns = 0

        fun close(endBlock: BlockPos, endShift: Float) {
            val blocksBetween = (endBlock.x - segmentFrom.x) * direction.stepX +
                (endBlock.y - segmentFrom.y) * direction.stepY +
                (endBlock.z - segmentFrom.z) * direction.stepZ
            val length = blocksBetween + endShift - segmentShift
            if (length > 0f) segments += BeamSegment(segmentFrom, direction, segmentShift, length, colourNow)
        }

        repeat(range) {
            val next = at.relative(direction)
            if (!level.isLoaded(next)) {
                close(at, HALF)
                return BeamPath(segments, reached)
            }
            val state = level.getBlockState(next)
            val block = state.block
            when {
                block is BeamReceiverBlock -> {
                    close(next, -HALF)
                    reached += next to colourNow
                    return BeamPath(segments, reached)
                }
                block is ReflectorBlock -> {
                    val turnedTo = ReflectorBlock.turn(state, direction)
                    if (turnedTo == null || turns >= MOST_TURNS) {
                        close(next, -HALF)
                        return BeamPath(segments, reached)
                    }
                    close(next, 0f)
                    turns++
                    direction = turnedTo
                    segmentFrom = next
                    segmentShift = 0f
                }
                block is BeaconBeamBlock && passes(state, level, next) -> {
                    val glass = block.color.textureDiffuseColor
                    val stained = if (stainedYet) ARGB.average(colourNow, glass) else glass
                    if (stained != colourNow) {
                        close(next, -HALF)
                        colourNow = stained
                        segmentFrom = next
                        segmentShift = -HALF
                    }
                    stainedYet = true
                }
                !passes(state, level, next) -> {
                    close(next, -HALF)
                    return BeamPath(segments, reached)
                }
            }
            at = next
        }
        close(at, HALF)
        return BeamPath(segments, reached)
    }

    private fun passes(state: BlockState, level: Level, pos: BlockPos): Boolean {
        if (state.`is`(STOPS_BEAMS)) return false
        return state.`is`(LETS_BEAMS_THROUGH) || state.getCollisionShape(level, pos).isEmpty
    }

    /** The rime colour nearest [colour], which is how a receiver with a crystal in it judges a beam. */
    fun nearestRimeColour(colour: Int): RimeColour = RimeColour.entries.minBy { rime ->
        val red = ARGB.red(colour) - ARGB.red(rime.tint)
        val green = ARGB.green(colour) - ARGB.green(rime.tint)
        val blue = ARGB.blue(colour) - ARGB.blue(rime.tint)
        red * red + green * green + blue * blue
    }
}
