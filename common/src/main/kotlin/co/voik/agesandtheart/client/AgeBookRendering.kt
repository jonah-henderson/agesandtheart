package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.LecternBooks
import net.minecraft.client.model.`object`.book.BookModel
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer
import co.voik.agesandtheart.content.AgeComponents
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.sprite.SpriteGetter
import net.minecraft.core.component.DataComponents
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemStack

/**
 * What a book looks like from outside. One of ours: how it is bound, the colour its binding is dyed, whether its
 * right-hand page is written on, the banner it wears, and the name on it in its colour, glowing or not. Any other — an
 * enchanted book, a book and quill — is [isOurs] false, and drawn as vanilla's enchanting table book.
 */
data class BookLook(
    val isOurs: Boolean,
    val binding: BookBinding,
    val bindingColour: Int,
    val writtenOnTheRight: Boolean,
    val cover: BookCover.Worn?,
    val title: String?,
    val titleColour: Int,
    val titleGlows: Boolean,
) {
    companion object {
        fun of(book: ItemStack): BookLook = BookLook(
            LecternBooks.isOurs(book),
            BookBinding.of(book),
            book.get(DataComponents.DYED_COLOR)?.let { ARGB.opaque(it.rgb) } ?: UNDYED,
            AgeBookModel.writtenOnTheRight(book),
            BookCover.Worn.on(book),
            titleOf(book),
            book.get(AgeComponents.TITLE_COLOUR)?.textColor ?: BookTitle.GILT,
            book.getOrDefault(AgeComponents.TITLE_GLOWS, false),
        )

        /**
         * The binding undyed: vanilla's book's dark brown where the greyscale is lightest, (119, 78, 34) over the
         * texture's grey of 137, and darker with it elsewhere.
         */
        private val UNDYED = ARGB.color(221, 145, 63)

        /** What the book is called: a name given it at an anvil, else the Age it describes or the place it links to. */
        private fun titleOf(book: ItemStack): String? {
            val named = book.get(DataComponents.CUSTOM_NAME)?.string
            val called = named ?: book.get(AgeComponents.BOOK_TITLE) ?: book.get(AgeComponents.LINK_TARGET)?.name
            return called?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * A book drawn as it looks — the model, the banner over its binding, the name on it — wherever it lies; and a book not
 * ours as vanilla's enchanting table book (Jonah, 2026-11-19), posed the same, since our model is laid out as vanilla's.
 */
class AgeBookRendering(private val sprites: SpriteGetter) {

    private val models = BookBinding.entries.associateWith { AgeBookModel(AgeBookModel.createLayer(it).bakeRoot(), it) }

    private val vanillaBook = BookModel(BookModel.createBodyLayer().bakeRoot())

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
        if (!look.isOurs) {
            // Vanilla's book opens by its boards' angle, in radians; ours by a share of a quarter turn.
            val vanillaState = BookModel.State(state.openness * QUARTER_TURN_RADIANS, VANILLA_FIRST_LEAF, VANILLA_SECOND_LEAF)
            collector.submitModel(
                vanillaBook, vanillaState, poseStack, lightCoords, overlayCoords, UNTINTED, EnchantTableRenderer.BOOK_TEXTURE, sprites, outlineColor,
            )
            return
        }
        val model = modelFor(look.binding)
        val renderType = model.renderType(AgeBookModel.TEXTURE)
        val pages = state.copy(drawn = AgeBookModel.Drawn.PAGES, writtenOnTheRight = look.writtenOnTheRight)
        val binding = state.copy(drawn = AgeBookModel.Drawn.BINDING)
        collector.submitModel(model, pages, poseStack, renderType, lightCoords, overlayCoords, UNTINTED, null, outlineColor)
        collector.submitModel(model, binding, poseStack, renderType, lightCoords, overlayCoords, look.bindingColour, null, outlineColor)
        model.setupAnim(state)
        look.cover?.let { BookCover.submit(model, it, poseStack, collector, sprites, lightCoords) }
        look.title?.let { BookTitle.submit(model, it, BookTitle.Ink(look.titleColour, look.titleGlows), poseStack, collector, lightCoords) }
    }

    private companion object {
        const val NO_OUTLINE = 0
        const val UNTINTED = -1
        const val QUARTER_TURN_RADIANS = (Math.PI / 2).toFloat()

        /** Where vanilla's lectern holds its book's two loose leaves. */
        const val VANILLA_FIRST_LEAF = 0.1f
        const val VANILLA_SECOND_LEAF = 0.9f
    }
}
