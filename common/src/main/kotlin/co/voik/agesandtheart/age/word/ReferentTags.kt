package co.voik.agesandtheart.age.word

import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey

/**
 * Whether the thing [word] names carries the tag [tag], asked only of the registries the word is an entry
 * of ([Word.referentRegistries]) — so a creature or a placed feature is asked too, and a tag on a biome
 * never reaches a block that happens to share its id.
 */
internal fun RegistryAccess.carriesTagNamed(word: Word, tag: Identifier): Boolean =
    word.referentRegistries.any { registry -> carriesIn(registry, word.id, tag) }

private fun RegistryAccess.carriesIn(registry: ResourceKey<out Registry<*>>, id: Identifier, tag: Identifier): Boolean {
    // A registry key read off an aspect is star-projected, and a tag key has to name its element type.
    @Suppress("UNCHECKED_CAST")
    val typed = registry as ResourceKey<out Registry<Any>>
    val holder = lookup(typed).orElse(null)?.get(id)?.orElse(null) ?: return false
    return holder.`is`(TagKey.create(typed, tag))
}
