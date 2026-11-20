package co.voik.agesandtheart.book

import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.Collections
import java.util.WeakHashMap

/**
 * A book finder's carriage as something solid (Jonah, 2026-11-20): stood on, and carried along the wall by it, and
 * pushing what it runs into as a piston's moving block does. Only the big still pieces of its frame are solid, the
 * chassis against the wall and the lectern's base at its foot, which the book surface folds onto; the surface, the
 * extractor and the paddles are not, and where a push cannot clear somebody the carriage passes through them.
 *
 * Both sides work out where a carriage is from the finder's task, path and the game time, so the client's collision and
 * the server's agree with nothing sent. Each side keeps where it last carried a carriage's riders to
 * ([BookFinderBlockEntity.carriageCarriedTo]), and a carriage is solid there and nowhere else: so a rider is always
 * standing on it, never inside it, and each tick it carries them the whole way from there to where it now is, however
 * late a client hears that a trip began. As a drifting body's are ([co.voik.agesandtheart.content.DriftingOre]), riders
 * and those pushed are moved by whichever side decides where they are: the server everything but players, each client
 * its players.
 */
object FinderCarriages {

    /** The carriage's still pieces as the finder's model has them facing north, in pixels of its block. */
    private val PIECES = listOf(
        AABB(0.0, 0.0, 13.0, 16.0, 1.0, 15.0),
        AABB(0.0, 15.0, 13.0, 16.0, 16.0, 15.0),
        AABB(0.0, 1.0, 13.0, 1.0, 15.0, 15.0),
        AABB(15.0, 1.0, 13.0, 16.0, 15.0, 15.0),
        AABB(0.0, 0.0, 0.0, 16.0, 1.0, 13.0),
    )

    /**
     * The finders loaded in each level, on whichever side holds it, which a collision asks after; kept by the finders
     * themselves as they are loaded and unloaded. A finder's carriage may be anywhere on its wall, far from its block,
     * so there is no nearer index to ask.
     */
    private val LOADED: MutableMap<Level, MutableSet<BookFinderBlockEntity>> = Collections.synchronizedMap(WeakHashMap())

    fun loaded(finder: BookFinderBlockEntity) {
        val level = finder.level ?: return
        synchronized(LOADED) { LOADED.getOrPut(level) { mutableSetOf() } += finder }
    }

    fun unloaded(finder: BookFinderBlockEntity) {
        val level = finder.level ?: return
        synchronized(LOADED) { LOADED[level]?.remove(finder) }
    }

    private fun findersIn(level: Level): List<BookFinderBlockEntity> = synchronized(LOADED) { LOADED[level]?.toList().orEmpty() }

    /** The solid pieces of every carriage in [level] that reach into [box], for the collision Mixin. */
    fun collidersIn(level: Level, box: AABB): List<VoxelShape> {
        val finders = findersIn(level)
        if (finders.isEmpty()) return emptyList()
        return finders.flatMap { finder -> piecesAt(finder, carriedTo(finder)).filter { it.intersects(box) } }.map(Shapes::create)
    }

    /** How far into its task [finder]'s carriage is now, in ticks, as its renderer measures it. */
    private fun nowOf(finder: BookFinderBlockEntity): Double {
        val level = finder.level ?: return 0.0
        return (level.gameTime - finder.startedAt).toDouble().coerceIn(0.0, finder.ticksOf(finder.task).toDouble())
    }

    /** Where [finder]'s carriage last carried its riders to, or where it is, before it has carried anyone. */
    private fun carriedTo(finder: BookFinderBlockEntity): WallPoint = finder.carriageCarriedTo ?: finder.carriageAt(nowOf(finder))

    /** The carriage's solid pieces in the world, at [at] on the wall. */
    private fun piecesAt(finder: BookFinderBlockEntity, at: WallPoint): List<AABB> {
        val facing = finder.facing
        val right = facing.counterClockWise
        val moved = Vec3(right.stepX * at.across, at.up, right.stepZ * at.across)
        val corner = Vec3.atLowerCornerOf(finder.blockPos).add(moved)
        return PIECES.map { piece -> inTheWorld(piece, facing).move(corner) }
    }

    /** [piece], in pixels as the model has it facing north, in blocks from the corner of a finder facing [facing]. */
    private fun inTheWorld(piece: AABB, facing: Direction): AABB {
        val one = ModelledShelves.toTheBlock(facing, Vec3(piece.minX, piece.minY, piece.minZ))
        val other = ModelledShelves.toTheBlock(facing, Vec3(piece.maxX, piece.maxY, piece.maxZ))
        return AABB(one, other)
    }

    /**
     * [finder]'s carriage moved on from where it last carried its riders to where it now is, carrying what stands on it
     * and pushing what it runs into. Riders are found where it was, before it rose into them; what it runs into, where it
     * now is. A carriage that has leapt, reloaded or rerouted far from where it was carries nobody.
     */
    fun moveWhatItMeets(finder: BookFinderBlockEntity) {
        val level = finder.level ?: return
        val was = carriedTo(finder)
        val now = finder.carriageAt(nowOf(finder))
        finder.carriageCarriedTo = now
        val before = piecesAt(finder, was)
        val after = piecesAt(finder, now)
        val moved = after.first().center.subtract(before.first().center)
        if (moved.lengthSqr() < STILL || moved.length() > MOST_CARRIED) return
        val reach = before.zip(after) { from, to -> from.minmax(to) }.reduce(AABB::minmax).inflate(0.0, A_FOOT, 0.0)
        for (entity in level.getEntities(null as Entity?, reach) { !it.isSpectator }) {
            val isOurs = level.isClientSide == (entity is Player)
            if (!isOurs) continue
            val sunkIn = howFarSunkInto(before, entity.boundingBox)
            when {
                sunkIn != null -> entity.setPos(entity.position().add(moved).add(0.0, sunkIn, 0.0))
                after.any { it.intersects(entity.boundingBox) } -> entity.move(MoverType.PISTON, moved)
            }
        }
    }

    /**
     * How far [box] has sunk into the top of [pieces] beneath it, nothing for a foot resting on one and a little for one
     * risen into, or null where none lies under it within [A_FOOT].
     */
    private fun howFarSunkInto(pieces: List<AABB>, box: AABB): Double? {
        val under = pieces.filter { piece ->
            val isUnderIt = piece.maxX > box.minX && piece.minX < box.maxX && piece.maxZ > box.minZ && piece.minZ < box.maxZ
            val topIsAtItsFoot = piece.maxY in (box.minY - A_FOOT)..(box.minY + SUNK_AT_MOST)
            isUnderIt && topIsAtItsFoot
        }
        val highest = under.maxOfOrNull { it.maxY } ?: return null
        return (highest - box.minY).coerceAtLeast(0.0)
    }

    /** How near a foot must be above a piece's top to be standing on it. */
    private const val A_FOOT = 0.05

    /** How far a foot may have sunk into a piece rising under it and still be standing on it. */
    private const val SUNK_AT_MOST = 0.3

    private const val STILL = 1e-8

    /** The furthest a carriage carries anyone at once: a few ticks' travel, which a late client catches up by. */
    private const val MOST_CARRIED = 2.0
}
