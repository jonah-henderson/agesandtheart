package co.voik.agesandtheart.worldgen.structure

import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.component.CustomData
import java.util.Collections
import java.util.WeakHashMap

/**
 * An item frame a template marked with [TAG] is given a map of the ground around it the first time it
 * loads, and the map stays **live**: while a player is near, it is drawn as though they were carrying it,
 * so it fills in and keeps up with whatever changes.
 *
 * Marked frames side by side on one wall make one picture between them, left to right west to east, each
 * a neighbouring map. Marked in the world the template is saved from, before saving: `/tag
 * @e[type=item_frame,distance=..30] add agesandtheart.local_map`.
 */
object LocalMaps {
    const val TAG = "agesandtheart.local_map"

    /** Marks a map as one of these, so a frame holding one is drawn live again after it reloads. */
    private const val MARKER = "agesandtheart_local_map"

    /** 1:1, so a map covers 128 blocks and a player standing in the structure draws all of it. */
    private const val SCALE: Byte = 0
    private const val BLOCKS_PER_MAP = 128

    /** How near a player must be for a map to be drawn, and how often it is. */
    private const val LIVE_RANGE = 96.0
    private const val TICKS_BETWEEN_DRAWS = 5

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
        if (server.tickCount % TICKS_BETWEEN_DRAWS == 0) drawLiveMaps()
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
            map.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putBoolean(MARKER, true) }))
            frame.removeTag(TAG)
            frame.setItem(map, false)
            live += frame
        }
    }

    private fun drawLiveMaps() {
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            val stillHoldsOne = !frame.isRemoved && isLocalMap(frame.item)
            if (!stillHoldsOne) {
                iterator.remove()
                continue
            }
            val level = frame.level() as? ServerLevel ?: continue
            val player = level.getNearestPlayer(frame, LIVE_RANGE) ?: continue
            val data = MapItem.getSavedData(frame.item, level) ?: continue
            (Items.FILLED_MAP as MapItem).update(level, player, data)
        }
    }

    private fun isLocalMap(stack: ItemStack): Boolean =
        stack.`is`(Items.FILLED_MAP) && stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.contains(MARKER) == true

    /** Called when the server stops, since a frame belongs to a level that is about to go. */
    fun serverStopped() {
        waitingForAMap.clear()
        live.clear()
    }
}
