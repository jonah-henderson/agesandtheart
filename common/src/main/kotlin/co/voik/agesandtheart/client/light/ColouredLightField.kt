package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.BlockLightFlood
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Each coloured source's own light, flooded through the world as vanilla floods block light, and the field the
 * painter reads it from.
 *
 * **The flood is vanilla's propagation, step for step** ([BlockLightFlood]), so a source's colour reaches exactly
 * where its light does: round corners, through gaps, and never through a wall into a room something else lights.
 *
 * Floods are worked out on the client thread, as chunks arrive and blocks change, and only for the level the
 * client stands in. What the chunk mesher reads is [fields], one immutable [SectionField] per section, replaced
 * whole when a flood through it changes and then marked for a rebuild.
 */
object ColouredLightField {

    /** One source's light: the level it casts at each block it reaches, by section. */
    private class Flood(val colour: Int, val bySection: Map<Long, Long2ByteOpenHashMap>)

    /** Every source's flood, by where the source stands — client thread only. */
    private val floods = HashMap<Long, Flood>()

    /** Which sources' floods reach into each section — client thread only. */
    private val reaching = HashMap<Long, MutableSet<Long>>()

    /** What the mesher reads: per section, the coloured light at each block. */
    private val fields = ConcurrentHashMap<Long, SectionField>()

    /**
     * The coloured light at every block of one section: the brightest any source casts there, and every source's
     * colour summed, each weighted by how bright its light is there — light adds, so the sum is the mixture.
     */
    class SectionField(val brightest: ByteArray, val red: FloatArray, val green: FloatArray, val blue: FloatArray)

    /** One corner's coloured light, as [cornerOf] reads it. */
    data class Corner(val brightest: Int, val red: Float, val green: Float, val blue: Float)

    fun holdsAnything(): Boolean = fields.isNotEmpty()

    /** [at] now casts [colour], with its light level in the alpha, or casts nothing — its flood redone or dropped. */
    fun sourceChanged(level: Level, at: BlockPos, colour: Int?) {
        if (!isTheClientsLevel(level)) return
        val key = at.asLong()
        if (colour == null) replaceFlood(key, null) else replaceFlood(key, flooded(level, at, colour))
    }

    /** A block that lets light through differently changed at [at], so every flood that could reach it is redone. */
    fun blocksChanged(level: Level, at: BlockPos) {
        if (!isTheClientsLevel(level)) return
        refloodWithin(level, at.x, at.y, at.z, FARTHEST_REACH)
    }

    /** A chunk arrived: its sources are flooded, and every flood near it redone, since light can now enter it. */
    fun chunkArrived(level: Level, chunkX: Int, chunkZ: Int, sources: Map<BlockPos, Int>) {
        if (!isTheClientsLevel(level)) return
        val middleX = SectionPos.sectionToBlockCoord(chunkX) + HALF_A_CHUNK
        val middleZ = SectionPos.sectionToBlockCoord(chunkZ) + HALF_A_CHUNK
        refloodWithin(level, middleX, null, middleZ, FARTHEST_REACH + HALF_A_CHUNK)
        for ((at, colour) in sources) replaceFlood(at.asLong(), flooded(level, at, colour))
    }

    /** A chunk left: the floods of the sources it held go with it. */
    fun chunkLeft(level: Level, chunkX: Int, chunkZ: Int) {
        if (!isTheClientsLevel(level)) return
        val leaving = floods.keys.filter { source ->
            SectionPos.blockToSectionCoord(BlockPos.getX(source)) == chunkX &&
                SectionPos.blockToSectionCoord(BlockPos.getZ(source)) == chunkZ
        }
        for (source in leaving) replaceFlood(source, null)
    }

    fun forget() {
        floods.clear()
        reaching.clear()
        fields.clear()
    }

    /**
     * The coloured light at the corner of a face at vertex ([x], [y], [z]), facing [facing]: read from the four
     * blocks in front of the face that meet at the corner, as smooth lighting reads the light there — the
     * brightest of them, and their colours summed. Null where none of them has any.
     */
    fun cornerOf(x: Int, y: Int, z: Int, facing: Direction): Corner? {
        var brightest = 0
        var red = 0f
        var green = 0f
        var blue = 0f
        val frontX = if (facing.axis == Direction.Axis.X) frontOf(x, facing.stepX) else x
        val frontY = if (facing.axis == Direction.Axis.Y) frontOf(y, facing.stepY) else y
        val frontZ = if (facing.axis == Direction.Axis.Z) frontOf(z, facing.stepZ) else z
        // The two axes in the face's plane, each with the block on either side of the vertex.
        val stepsX = if (facing.axis == Direction.Axis.X) ONLY_THE_FRONT else EITHER_SIDE
        val stepsY = if (facing.axis == Direction.Axis.Y) ONLY_THE_FRONT else EITHER_SIDE
        val stepsZ = if (facing.axis == Direction.Axis.Z) ONLY_THE_FRONT else EITHER_SIDE
        for (stepX in stepsX) for (stepY in stepsY) for (stepZ in stepsZ) {
            val blockX = frontX - stepX
            val blockY = frontY - stepY
            val blockZ = frontZ - stepZ
            val field = fields[SectionPos.asLong(blockX shr SECTION_BITS, blockY shr SECTION_BITS, blockZ shr SECTION_BITS)] ?: continue
            val index = indexIn(blockX, blockY, blockZ)
            brightest = maxOf(brightest, field.brightest[index].toInt())
            red += field.red[index]
            green += field.green[index]
            blue += field.blue[index]
        }
        return if (brightest == 0) null else Corner(brightest, red, green, blue)
    }

