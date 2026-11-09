package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Tornado

/**
 * Lightning inside a tornado's funnel, as those who looked up one have told it: flickering almost without pause, zigzagging
 * from wall to wall. When a tornado flashes and where its bolt runs are drawn off its id and the world's clock alone,
 * so every client sees the same strike, and both the funnel's renderer and the light inside it ([TornadoLight]) agree.
 */
object TornadoLightning {

    /** How bright [tornado] is flashing at [gameTime], from nought to one, at full strength as it stands. */
    fun flashOf(tornado: Tornado, gameTime: Long): Float {
        val strength = tornado.descended * (1.0f - tornado.roped)
        if (strength <= NONE) return NONE
        val slot = gameTime / SLOT_TICKS
        if (unit(tornado.id, slot, FLASHES) >= FLASH_SHARE) return NONE
        return FLICKER[(gameTime % SLOT_TICKS).toInt()] * strength
    }

    /**
     * The bolt of [tornado]'s flash at [gameTime]: points round its middle, each a share of the way up the funnel, an
     * angle round it and a share of the way out to its wall, crossing from side to side.
     */
    fun boltOf(tornado: Tornado, gameTime: Long): List<BoltPoint> {
        val slot = gameTime / SLOT_TICKS
        var up = LOWEST_BOLT + (HIGHEST_BOLT - LOWEST_BOLT) * unit(tornado.id, slot, HEIGHT)
        var angle = unit(tornado.id, slot, ANGLE) * FULL_TURN
        return List(BOLT_POINTS) { index ->
            if (index > 0) {
                angle += (CROSSES_LEAST + (CROSSES_MOST - CROSSES_LEAST) * unit(tornado.id, slot, ANGLE + index)) * HALF_TURN
                up += (unit(tornado.id, slot, HEIGHT + index) - HALF) * STEPS_UP_BY
            }
            BoltPoint(up.coerceIn(NONE, 1.0f), angle, OUT_TO_THE_WALL)
        }
    }

    /** One bend of a bolt: [up] of the way up the funnel, at [angle] round it, [out] of the way to its wall. */
    data class BoltPoint(val up: Float, val angle: Float, val out: Float)

    /** A number in 0..1 for one tornado, one slot and one use. */
    private fun unit(id: Int, slot: Long, use: Int): Float {
        var hash = id.toLong() * ID_STEP + slot * SLOT_STEP + use * USE_STEP
        hash = (hash xor (hash ushr MIX_SHIFT)) * MIX
        hash = hash xor (hash ushr MIX_SHIFT)
        return (hash and UNIT_MASK).toFloat() / UNIT_WHOLE
    }

    /** A flash is a third of a second of flickers, and about one slot in three has one: one a second or so. */
    private const val SLOT_TICKS = 6L
    private const val FLASH_SHARE = 0.33f
    private val FLICKER = floatArrayOf(1.0f, 0.3f, 0.85f, 0.45f, 0.1f, 0.0f)

    /** Where in the funnel a bolt runs, how far each bend crosses the funnel, in half turns, and how far it climbs. */
    private const val LOWEST_BOLT = 0.15f
    private const val HIGHEST_BOLT = 0.8f
    private const val BOLT_POINTS = 6
    private const val CROSSES_LEAST = 0.6f
    private const val CROSSES_MOST = 1.4f
    private const val STEPS_UP_BY = 0.12f
    private const val OUT_TO_THE_WALL = 0.8f

    private const val FLASHES = 0
    private const val HEIGHT = 16
    private const val ANGLE = 32
    private const val ID_STEP = -0x61C8864680B583EBL
    private const val SLOT_STEP = 0x2545F4914F6CDD1DL
    private const val USE_STEP = 0x27D4EB2F165667C5L
    private const val MIX = -0x40A7B892E31B1A47L
    private const val MIX_SHIFT = 31
    private const val UNIT_MASK = 0xFFFFFFL
    private const val UNIT_WHOLE = 16777216.0f

    private const val NONE = 0.0f
    private const val HALF = 0.5f
    private const val FULL_TURN = 6.2831855f
    private const val HALF_TURN = 3.1415927f
}
