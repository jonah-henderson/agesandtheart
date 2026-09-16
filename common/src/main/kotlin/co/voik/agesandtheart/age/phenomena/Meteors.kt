package co.voik.agesandtheart.age.phenomena

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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An Age that the sky falls on in showers (design §5.2).
 *
 * **The phenomenon is the storm ([MeteorStorm]); this is only the weather that gathers them.** Everything
 * that happens once one is up happens on the entity, which is what lets this stay a couple of rolls a tick
 * however long a storm runs — the same division `Sandfall` makes, and for the same reason.
 *
 * **Its three dials are all instability's** (Jonah, 2026-09-06), which is one more than any other
 * phenomenon here has: how *often* a storm gathers, how *long* it lasts, and how *hard* the bodies come
 * in. A rung raises all three, so `teeming meteors` is not merely more storms but worse ones — which is
 * what stops the top of the ramp reading as the bottom repeated.
 */
object Meteors {

    /**
     * One tick of it: whether another storm gathers, and where.
     *
     * **At most one at a time under an ordinary claim.** A shower is an event you notice and answer, and
     * two at once over the same country would read as weather rather than as an arrival — so the count is
     * what a rung raises first.
     */
    fun fall(level: ServerLevel, density: Double, fury: Double) {
        if (Sampling.watchers(level).isEmpty()) return
        // **Rolled at the quickened rate, and thinned back out again when nothing drew it.** A lure
        // shortens the wait, but asking whether one exists is a scan — so doing it on every tick to
        // decide whether to roll would cost a thousand times what it saves. Rolling at the faster rate
        // and letting three in four through without a lure comes to the same two rates and asks the
        // question about three times an hour instead.
        if (level.random.nextInt(quickenedFor(density)) != NOW) return
        // **Counted after the roll, not before it.** This walks the level's whole entity list, and asking
        // it on every tick of every meteoric Age is thousands of class checks twenty times a second for an
        // answer that is nearly always the same. Behind the roll it is asked about three times an hour,
        // which is the same argument the comment above makes about the lure.
        if (gatheringIn(level) >= MOST_AT_ONCE) return
        val somebody = Sampling.somebody(level) ?: return
        val drawn = drawnNear(level, somebody.position())
        if (drawn == null && level.random.nextDouble() > WITHOUT_A_LURE) return
        gatherOneNearSomebody(level, somebody, drawn, density, fury)
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
     * Gathers one out past somebody, **on the ground it will fall on**.
     *
     * A storm is a place rather than a thing in the air, and where that place *is* has to include its
     * height: a body is thrown from where its own light hung and aimed at the ground, so every block a
     * storm hangs above that ground steepens the arrival past the angle it was drawn at. Hanging one
     * forty-five up turned a ten-degree approach into twenty-six, and the sky and the rock stopped
     * agreeing about how a meteor comes in (Jonah, walked). Sitting it on the surface costs nothing —
     * nothing collides with it, nothing draws it, and the lights it hangs are thousands of blocks up.
     *
     * **Out past, and rarely overhead.** A storm centred on a player is a scripted event rather than
     * weather, and design §5.2 refuses it in as many words: falling *near you* rather than *over an area
     * you are in* makes shelter useless, because you are being aimed at. One gathered a little way off is
     * a place you can see being pounded, walk out of, or walk toward once it is over.
     */
    private fun gatherOneNearSomebody(
        level: ServerLevel,
        somebody: Player,
        drawn: Lures.Drawn?,
        density: Double,
        fury: Double,
    ) {
        val random = level.random
        val bearing = random.nextDouble() * FULL_TURN
        val away = NEAREST_APPROACH + random.nextDouble() * (FURTHEST_APPROACH - NEAREST_APPROACH)
        val spot = BlockPos.containing(somebody.x + cos(bearing) * away, somebody.y, somebody.z + sin(bearing) * away)
        raise(level, spot, density, fury, drawn)
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
        fury: Double,
        drawn: Lures.Drawn?,
        slant: Double? = null,
        lasting: Int? = null,
    ): MeteorStorm {
        val middle = if (drawn != null) {
            BlockPos.containing(drawn.at.x, otherwise.y.toDouble(), drawn.at.z)
        } else {
            otherwise
        }
        val reach = drawn?.let { Lures.reachFor(it.blocks) } ?: MeteorStorm.REACH
        val where = Vec3(middle.x + HALF, standsAbove(level, middle, reach), middle.z + HALF)
        val falling = lasting ?: lengthenedBy(
            MeteorStorm.SHORTEST_FALL +
                level.random.nextInt(MeteorStorm.ORDINARY_FALL - MeteorStorm.SHORTEST_FALL + ONE),
            density,
        )
        return MeteorStorm.gatherAt(level, where, bodiesFor(fury, falling), falling, fury, slant, reach)
    }

    /** What is drawing a storm near here, if anything — the lure a caller must consult before [raise]. */
    fun drawnNear(level: ServerLevel, around: Vec3): Lures.Drawn? =
        Lures.nearest(level, around, FURTHEST_APPROACH)

    /**
     * How many bodies a storm drops over the whole of its life, which is also what the sky promises.
     *
     * **Worked out from how long it falls for**, so a longer storm is not a denser one: what a rung buys
     * is the *rate*, and the length is bought separately by [lengthenedBy]. Every one of these is a light
     * in the sky before it is a rock on the ground, so this number is what a player actually counts — and
     * it is a third dial the instability already had rather than a fourth.
     *
     * **Thinned, and the rung widened to make up for it** (Jonah, walked). A forty-five-second storm at
     * the old rate was three hundred bodies, which is three hundred lights drawn every frame; and the
     * impact disc is four times the area it was, so the same count over it was never going to read as the
     * same pounding anyway. An ordinary storm is now a body every quarter-second and a ruined Age's is
     * back past where this started.
     */
    private fun bodiesFor(fury: Double, falling: Int): Int =
        (falling / EVERY * (ONE_WHOLE + fury * THICKER_WHEN_FIERCE))
            .roundToInt()
            .coerceIn(ONE, falling / CLOSEST_TOGETHER)

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
     * Far enough out to be somewhere else, near enough to see and to reach afterwards.
     *
     * **Bounded above by the simulation distance, which is the real constraint.** Entities tick only in
     * chunks a player keeps ticking — about ten chunks — so a storm gathered further out than this does
     * not happen at all until somebody walks towards it, which is worse than one that lands on you. With
     * a disc a hundred and thirty-five wide, that leaves about one storm in seven centred far enough off
     * to be watched from outside; the rest are somewhere you are standing, and running is the answer.
     */
    private const val NEAREST_APPROACH = 40.0
    private const val FURTHEST_APPROACH = 152.0

    /**
     * One storm at a time, whatever the rung. A rung already buys more storms by shortening the wait
     * between them, and two at once is every light in the sky drawn twice a frame.
     */
    private const val MOST_AT_ONCE = 1

    /** A body every this many ticks in an Age at rest — four a second. */
    private const val EVERY = 5

    /** What being fierce adds to that, and how close together bodies may get however fierce it is. */
    private const val THICKER_WHEN_FIERCE = 2.2
    private const val CLOSEST_TOGETHER = 2

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