    /**
     * The block in front of a face along its own axis, from a vertex on that axis: the vertex is the face's plane,
     * so the block in front starts there going out and ends there coming back.
     */
    private fun frontOf(vertex: Int, step: Int): Int = if (step > 0) vertex else vertex - 1

    /** How bright a light level is, as the lightmap draws it — `lightmap.fsh`'s `level / (4 - 3 * level)`. */
    fun brightnessOf(level: Float): Float {
        val share = (level / FULLEST).coerceIn(0f, 1f)
        return share / (BRIGHTNESS_CURVE - (BRIGHTNESS_CURVE - 1f) * share)
    }

    /**
     * Whether [level] is the one the client stands in, **starting over when that has changed**: the floods of the
     * level left behind would otherwise stay, since its chunks unload after the client has already moved on.
     */
    private fun isTheClientsLevel(level: Level): Boolean {
        if (level !== Minecraft.getInstance().level) return false
        if (floodedIn.get() !== level) {
            forget()
            floodedIn = WeakReference(level)
        }
        return true
    }

    /** The level the floods are of. Weak, so a level the client has left is not held for them. */
    private var floodedIn = WeakReference<Level>(null)

    private fun refloodWithin(level: Level, x: Int, y: Int?, z: Int, reach: Int) {
        val near = floods.keys.filter { source ->
            val acrossX = kotlin.math.abs(BlockPos.getX(source) - x)
            val acrossZ = kotlin.math.abs(BlockPos.getZ(source) - z)
            val acrossY = if (y == null) 0 else kotlin.math.abs(BlockPos.getY(source) - y)
            acrossX <= reach && acrossZ <= reach && acrossY <= reach
        }
        for (source in near) {
            val colour = floods[source]?.colour ?: continue
            replaceFlood(source, flooded(level, BlockPos.of(source), colour))
        }
    }

    /** [colour]'s light flooded out from [at] at the level in its alpha, as vanilla floods it ([BlockLightFlood]). */
    private fun flooded(level: Level, at: BlockPos, colour: Int): Flood {
        val levels = BlockLightFlood.from(level, at, ARGB.alpha(colour))
        val bySection = HashMap<Long, Long2ByteOpenHashMap>()
        for (entry in levels.long2ByteEntrySet()) {
            val position = entry.longKey
            val section = SectionPos.asLong(
                BlockPos.getX(position) shr SECTION_BITS,
                BlockPos.getY(position) shr SECTION_BITS,
                BlockPos.getZ(position) shr SECTION_BITS,
            )
            bySection.getOrPut(section) { Long2ByteOpenHashMap() }.put(position, entry.byteValue)
        }
        return Flood(colour, bySection)
    }

    /** Puts [flood] in [source]'s place, rebuilding and redrawing every section the change touched. */
    private fun replaceFlood(source: Long, flood: Flood?) {
        val before = floods[source]
        if (before != null && flood != null && before.colour == flood.colour && before.bySection == flood.bySection) return
        if (before == null && flood == null) return
        val touched = (before?.bySection?.keys.orEmpty() + flood?.bySection?.keys.orEmpty()).toSet()
        before?.bySection?.keys?.forEach { section -> reaching[section]?.remove(source) }
        if (flood == null) floods.remove(source) else floods[source] = flood
        flood?.bySection?.keys?.forEach { section -> reaching.getOrPut(section) { HashSet() }.add(source) }
        for (section in touched) rebuild(section)
    }

    private fun rebuild(section: Long) {
        val sources = reaching[section].orEmpty()
        if (sources.isEmpty()) {
            reaching.remove(section)
            fields.remove(section)
        } else {
            fields[section] = fieldOf(section, sources)
        }
        Minecraft.getInstance().level?.setSectionDirtyWithNeighbors(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section))
    }

    private fun fieldOf(section: Long, sources: Set<Long>): SectionField {
        val brightest = ByteArray(BLOCKS_IN_A_SECTION)
        val red = FloatArray(BLOCKS_IN_A_SECTION)
        val green = FloatArray(BLOCKS_IN_A_SECTION)
        val blue = FloatArray(BLOCKS_IN_A_SECTION)
        for (source in sources) {
            val flood = floods[source] ?: continue
            val levels = flood.bySection[section] ?: continue
            for (entry in levels.long2ByteEntrySet()) {
                val position = entry.longKey
                val index = indexIn(BlockPos.getX(position), BlockPos.getY(position), BlockPos.getZ(position))
                val lit = entry.byteValue
                if (lit > brightest[index]) brightest[index] = lit
                val weight = brightnessOf(lit.toFloat())
                red[index] += ARGB.red(flood.colour) / FULL_CHANNEL * weight
                green[index] += ARGB.green(flood.colour) / FULL_CHANNEL * weight
                blue[index] += ARGB.blue(flood.colour) / FULL_CHANNEL * weight
            }
        }
        return SectionField(brightest, red, green, blue)
    }

    private fun indexIn(x: Int, y: Int, z: Int): Int =
        ((y and SECTION_MASK) shl (2 * SECTION_BITS)) or ((z and SECTION_MASK) shl SECTION_BITS) or (x and SECTION_MASK)

    /** The farthest any block light carries, and so the farthest a change can be from a flood it moves. */
    private const val FARTHEST_REACH = 15

    /** Of the blocks meeting at a vertex along an axis in a face's plane: the one ending there, and the one starting there. */
    private val EITHER_SIDE = intArrayOf(1, 0)
    private val ONLY_THE_FRONT = intArrayOf(0)

    private const val HALF_A_CHUNK = 8
    private const val SECTION_BITS = 4
    private const val SECTION_MASK = 15
    private const val BLOCKS_IN_A_SECTION = 4096
    private const val FULLEST = 15f
    private const val BRIGHTNESS_CURVE = 4f
    private const val FULL_CHANNEL = 255f
}
