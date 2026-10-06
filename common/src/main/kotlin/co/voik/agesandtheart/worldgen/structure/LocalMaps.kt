package co.voik.agesandtheart.worldgen.structure

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.Collections
import java.util.WeakHashMap

/**
 * An item frame a template marked with [TAG] is given a map of the ground around it the first time it
 * loads, drawn whole at once from the chunks already there. The map stays **live**: while a player is near,
 * it is redrawn a slice at a time, so it fills in the chunks that arrive later and keeps up with whatever
 * changes. Nothing here ever loads or generates a chunk; a column whose chunk is not loaded keeps whatever it
 * had (the biome outline, at first) until a later pass finds it.
 *
 * Marked frames side by side on one wall make one picture between them, left to right west to east, each
 * a neighbouring map. Marked in the world the template is saved from, before saving: `/tag
 * @e[type=item_frame,distance=..30] add agesandtheart.local_map`.
 */
object LocalMaps {
    const val TAG = "agesandtheart.local_map"

    /** Marks a map as one of these, so a frame holding one is drawn live again after it reloads. */
    private const val MARKER = "agesandtheart_local_map"

    /** 1:1, so a pixel is a block and a map covers 128 of them; [drawSlice] assumes it. */
    private const val SCALE: Byte = 0
    private const val BLOCKS_PER_MAP = 128
    private const val HALF_A_MAP = BLOCKS_PER_MAP / 2

    /** How near a player must be for a map to be redrawn, and how many of its north–south slices a tick. */
    private const val LIVE_RANGE = 96.0
    private const val SLICES_PER_TICK = 2

    private val waitingForAMap: MutableSet<ItemFrame> = Collections.newSetFromMap(WeakHashMap())
    private val live: MutableSet<ItemFrame> = Collections.newSetFromMap(WeakHashMap())

    fun loaded(frame: ItemFrame) {
        when {
            TAG in frame.entityTags() -> waitingForAMap += frame
            isLocalMap(frame.item) -> live += frame
        }
    }

    fun unloaded(frame: ItemFrame) {
        waitingForAMap -= frame
        live -= frame
    }

    /**
     * Frames are given their maps on the tick after they load rather than as they do, so that every marked
     * frame of one wall is in the level when the picture is divided between them.
     */
    fun tick(server: MinecraftServer) {
        if (waitingForAMap.isNotEmpty()) {
            val arrived = waitingForAMap.filterNot { it.isRemoved }
            waitingForAMap.clear()
            picturesAmong(arrived).forEach(::hangMaps)
        }
        redrawLiveMaps(server.tickCount)
    }

    /** [frames] in runs along one wall, each run left to right as somebody facing the wall sees it. */
    private fun picturesAmong(frames: List<ItemFrame>): List<List<ItemFrame>> {
        fun rightOf(frame: ItemFrame): Direction = frame.direction.counterClockWise
        fun alongTheWall(frame: ItemFrame): Int = frame.pos.get(rightOf(frame).axis) * rightOf(frame).axisDirection.step
        val walls = frames.groupBy { frame ->
            val acrossTheWall = frame.pos.get(frame.direction.axis)
            listOf(frame.level(), frame.direction, frame.pos.y, acrossTheWall)
        }
        return walls.values.flatMap { onOneWall ->
            val ordered = onOneWall.sortedBy(::alongTheWall)
            val runs = mutableListOf(mutableListOf(ordered.first()))
            for (frame in ordered.drop(1)) {
                val adjoinsTheLast = alongTheWall(frame) - alongTheWall(runs.last().last()) == 1
                if (adjoinsTheLast) runs.last() += frame else runs += mutableListOf(frame)
            }
            runs
        }
    }

