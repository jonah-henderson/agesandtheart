package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.roundToInt

/**
 * Snow driven sideways (design §5.2) — the Age buried from one direction rather than dusted from above.
 *
 * **The wind is the whole character of it** (Jonah, 2026-09-05). Snow that settles evenly is weather; snow
 * that piles against one face of everything, drifts up a wall and scours the other side is a *storm*, and
 * the difference is one vector. So a blizzard picks a bearing and lays its snow along it: deep where the
 * wind is stopped, thin where it runs free.
 *
 * **The bearing is derived, never stored** (§5.4). It comes from the Age's seed and the day, so every
 * client and every reload agree about which way the wind is blowing without anything being written down,
 * and a new day brings a new storm rather than the same one for ever.
 *
 * **A drift is a record of itself.** Snow piles into a layer, a full layer becomes a block and takes the
 * next drift on top of it, and three of any stage standing together harden the bottom one — snow to ice,
 * ice to packed, packed to blue, which is terminal. So a column grows while each block in it stops, and
 * what is left is a bank with fresh snow at the top and blue ice at the bottom: how long a blizzard has
 * worked this place, readable from the side, with nothing written down (§5.4).
 *
 * **Light is the answer and it is vanilla's own.** `Biome.shouldFreeze` and `shouldSnow` have both tested
 * `getBrightness(BLOCK) < 10` since the game had snow, so lighting your ground already stops it piling up;
 * nothing here re-states that rule, it simply obeys it. What is ours is holding the player to the same
 * threshold, which is what makes a lit path home worth building.
 */
object Blizzard {

    /**
     * One tick of it, over the ground near whoever is in the Age.
     *
     * Does nothing at all unless the Age is actually in weather: a blizzard *is* the storm, so what makes
     * it come and go is [shareOfTheTime] steering the Age's own weather rather than anything decided here.
     */
    fun blow(level: ServerLevel, density: Double, fury: Double) {
        if (!level.isRaining) return
        val severity = severityOf(density, fury)
        val bearing = bearingIn(level)
        val cursor = BlockPos.MutableBlockPos()
        for (player in level.players()) {
            val around = BlockPos.containing(player.position())
            repeat(driftsPerTick(severity)) {
                val x = around.x + level.random.nextInt(-REACH, REACH)
                val z = around.z + level.random.nextInt(-REACH, REACH)
                driftAt(level, cursor, x, z, bearing, severity)
            }
        }
    }

