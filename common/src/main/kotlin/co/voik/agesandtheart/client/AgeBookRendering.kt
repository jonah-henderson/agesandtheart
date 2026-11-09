package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.content.AgeComponents
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.sprite.SpriteGetter
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * What a book of ours looks like from outside: how it is bound, its texture, the banner it wears, and the name on it
 * in its colour, glowing or not.
 */
data class BookLook(
    val binding: BookBinding,
    val texture: Identifier,
    val cover: BookCover.Worn?,
    val title: String?,
    val titleColour: Int,
    val titleGlows: Boolean,
) {
    companion object {
        fun of(book: ItemStack): BookLook = BookLook(
            BookBinding.of(book),
            AgeBookModel.textureOf(book),
            BookCover.Worn.on(book),
            titleOf(book),
            book.get(AgeComponents.TITLE_COLOUR)?.textColor ?: BookTitle.GILT,
            book.getOrDefault(AgeComponents.TITLE_GLOWS, false),
        )

        /** What the book is called: a name given it at an anvil, else the Age it describes or the place it links to. */
        private fun titleOf(book: ItemStack): String? {
            val named = book.get(DataComponents.CUSTOM_NAME)?.string
            val called = named ?: book.get(AgeComponents.BOOK_TITLE) ?: book.get(AgeComponents.LINK_TARGET)?.name
            return called?.takeIf { it.isNotBlank() }
        }
    }
}

/** A book of ours drawn as it looks — the model, the banner over its binding, the name on it — wherever it lies. */
class AgeBookRendering(private val sprites: SpriteGetter) {

    private val models = BookBinding.entries.associateWith { AgeBookModel(AgeBookModel.createLayer(it).bakeRoot(), it) }

    fun modelFor(binding: BookBinding): AgeBookModel = models.getValue(binding)

    fun submit(
        look: BookLook,
        state: AgeBookModel.State,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int = OverlayTexture.NO_OVERLAY,
        outlineColor: Int = NO_OUTLINE,
    ) {
        val model = modelFor(look.binding)
        collector.submitModel(model, state, poseStack, look.texture, lightCoords, overlayCoords, outlineColor)
        model.setupAnim(state)
        look.cover?.let { BookCover.submit(model, it, poseStack, collector, sprites, lightCoords) }
        look.title?.let { BookTitle.submit(model, it, BookTitle.Ink(look.titleColour, look.titleGlows), poseStack, collector, lightCoords) }
    }

    private companion object {
        const val NO_OUTLINE = 0
    }
}
