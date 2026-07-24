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
 * Persists which Ages exist (and a counter for minting distinct Age ids) so they can be
 * re-registered on server restart.
 *
 * Runtime-dimension libraries (Fantasy included) do NOT auto-restore dynamic dimensions across
 * restarts — they only manage them while the server runs. So we track the set of Age ids
 * ourselves in vanilla [SavedData] (stored under the overworld's data storage), and replay them
 * via [Ages.reloadSaved] on boot.
 *
 * For the spike we persist the id + a monotonic counter; in v1 this grows to store each Age's
 * ordered symbols + seed.
 */
class AgeSavedData : SavedData() {
    val ages: MutableSet<ResourceLocation> = linkedSetOf()
    private var counter: Int = 0

    fun add(id: ResourceLocation) {
        if (ages.add(id)) setDirty()
    }

    /** Returns the next distinct Age index (1, 2, 3, …), persisting the advance. */
    fun allocateIndex(): Int {
        counter += 1
        setDirty()
        return counter
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val agesTag = ListTag()
        for (id in ages) agesTag.add(StringTag.valueOf(id.toString()))
        tag.put(KEY_AGES, agesTag)
        tag.putInt(KEY_COUNTER, counter)
        return tag
    }

    companion object {
        private const val NAME = "agesandtheart_ages"
        private const val KEY_AGES = "ages"
        private const val KEY_COUNTER = "counter"

        private fun factory(): Factory<AgeSavedData> =
            Factory({ AgeSavedData() }, { tag, _ -> load(tag) }, null)

        private fun load(tag: CompoundTag): AgeSavedData {
            val restored = AgeSavedData()
            val storedAges = tag.getList(KEY_AGES, Tag.TAG_STRING.toInt())
            for (index in 0..<storedAges.size) {
                ResourceLocation.tryParse(storedAges.getString(index))?.let { restored.ages.add(it) }
            }
            restored.counter = tag.getInt(KEY_COUNTER)
            return restored
        }

        /** Loads (or creates) the Age registry for this server, from the overworld's data storage. */
        fun get(server: MinecraftServer): AgeSavedData =
            server.overworld().dataStorage.computeIfAbsent(factory(), NAME)
    }
}
