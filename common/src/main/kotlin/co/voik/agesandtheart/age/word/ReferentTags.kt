package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.language.Word
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import kotlin.jvm.optionals.getOrNull

/**
 * Whether the thing [word] names carries the tag [tag], asked only of the registries the word is an entry
 * of ([Word.referentRegistries]) — so a creature or a placed feature is asked too, and a tag on a biome
 * never reaches a block that happens to share its id. Registries whose tags are not bound carry none.
 */
internal fun HolderLookup.Provider.carriesTagNamed(word: Word, tag: Identifier): Boolean =
    word.referentRegistries.any { registry -> carriesIn(registry, word.id, tag) }

private fun HolderLookup.Provider.carriesIn(registry: ResourceKey<out Registry<*>>, id: Identifier, tag: Identifier): Boolean {
    // A registry key read off an aspect is star-projected, and a tag key has to name its element type.
    @Suppress("UNCHECKED_CAST")
    val typed = registry as ResourceKey<out Registry<Any>>
    val holder = lookup(typed).getOrNull()?.get(ResourceKey.create(typed, id))?.getOrNull() ?: return false
    return try {
        holder.`is`(TagKey.create(typed, tag))
    } catch (tagsAreNotBound: IllegalStateException) {
        false
    }
}
