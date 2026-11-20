package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookFinderBlock
import co.voik.agesandtheart.book.BookFinderBlockEntity
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.FinderMotions
import co.voik.agesandtheart.book.FinderMotions.Deliver
import co.voik.agesandtheart.book.FinderMotions.Fold
import co.voik.agesandtheart.book.FinderMotions.Pull
import co.voik.agesandtheart.book.FinderPart
import co.voik.agesandtheart.book.FrameTask
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.WallPoint
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f

class BookFinderRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var task: FrameTask = FrameTask.RESTING

    /** Ticks into [task]. */
    var elapsed: Float = 0.0f

    /** Where the finder is across the face of the wall, from its own space. */
    var at: WallPoint = WallPoint(0.0, 0.0)

    /** The slot it draws a book from or pushes one into. */
    var slot: Int = 0

    /**
     * How far in its paddles rest at home: into the chiselled shelf it is mounted on, or no further than that block's
     * face where it is mounted on anything else (Jonah, 2026-11-20).
     */
    var tuckedAtHome: Float = 0.0f

    var look: BookLook? = null
    var binding: BookBinding = BookBinding.THICK

    /** How thick the book is drawn: a book not ours is vanilla's, as thin as a linking book. */
    var drawnAs: BookBinding = BookBinding.THICK

    /** How far the book on the lectern has swung open. */
    var openness: Float = 0.0f
    var panelPage: BookPage? = null
    var panel: OpenOnAStand.Panel? = null
    var finderLight: Int = 0
    val parts: Map<FinderPart, BlockModelRenderState> = MOVING_PARTS.associateWith { BlockModelRenderState() }

    companion object {
        val MOVING_PARTS: List<FinderPart> = FinderPart.entries - FinderPart.STATIC
    }
}

/**
 * A book finder at work (design §7.8.3), as Jonah's model moves (Jonah, 2026-11-18): its frame stays with the block,
 * and everything else travels across the wall along the path its finder sent. The [FinderMotions] are played out on its
 * parts here: the lectern's surface folding flat to travel, the extractor sliding to a slot's column as its rotator rides
 * to the slot's row, the paddles reaching in and drawing the book out at a shelf's size, and at home the rotator turning
 * the book onto its cover, the lectern meeting it and taking it down, the book growing to its own size as it turns once
 * let go, and the book surface raising it to be read as a lectern's book is.
 *
 * Everything is posed in the model's frame, facing north with the shelf to the south, in pixels, and turned by facing at
 * the end.
 */
class BookFinderRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookFinderBlockEntity, BookFinderRenderState>, RendersBeyondItsBlock {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): BookFinderRenderState = BookFinderRenderState()

    override fun extractRenderState(
        finder: BookFinderBlockEntity,
        state: BookFinderRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(finder, state, partialTicks, cameraPosition, breakProgress)
        val level = finder.level ?: return
        state.facing = finder.facing
        state.task = finder.task
        // Between the last tick and this one, as entities are drawn, so what rides the carriage keeps step with it.
        val sinceTheLastTick = (level.gameTime - finder.startedAt - 1) + partialTicks
        state.elapsed = sinceTheLastTick.coerceIn(0.0f, finder.ticksOf(finder.task).toFloat())
        state.at = finder.carriageAt(state.elapsed.toDouble())
        state.slot = finder.errand?.slot ?: 0
        val mountedOn = level.getBlockState(finder.blockPos.relative(finder.facing.opposite))
        state.tuckedAtHome = if (BookFinders.isAShelfFacing(mountedOn, finder.facing)) STOWED else CLEAR_OF_THE_SHELF
        val right = finder.facing.counterClockWise
        val finderSpace = finder.blockPos.relative(right, Math.round(state.at.across).toInt()).above(Math.round(state.at.up).toInt())
        state.finderLight = LightCoordsUtil.getLightCoords(level, finderSpace)
        val book = finder.carried
        state.look = book.takeUnless { it.isEmpty }?.let(BookLook::of)
        state.binding = BookBinding.of(book)
        state.drawnAs = if (state.look?.isOurs == true) state.binding else BookBinding.THIN
        state.panelPage = LecternBooks.panelPageOf(book)
        val isOnTheLectern = finder.task == FrameTask.PRESENTING || finder.task == FrameTask.CLOSING
        state.openness = if (isOnTheLectern) StandSwings.opennessOf(finder, BookFinderBlockEntity.PLACE, finder.isOpen, partialTicks) else 0.0f
        for ((part, model) in state.parts) {
            blockModels.update(model, BookFinders.BLOCK.defaultBlockState().setValue(BookFinderBlock.PART, part), PARTS)
        }
        state.panel = if (state.panelPage == null || state.openness <= 0.0f) null
        else OpenOnAStand.panelOf(finder.level, finder.blockPos, BookFinderBlockEntity.PLACE)
    }

    override fun shouldRenderOffScreen(): Boolean = true

    /** Somewhere on its wall, which is at most [co.voik.agesandtheart.book.BookFinderWall.MOST_SHELVES] shelves. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(WALL_REACH)

    override fun submit(state: BookFinderRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val posture = postureOf(state)
        val finder = facingTurn(state.facing).translate((-state.at.across).toFloat(), state.at.up.toFloat(), 0.0f)
        fun submitPart(part: FinderPart, pose: Matrix4f) {
            val model = state.parts.getValue(part)
            if (model.isEmpty) return
            poseStack.pushPose()
            poseStack.mulPose(Matrix4f(finder).mul(pose))
            model.submit(poseStack, collector, state.finderLight, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()
        }
        submitPart(FinderPart.CHASSIS, Matrix4f())
        submitPart(FinderPart.EXTRACTOR, railsFrame(posture.arm))
        submitPart(FinderPart.ROTATOR, rotatorFrame(posture.arm))
        submitPart(FinderPart.LEFT_PADDLE, paddleFrame(posture.arm, Side.LEFT))
        submitPart(FinderPart.RIGHT_PADDLE, paddleFrame(posture.arm, Side.RIGHT))
        submitPart(FinderPart.LECTERN, lecternFrame(posture.lift))
        submitPart(FinderPart.BOOK_SURFACE, surfaceFrame(posture.lift, posture.fold))

        val look = state.look ?: return
        val book = posture.book ?: return
        poseStack.pushPose()
        poseStack.mulPose(Matrix4f(finder).mul(book))
        OpenOnAStand.submit(rendering, look, state.panelPage, state.panel, posture.open, poseStack, collector, state.finderLight)
        poseStack.popPose()
    }

    /**
     * The extractor slid [across] from the middle, its rotator ridden [rise] up the rails from the middle and turned [tip]
     * of the way onto its side, and the rotator's paddles [reach] pixels in from rest. Only the paddles go in and out.
     */
    private data class Arm(
        val across: Float = 0.0f,
        val rise: Float = 0.0f,
        val tip: Float = 0.0f,
        val reach: Float = 0.0f,
    )

    /** The rotator's two paddles, as the wall is faced: the left is east in the model's frame. */
    private enum class Side(val eastward: Float) { LEFT(1.0f), RIGHT(-1.0f) }

    /**
     * Everything that moves: the extractor, how far the lectern has risen ([lift], in pixels) and its surface folded
     * ([fold], up to flat), and the book's pose and how far open it is, where there is a book to draw.
     */
    private data class Posture(
        val arm: Arm = Arm(),
        val lift: Float = 0.0f,
        val fold: Float = 0.0f,
        val book: Matrix4f? = null,
        val open: Float = 0.0f,
    )

    private fun postureOf(state: BookFinderRenderState): Posture {
        val lasts = when (state.task) {
            FrameTask.FOLDING, FrameTask.UNFOLDING -> FinderMotions.FOLD.ticks
            FrameTask.PULLING, FrameTask.PUSHING -> FinderMotions.PULL.ticks
            FrameTask.DELIVERING, FrameTask.STOWING -> FinderMotions.DELIVER.ticks
            else -> 0
        }
        val backward = lasts - state.elapsed
        return when (state.task) {
            FrameTask.RESTING -> atRest(state)
            FrameTask.FOLDING -> folding(state, state.elapsed)
            FrameTask.UNFOLDING -> folding(state, backward)
            FrameTask.GOING_FOR, FrameTask.COMING_BACK -> travelling(state, carrying = false)
            // Out of another finder's way, with the book from the lectern where there was one.
            FrameTask.STEPPING_ASIDE, FrameTask.STANDING_ASIDE, FrameTask.STEPPING_BACK -> travelling(state, carrying = state.look != null)
            FrameTask.BRINGING, FrameTask.TAKING_BACK -> travelling(state, carrying = true)
            FrameTask.PULLING -> pulling(state, state.elapsed)
            FrameTask.PUSHING -> pulling(state, backward)
            FrameTask.DELIVERING -> delivering(state, state.elapsed)
            FrameTask.STOWING -> delivering(state, backward)
            FrameTask.PRESENTING, FrameTask.CLOSING ->
                atRest(state).copy(book = bookOnTheLectern(state, 0.0f, 0.0f, state.openness), open = state.openness)
        }
    }

    /** At home with the surface up: the rotator at the top, its paddles tucked in as far as they go there. */
    private fun atRest(state: BookFinderRenderState): Posture = Posture(arm = Arm(rise = HOME_RISE, reach = state.tuckedAtHome))

    private fun folding(state: BookFinderRenderState, ticks: Float): Posture {
        fun share(step: Fold) = eased(FinderMotions.FOLD.through(step, ticks))
        val arm = Arm(rise = HOME_RISE, reach = state.tuckedAtHome * (1.0f - share(Fold.PADDLES_OUT)))
        return Posture(arm = arm, fold = share(Fold.SURFACE_DOWN))
    }

    /** Empty, the rotator at the top; carrying, in the middle with the book drawn out as far as the paddles come. */
    private fun travelling(state: BookFinderRenderState, carrying: Boolean): Posture {
        if (!carrying) return Posture(arm = Arm(rise = HOME_RISE), fold = 1.0f)
        val arm = Arm()
        return Posture(arm = arm, fold = 1.0f, book = carriedBook(state, arm))
    }

    private fun pulling(state: BookFinderRenderState, ticks: Float): Posture {
        fun share(step: Pull) = eased(FinderMotions.PULL.through(step, ticks))
        val column = SLOT_ACROSS[state.slot % SLOT_COLUMNS]
        val row = riseToTheRowOf(state.slot)
        val toTheSlot = share(Pull.TO_THE_SLOT)
        val toTheMiddle = share(Pull.TO_THE_MIDDLE)
        // From the top to the slot, and from there to the middle.
        val arm = Arm(
            across = column * (toTheSlot - toTheMiddle),
            rise = HOME_RISE + (row - HOME_RISE) * toTheSlot - row * toTheMiddle,
            reach = STOWED * (share(Pull.REACH_IN) - share(Pull.DRAW_OUT)),
        )
        val inTheSlot = Arm(across = column, rise = row, reach = STOWED)
        val book = carriedBook(state, if (FinderMotions.PULL.hasBegun(Pull.DRAW_OUT, ticks)) arm else inTheSlot)
        return Posture(arm = arm, fold = 1.0f, book = book)
    }

    private fun delivering(state: BookFinderRenderState, ticks: Float): Posture {
        fun share(step: Deliver) = eased(FinderMotions.DELIVER.through(step, ticks))
        fun hasBegun(step: Deliver) = FinderMotions.DELIVER.hasBegun(step, ticks)
        val lift = LEVEL * (share(Deliver.LEVEL) - share(Deliver.LOWER_THE_BOOK))
        val fold = 1.0f - share(Deliver.SURFACE_UP)
        // Tucked into the gap between books while the lectern takes the book down, then out to right itself in front of
        // the shelf, clear of the book now below it, up to the top, and back in as far as it rests. Mounted on anything
        // but a shelf it tucks into the block all the same, rather than meet the book on its way down (Jonah).
        val righted = share(Deliver.RIGHT_ITSELF)
        val outToRightItself = (STOWED - CLEAR_OF_THE_SHELF) * share(Deliver.COME_OUT)
        val backInToRest = (state.tuckedAtHome - CLEAR_OF_THE_SHELF) * share(Deliver.GO_BACK_IN)
        val arm = Arm(
            across = SHIFT_ASIDE * (share(Deliver.SHIFT_ASIDE) - righted),
            rise = HOME_RISE * share(Deliver.RIDE_UP),
            tip = share(Deliver.TIP) - righted,
            reach = STOWED * share(Deliver.TUCK_IN) - outToRightItself + backInToRest,
        )
        val tippedAtTheLevel = carriedBook(state, Arm(tip = 1.0f))
        val book = when {
            hasBegun(Deliver.TURN_THE_BOOK) -> {
                val tippedAtRest = Matrix4f().translation(0.0f, pixels(-LEVEL), 0.0f).mul(tippedAtTheLevel)
                val onTheLectern = bookOnTheLectern(state, 0.0f, fold, open = 0.0f)
                turnedOnto(tippedAtRest, onTheLectern, shelvedSizeOf(state), state.drawnAs, share(Deliver.TURN_THE_BOOK))
            }
            hasBegun(Deliver.LOWER_THE_BOOK) -> Matrix4f().translation(0.0f, pixels(lift - LEVEL), 0.0f).mul(tippedAtTheLevel)
            else -> carriedBook(state, Arm(tip = arm.tip))
        }
        return Posture(arm = arm, lift = lift, fold = fold, book = book)
    }

    /** How far the rotator rides up from the middle to the row of [slot]: the shelf's upper row is its first three slots. */
    private fun riseToTheRowOf(slot: Int): Float {
        val isInTheUpperRow = slot < BookFinderBlockEntity.SLOTS / 2
        return (if (isInTheUpperRow) UPPER_ROW_MIDDLE else LOWER_ROW_MIDDLE) - ROTATOR_MIDDLE_UP
    }

    /**
     * How a book is drawn on a chiselled shelf and in the paddles, as a share of its own size along its length, height and
     * thickness: as the shelf's books are, six tall and four thick, squashed or stretched to it, and as long for its
     * height as it is.
     */
    private fun shelvedSizeOf(state: BookFinderRenderState): Vector3f {
        val height = if (state.look?.isOurs == false) VANILLA_BOOK_HEIGHT else OUR_BOOK_HEIGHT
        val shortened = SHELVED_BOOK_HEIGHT / height
        return Vector3f(shortened, shortened, SHELVED_BOOK_THICKNESS / AgeBookModel.shutThickness(state.drawnAs))
    }

    /**
     * Where a shelved book's spine stands: far enough out that the faces beside it, which a descriptive book's boards set
     * back from it, are just proud of the shelf.
     */
    private fun spineOnTheShelf(state: BookFinderRenderState): Float {
        val sidesBehindTheSpine = if (state.look?.isOurs == true) state.drawnAs.boardThickness * shelvedSizeOf(state).x else 0.0f
        return SHELF_FACE - PROUD_OF_THE_SHELF - sidesBehindTheSpine
    }

    /**
     * A book at a shelf's size upright in the middle of the rotator, spine out and fore-edge toward the shelf as a shelf
     * holds it, where the paddles [Arm.reach]ing in as far as they go take hold of it in its slot.
     */
    private fun carriedBook(state: BookFinderRenderState, arm: Arm): Matrix4f =
        rotatorFrame(arm)
            .translate(pixels(RING_MIDDLE_ACROSS), pixels(ROTATOR_MIDDLE_UP), pixels(spineOnTheShelf(state) - STOWED + arm.reach))
            // The shut model's spine is its origin and its fore-edge along +X: turned so the fore-edge points into the wall.
            .rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
            .scale(shelvedSizeOf(state))

    /** The book lying on the surface [open] of the way open, as a lectern holds a book, the lectern [lift]ed and its surface [fold]ed. */
    private fun bookOnTheLectern(state: BookFinderRenderState, lift: Float, fold: Float, open: Float): Matrix4f {
        val pose = PoseStack()
        OpenOnAStand.poseOpen(pose, HELD_AT, OpenOnAStand.readerYRotOf(Direction.NORTH), state.binding, open)
        return surfaceFrame(lift, fold).mul(pose.last().pose())
    }

    /** The extractor's rails, slid across. */
    private fun railsFrame(arm: Arm): Matrix4f = Matrix4f().translation(pixels(arm.across), 0.0f, 0.0f)

    /** The rotator, slid across with the rails, ridden up them, and turned about its middle on the axis into the shelf. */
    private fun rotatorFrame(arm: Arm): Matrix4f = railsFrame(arm)
        .translate(0.0f, pixels(arm.rise), 0.0f)
        .translate(pixels(RING_MIDDLE_ACROSS), pixels(ROTATOR_MIDDLE_UP), 0.0f)
        .rotate(Axis.ZP.rotationDegrees(TIP_DEGREES * arm.tip))
        .translate(-pixels(RING_MIDDLE_ACROSS), -pixels(ROTATOR_MIDDLE_UP), 0.0f)

    /** A paddle, a clearance out from the book it holds, [Arm.reach] in. */
    private fun paddleFrame(arm: Arm, side: Side): Matrix4f =
        rotatorFrame(arm).translate(pixels(side.eastward * PADDLE_CLEARANCE), 0.0f, pixels(arm.reach))

    private fun lecternFrame(lift: Float): Matrix4f = Matrix4f().translation(0.0f, pixels(lift), 0.0f)

    /**
     * The surface [fold] of the way flat: turned down about its front edge, and raised and drawn back as it turns so
     * that, flat, it lies in the arms' bracket.
     */
    private fun surfaceFrame(lift: Float, fold: Float): Matrix4f = lecternFrame(lift)
        .translate(0.0f, pixels(ONTO_THE_ARMS * fold), pixels(INTO_THE_BRACKET * fold))
        .translate(0.0f, pixels(SURFACE_HINGE.y), pixels(SURFACE_HINGE.z))
        .rotate(Axis.XP.rotationDegrees(SURFACE_TILT_DEGREES * fold))
        .translate(0.0f, -pixels(SURFACE_HINGE.y), -pixels(SURFACE_HINGE.z))

    /**
     * A book lying on its cover at [from], [grownFrom] its own size, [share] of the way to lying as a lectern holds it at
     * its own size at [to]: one quarter turn about the upright through its middle, which is all that is between them
     * ([TIP_DEGREES]), growing about its middle as its middle moves across. Both lie on the surface, so its underside
     * stays there.
     */
    private fun turnedOnto(from: Matrix4f, to: Matrix4f, grownFrom: Vector3f, binding: BookBinding, share: Float): Matrix4f {
        val middleOfTheBook = Vector3f(pixels(AgeBookModel.shutLength(binding) / 2), 0.0f, 0.0f)
        val fromMiddle = from.transformPosition(Vector3f(middleOfTheBook))
        val toMiddle = to.transformPosition(Vector3f(middleOfTheBook))
        return Matrix4f()
            .translation(fromMiddle.lerp(toMiddle, share))
            .rotate(Axis.YP.rotationDegrees(BOOK_TURN_DEGREES * (1.0f - share)))
            .translate(toMiddle.negate())
            .mul(to)
            .translate(middleOfTheBook)
            .scale(Vector3f(grownFrom).lerp(Vector3f(FULL_SIZE), share))
            .translate(Vector3f(middleOfTheBook).negate())
    }

    private fun facingTurn(facing: Direction): Matrix4f = Matrix4f()
        .translate(MIDDLE, MIDDLE, MIDDLE)
        .rotate(Axis.YP.rotationDegrees(-BookbinderRenderer.clockwiseTurnOf(facing)))
        .translate(-MIDDLE, -MIDDLE, -MIDDLE)

    private fun eased(progress: Float): Float = progress * progress * (3 - 2 * progress)

    private fun pixels(count: Float): Float = count / PIXELS_A_BLOCK

    private companion object {
        val PARTS: BlockDisplayContext = BlockDisplayContext.create()
        const val MIDDLE = 0.5f
        const val QUARTER_TURN = 90.0f
        const val PIXELS_A_BLOCK = 16.0f
        const val NO_OUTLINE = 0
        const val WALL_REACH = 32.0

        // The model's measures, in pixels.

        /** The middle of each of the shelf's rows. */
        const val UPPER_ROW_MIDDLE = 12.0f
        const val LOWER_ROW_MIDDLE = 4.0f

        /** The rotator's middle as the model has it, between the rows, where it carries a book and lays it down. */
        const val ROTATOR_MIDDLE_UP = 8.0f

        /** How far the rotator rides up from the middle to stand at the top, beside the upper row, while it has no book. */
        const val HOME_RISE = UPPER_ROW_MIDDLE - ROTATOR_MIDDLE_UP
        const val RING_MIDDLE_ACROSS = 8.0f

        /**
         * The extractor's rails stand this far toward the shelf; the rotator turns a quarter about its middle to lay a
         * book down, which way being the one that leaves it face up, spine to the reader, a quarter turn about the
         * upright ([BOOK_TURN_DEGREES]) from lying as a lectern holds it.
         */
        const val RING_DEPTH = 14.0f
        const val TIP_DEGREES = -90.0f
        const val BOOK_TURN_DEGREES = 90.0f

        /**
         * How far the extractor, its rotator turned on its side, shifts before the paddles go into the shelf or after they
         * come out of it: then lying flat, above and below the plank between the rows, they pass through the gap between
         * the middle column's books and the left ones'.
         */
        const val SHIFT_ASIDE = 2.5f

        /** The shelf's face. */
        const val SHELF_FACE = 16.0f

        /** How far a book in its slot stands out of the shelf's face, so the two are not drawn in one plane. */
        const val PROUD_OF_THE_SHELF = 0.1f

        /** The paddles reach this far from the ring toward the reader. */
        const val PADDLE_LENGTH = 8.0f

        /**
         * How far each paddle stands off a book's cover, and the lectern's surface off a paddle under the book, so none is
         * drawn in one plane with another: further than a title on a cover is pulled toward the eye ([BookTitle]'s
         * polygon offset), which at a hundredth of a pixel drew titles over the paddles from a few blocks off.
         */
        const val PADDLE_CLEARANCE = 0.1f

        /**
         * How far the paddles go into the shelf, to take hold of a book or be out of the way: as far as they reach while
         * still held in the ring. Drawn back out of it as far as they come, a book is clear of the shelf by more than a
         * pixel and over the folded surface.
         */
        const val STOWED = PADDLE_LENGTH

        /** The paddles as far in as they come while wholly out of the shelf, where they can turn without meeting its books. */
        const val CLEAR_OF_THE_SHELF = SHELF_FACE - RING_DEPTH

        /**
         * The middles of the books the shelf's texture draws in its slots, three across, from the left as the wall is
         * faced, which in the model's frame is east.
         */
        const val SLOT_COLUMNS = 3
        val SLOT_ACROSS = floatArrayOf(5.0f, 0.0f, -5.0f)


        /**
         * A book in a chiselled shelf is drawn as the shelf's are, six tall and four thick, the gap between an extractor's
         * paddles, and grows to its own size only once on the lectern.
         */
        const val SHELVED_BOOK_HEIGHT = 6.0f
        const val SHELVED_BOOK_THICKNESS = 4.0f
        const val OUR_BOOK_HEIGHT = 8.0f
        const val VANILLA_BOOK_HEIGHT = 10.0f
        const val FULL_SIZE = 1.0f

        /**
         * The book surface, a pixel thick, folded flat between arms a pixel high, flush with their tops, and behind the
         * bar across their front ends.
         */
        const val FLAT_SURFACE_BOTTOM = 0.0f
        const val FLAT_SURFACE_TOP = FLAT_SURFACE_BOTTOM + 1.0f
        const val FLAT_SURFACE_FRONT = 1.0f
        const val SURFACE_TILT_DEGREES = 22.5f

        /**
         * How far the lectern rises for its surface to meet a book the rotator has turned onto its cover in the middle:
         * half a shelved book's thickness below the rotator's middle, under the paddle beneath the book.
         */
        const val LEVEL = ROTATOR_MIDDLE_UP - SHELVED_BOOK_THICKNESS / 2 - 2 * PADDLE_CLEARANCE - FLAT_SURFACE_TOP

        /**
         * The surface's front lower edge as the model tilts it, which it folds about: the element's corner `(y 3, z 3)`
         * turned -22.5° about `(y -4, z 8)`, the same slope a lectern's top has.
         */
        val SURFACE_HINGE: Vector3f = Matrix4f()
            .translate(0.0f, -4.0f, 8.0f)
            .rotate(Axis.XP.rotationDegrees(-SURFACE_TILT_DEGREES))
            .translate(0.0f, 4.0f, -8.0f)
            .transformPosition(Vector3f(0.0f, 3.0f, 3.0f))

        /** How far the folded surface is raised to lie flush with the arms, and drawn back to lie behind their front bar. */
        val ONTO_THE_ARMS: Float = FLAT_SURFACE_BOTTOM - SURFACE_HINGE.y
        val INTO_THE_BRACKET: Float = FLAT_SURFACE_FRONT - SURFACE_HINGE.z

        /** Where the book on the lectern has its middle, as `BookFinderBlockEntity.openBookPose` says. */
        val HELD_AT = Vec3(0.5, BookFinderBlockEntity.OPEN_BOOK_UP, 0.5)
    }
}
