package co.voik.agesandtheart.age

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/**
 * Persists which Ages exist so they can be re-registered on server restart.
 *
 * Runtime-dimension libraries (Fantasy included) do NOT auto-restore dynamic dimensions across
 * restarts — they only manage them while the server runs. So we track the set of Age ids
 * ourselves in vanilla [SavedData] (stored under the overworld's data storage), and replay them
 * via [AgeManager.reloadSavedAges] on boot.
 *
 * For the spike we only persist the id; the generation recipe is fixed (see [AgeGen]). In v1
 * this grows to store each Age's ordered symbols + seed.
 */
class AgeSavedData : SavedData() {
    val ages: MutableSet<ResourceLocation> = linkedSetOf()

    fun add(id: ResourceLocation) {
        if (ages.add(id)) setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        for (id in ages) list.add(StringTag.valueOf(id.toString()))
        tag.put(KEY_AGES, list)
        return tag
    }

    companion object {
        private const val NAME = "agesandtheart_ages"
        private const val KEY_AGES = "ages"

        private fun factory(): Factory<AgeSavedData> =
            Factory({ AgeSavedData() }, { tag, _ -> load(tag) }, null)

        private fun load(tag: CompoundTag): AgeSavedData {
            val data = AgeSavedData()
            val list = tag.getList(KEY_AGES, Tag.TAG_STRING.toInt())
            for (i in 0 until list.size) {
                ResourceLocation.tryParse(list.getString(i))?.let { data.ages.add(it) }
            }
            return data
        }

        /** Loads (or creates) the Age registry for this server, from the overworld's data storage. */
        fun get(server: MinecraftServer): AgeSavedData =
            server.overworld().dataStorage.computeIfAbsent(factory(), NAME)
    }
}
