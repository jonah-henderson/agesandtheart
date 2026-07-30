package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/**
 * Persists which Ages exist and the [AgeRecipe] each was written from, plus a counter for minting ids,
 * so every one can be rebuilt on restart. Vanilla [SavedData] under the overworld's data storage.
 *
 * The recipe is the whole description: nothing about an Age's world lives anywhere else.
 */
class AgeSavedData : SavedData() {
    private val recipes: MutableMap<ResourceLocation, AgeRecipe> = linkedMapOf()
    private var counter: Int = 0

    /** Every Age that exists, in the order they were written. */
    val ages: Set<ResourceLocation> get() = recipes.keys

    fun add(id: ResourceLocation, recipe: AgeRecipe) {
        if (recipes.put(id, recipe) != recipe) setDirty()
    }

    fun remove(id: ResourceLocation) {
        if (recipes.remove(id) != null) setDirty()
    }

    /**
     * The recipe [id] was written from — defaulting, for an Age we have somehow lost the record of, to
     * the Spire preset that every Age was before recipes existed.
     */
    fun recipe(id: ResourceLocation): AgeRecipe = recipes[id] ?: AgeRecipe.of(AgePreset.SPIRE, id)

    /** Returns the next distinct Age index (1, 2, 3, …), persisting the advance. */
    fun allocateIndex(): Int {
        counter += 1
        setDirty()
        return counter
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val written = recipes.map { (id, recipe) -> WrittenAge(id, recipe) }
        val encoded = WrittenAge.LIST_CODEC.encodeStart(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("Could not write Age recipes: $problem") }
        tag.put(KEY_RECIPES, encoded)
        tag.putInt(KEY_COUNTER, counter)
        return tag
    }

    companion object {
        private const val NAME = "agesandtheart_ages"
        private const val KEY_RECIPES = "recipes"
        private const val KEY_COUNTER = "counter"

        // Ages written before recipes existed: an ordered list of ids, and a side table of
        // generator-kind strings. Read-only — nothing writes these any more.
        private const val KEY_LEGACY_AGES = "ages"
        private const val KEY_LEGACY_KINDS = "generators"

        /**
         * Ages carry no vanilla data-fixer type, so [restoreLegacy] does that job. The null is correct:
         * vanilla marks the parameter `@Nullable`, but the annotation is stripped from the artifact we
         * compile against, so Kotlin reads it as non-null.
         */
        @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        private fun factory(): Factory<AgeSavedData> =
            Factory({ AgeSavedData() }, { tag, _ -> load(tag) }, null)

        private fun load(tag: CompoundTag): AgeSavedData {
            val restored = AgeSavedData()
            if (tag.contains(KEY_RECIPES, Tag.TAG_LIST.toInt())) {
                WrittenAge.LIST_CODEC.parse(NbtOps.INSTANCE, tag.getList(KEY_RECIPES, Tag.TAG_COMPOUND.toInt()))
                    .resultOrPartial { problem -> Constants.LOG.error("Unreadable Age recipes: {}", problem) }
                    .ifPresent { written -> written.forEach { restored.recipes[it.id] = it.recipe } }
            } else {
                restoreLegacy(tag, restored)
            }
            restored.counter = tag.getInt(KEY_COUNTER)
            return restored
        }

        /**
         * Rebuilds recipes for Ages saved before they existed, so those worlds come back unchanged: the
         * kind string names the preset, and the seed is the one the backend derived from the id anyway.
         * A missing or unknown kind falls back to Spire, as the old `generatorKey` accessor did.
         */
        private fun restoreLegacy(tag: CompoundTag, restored: AgeSavedData) {
            val storedAges = tag.getList(KEY_LEGACY_AGES, Tag.TAG_STRING.toInt())
            if (storedAges.isEmpty()) return
            val storedKinds = tag.getCompound(KEY_LEGACY_KINDS)
            for (index in 0..<storedAges.size) {
                val id = ResourceLocation.tryParse(storedAges.getString(index)) ?: continue
                val preset = AgePreset.byKey(storedKinds.getString(id.toString())) ?: AgePreset.SPIRE
                restored.recipes[id] = AgeRecipe.of(preset, id)
            }
            Constants.LOG.info("Migrated {} Age(s) from generator kinds to recipes", restored.recipes.size)
        }

        /** Loads (or creates) the Age registry for this server, from the overworld's data storage. */
        fun get(server: MinecraftServer): AgeSavedData =
            server.overworld().dataStorage.computeIfAbsent(factory(), NAME)
    }
}

/**
 * One Age's row in the save file: which Age, and the recipe it was written from.
 *
 * A list of rows rather than a map keyed by id, because NBT compounds are unordered and the order Ages
 * were written in is worth keeping — it is what `/age list` shows.
 */
private data class WrittenAge(val id: ResourceLocation, val recipe: AgeRecipe) {
    companion object {
        val LIST_CODEC: Codec<List<WrittenAge>> = RecordCodecBuilder.create { instance ->
            instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(WrittenAge::id),
                // Inlined rather than nested, so a row reads as one flat record.
                AgeRecipe.MAP_CODEC.forGetter(WrittenAge::recipe),
            ).apply(instance, ::WrittenAge)
        }.listOf()
    }
}
