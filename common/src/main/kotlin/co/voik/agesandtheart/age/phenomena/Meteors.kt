package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.content.Lures
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An Age that the sky falls on in showers (design §5.2).
 *
 * **The phenomenon is the storm ([MeteorStorm]); this is only the weather that gathers them.** Everything
 * that happens once one is up happens on the entity, which is what lets this stay a couple of rolls a tick
 * however long a storm runs — the same division `Sandfall` makes, and for the same reason.
 *
 * **Three dials** ([MeteorDials]): how *often* a storm gathers, how *long* it lasts, and how *hard* the
 * bodies come in. Instability buys each apart; a written rung raises the first two, and where both are
 * true they compound. How many bodies a storm drops follows how long it lasts and nothing else, since
 * every body is a light drawn in the sky.
 */
object Meteors {

    /**
     * One tick of it: whether another storm gathers, and where.
     *
     * **At most one at a time under an ordinary claim.** A shower is an event you notice and answer, and
     * two at once over the same country would read as weather rather than as an arrival — so the count is
     * what a rung raises first.
     */
    fun fall(level: ServerLevel, density: Double, dials: MeteorDials) {
        if (Sampling.watchers(level).isEmpty()) return
        // **Rolled at the quickened rate, and thinned back out again when nothing drew it.** A lure
        // shortens the wait, but asking whether one exists is a scan — so doing it on every tick to
        // decide whether to roll would cost a thousand times what it saves. Rolling at the faster rate
        // and letting three in four through without a lure comes to the same two rates and asks the
        // question about three times an hour instead.
        val owed = PhenomenaCeiling.isOwed(level, Phenomenon.METEORS)
        if (!owed && level.random.nextInt(quickenedFor(asIfTeeming(density, dials.often))) != NOW) return
        // **Counted after the roll, not before it.** This walks the level's whole entity list, and asking
        // it on every tick of every meteoric Age is thousands of class checks twenty times a second for an
        // answer that is nearly always the same. Behind the roll it is asked about three times an hour,
        // which is the same argument the comment above makes about the lure.
        if (gatheringIn(level) >= MOST_AT_ONCE) return
        val somebody = Sampling.somebody(level) ?: return
        val drawn = drawnNear(level, somebody.position())
        if (!owed && drawn == null && level.random.nextDouble() > WITHOUT_A_LURE) return
        if (!PhenomenaCeiling.mayBegin(level, Phenomenon.METEORS)) return
        gatherOneNearSomebody(level, somebody, drawn, density, dials)
    }

