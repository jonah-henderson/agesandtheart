package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AstriteBlock
import co.voik.agesandtheart.content.Lures
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.world.phys.Vec3
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
 * **The rings and the column are found by opposite routes, and that is the point.** A ring is a close-up
 * detail of a block you are standing at, so it starts from the viewer and costs nothing at any ordinary
 * altitude. A column announces to *the ground* that this lure is what the storm came for, and it stands
 * for as long as the violet does — so it starts from the storm instead, which already stands on the
 * cluster's own middle. Hanging it off the viewer's height put it behind the one condition that made it
 * unwatchable.
 */
object LureLooks {

    /** Called from `ClientSetup.clientTick`, beside the storms' own wind. */
    fun pulse(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        answer(level)
        if (player.y < AstriteBlock.HIGH_ENOUGH - WITHIN_SIGHT) return
        val drawn = Lures.nearest(level, player.position(), WITHIN_SIGHT) ?: return
        breathe(level, drawn)
    }

    /**
     * The rings it turns over while it waits.
     *
     * Several at once, each on its own clock and its own size, and **every other one running inward** — so
     * the set breathes rather than pulsing in step, which is what stops a handful of rings reading as one
     * flashing ring. They thin as they widen, so the outer edge fades instead of ending, and the whole set
     * grows with how hard the cluster draws, which is how adding blocks is visibly worth something.
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
                mote(
                    level,
                    drawn.at.x + cos(around) * radius,
                    drawn.at.y + MIDDLE,
                    drawn.at.z + sin(around) * radius,
                    NO_DRIFT,
                )
            }
        }
    }

    /**
     * The column a lure throws up for as long as a storm is answering it.
     *
     * **Found from the storm rather than from the viewer.** A storm drawn to a lure stands on that
     * cluster's own middle, so its position *is* where the column goes; all the scan has to settle is how
     * high the blocks were stacked. Nothing is sent for this — a narrowed reach is a reach no storm has on
     * its own, and both sides work the rest out from the storm's own clock.
     *
     * **It rides [MeteorStorm.castStrength] rather than a window of its own**, which is what makes "up
     * with the warning light and down with it" true by construction: the column and the violet are then
     * the same curve read twice, and retiming one cannot leave the other behind.
     */
    private fun answer(level: ClientLevel) {
        for (entity in level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            if (!drawnIn(storm)) continue
            val standing = storm.castStrength()
            if (standing <= NOTHING) continue
            val lure = Lures.nearest(level, aloftOver(storm), NEAR_ITS_MIDDLE) ?: continue
            raise(level, lure, standing)
        }
    }

    private fun drawnIn(storm: MeteorStorm): Boolean = storm.reach < MeteorStorm.REACH - A_LITTLE

    /** Where to look for the blocks that drew a storm: straight up its own middle, into the headroom. */
    private fun aloftOver(storm: MeteorStorm): Vec3 =
        Vec3(storm.x, (AstriteBlock.HIGH_ENOUGH + INTO_THE_HEADROOM).toDouble(), storm.z)

    private fun raise(level: ClientLevel, lure: Lures.Drawn, standing: Float) {
        repeat(MOTES_A_COLUMN) {
            val up = level.random.nextDouble() * standing * COLUMN_REACHES
            // Thinner the higher it goes, so the head of it frays out rather than stopping flat.
            if (level.random.nextDouble() * COLUMN_REACHES < up) return@repeat
            mote(
                level,
                lure.at.x + strayed(level),
                lure.at.y + MIDDLE + up,
                lure.at.z + strayed(level),
                RISES_AT,
            )
        }
    }

    /**
     * One mote, drawn however far off it is.
     *
     * Forced past the thirty-two-block cull on purpose: both of these are signals rather than ambience,
     * and the column's whole job is to be read from the ground a couple of hundred blocks below.
     */
    private fun mote(level: ClientLevel, x: Double, y: Double, z: Double, drift: Double) {
        level.addParticle(DRAWING, FORCED, SHOW_ANYWAY, x, y, z, NO_DRIFT, drift, NO_DRIFT)
    }

    private fun strayed(level: ClientLevel): Double = (level.random.nextDouble() - MIDDLE) * COLUMN_WANDERS

    /** The pack's violet, in the one particle vanilla lets us colour. */
    private val DRAWING = DustParticleOptions(AgeContent.ASTRITE_TINT, 1.0f)

    private const val FORCED = true
    private const val SHOW_ANYWAY = true

    /** How far off a lure is still worth drawing rings for. */
    private const val WITHIN_SIGHT = 48.0

    /** And how far above a storm's own middle to go looking for the blocks that drew it. */
    private const val NEAR_ITS_MIDDLE = 32.0
    private const val INTO_THE_HEADROOM = 16

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

    /** The column, whose height is the cast's own strength — so it stands for the whole of the storm. */
    private const val COLUMN_REACHES = 8.0
    private const val COLUMN_WANDERS = 0.7
    private const val MOTES_A_COLUMN = 12
    private const val RISES_AT = 0.06

    /** Below any reach a storm has of its own accord, so being narrower means something drew it. */
    private const val A_LITTLE = 1.0

    private const val EVERY_OTHER = 2
    private const val FIRST = 0
    private const val AT_LEAST_ONE = 1
    private const val NOTHING = 0.0f
    private const val MIDDLE = 0.5
    private const val NO_DRIFT = 0.0
    private const val ALL_OF_IT = 1.0
    private const val FULL_TURN = 2.0 * PI
}
