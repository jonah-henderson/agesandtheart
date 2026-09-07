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
        if (level.players().isEmpty()) return
        if (gatheringIn(level) >= atMostFor(density)) return
        // **Rolled at the quickened rate, and thinned back out again when nothing drew it.** A lure
        // shortens the wait, but asking whether one exists is a scan — so doing it on every tick to
        // decide whether to roll would cost a thousand times what it saves. Rolling at the faster rate
        // and letting three in four through without a lure comes to the same two rates and asks the
        // question about three times an hour instead.
        if (level.random.nextInt(quickenedFor(density)) != NOW) return
        val somebody = level.players()[level.random.nextInt(level.players().size)]
        val drawn = Lures.nearest(level, somebody.position(), FURTHEST_APPROACH)
        if (drawn == null && level.random.nextDouble() > WITHOUT_A_LURE) return
        gatherOneNearSomebody(level, somebody, drawn, density, fury)
    }

    /** How many are already up. Bounded by [atMostFor], so this is a walk over one or two. */
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
        // A lure says where; without one it is a bearing and a distance, as it has always been.
        val middle = if (drawn != null) {
            BlockPos.containing(drawn.at.x, somebody.y, drawn.at.z)
        } else {
            val bearing = random.nextDouble() * FULL_TURN
            val away = NEAREST_APPROACH + random.nextDouble() * (FURTHEST_APPROACH - NEAREST_APPROACH)
            BlockPos.containing(somebody.x + cos(bearing) * away, somebody.y, somebody.z + sin(bearing) * away)
        }
        val where = Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, middle))
        val baseline = MeteorStorm.SHORTEST_FALL +
            random.nextInt(MeteorStorm.ORDINARY_FALL - MeteorStorm.SHORTEST_FALL + ONE)
        val falling = lengthenedBy(baseline, density)
        val reach = drawn?.let { Lures.reachFor(it.blocks) } ?: MeteorStorm.REACH
        MeteorStorm.gatherAt(level, where, bodiesFor(fury, falling), falling, fury, reach = reach)
    }

    /** How many storms may be up at once — one ordinarily, and more as a rung asks for more. */
    private fun atMostFor(density: Double): Int = (ONE + density * MORE_AT_ONCE).roundToInt()

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


    private const val MORE_AT_ONCE = 1.5

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

    /** What a lure takes off the wait, and the share that must be thinned out again without one. */
    private const val WITH_A_LURE = 0.75
    private const val WITHOUT_A_LURE = 0.75

    private const val FULL_TURN = 2.0 * PI
}