    /**
     * The plane a storm hangs its bodies off: **the highest ground under the disc, not the ground under
     * its middle** (Jonah, 2026-09-07).
     *
     * A body is thrown from a point up its own entry line measured off this plane, and aimed at the ground
     * where it lands. Taking the plane from the middle alone is right on a hillside and wrong on anything
     * steep: over an Age whose land reaches the ceiling, a shallow arrival starts twenty-odd blocks above
     * the *centre's* ground and therefore well inside a mountain a hundred blocks away. Sampling the disc
     * and taking the highest costs a handful of heightmap lookups three times an hour, and on flat ground
     * it is exactly what the old rule gave.
     *
     * **It is not a guarantee.** A spire between the samples can still poke above the plane, and a body
     * aimed past it will meet it — which is a meteor hitting a mountain, and reads as one. What this rules
     * out is the systematic case: an entire storm starting underground.
     */
    fun standsAbove(level: ServerLevel, middle: BlockPos, reach: Double): Double {
        var highest = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, middle).y
        val step = (reach * TWICE / ACROSS_THE_DISC).roundToInt().coerceAtLeast(ONE)
        var awayX = -reach.roundToInt()
        while (awayX <= reach) {
            var awayZ = -reach.roundToInt()
            while (awayZ <= reach) {
                val at = middle.offset(awayX, 0, awayZ)
                val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, at).y
                if (ground > highest) highest = ground
                awayZ += step
            }
            awayX += step
        }
        return highest.toDouble()
    }

    /**
     * How many are already up.
     *
     * **A walk of every entity in the level**, not of the storms — `EntityTypeTest` filters the result and
     * does not index it. Bounded by [MOST_AT_ONCE] in what it *returns*, which is not the same as what it
     * costs, so it is asked behind the roll rather than in front of it.
     */
    private fun gatheringIn(level: ServerLevel): Int =
        level.getEntities(EntityTypeTest.forClass(MeteorStorm::class.java)) { true }.size

    /**
     * How far from a watcher a storm may do anything at all, in blocks.
     *
     * **Everything a storm involves has to happen inside this, and that is what bounds the disc.** Past a
     * player's chunks, a body is thrown into ground neither side can read and into a chunk that does not
     * tick: it never falls, and the light that promised it goes out over nothing (Jonah, walked). Vanilla
     * fixes both limits and neither can be bought off — entities tick only within the simulation distance,
     * and one is drawn to a client only within `min(its tracking range, that player's view distance)`.
     *
     * A chunk of margin, because a player walks while a storm is coming in.
     */
    fun withinSight(level: ServerLevel): Double {
        val players = level.server.playerList
        val chunks = minOf(players.viewDistance, players.simulationDistance) - A_CHUNK_OF_MARGIN
        return (chunks * CHUNK_WIDTH).toDouble().coerceAtLeast(CHUNK_WIDTH.toDouble())
    }

    /**
     * And what is left for the disc once a body's own entry line has taken its share.
     *
     * A body is thrown from [MeteorStorm.ENTRY_RANGE] back up its line, so the throw is that much further
     * out than the place it is aimed at — which is the part of this that is spent before anything is
     * placed. What remains is the storm's distance from the watcher plus its reach, together.
     */
    private fun roomFor(level: ServerLevel): Double =
        (withinSight(level) - MeteorStorm.ENTRY_RANGE).coerceAtLeast(SMALLEST_REACH)

    /**
     * How far out a storm gathers from the watcher it gathers near, in blocks.
     *
     * Public because the debug trigger stands one at this distance when it is not told another: a storm
     * somebody asked for should be the storm the Age would have raised, and a figure of its own would drift
     * from this one the moment a view distance changed.
     */
    fun gathersAway(level: ServerLevel): Double = roomFor(level) * ((NEAREST_SHARE + FURTHEST_SHARE) / TWICE)

    /**
     * Gathers one out past somebody, **on the ground it will fall on**.
     *
     * A storm is a place rather than a thing in the air, and where that place *is* has to include its
     * height: a body is thrown from where its own light hung and aimed at the ground, so every block a
     * storm hangs above that ground steepens the arrival past the angle it was drawn at. Hanging one
     * forty-five up turned a ten-degree approach into twenty-six, and the sky and the rock stopped
     * agreeing about how a meteor comes in (Jonah, walked). Sitting it on the surface costs nothing —
     * nothing collides with it, nothing draws it, and the lights it hangs are thousands of blocks up.
     *
     * **Out past, never on top of.** A storm centred on a player is a scripted event rather than weather,
     * and design §5.2 refuses it in as many words: falling *near you* rather than *at you* is what leaves
     * shelter and running worth anything.
     *
     * **What the budget took is the storm watched from outside it.** The offset is a share of the room a
     * client leaves ([roomFor]) rather than a distance of its own, and at an ordinary view distance that
     * offset is smaller than the reach — so a player is inside the disc rather than beside it, and running
     * out of it is the whole of the answer. Design §5.2 is written as though standing outside one were the
     * common case; it is now the case only at a wide view distance.
     */
    private fun gatherOneNearSomebody(
        level: ServerLevel,
        somebody: Player,
        drawn: Lures.Drawn?,
        density: Double,
        dials: MeteorDials,
    ) {
        val random = level.random
        val bearing = random.nextDouble() * FULL_TURN
        val room = roomFor(level)
        val away = room * (NEAREST_SHARE + random.nextDouble() * (FURTHEST_SHARE - NEAREST_SHARE))
        val spot = BlockPos.containing(somebody.x + cos(bearing) * away, somebody.y, somebody.z + sin(bearing) * away)
        raise(level, spot, density, dials, drawn)
    }

    /**
     * **The one place a storm is stood up**, whoever asked for it.
     *
     * Everything about where it falls and how it behaves is decided here — the lure, the plane it hangs
     * off, how long it lasts and how many bodies that comes to — because the debug command has now twice
     * drifted from written weather by working any of it out for itself. Once over the ground it hangs off,
     * and once over lures, which it simply never consulted (Jonah, walked both). A caller supplies where
     * it would otherwise fall and what the Age is like; anything it passes beyond that is an override.
     *
     * [otherwise] is where it goes with nothing drawing it; [drawn] wins when there is.
     */
    fun raise(
        level: ServerLevel,
        otherwise: BlockPos,
        density: Double,
        dials: MeteorDials,
        drawn: Lures.Drawn?,
        slant: Double? = null,
        lasting: Int? = null,
    ): MeteorStorm {
        val middle = if (drawn != null) {
            BlockPos.containing(drawn.at.x, otherwise.y.toDouble(), drawn.at.z)
        } else {
            otherwise
        }
        val asked = drawn?.let { Lures.reachFor(it.blocks) } ?: MeteorStorm.REACH
        val reach = asked.coerceAtMost(roomLeftAround(level, middle))
        val where = Vec3(middle.x + HALF, standsAbove(level, middle, reach), middle.z + HALF)
        val falling = lasting ?: lengthenedBy(
            MeteorStorm.SHORTEST_FALL +
                level.random.nextInt(MeteorStorm.ORDINARY_FALL - MeteorStorm.SHORTEST_FALL + ONE),
            asIfTeeming(density, dials.long),
        )
        val storm = MeteorStorm.gatherAt(level, where, bodiesFor(falling), falling, dials.power, slant, reach)
        PhenomenaCeiling.began(level, Phenomenon.METEORS, storm)
        return storm
    }

    /**
     * How wide a storm centred at [middle] may spread, given where the nearest watcher is standing.
     *
     * The disc and the distance out to it come out of one budget, so a storm that gathered further off is
     * a tighter one rather than one whose far side lands where nobody can see it. A storm the debug
     * command stood up beyond the budget altogether keeps the floor and says what it got.
     */
    private fun roomLeftAround(level: ServerLevel, middle: BlockPos): Double {
        val watching = Sampling.watchers(level).minByOrNull { awayFrom(it.position(), middle) }
            ?: return MeteorStorm.REACH
        return (roomFor(level) - awayFrom(watching.position(), middle)).coerceAtLeast(SMALLEST_REACH)
    }

    /** How far apart two places are on the ground, which is the distance every limit here is measured in. */
    private fun awayFrom(watcher: Vec3, middle: BlockPos): Double =
        hypot(watcher.x - (middle.x + HALF), watcher.z - (middle.z + HALF))

    /**
     * What is drawing a storm near here, if anything — the lure a caller must consult before [raise].
     *
     * **A lure reaches as far as a storm can**, rather than further: one found beyond that would draw a
     * storm to a place whose bodies could not be thrown, which is the lure doing the very thing this
     * phenomenon was just taught not to do.
     */
    fun drawnNear(level: ServerLevel, around: Vec3): Lures.Drawn? =
        Lures.nearest(level, around, roomFor(level))

    /**
     * How many bodies a storm drops over the whole of its life, which is also what the sky promises: **a
     * body every quarter-second, for as long as it falls**, and nothing else changes it (Jonah,
     * 2026-09-23). Every one is a light in the sky before it is a rock on the ground, so a longer storm is
     * more of them and a harder one is not.
     */
    private fun bodiesFor(falling: Int): Int = (falling / EVERY).coerceAtLeast(ONE)

    /**
     * A storm's own length — **ten seconds at the low end, a full minute at the high** (Jonah).
     *
     * A minute of bodies at full weight drills a landscape down rather than pocking it, which is what the
     * top of this scale is meant to be: not a longer nuisance but a different order of event.
     */
    private fun lengthenedBy(falling: Int, density: Double): Int =
        (falling * (ONE_WHOLE + (density - Rung.ORDINARY) * LONGER_WHEN_TEEMING))
            .roundToInt()
            .coerceIn(MeteorStorm.SHORTEST_FALL, MeteorStorm.LONGEST_FALL)

    /**
     * How long to wait between storms, in ticks of rolling.
     *
     * A quiet Age goes minutes between them; a teeming one is scarcely out of one. Divided rather than
     * subtracted so the ends of the range stay proportionate however the middle is tuned.
     */
    private fun betweenStormsFor(density: Double): Int =
        (BETWEEN_STORMS / (density / Rung.ORDINARY).coerceAtLeast(A_TRICKLE)).roundToInt().coerceAtLeast(ONE)

    /** The wait a lure would buy, which is what the roll is actually made at — see [fall]. */
    private fun quickenedFor(density: Double): Int =
        (betweenStormsFor(density) * WITH_A_LURE).roundToInt().coerceAtLeast(ONE)

    private const val NOW = 0
    private const val ONE = 1
    private const val ONE_WHOLE = 1.0

    /**
     * How much of [roomFor]'s budget goes on standing the storm away from the watcher, the rest being its
     * reach — **far enough out to be somewhere else, and never so far that its far side is out of sight**.
     *
     * Shares rather than distances, because the budget is the player's own view and simulation distances
     * and a storm has to fit whatever those are. At the usual twelve chunks this is a storm gathered
     * sixteen to forty blocks off with a reach of forty to sixty-four, where it used to be forty to a
     * hundred and fifty off with a reach of a hundred and thirty-five — smaller, and every body of it
     * lands where it can be watched landing.
     */
    private const val NEAREST_SHARE = 0.2
    private const val FURTHEST_SHARE = 0.5

    /** No storm is thinner than this, however little room a view distance leaves it. */
    private const val SMALLEST_REACH = 16.0

    /** Kept back from the view distance, because a player walks while a storm is coming in. */
    private const val A_CHUNK_OF_MARGIN = 1
    private const val CHUNK_WIDTH = 16

    /**
     * One storm at a time, whatever the rung. A rung already buys more storms by shortening the wait
     * between them, and two at once is every light in the sky drawn twice a frame.
     */
    private const val MOST_AT_ONCE = 1

    /** A body every this many ticks — four a second. */
    private const val EVERY = 5

    /** What a rung adds to a storm's length: an ordinary Age ten seconds, a teeming one a minute. */
    private const val LONGER_WHEN_TEEMING = 1.0

    /**
     * How long an ordinary meteoric Age waits between storms, in ticks — **twenty minutes** (Jonah).
     *
     * **The rung divides this rather than multiplying a base nobody sees.** The old shape was
     * `3600 / (1 + density × 5)`, which put an *ordinary* claim at six times its own base rate and left
     * the written figure meaning nothing — thirty seconds between storms, which is a siege rather than
     * weather. A rung now says what it does: twice the claim, twice as often.
     *
     * Rain is the scale this is pitched against: vanilla begins a downpour about every eighty minutes.
     * A storm being rarer than the material's own farm wants is deliberate — the crater deposit (§5.2)
     * is the trickle that makes the first astrite affordable, and the lure is what makes the rest of it.
     */
    private const val BETWEEN_STORMS = 24000.0

    /** No rung goes to nothing; a claim this faint still means the Age has meteors in it. */
    private const val A_TRICKLE = 0.1

    /** How many samples span the disc when looking for the highest ground under it. */
    private const val ACROSS_THE_DISC = 6
    private const val TWICE = 2.0
    private const val HALF = 0.5

    /** What a lure takes off the wait, and the share that must be thinned out again without one. */
    private const val WITH_A_LURE = 0.75
    private const val WITHOUT_A_LURE = 0.75

    private const val FULL_TURN = 2.0 * PI
}
