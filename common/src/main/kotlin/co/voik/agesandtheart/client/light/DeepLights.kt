package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.ChunkBlockIndex
import co.voik.agesandtheart.client.AddedLight
import co.voik.agesandtheart.client.AgeRenderTypes
import co.voik.agesandtheart.client.DeepWaterFog
import co.voik.agesandtheart.location
import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.tags.TagKey
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3

/**
 * Lights you can see from across an abyss, and where they are.
 *
 * **An abyss with no lights in it is not oppressive, only tedious.** Twenty-four blocks of sight and
 * nothing on the horizon leaves a diver dead-reckoning through black water; what makes the dark worth
 * having is a candle on a dark hill — you can see the light and nothing whatever around it, so you always
 * have somewhere to go and no idea what is there when you arrive (Jonah, 2026-09-10).
 *
 * **Every light in the deep is drawn identically, and that is the design rather than a saving.** An ore
 * cluster, an ocean monument and a hunter's lure are one point of light at range, so approaching any of
 * them is a decision made without knowing which it is. This object owns the whole vocabulary — the colour,
 * the size and how far it carries — so "the same" is true by construction and not by two files agreeing;
 * `HadalfishRenderer` asks it the same questions the blocks do.
 *
 * **The index is a [ChunkBlockIndex], as `Wounds`' is.** A section's palette dismisses nearly all of it
 * before a block is read, chunks fill it as they arrive, and `setBlocksDirty` keeps it true for anything
 * placed afterwards. It is a sibling of [TintedLights], sharing only the index class: this needs no mesher —
 * so it must work where `TintedLights` deliberately switches itself off — and it is keyed on a tag rather
 * than on registered blocks.
 */
object DeepLights {

    /**
     * What shines through the deep, as a tag.
     *
     * A tag because which blocks are beacons is a content decision: the sea lantern seeds it, an ocean
     * monument is therefore a constellation of them for free, and the deep-sea material can join by
     * datapack when it exists.
     */
    val SHINES_THROUGH_THE_DEEP: TagKey<Block> =
        TagKey.create(Registries.BLOCK, "shines_through_the_deep".location())

    /**
     * What a light in the deep is, as light rather than as a surface.
     *
     * The sea lantern's own pale cyan, because it is the one source here that cannot be restyled — vanilla
     * owns its texture, and a red bloom around a white block would read as two things rather than one. The
     * hadalfish's eye is a generated sixteen pixels of ours and was moved to match, which is the direction
     * that costs nothing.
     *
     * The blend is additive, so this is what a light *adds* to the black it stands in.
     */
    val LIT = Rgba(red = 0.62f, green = 1.0f, blue = 0.94f, alpha = 0.85f)

    /**
     * How far a light carries, in blocks: full strength between the middle two, gone outside the outer two.
     *
     * **It fades out as you arrive, not only as you leave** (Jonah, walked 2026-09-10). A bloom is a
     * *distance* affordance — the thing you steer by when there is nothing else — and once you are near
     * enough to see the sea lantern itself it is only a smear over the thing you came to look at.
     *
     * **The handover is a share of the fog's reach, not its end** (Jonah, walked 2026-09-10). Handing over
     * at `DeepWaterFog.NOTHING_BEYOND` left a gap you could swim through with the bloom gone and the block
     * not yet visible, because the fog is a linear ramp: at its stated end a block is *completely* hidden,
     * and it only becomes readable a good way inside that. [SEEN_THROUGH] is where it does. Written as a
     * fraction so that tuning how far the abyss lets you see moves the handover with it.
     *
     * [SEEN_UNTIL] is where vanilla stops drawing an entity the hadalfish's size
     * (`Entity.shouldRenderAtSqrDistance`), so the lure fades out exactly as it would have been culled.
     * Blocks are held to the same numbers deliberately — see the note above about telling them apart.
     */
    val HIDDEN_WITHIN = DeepWaterFog.NOTHING_BEYOND * SEEN_THROUGH
    /**
     * How much of the fog's reach you can actually make out a block through.
     *
     * Well inside it, and not by a little: the fog is linear from the eye, so a block is most of the way
     * washed out long before its stated end. Walked twice and moved in both times — six tenths still left
     * a gap you could swim through, and this is where a lantern is genuinely a lantern rather than a
     * lighter patch of dark.
     */
    const val SEEN_THROUGH = 0.35

    const val FULLY_BY = 48.0
    const val SEEN_FULLY = 120.0
    const val SEEN_UNTIL = 136.0

    /**
     * How far a bloom is pushed toward the camera, in blocks.
     *
     * **Or it is drawn inside the block it belongs to and mostly cut away by it** — the pipeline is
     * depth-tested, deliberately, so that rock hides a light behind it; the price is that a light at a
     * block's own centre is hidden by its own six faces. A shade over the half-diagonal of a cube clears
     * it from every angle, and pushing toward the *camera* rather than along a fixed axis is what makes
     * that true from every angle rather than most of them.
     */
    const val CLEAR_OF_ITS_OWN_BLOCK = 0.9

