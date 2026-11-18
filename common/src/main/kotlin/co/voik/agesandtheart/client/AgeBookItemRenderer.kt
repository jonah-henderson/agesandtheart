package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.client.panel.BookMaps
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.panel.OpenBookPanels
import com.mojang.blaze3d.vertex.PoseStack
import co.voik.agesandtheart.content.AgeComponents
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.client.resources.model.sprite.SpriteGetter
import net.minecraft.util.Mth
import net.minecraft.util.StringRepresentable
import net.minecraft.world.entity.player.Player
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.Optional
import kotlin.math.cos
import kotlin.math.sin
import java.util.function.Consumer

/**
 * A descriptive or linking book as an item, drawn as the shut book itself so its banner and name show in a slot,
 * a hand or a frame. Its front cover faces +Z, the way a flat item's sprite does, so the item model's display
 * transforms are a flat item's. In a hand the item model says which view it is [heldIn], and it is held as a lectern
 * holds it, opening as a lectern's does: in first person standing where Jonah's own project of it stands and placed by
 * the item model's display transform, in third person lying in the world, its top the way its holder faces and leant
 * toward them by a [lean], whatever the arm has done to it; a book its reader has open
 * ([AgeComponents.BOOK_OPENED_AT]) opens as a lectern's does over half a second, and shuts at once. Open, it shows its
 * panel on its page as a book lying open does: another player's as [OpenBookPanels] chose it, the reader's own as their
 * screen has it.
 */
