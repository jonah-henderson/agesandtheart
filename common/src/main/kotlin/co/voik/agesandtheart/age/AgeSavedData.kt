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

    private constructor(written: List<WrittenAge>, counter: Int) : this() {
        written.forEach { recipes[it.id] = it.recipe }
        this.counter = counter
    }

    /** Every Age that exists, in the order they were written. */
    val ages: Set<Identifier> get() = recipes.keys

    fun add(id: Identifier, recipe: AgeRecipe) {
        if (recipes.put(id, recipe) != recipe) setDirty()
    }

    fun remove(id: Identifier) {
        if (recipes.remove(id) != null) setDirty()
    }

    /**
     * The recipe [id] was written from — defaulting, for an Age we have somehow lost the record of, to
     * the Spire preset that every Age was before recipes existed.
     */
    fun recipe(id: Identifier): AgeRecipe = recipes[id] ?: AgeRecipe.of(AgePreset.SPIRE, id)

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
