package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Rung
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
     * How much of the time an Age at this severity is in a storm, as [AgeWeather.Conditions] wants it.
     *
     * **The whole of how a blizzard scales.** An ordinary one comes about as often as vanilla's rain and
     * passes in a few minutes; a furious one is an Age scarcely ever out of it. Nothing about the snow
     * itself gets harder — what changes is how much of your time is spent in it, which is the register
     * §5.2 asks for: inexorable rather than violent.
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
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
        cursor.set(x, ground, z)
        if (!level.isLoaded(cursor)) return
        // Vanilla's own rule, obeyed rather than restated: a lit place keeps its ground.
        if (level.getBrightness(LightLayer.BLOCK, cursor) >= KEEPS_ITS_GROUND) return
        if (!level.canSeeSky(cursor)) return
        val standing = level.getBlockState(cursor)
        val sheltered = level.getBlockState(cursor.immutable().relative(bearing)).isSolidRender
        val laying = if (sheltered) inTheLee(severity) else ONE_LAYER
        when {
            standing.isAir -> if (Blocks.SNOW.defaultBlockState().canSurvive(level, cursor)) {
                level.setBlock(cursor, deepened(Blocks.SNOW.defaultBlockState(), laying), Block.UPDATE_ALL)
            }
            standing.`is`(Blocks.SNOW) -> level.setBlock(cursor, deepened(standing, laying), Block.UPDATE_ALL)
            // A drift that has reached its full depth compresses, which is the ladder §5.2 wants: how long
            // this has been going on is legible from the block rather than from a counter.
            else -> compress(level, cursor, standing)
        }
    }

    /** [state] with [layers] more of it, up to what a snow layer can hold. */
    private fun deepened(state: BlockState, layers: Int): BlockState {
        val standing = state.getValueOrElse(SnowLayerBlock.LAYERS, ONE_LAYER)
        return Blocks.SNOW.defaultBlockState()
            .setValue(SnowLayerBlock.LAYERS, (standing + layers).coerceAtMost(SnowLayerBlock.MAX_HEIGHT))
    }

    /**
     * The compression ladder: a full drift becomes ice, and ice becomes harder ice.
     *
     * **Self-capping on purpose.** Blue ice is terminal, so a place cannot go on progressing for ever the
     * way a rising sea can — which is what lets the burial be a hazard rather than a punishment with no
     * end.
     */
    private fun compress(level: ServerLevel, at: BlockPos, standing: BlockState) {
        val next = when {
            standing.`is`(Blocks.SNOW_BLOCK) -> Blocks.ICE
            standing.`is`(Blocks.ICE) -> Blocks.PACKED_ICE
            standing.`is`(Blocks.PACKED_ICE) -> Blocks.BLUE_ICE
            else -> return
        }
        level.setBlock(at, next.defaultBlockState(), Block.UPDATE_ALL)
    }

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
