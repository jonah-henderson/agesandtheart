package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.content.AstriteBlock
import co.voik.agesandtheart.content.Lures
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.DustParticleOptions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a lure looks like while it is drawing, and what it does when a storm answers (design §7.1.2).
 *
 * **Drawn about the cluster, not about each block.** This began on the block's own `animateTick`, which
 * gave every block of a lure its own ring — eight blocks, eight rings, all overlapping and none of them
 * the shape of the thing. A block also cannot cheaply know where its cluster's middle is, and asking would
 * cost a scan per block per tick. From the client's own tick there is one scan, one centroid and one set of
 * rings, which is both cheaper and the picture that was wanted.
 *
 * **It costs nothing anywhere a lure cannot be.** The whole thing is behind a check on the viewer's own
 * height, and a lure only exists above [AstriteBlock.HIGH_ENOUGH] — so at any ordinary altitude this is one
 * comparison and a return.
 */
object LureLooks {

    /** Called from each loader's client tick, beside the storms' own wind. */
    fun pulse(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        if (player.y < AstriteBlock.HIGH_ENOUGH - WITHIN_SIGHT) return
        val drawn = Lures.nearest(level, player.position(), WITHIN_SIGHT) ?: return
        breathe(level, drawn)
        answer(level, drawn)
    }

    /**
     * The rings it turns over while it waits.
     *
     * Several at once, each on its own clock and its own size, and **every other one running inward** — so
     * the set breathes rather than pulsing in step, which is what stops a handful of rings reading as one
     * flashing ring. They thin as they widen, so the outer edge fades instead of ending.
     */
    private fun breathe(level: ClientLevel, drawn: Lures.Drawn) {
        val widest = WIDEST_OUT + Lures.drawnness(drawn.blocks) * WIDER_WHEN_DRAWN_HARD
        for (ring in 0..<RINGS) {
            val turns = RING_TURNS + ring * TURNS_VARY
            val phase = ((level.gameTime + ring * RINGS_APART) % turns).toDouble() / turns
            val out = if (ring % EVERY_OTHER == FIRST) phase else ALL_OF_IT - phase
            val radius = CLOSEST_IN + (widest + ring * SIZES_VARY - CLOSEST_IN) * out
            val motes = (MOTES_A_RING * (ALL_OF_IT - out)).toInt() + AT_LEAST_ONE
            repeat(motes) {
                val around = level.random.nextDouble() * FULL_TURN
                level.addParticle(
                    DRAWING,
                    drawn.at.x + cos(around) * radius,
                    drawn.at.y + MIDDLE,
                    drawn.at.z + sin(around) * radius,
                    NO_DRIFT,
                    NO_DRIFT,
                    NO_DRIFT,
                )
            }
        }
    }

    /**
     * The column it throws up when a storm actually answers it.
     *
     * **Read off the storm rather than sent**: a storm that was drawn in carries a reach narrower than a
     * storm ever has on its own, and it knows its own age — so "this lure has just been answered" is two
     * comparisons on an entity the client already has.
     */
    private fun answer(level: ClientLevel, drawn: Lures.Drawn) {
        val storm = level.entitiesForRendering()
            .filterIsInstance<MeteorStorm>()
            .firstOrNull { justAnswered(it, drawn) } ?: return
        val risen = storm.age.toDouble() / COLUMN_LASTS
        repeat(MOTES_A_COLUMN) {
            val up = level.random.nextDouble() * risen * COLUMN_REACHES
            // Thinner the higher it goes, so the head of it frays out rather than stopping flat.
            if (level.random.nextDouble() * COLUMN_REACHES < up) return@repeat
            level.addParticle(
                DRAWING,
                drawn.at.x + strayed(level),
                drawn.at.y + MIDDLE + up,
                drawn.at.z + strayed(level),
                NO_DRIFT,
                RISES_AT,
                NO_DRIFT,
            )
        }
    }

    private fun justAnswered(storm: MeteorStorm, drawn: Lures.Drawn): Boolean {
        val wasDrawn = storm.reach < MeteorStorm.REACH - A_LITTLE
        val stillRising = storm.age in FIRST..<COLUMN_LASTS
        val overThisOne = storm.position().multiply(ALL_OF_IT, NO_DRIFT, ALL_OF_IT)
            .distanceToSqr(drawn.at.multiply(ALL_OF_IT, NO_DRIFT, ALL_OF_IT)) < OVER_IT * OVER_IT
        return wasDrawn && stillRising && overThisOne
    }

    private fun strayed(level: ClientLevel): Double = (level.random.nextDouble() - MIDDLE) * COLUMN_WANDERS

    /** The pack's violet, in the one particle vanilla lets us colour. */
    private val DRAWING = DustParticleOptions(0x9E72FF, 1.0f)

    /** How far off a lure is still worth drawing, and how near a storm must be to be *this* lure's. */
    private const val WITHIN_SIGHT = 48.0
    private const val OVER_IT = 24.0

    /** How many rings turn at once, and how far apart their clocks and their sizes run. */
    private const val RINGS = 3
    private const val RING_TURNS = 70L
    private const val TURNS_VARY = 13L
    private const val RINGS_APART = 23L
    private const val SIZES_VARY = 0.5

    private const val CLOSEST_IN = 0.6
    private const val WIDEST_OUT = 2.6
    private const val WIDER_WHEN_DRAWN_HARD = 3.4
    private const val MOTES_A_RING = 4

    /** The column: how long it takes to reach its height, and what that height is. */
    private const val COLUMN_LASTS = 40
    private const val COLUMN_REACHES = 8.0
    private const val COLUMN_WANDERS = 0.7
    private const val MOTES_A_COLUMN = 12
    private const val RISES_AT = 0.06

    /** Below any reach a storm has of its own accord, so being narrower means something drew it. */
    private const val A_LITTLE = 1.0

    private const val EVERY_OTHER = 2
    private const val FIRST = 0
    private const val AT_LEAST_ONE = 1
    private const val MIDDLE = 0.5
    private const val NO_DRIFT = 0.0
    private const val ALL_OF_IT = 1.0
    private const val FULL_TURN = 2.0 * PI
}