    private fun hangMaps(picture: List<ItemFrame>) {
        val level = picture.first().level() as? ServerLevel ?: return
        val middleX = picture.sumOf { it.pos.x } / picture.size.toDouble()
        val middleZ = picture.sumOf { it.pos.z } / picture.size.toDouble()
        picture.forEachIndexed { index, frame ->
            val offset = (index - (picture.size - 1) / 2.0) * BLOCKS_PER_MAP
            val map = MapItem.create(level, (middleX + offset).toInt(), middleZ.toInt(), SCALE, true, false)
            MapItem.renderBiomePreviewMap(level, map)
            MapItem.getSavedData(map, level)?.let { data -> drawWhole(level, data) }
            map.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putBoolean(MARKER, true) }))
            frame.removeTag(TAG)
            frame.setItem(map, false)
            live += frame
        }
    }

    /** Every map with a player near redraws the same [SLICES_PER_TICK] slices, so one pass takes 64 ticks. */
    private fun redrawLiveMaps(tickCount: Int) {
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            val stillHoldsOne = !frame.isRemoved && isLocalMap(frame.item)
            if (!stillHoldsOne) {
                iterator.remove()
                continue
            }
            val level = frame.level() as? ServerLevel ?: continue
            val aPlayerIsNear = level.getNearestPlayer(frame, LIVE_RANGE) != null
            if (!aPlayerIsNear) continue
            val data = MapItem.getSavedData(frame.item, level) ?: continue
            repeat(SLICES_PER_TICK) { step ->
                val pixelX = Math.floorMod(tickCount * SLICES_PER_TICK + step, BLOCKS_PER_MAP)
                drawSlice(level, data, pixelX)
            }
        }
    }

    private fun drawWhole(level: ServerLevel, data: MapItemSavedData) {
        for (pixelX in 0 until BLOCKS_PER_MAP) drawSlice(level, data, pixelX)
    }

    /**
     * Vanilla's [MapItem.update] for one north–south line of pixels, at 1:1, with no player: no radius, no
     * stepping, no dark rim. It starts a pixel north of the map because each pixel is shaded against the
     * height of the one north of it.
     */
    private fun drawSlice(level: ServerLevel, data: MapItemSavedData, pixelX: Int) {
        if (level.dimension() != data.dimension) return
        val worldX = data.centerX - HALF_A_MAP + pixelX
        var northHeight: Int? = null
        for (pixelZ in -1 until BLOCKS_PER_MAP) {
            val worldZ = data.centerZ - HALF_A_MAP + pixelZ
            val surface = surfaceAt(level, worldX, worldZ)
            if (surface == null) {
                northHeight = null
                continue
            }
            val brightness = brightnessOf(surface, northHeight, pixelX, pixelZ)
            northHeight = surface.height
            val onTheMap = pixelZ >= 0
            if (onTheMap) data.updateColor(pixelX, pixelZ, surface.color.getPackedId(brightness))
        }
    }

    /** What the map sees of one column: its colour, the height it was found at, and how deep any water is. */
    private data class Surface(val color: MapColor, val height: Int, val waterDepth: Int)

    /** The column at [x], [z], or null when its chunk is not loaded — never loaded or generated to find out. */
    private fun surfaceAt(level: ServerLevel, x: Int, z: Int): Surface? {
        val chunk = level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
            ?: return null
        if (level.dimensionType().hasCeiling()) return ceilingSurface(level, x, z)
        val position = BlockPos.MutableBlockPos(x, 0, z)
        var height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1
        if (height <= level.minY) return Surface(Blocks.BEDROCK.defaultBlockState().getMapColor(level, position), height, 0)
        var state: BlockState
        do {
            position.setY(--height)
            state = chunk.getBlockState(position)
        } while (state.getMapColor(level, position) == MapColor.NONE && height > level.minY)
        var waterDepth = 0
        val underFluid = height > level.minY && !state.fluidState.isEmpty
        if (underFluid) {
            val below = BlockPos.MutableBlockPos().set(position)
            var belowY = height - 1
            do {
                below.setY(belowY--)
                waterDepth++
            } while (belowY > level.minY && !chunk.getBlockState(below).fluidState.isEmpty)
            state = stateShownForFluid(level, state, position)
        }
        return Surface(state.getMapColor(level, position), height, waterDepth)
    }

    /** Vanilla's speckle of dirt and stone for a dimension with a roof, where the ground cannot be seen. */
    private fun ceilingSurface(level: ServerLevel, x: Int, z: Int): Surface {
        val seed = x + z * 231871
        val noise = seed * seed * 31287121 + seed * 11
        val showsDirt = (noise shr 20 and 1) == 0
        val block = if (showsDirt) Blocks.DIRT else Blocks.STONE
        return Surface(block.defaultBlockState().getMapColor(level, BlockPos.ZERO), CEILING_HEIGHT, 0)
    }

    private const val CEILING_HEIGHT = 100

    /** A fluid that does not cover a sturdy top is drawn as the fluid, as vanilla does for waterlogged blocks. */
    private fun stateShownForFluid(level: ServerLevel, state: BlockState, position: BlockPos): BlockState {
        val fluid = state.fluidState
        val drawnAsTheFluid = !fluid.isEmpty && !state.isFaceSturdy(level, position, Direction.UP)
        return if (drawnAsTheFluid) fluid.createLegacyBlock() else state
    }

    /**
     * Vanilla's shading at 1:1: water darkens with depth, land brightens climbing north to south and darkens
     * falling, and both alternate in a checkerboard. A pixel whose northern neighbour is unknown is shaded as
     * level with it, and corrected on a later pass.
     */
    private fun brightnessOf(surface: Surface, northHeight: Int?, pixelX: Int, pixelZ: Int): MapColor.Brightness {
        val checker = (pixelX + pixelZ) and 1
        if (surface.color == MapColor.WATER) {
            val depthShade = surface.waterDepth * WATER_SHADE_PER_BLOCK + checker * WATER_CHECKER_SHADE
            return when {
                depthShade < WATER_BRIGHT_BELOW -> MapColor.Brightness.HIGH
                depthShade > WATER_DARK_ABOVE -> MapColor.Brightness.LOW
                else -> MapColor.Brightness.NORMAL
            }
        }
        val climb = surface.height - (northHeight ?: surface.height)
        val slopeShade = climb * LAND_SHADE_PER_BLOCK + (checker - 0.5) * LAND_CHECKER_SHADE
        return when {
            slopeShade > LAND_BRIGHT_ABOVE -> MapColor.Brightness.HIGH
            slopeShade < -LAND_BRIGHT_ABOVE -> MapColor.Brightness.LOW
            else -> MapColor.Brightness.NORMAL
        }
    }

    private const val WATER_SHADE_PER_BLOCK = 0.1
    private const val WATER_CHECKER_SHADE = 0.2
    private const val WATER_BRIGHT_BELOW = 0.5
    private const val WATER_DARK_ABOVE = 0.9

    /** Vanilla's `4 / (scale + 4)` with a scale of 1. */
    private const val LAND_SHADE_PER_BLOCK = 0.8
    private const val LAND_CHECKER_SHADE = 0.4
    private const val LAND_BRIGHT_ABOVE = 0.6

    private fun isLocalMap(stack: ItemStack): Boolean =
        stack.`is`(Items.FILLED_MAP) && stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.contains(MARKER) == true

    /** Called when the server stops, since a frame belongs to a level that is about to go. */
    fun serverStopped() {
        waitingForAMap.clear()
        live.clear()
    }
}