    /**
     * Tell everyone in [level] whether a blizzard blows here and how hard.
     *
     * Sent even where there is none, because a client that is only told when there *is* one keeps the last
     * storm it heard about after its owner links somewhere calm.
     */
    fun tellTheClients(
        level: ServerLevel,
        befalls: Map<Phenomenon, Double>,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ) {
        val age = level.dimension().identifier()
        val density = befalls[Phenomenon.BLIZZARD]
        val telling = if (density == null) {
            BlizzardPayload.noneIn(age)
        } else {
            val severity = severityOf(density, Happenings.furyOf(spending, prices, Phenomenon.BLIZZARD))
            BlizzardPayload(age, severity, bearingIn(level).get2DDataValue())
        }
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    /**
     * How much of the time an Age at this severity is in a storm, as [AgeWeather.Conditions] wants it.
     *
     * **One of the two things severity drives**, and the one that decides how much of your life is spent
     * in a storm: an ordinary blizzard comes about as often as vanilla's rain and passes in a few minutes,
     * where a furious Age is scarcely ever out of one.
     *
     * The other is how hard it blows while it is here — [driftsPerTick] and [inTheLee] on this side, and
     * the visibility, the wind and the speed of the snow on the client's. A blizzard bought with
     * instability or asked for at a rung is fiercer *and* more constant, because a storm that came more
     * often without getting worse would only be tedious.
     */
    fun shareOfTheTime(severity: Double): Double =
        (AS_OFTEN_AS_RAIN + (severity - Rung.ORDINARY) * MORE_OF_THE_TIME)
            .coerceIn(AS_OFTEN_AS_RAIN, ALMOST_ALWAYS)

    /**
     * How hard this blizzard is, from what was written and what was inflicted together.
     *
     * A written rung says how hard a writer asked for it; an Age's instability arrives at
     * [Rung.ORDINARY] with fury as the only thing making it fierce ([Happenings.befalling]). The two
     * compound, because an Age that asked for a blizzard *and* fell apart has both.
     */
    fun severityOf(density: Double, fury: Double): Double =
        (density / Rung.ORDINARY) * (Rung.ORDINARY + fury * FURY_DRIVES)

    /**
     * Which way the wind blows here today — the same answer for everyone, from nothing written down.
     *
     * Keyed on the Age's seed and its day, so a storm has a direction that holds while it blows and the
     * next one does not inherit it.
     */
    fun bearingIn(level: ServerLevel): Direction {
        val day = level.gameTime / TICKS_PER_DAY
        val drawn = XoroshiroRandomSource(level.seed xor WIND_SALT xor day).nextInt(HORIZONS)
        return Direction.from2DDataValue(drawn)
    }

    /**
     * One drift, at the column over [x], [z].
     *
     * **Deeper where the wind is stopped.** A column with rock immediately downwind is in the lee of it,
     * which is where a drift actually builds — so that is where the snow goes several layers at a time,
     * and open ground gets one layer at a time like ordinary weather. That is the whole of "driving": the
     * same snowfall, distributed by what is in its way.
     */
    private fun driftAt(
        level: ServerLevel,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        z: Int,
        bearing: Direction,
        severity: Double,
    ) {
        // **`WORLD_SURFACE`, not `MOTION_BLOCKING`**, and the difference is the whole of why nothing used
        // to stack. A one-layer snow does not block motion and a two-layer one does, so the motion
        // heightmap jumped above the drift the moment it reached two — and `SnowLayerBlock.canSurvive`
        // refuses a layer on top of anything but a full face or a full eight, so every column in the Age
        // stopped dead at two layers and the ground read as noise. `NOT_AIR` puts this above the drift
        // whatever depth it is.
        val open = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        cursor.set(x, open, z)
        if (!level.isLoaded(cursor)) return
        if (level.getBrightness(LightLayer.BLOCK, cursor) >= KEEPS_ITS_GROUND) return
        if (!level.canSeeSky(cursor)) return

        // **Snow runs downhill before it piles up**, which is what stops a storm laying spikes and pits.
        // Each drift is placed at random and independently, so left alone the depths are Poisson noise;
        // real snow finds the low ground first, and one look at the neighbours is the whole of that.
        val (atX, atZ) = downhillOf(level, x, z, open)
        if (atX != x || atZ != z) {
            driftOnto(level, cursor, atX, atZ, bearing, severity)
            return
        }
        driftOnto(level, cursor, x, z, bearing, severity)
    }

    /**
     * The column a flake laid here would actually come to rest on — this one, or a markedly lower neighbour.
     *
     * A snow bank has an angle of repose; a column of noise does not. Nothing here models an angle, it
     * simply refuses to build a tower beside a hollow, which is enough for a drift to read as one.
     */
    private fun downhillOf(level: ServerLevel, x: Int, z: Int, open: Int): Pair<Int, Int> {
        var lowestX = x
        var lowestZ = z
        var lowest = open
        for (way in Direction.Plane.HORIZONTAL) {
            val overX = x + way.stepX
            val overZ = z + way.stepZ
            val theirs = level.getHeight(Heightmap.Types.WORLD_SURFACE, overX, overZ)
            if (theirs < lowest - A_STEEP_STEP) {
                lowest = theirs
                lowestX = overX
                lowestZ = overZ
            }
        }
        return lowestX to lowestZ
    }

    /**
     * Lay this drift on the column at [x], [z].
     *
     * **Deeper where the wind is stopped**, which is what "driving" means mechanically: the same snowfall,
     * distributed by what is in its way. A column in the lee grows faster, and because a full column
     * becomes a block and takes another on top of it, that growth *climbs* the wall rather than only
     * thickening at its foot.
     */
    private fun driftOnto(
        level: ServerLevel,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        z: Int,
        bearing: Direction,
        severity: Double,
    ) {
        val open = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        val top = open - 1
        if (depthOfDriftAt(level, cursor, x, top, z) >= DEEPEST_DRIFT) return

        cursor.set(x, top, z)
        val standing = level.getBlockState(cursor)
        val sheltered = level.getBlockState(cursor.immutable().relative(bearing)).isSolidRender
        val laying = if (sheltered) inTheLee(severity) else ONE_LAYER

        if (standing.`is`(Blocks.SNOW)) {
            val deep = standing.getValue(SnowLayerBlock.LAYERS)
            if (deep + laying <= SnowLayerBlock.MAX_HEIGHT) {
                level.setBlock(cursor, snowOf(deep + laying), Block.UPDATE_ALL)
            } else {
                // **A full drift becomes a block and the next one starts on top of it**, which is what lets
                // snow pile on itself. Vanilla would allow a layer over a full eight, but hardening it here
                // is what makes the column a record of its own depth.
                level.setBlock(cursor, Blocks.SNOW_BLOCK.defaultBlockState(), Block.UPDATE_ALL)
                cursor.set(x, open, z)
                level.setBlock(cursor, snowOf(deep + laying - SnowLayerBlock.MAX_HEIGHT), Block.UPDATE_ALL)
            }
        } else {
            cursor.set(x, open, z)
            if (Blocks.SNOW.defaultBlockState().canSurvive(level, cursor)) {
                level.setBlock(cursor, snowOf(laying), Block.UPDATE_ALL)
            }
        }
        settle(level, cursor, x, top, z)
    }

    /** A snow layer [layers] deep, clamped to what one block can hold. */
    private fun snowOf(layers: Int): BlockState = Blocks.SNOW.defaultBlockState()
        .setValue(SnowLayerBlock.LAYERS, layers.coerceIn(ONE_LAYER, SnowLayerBlock.MAX_HEIGHT))

    /**
     * How deep the drift standing over this column already is, in blocks of ours.
     *
     * Counts down from the top through anything the storm could have laid, so a column that has been
     * compressing for a while is measured by what it has become rather than by what fell last.
     */
    private fun depthOfDriftAt(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int): Int {
        var depth = 0
        while (depth < DEEPEST_DRIFT) {
            cursor.set(x, top - depth, z)
            if (!laidByAStorm(level.getBlockState(cursor))) break
            depth++
        }
        cursor.set(x, top, z)
        return depth
    }

    /**
     * The compression ladder, walked up the column: **three of a stage and the bottom one hardens**.
     *
     * Snow becomes ice, ice becomes packed, packed becomes blue, and blue is terminal — so a place cannot
     * progress for ever the way a rising sea can, while the *column* goes on growing. What that leaves is a
     * drift whose depth is its history: fresh snow at the top and blue ice at the bottom, readable at a
     * glance from the side (§5.4). Nothing records how long a blizzard has worked a place; the drift is the
     * record.
     */
    private fun settle(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int) {
        for ((stage, hardened) in PACKING) {
            var run = 0
            for (depth in 0..<DEEPEST_DRIFT) {
                cursor.set(x, top - depth, z)
                if (!level.getBlockState(cursor).`is`(stage)) {
                    run = 0
                    continue
                }
                run++
                if (run < A_DEEP_ENOUGH_RUN) continue
                level.setBlock(cursor, hardened.defaultBlockState(), Block.UPDATE_ALL)
                run = 0
            }
        }
    }

    /** Whether a blizzard could have put this here, which is what a drift is measured through. */
    private fun laidByAStorm(state: BlockState): Boolean =
        state.`is`(Blocks.SNOW) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(Blocks.ICE) ||
            state.`is`(Blocks.PACKED_ICE) || state.`is`(Blocks.BLUE_ICE)

    /** What each stage of the drift hardens into, once three of it stand together. */
    private val PACKING = listOf(
        Blocks.SNOW_BLOCK to Blocks.ICE,
        Blocks.ICE to Blocks.PACKED_ICE,
        Blocks.PACKED_ICE to Blocks.BLUE_ICE,
    )

    /** How much lower a neighbour must be before the snow goes there instead. */
    private const val A_STEEP_STEP = 2

    /** Three of a stage and the bottom one hardens, which is what lets the column keep growing. */
    private const val A_DEEP_ENOUGH_RUN = 3

    /**
     * How deep a drift may get before the storm stops adding to it.
     *
     * **Not a fence against burial**, which is the hazard and is meant to happen — only against a column
     * that would otherwise climb to the top of the world.
     */
    private const val DEEPEST_DRIFT = 24

    /** How many layers a sheltered column takes at once — more of them the fiercer the storm. */
    private fun inTheLee(severity: Double): Int =
        (LEE_LAYERS * severity).roundToInt().coerceIn(LEE_LAYERS, MOST_AT_ONCE)

    /** How many columns are touched a tick, which is how fast the Age fills in. */
    private fun driftsPerTick(severity: Double): Int =
        (DRIFTS_ORDINARILY * severity).roundToInt().coerceIn(1, MOST_DRIFTS)

    /** Vanilla's threshold, and the whole of the counterplay. */
    private const val KEEPS_ITS_GROUND = 10

    /** How far from a player the storm is worked, in blocks. */
    private const val REACH = 48

    private const val ONE_LAYER = 1
    private const val LEE_LAYERS = 2
    private const val MOST_AT_ONCE = 4

    private const val DRIFTS_ORDINARILY = 24
    private const val MOST_DRIFTS = 160

    /** About as much of the time as vanilla rains, which is what an ordinary blizzard should feel like. */
    private const val AS_OFTEN_AS_RAIN = 0.3

    /** Scarcely ever out of one, which is the top of the ladder rather than a value a writer reaches. */
    private const val ALMOST_ALWAYS = 0.98

    /** How much of the axis a full rung or a full fury covers. */
    private const val MORE_OF_THE_TIME = 0.34

    /** What a full reach of the manifestation multiplies a written rung by. */
    private const val FURY_DRIVES = 2.0

    private const val HORIZONS = 4
    private const val TICKS_PER_DAY = 24000L

    /** So the wind is decorrelated from everything else the seed drives. */
    private const val WIND_SALT = 0xB112_2A2DL
}
