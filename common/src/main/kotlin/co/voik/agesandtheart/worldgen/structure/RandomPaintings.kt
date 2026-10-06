package co.voik.agesandtheart.worldgen.structure

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.tags.PaintingVariantTags
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.phys.Vec3

/**
 * A painting a template marked with [TAG] takes a random placeable variant of its own size the first time
 * it loads, so each copy of a structure hangs different pictures. The tag is removed once it has, and a
 * painting a player hangs carries no tag, so nothing else is ever repainted.
 *
 * Marked in the world the template is saved from, before saving: `/tag @e[type=painting,distance=..30] add
 * agesandtheart.random_painting`.
 *
 * It is also hung back where the template had it. Placing a template snaps each entity to its position, and
 * a painting takes its anchor from the block that position falls in; an even side puts the position on a
 * block boundary, so the anchor lands a block up, and a block along the wall wherever its left points the
 * positive way. On those rotations it hangs half off its wall and drops at its first survival check.
 */
object RandomPaintings {
    const val TAG = "agesandtheart.random_painting"

    fun loaded(painting: Painting) {
        if (TAG !in painting.entityTags()) return
        painting.removeTag(TAG)
        val current = painting.variant.value()
        painting.setPos(Vec3.atCenterOf(anchorAsSaved(painting.pos, painting.direction, current.width(), current.height())))
        val sameSize = painting.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
            .getTagOrEmpty(PaintingVariantTags.PLACEABLE)
            .filter { it.value().width() == current.width() && it.value().height() == current.height() }
        if (sameSize.isEmpty()) return
        // The level's random, not the painting's: two paintings a template places together draw alike from theirs.
        painting.setComponent(DataComponents.PAINTING_VARIANT, sameSize[painting.level().random.nextInt(sameSize.size)])
    }

    /** The anchor a template saved, from the one its placement snapped to. */
    private fun anchorAsSaved(snapped: BlockPos, facing: Direction, width: Int, height: Int): BlockPos {
        val left = facing.counterClockWise
        val wentUp = height % 2 == 0
        val wentLeft = width % 2 == 0 && left.axisDirection == Direction.AxisDirection.POSITIVE
        return snapped
            .let { if (wentUp) it.below() else it }
            .let { if (wentLeft) it.relative(left.opposite) else it }
    }
}
