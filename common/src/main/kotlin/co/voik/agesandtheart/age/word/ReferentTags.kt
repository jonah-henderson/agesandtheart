package co.voik.agesandtheart.age.word

import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey

/**
 * Whether the thing [id] names carries the tag [tag]. Every registry a derived word can be read off is
 * asked, since the id alone does not say which it came from.
 */
internal fun RegistryAccess.carriesTagNamed(id: Identifier, tag: Identifier): Boolean =
    carriesIn(Registries.BLOCK, id, tag) ||
        carriesIn(Registries.BIOME, id, tag) ||
        carriesIn(Registries.STRUCTURE_SET, id, tag)

private fun <T : Any> RegistryAccess.carriesIn(
    registry: ResourceKey<out Registry<T>>,
    id: Identifier,
    tag: Identifier,
): Boolean {
    val holder = lookup(registry).orElse(null)?.get(id)?.orElse(null) ?: return false
    return holder.`is`(TagKey.create(registry, tag))
}
