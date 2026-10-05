package co.voik.agesandtheart.worldgen.structure

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.tags.PaintingVariantTags
import net.minecraft.world.entity.decoration.painting.Painting

/**
 * A painting a template marked with [TAG] takes a random placeable variant of its own size the first time
 * it loads, so each copy of a structure hangs different pictures. The tag is removed once it has, and a
 * painting a player hangs carries no tag, so nothing else is ever repainted.
 *
 * Marked in the world the template is saved from, before saving: `/tag @e[type=painting,distance=..30] add
 * agesandtheart.random_painting`.
 */
object RandomPaintings {
    const val TAG = "agesandtheart.random_painting"

    fun loaded(painting: Painting) {
        if (TAG !in painting.entityTags()) return
        painting.removeTag(TAG)
        val current = painting.variant.value()
        val sameSize = painting.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
            .getTagOrEmpty(PaintingVariantTags.PLACEABLE)
            .filter { it.value().width() == current.width() && it.value().height() == current.height() }
        if (sameSize.isEmpty()) return
        // The level's random, not the painting's: two paintings a template places together draw alike from theirs.
        painting.setComponent(DataComponents.PAINTING_VARIANT, sameSize[painting.level().random.nextInt(sameSize.size)])
    }
}
