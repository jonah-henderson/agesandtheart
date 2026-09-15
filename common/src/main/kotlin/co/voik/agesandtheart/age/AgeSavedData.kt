package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType

/**
 * Persists which Ages exist and the [AgeRecipe] each was written from, plus a counter for minting ids,
 * so every one can be rebuilt on restart. Vanilla [SavedData] under the overworld's data storage.
 *
 * The recipe is the whole description: nothing about an Age's world lives anywhere else.
 *
 * **Saved through a codec**, which is all vanilla offers now — `SavedData.save(CompoundTag)` and its
 * `Factory` are gone, replaced by a [SavedDataType] carrying a `Codec`. That suits this class, whose
 * contents were already codec-shaped and were being hand-written into NBT around them.
 */
class AgeSavedData() : SavedData() {
    private val recipes: MutableMap<Identifier, AgeRecipe> = linkedMapOf()
    private var counter: Int = 0

    /**
     * How long anybody has spent in each Age, in ticks — **the one counted number §5.4 allows** (design
     * §5.2, settled 2026-09-10), and the only thing the mod keeps beside a recipe that is not the recipe.
     *
     * It exists because the deluge must advance *only while somebody is in the Age*, and there is nothing
     * to derive that from: an Age has no clock of its own, and vanilla's one "time spent here" counter is
     * per chunk. The licence is deliberately narrow — one counter for one bounded, resolving phenomenon,
     * not a ledger — and every register that can still pass §5.4's legibility test must.
     *
     * Absent for an Age nobody has stood in, which reads as zero. Written only for Ages that have a number
     * worth keeping, so an ordinary save gains nothing.
     */
    private val presence: MutableMap<Identifier, Long> = linkedMapOf()

    private constructor(written: List<WrittenAge>, counter: Int, presence: List<TimeSpent>) : this() {
        written.forEach { recipes[it.id] = it.recipe }
        this.counter = counter
        presence.forEach { this.presence[it.id] = it.ticks }
    }

    /** Every Age that exists, in the order they were written. */
    val ages: Set<Identifier> get() = recipes.keys

    fun add(id: Identifier, recipe: AgeRecipe) {
        if (recipes.put(id, recipe) != recipe) setDirty()
    }

    fun remove(id: Identifier) {
        if (recipes.remove(id) != null) setDirty()
        // A binned Age's clock goes with it, or an id minted again later would inherit somebody else's
        // drowning.
        if (presence.remove(id) != null) setDirty()
    }

    /** The recipe [id] was written from, or null where no Age of that id exists. */
    fun recipe(id: Identifier): AgeRecipe? = recipes[id]

    /** How many ticks somebody has been standing in [id], counting no faster for a crowd. */
    fun presenceIn(id: Identifier): Long = presence[id] ?: 0L

    /**
     * One more tick of somebody being in [id].
     *
     * **"Any player present", never the sum of them** (Jonah, 2026-09-12). Counting per player would make
     * a busy server drown an Age four times faster than a quiet one, and would take away the thing a
     * writer can read off their own book — how long until the sea gets where it is going.
     *
     * Marked dirty every tick it advances, which is what `setDirty` is for; the save itself is written on
     * the world's own schedule rather than on ours.
     */
    fun spendATickIn(id: Identifier) {
        presence[id] = presenceIn(id) + 1L
        setDirty()
    }

    /** Returns the next distinct Age index (1, 2, 3, …), persisting the advance. */
    fun allocateIndex(): Int {
        counter += 1
        setDirty()
        return counter
    }

    companion object {
        private const val NAME = "agesandtheart_ages"

        private val CODEC: Codec<AgeSavedData> = RecordCodecBuilder.create { instance ->
            instance.group(
                WrittenAge.LIST_CODEC.fieldOf("recipes")
                    .forGetter { saved -> saved.recipes.map { (id, recipe) -> WrittenAge(id, recipe) } },
                Codec.INT.optionalFieldOf("counter", 0).forGetter { it.counter },
                TimeSpent.LIST_CODEC.optionalFieldOf("presence", emptyList())
                    .forGetter { saved -> saved.presence.map { (id, ticks) -> TimeSpent(id, ticks) } },
            ).apply(instance, ::AgeSavedData)
        }

        /**
         * Ages carry no data-fixer type of their own. [DataFixTypes.LEVEL] is named because the record
         * demands one and this data lives beside the level's; nothing here is ever fixed up, since an Age
         * is rebuilt from its recipe rather than migrated.
         */
        private val TYPE: SavedDataType<AgeSavedData> =
            SavedDataType(Identifier.withDefaultNamespace(NAME), ::AgeSavedData, CODEC, DataFixTypes.LEVEL)

        /** Loads (or creates) the Age registry for this server, from the overworld's data storage. */
        fun get(server: MinecraftServer): AgeSavedData = server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}

/**
 * One Age's row in the save file: which Age, and the recipe it was written from.
 *
 * A list of rows rather than a map keyed by id, because NBT compounds are unordered and the order Ages
 * were written in is worth keeping — it is what `/age list` shows.
 */
private data class WrittenAge(val id: Identifier, val recipe: AgeRecipe) {
    companion object {
        val LIST_CODEC: Codec<List<WrittenAge>> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(WrittenAge::id),
                // Inlined rather than nested, so a row reads as one flat record.
                AgeRecipe.MAP_CODEC.forGetter(WrittenAge::recipe),
            ).apply(instance, ::WrittenAge)
        }.listOf()
    }
}

/**
 * One Age's clock: how long anybody has stood in it, in ticks.
 *
 * A row rather than a map entry for [WrittenAge]'s reason — and separate from that row rather than a field
 * on it, because the recipe is the Age's description and this is emphatically not. Anything reading the
 * save should be able to see at a glance which half is which.
 */
private data class TimeSpent(val id: Identifier, val ticks: Long) {
    companion object {
        val LIST_CODEC: Codec<List<TimeSpent>> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(TimeSpent::id),
                Codec.LONG.fieldOf("ticks").forGetter(TimeSpent::ticks),
            ).apply(instance, ::TimeSpent)
        }.listOf()
    }
}