    /**
     * How wide a light is drawn: **the size of the thing it comes from, until that would be too small to
     * see, and a fixed size on the screen after that.**
     *
     * Two anchors rather than one, because near and far want opposite things. Up close a bloom smaller
     * than its own block reads as *shrinking into* it and the glowing illusion breaks (Jonah, walked
     * 2026-09-10), so the near term is a block's own half-width and the light sits on the face it comes
     * from. Far away a world-space size dwindles to a sub-pixel flicker, so the far term grows with
     * distance and holds a constant angle — the same reason vanilla draws its sun at a fixed size on the
     * vault. They cross at about sixty-seven blocks and neither end has to compromise for the other.
     *
     * `AddedLight.halo` draws a faint ring `SPREAD` times wider around whatever it is given, so the glow
     * is about three times these numbers; leaving that out of the arithmetic is how three walks running
     * read a light as the wrong size.
     */
    private const val BLOCK_SIZED = 0.5
    private const val STAYS_READABLE = 0.0075

    private const val GONE = 0.01f

    /** The colour a light this far off is drawn in, or null where it has faded out entirely. */
    fun litAt(range: Double): Rgba? {
        val strength = carriesTo(range)
        if (strength <= GONE) return null
        return LIT.copy(alpha = LIT.alpha * strength)
    }

    /** How wide a light this far off is drawn — see [BLOCK_SIZED] and [STAYS_READABLE]. */
    fun acrossAt(range: Double): Double = maxOf(BLOCK_SIZED, range * STAYS_READABLE)

    /** Nothing, up into full strength, along the plateau, and out again — with no corner at any end. */
    private fun carriesTo(range: Double): Float = when {
        range <= HIDDEN_WITHIN -> 0.0f
        range < FULLY_BY -> eased((range - HIDDEN_WITHIN) / (FULLY_BY - HIDDEN_WITHIN))
        range <= SEEN_FULLY -> 1.0f
        range >= SEEN_UNTIL -> 0.0f
        else -> eased((SEEN_UNTIL - range) / (SEEN_UNTIL - SEEN_FULLY))
    }

    /** Smoothstep, so neither end of a fade arrives as a visible edge in the brightness. */
    private fun eased(share: Double): Float {
        val at = share.coerceIn(0.0, 1.0)
        return (at * at * (3.0 - 2.0 * at)).toFloat()
    }

    /**
     * Draw every indexed light near the camera, in one submission.
     *
     * The pose arrives camera-relative and untranslated, exactly as `WoundField` documents, so each light
     * is written at its own offset from the camera and the whole field is one piece of geometry.
     */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, camera: Vec3) {
        val level = Minecraft.getInstance().level ?: return
        // **Only from inside the deep**, which is both the design and the early-out that makes this free
        // everywhere else. A sea lantern is an ordinary block in an ordinary world and must stay one — a
        // hundred and twenty blocks of unfogged bloom over somebody's overworld base is not a feature. It
        // is the same test the water fog is switched on by, asked of the same block.
        if (!DeepWaterFog.deepAt(level, camera)) return
        // Gathered before submitting: the lambda below runs inside the buffer's own bookkeeping, and
        // walking the index there would hold it open for the length of the walk.
        val inSight = mutableListOf<BlockPos>()
        lights.eachWithin(level, camera, SEEN_UNTIL) { light, _ -> inSight.add(light) }
        if (inSight.isEmpty()) return
        for (light in inSight) {
            val middle = Vec3(light.x + HALF, light.y + HALF, light.z + HALF)
            val towardCamera = camera.subtract(middle)
            val range = towardCamera.length()
            val colour = litAt(range) ?: continue
            // Lifted clear of the block's own faces, toward whoever is looking — see CLEAR_OF_ITS_OWN_BLOCK.
            val drawnAt = middle.add(towardCamera.scale(CLEAR_OF_ITS_OWN_BLOCK / range))
            poseStack.pushPose()
            poseStack.translate(drawnAt.x - camera.x, drawnAt.y - camera.y, drawnAt.z - camera.z)
            AddedLight.halo(collector, poseStack, towardCamera, colour, acrossAt(range), AgeRenderTypes.lightThroughFog)
            poseStack.popPose()
        }
    }

    /** Every light a chunk holds, read as it arrives — the index's whole supply. */
    fun stocked(level: Level, chunk: ChunkAccess) = lights.stocked(level, chunk)

    /** And as it goes, so an unloaded chunk's lights stop being drawn where nobody is. */
    fun emptied(level: Level, at: ChunkPos) = lights.emptied(level, at)

    /** A block changing where a client can see it — how a light placed after its chunk arrived is heard of. */
    fun noticed(level: Level, at: BlockPos, was: BlockState, now: BlockState) {
        val wasOne = shines(was)
        val isOne = shines(now)
        if (wasOne == isOne) return
        if (isOne) lights.arrived(level, at, Unit) else lights.gone(level, at)
    }

    /** Leaving a world. The index is the client's alone, so nothing else needs telling. */
    fun forget() = lights.forget()

    private fun shines(state: BlockState): Boolean = state.`is`(SHINES_THROUGH_THE_DEEP)

    /** Read on the client thread only — chunks arriving, blocks changing, and the render, are all it. */
    private val lights = ChunkBlockIndex.matching(::shines)

    /** From a block's corner to its middle, which is where its light is drawn. */
    private const val HALF = 0.5
}
