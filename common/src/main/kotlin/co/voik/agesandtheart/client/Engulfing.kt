package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What being *inside* a column of sand does to what you can see (design §5.2.2).
 *
 * **Positional layers, not a post-processing chain**, for the reason `notes/corruption-research.md` settles
 * and [Corruption] already relies on: an attribute layer is handed a position and returns a value, which is
 * exactly the shape of "blind here and clear a step away". A post effect is one shader over the whole frame
 * and could not tell the difference.
 *
 * **It is water fog with a different colour, and deliberately so.** Being under a column has no counterplay
 * — the answer to a sandfall is not to be where it is — so what it does to somebody who ignored that should
 * be total: sand to the eyeballs, the ground under your feet and nothing else. Vanilla already teaches every
 * player what this means, because it is what being underwater looks like.
 *
 * **Only inside the solid middle**, which is what makes it read rather than merely happen. The shell around
 * it is see-through on purpose: you watch the wall of sand arrive, you step into it, and *then* the world
 * goes. Blinding somebody in the haze they can still see through would be the picture and the air
 * disagreeing about where the column is.
 */
object Engulfing {

    /**
     * Lays the sand over whatever the Age's own air said.
     *
     * **Last, over [Corruption] as well.** A wound's dread is a gradient you navigate by; a column is a
     * wall you are inside. Nothing shows through it, including the thing that would otherwise be darkening
     * the same frame.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, at, _ ->
            if (engulfedAt(level, at)) SAND.packed() else was
        }
        // Not the sky's own fog end: that would paint the horizon and leave the world in front of you
        // perfectly clear, which is the opposite of the point.
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, at, _ ->
            if (engulfedAt(level, at)) AT_ARM_S_LENGTH else was
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, at, _ ->
            if (engulfedAt(level, at)) YOUR_OWN_FEET else was
        }
        return layers
    }

    /**
     * Whether [at] is inside the solid middle of some column.
     *
     * **Memoised on the position, because the three layers above all ask about the same one.** Vanilla
     * calls a positional layer per attribute per frame and the answer cannot differ between them, so the
     * search runs once and the other two read it back. Without that this would be three entity queries a
     * frame to answer one question.
     */
    private fun engulfedAt(level: ClientLevel, at: Vec3): Boolean {
        asked?.let { (where, answer) -> if (where == at) return answer }
        val answer = searchFor(level, at)
        asked = at to answer
        return answer
    }

    private var asked: Pair<Vec3, Boolean>? = null

    /**
     * The columns near enough to be standing in, asked one at a time.
     *
     * A box query rather than a walk over the level's entities: it is indexed by chunk section, so the
     * common case — no column anywhere near — costs a lookup and an empty list. [REACH] only has to cover
     * the widest a column may stand.
     */
    private fun searchFor(level: ClientLevel, at: Vec3): Boolean {
        val near = AABB.ofSize(at, REACH, REACH, REACH)
        return level.getEntitiesOfClass(SandColumn::class.java, near).any { column ->
            // Above the ground it walks on, or you are in a cave under it and the roof is doing its job.
            at.y >= column.y && column.covers(at.x, at.z, column.coreHalfWidth.toDouble())
        }
    }

    /** Sand at the density it falls in — dark, because none of the sky is reaching you in there. */
    private val SAND = Rgba.of(0xFF6B5A3Cu.toInt())

    /** Where the sand begins, in blocks. Not zero: the very front of the view stays legible. */
    private const val AT_ARM_S_LENGTH = 0.4f

    /** And where it is total. Enough to see what you are standing on and nothing beyond it. */
    private const val YOUR_OWN_FEET = 2.2f

    /** Twice the widest a column may stand, which is all a search has to cover. */
    private const val REACH = 42.0
}
