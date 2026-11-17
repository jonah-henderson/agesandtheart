package co.voik.agesandtheart.imaging

import com.mojang.serialization.Codec
import net.minecraft.core.UUIDUtil
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * When each picture was last seen: fetched by a client, or loaded on a wall, in a carousel or in a player's inventory.
 * What [PictureSweep] reads to choose which pictures shrink first when they outgrow their space. Held to the hour,
 * so the save is not marked changed every time a picture is looked at.
 */
class PicturesSeen private constructor(seen: Map<UUID, Long>) : SavedData() {

    private val lastSeen = ConcurrentHashMap(seen)

    constructor() : this(emptyMap())

    fun saw(id: UUID) {
        val now = System.currentTimeMillis()
        val before = lastSeen[id]
        if (before != null && now - before < HELD_TO_MILLIS) return
        lastSeen[id] = now
        setDirty()
    }

    /** Every picture's last sighting, as it stands now. */
    fun snapshot(): Map<UUID, Long> = HashMap(lastSeen)

    companion object {
        private const val HELD_TO_MILLIS = 60L * 60L * 1000L

        private val CODEC: Codec<PicturesSeen> = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
            .xmap(::PicturesSeen) { it.lastSeen }

        /** Pictures carry no data-fixer type of their own; [DataFixTypes.LEVEL] is the one saved data is filed under. */
        private val TYPE: SavedDataType<PicturesSeen> = SavedDataType(Imaging.id("pictures_seen"), ::PicturesSeen, CODEC, DataFixTypes.LEVEL)

        fun of(server: MinecraftServer): PicturesSeen = server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