class AgeBookItemRenderer(
    sprites: SpriteGetter,
    private val heldIn: HeldIn?,
    private val lean: Float,
) : SpecialModelRenderer<AgeBookItemRenderer.Held> {

    /** Which hand view an item model asks for: the eye's own, or another's of the holder. */
    enum class HeldIn(private val serialised: String) : StringRepresentable {
        FIRST_PERSON("first_person"),
        THIRD_PERSON("third_person");

        override fun getSerializedName(): String = serialised

        companion object {
            val CODEC: Codec<HeldIn> = StringRepresentable.fromEnum(::values)
        }
    }

    /**
     * The book as it looks; when its reader opened it, if they have it open; and its panel, if it shows one: the page,
     * whether it is the one this client's panel shows, and the map in its place where panels are maps.
     */
    class Held(
        val look: BookLook,
        val openedAt: Long?,
        val panelPage: BookPage? = null,
        val isTheShownOne: Boolean = false,
        val mapPicture: RenderType? = null,
        /** The way its holder's body faces, as a yaw, where it is held by a player. */
        val holderFacing: Float? = null,
    )

    private val rendering = AgeBookRendering(sprites)

    override fun extractArgument(stack: ItemStack): Held {
        val look = BookLook.of(stack)
        if (heldIn == null) return Held(look, null)
        val holding = holdingOf(stack)
        val holderFacing = holding?.let { (holder, _) ->
            Mth.rotLerp(Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false), holder.yBodyRotO, holder.yBodyRot)
        }
        val openedAt = stack.get(AgeComponents.BOOK_OPENED_AT) ?: return Held(look, null, holderFacing = holderFacing)
        val panelPage = LecternBooks.panelPageOf(stack)
        val book = holding?.let { (holder, hand) ->
            if (holder === Minecraft.getInstance().player) BookBeingRead.InHand(hand) else BookBeingRead.InAnothersHand(holder.id, hand)
        }
        if (panelPage == null || book == null) return Held(look, openedAt, holderFacing = holderFacing)
        val isOwn = book is BookBeingRead.InHand
        val isTheShownOne = if (isOwn) LinkingPanel.showingFor == book else OpenBookPanels.shown == book
        val mapPicture = if (BookMaps.isOn) BookMaps.pictureOnAPage(book) else null
        // As a fallen book tells it: so the frame lays down the picture this one will show. The reader's own screen
        // lays down its live picture itself.
        val showsTheLivePanel = isTheShownOne && mapPicture == null && !BookMaps.isOn
        when {
            showsTheLivePanel && !isOwn -> OpenBookPanels.sawTheShownBook()
            !showsTheLivePanel && mapPicture == null -> OpenBookPanels.sawAMistedBook()
        }
        return Held(look, openedAt, panelPage, isTheShownOne, mapPicture, holderFacing)
    }

    /** Who holds [stack] in which hand, found by the stack itself among the players in this client's world. */
    private fun holdingOf(stack: ItemStack): Pair<Player, InteractionHand>? {
        val level = Minecraft.getInstance().level ?: return null
        for (holder in level.players()) {
            val hand = InteractionHand.entries.firstOrNull { holder.getItemInHand(it) === stack } ?: continue
            return holder to hand
        }
        return null
    }

    override fun submit(
        held: Held?,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        held ?: return
        val look = held.look
        val view = heldIn
        poseStack.pushPose()
        if (view == null) {
            centreInTheItem(look.binding, poseStack)
            rendering.submit(look, SHUT, poseStack, collector, lightCoords, overlayCoords, outlineColor)
            poseStack.popPose()
            return
        }
        // As a lectern draws it, from its middle: laid in the lectern's own frame for this view, then on its back board
        // as far as it is open.
        val swing = swingOf(held.openedAt)
        val pose = BookOnTheSlope.bookAt(swing)
        when (view) {
            HeldIn.FIRST_PERSON -> poseStack.translate(FIRST_PERSON_X, FIRST_PERSON_Y, FIRST_PERSON_Z)
            HeldIn.THIRD_PERSON -> {
                poseStack.translate(HALF, HALF, HALF)
                poseStack.scale(ENLARGED, ENLARGED, ENLARGED)
                poseStack.mulPose(Matrix4f().set(turnToALectern(poseStack, held.holderFacing)))
            }
        }
        BookOnTheSlope.layOnItsBack(swing, look.binding, poseStack)
        rendering.submit(look, pose, poseStack, collector, lightCoords, overlayCoords, outlineColor)
        val panelPage = held.panelPage
        if (swing > 0.0f && panelPage != null) {
            PanelOnThePage.submit(panelPage, held.isTheShownOne, held.mapPicture, rendering.modelFor(look.binding), pose, poseStack, collector)
        }
        poseStack.popPose()
    }

    /**
     * The turn that takes what [poseStack] draws in to a lectern's frame, for a book seen in another's hand: its x away
     * from the page toward the reader, its y the way the book's top points, its z the reader's left. Worked out from the
     * pose itself, which a third-person hand draws with the world's own axes, so whatever the arm and its swing have
     * turned it, the book lies flat with its top the way its holder's body faces. First person is drawn with the view's
     * turn in it, and is placed by the item model's display transform instead.
     */
    private fun turnToALectern(poseStack: PoseStack, holderFacing: Float?): Matrix3f {
        val drawnIn = Matrix3f(poseStack.last().pose())
        val columns = (0..<AXES).map { drawnIn.getColumn(it, Vector3f()).normalize() }
        drawnIn.setColumn(0, columns[0]).setColumn(1, columns[1]).setColumn(2, columns[2])
        val up = Vector3f(0.0f, 1.0f, 0.0f)
        val yaw = (holderFacing ?: 0.0f) * Mth.DEG_TO_RAD
        val ahead = Vector3f(-sin(yaw), 0.0f, cos(yaw))
        // Leant toward the reader as a lectern's slope is: the page turned back toward them, its top raised.
        val leanRadians = lean * Mth.DEG_TO_RAD
        val towardTheReader = Vector3f(up).mul(cos(leanRadians)).sub(Vector3f(ahead).mul(sin(leanRadians)))
        val top = Vector3f(ahead).mul(cos(leanRadians)).add(Vector3f(up).mul(sin(leanRadians)))
        val left = Vector3f(towardTheReader).cross(top)
        val wanted = Matrix3f(towardTheReader, top, left)
        return drawnIn.transpose().mul(wanted)
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        val poseStack = PoseStack()
        val largest = rendering.modelFor(BookBinding.THICK)
        centreInTheItem(largest.binding, poseStack)
        largest.setupAnim(SHUT)
        largest.root().getExtentsForGui(poseStack, output)
    }

    /** The shut book, spine to fore-edge from 0 and centred on the other two, centred in the item's cube and enlarged. */
    private fun centreInTheItem(binding: BookBinding, poseStack: PoseStack) {
        poseStack.translate(HALF, HALF, HALF)
        poseStack.scale(ENLARGED, ENLARGED, ENLARGED)
        poseStack.translate(-AgeBookModel.shutLength(binding) / 2 / UNITS_PER_BLOCK, 0.0f, 0.0f)
    }

    /** How far open, nought to one, a book its reader opened at [openedAt] stands: eased as a lectern's book swings. */
    private fun swingOf(openedAt: Long?): Float {
        openedAt ?: return 0.0f
        val level = Minecraft.getInstance().level ?: return 0.0f
        val partialTick = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
        val ticksOpen = level.gameTime - openedAt + partialTick
        val progress = (ticksOpen / LecternBookRenderer.SWING_TICKS).toFloat().coerceIn(0.0f, 1.0f)
        return progress * progress * (EASING_THREE - EASING_TWO * progress)
    }

    class Unbaked(private val heldIn: HeldIn?, private val lean: Float) : SpecialModelRenderer.Unbaked<Held> {
        override fun bake(context: SpecialModelRenderer.BakingContext): SpecialModelRenderer<Held> =
            AgeBookItemRenderer(context.sprites(), heldIn, lean)

        override fun type(): MapCodec<Unbaked> = MAP_CODEC

        companion object {
            /**
             * `held`: in the item model's hand cases, `first_person` or `third_person`, so the book is held there as a
             * lectern holds it and opens as one does when read; `lean`, how many degrees it leans toward its reader.
             */
            val MAP_CODEC: MapCodec<Unbaked> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    HeldIn.CODEC.optionalFieldOf("held").forGetter { Optional.ofNullable(it.heldIn) },
                    Codec.FLOAT.optionalFieldOf("lean", LECTERN_LEAN).forGetter { it.lean },
                ).apply(instance) { held, lean -> Unbaked(held.orElse(null), lean) }
            }
        }
    }

    private companion object {
        val SHUT = AgeBookModel.State(0.0f)

        const val AXES = 3

        /** A lectern's slope: its page turned this far from flat toward the reader. */
        const val LECTERN_LEAN = 22.5f

        /**
         * Where a lectern's frame stands in the item, in first person: the shut book there is Jonah's
         * `converted_linking_book.bbmodel` exactly, part for part, so the first-person display he set on that project
         * places it as he saw it (`scripts/books/age_book_held.py` copies it across).
         */
        const val FIRST_PERSON_X = 6.0f / 16.0f
        const val FIRST_PERSON_Y = 4.0f / 16.0f
        const val FIRST_PERSON_Z = 5.0f / 16.0f
        const val EASING_TWO = 2.0f
        const val EASING_THREE = 3.0f
        const val HALF = 0.5f

        /** Eight pixels tall becomes fourteen, near filling a slot as a flat item does. */
        const val ENLARGED = 1.75f
        const val UNITS_PER_BLOCK = 16.0f
    }
}
