package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Tectonic activity: an Age whose ground gives way under itself (design §5.3).
 *
 * **A phenomenon a writer asks for rather than a consequence an Age suffers** (Jonah, 2026-09-11), which
 * is what gives the mechanism an owner, a density dial and a word of its own. §5.3's collapse is welcome to
 * reuse [CaveIn] later, and neither will depend on the other — a tear at the world's floor is only the most
 * specific case of ground coming apart.
 *
 * **It strikes where the player is, underfoot included** (Jonah, 2026-09-11). The crack is the warning and
 * it is a real one: three seconds of unmistakable fracture before anything moves, which is a stride and a
 * jump. A hazard that could only ever happen at a distance would be scenery.
 */
object CaveIns {

    /**
     * Open whatever this Age is due, near whoever is in it.
     *
     * Sited off a player's own position the way every other phenomenon here is, so what it costs scales
     * with how many people are about rather than with how much Age has been generated.
     *
     * **The rung says how often and the [dials] how bad**, which is the cleanest split this phenomenon
     * could have had: `teeming tectonics` is the ground giving way four times as often, and an Age at odds
     * with itself is the ground giving way over more of itself, faster, or both. Where both, they compound.
     */
    fun stir(level: ServerLevel, often: Double, dials: TectonicsDials) {
        val watching = Sampling.watchers(level)
        if (watching.isEmpty()) return
        val behaviour = TectonicsBehaviour.of(level.server)
        val random = level.random
        repeat(Happenings.timesFor(often, ROLLS)) {
            val owed = PhenomenaCeiling.isOwed(level, Phenomenon.TECTONICS)
            if (!owed && random.nextInt(SELDOM) != 0) return@repeat
            val near = watching[random.nextInt(watching.size)].blockPosition()
            val x = near.x + random.nextInt(NEARBY * 2 + 1) - NEARBY
            val z = near.z + random.nextInt(NEARBY * 2 + 1) - NEARBY
            if (!level.isLoaded(BlockPos(x, near.y, z))) return@repeat
            val aimedAt = near.y + random.nextInt(WITHIN_HEIGHT + 1) - random.nextInt(WITHIN_HEIGHT + 1)
            val ground = faceNear(level, x, z, aimedAt, near.y) ?: return@repeat
            if (!PhenomenaCeiling.mayBegin(level, Phenomenon.TECTONICS)) return@repeat
            val shape = random.nextLong()
            CaveIn.begin(
                level = level,
                at = ground,
                shape = shape,
                form = behaviour.shapeDrawnFrom(shape),
                reach = behaviour.reachAt(dials.size),
                pace = behaviour.paceAt(dials.speed),
            )
        }
    }

    /**
     * Where a cave-in begins: the exposed face nearest [aimedAt] in this column, within [WITHIN_HEIGHT] of it — a floor
     * or a ceiling, so one sometimes comes down over a player rather than only opening under them. [aimedAt] is drawn
     * about [playerHeight] leaning toward it, so the floor a player is on is the likeliest, and the roof of a roofed
     * Age is reached only from near it.
     *
     * Solid is what the ocean floor's heightmap counts, so under the sea the face is the seabed. With no face near, a
     * player above the surface (flying, or on a tower) is struck at the surface, and anyone else not at all.
     */
    private fun faceNear(level: ServerLevel, x: Int, z: Int, aimedAt: Int, playerHeight: Int): BlockPos? {
        val counts = Heightmap.Types.OCEAN_FLOOR.isOpaque()
        val top = level.minY + level.height - ONE
        val cursor = BlockPos.MutableBlockPos()
        fun solid(y: Int) = counts.test(level.getBlockState(cursor.set(x, y, z)))
        fun isFace(y: Int): Boolean {
            val inTheWorld = y > level.minY && y < top
            return inTheWorld && solid(y) && (!solid(y + ONE) || !solid(y - ONE))
        }
        for (away in 0..WITHIN_HEIGHT) {
            if (isFace(aimedAt - away)) return BlockPos(x, aimedAt - away, z)
            if (away > 0 && isFace(aimedAt + away)) return BlockPos(x, aimedAt + away, z)
        }
        val surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - ONE
        return if (playerHeight > surface) BlockPos(x, surface, z) else null
    }

    /**
     * How many chances a pass gets and how rarely each one takes.
     *
     * Split in two on purpose: the rolls are what a frequency scales, and [SELDOM] is what keeps a tectonic
     * Age to a collapse every minute or so rather than a landscape dissolving. One cave-in is a long,
     * loud event — this is not a tempest, which wants to be happening constantly and faintly.
     */
    private const val ROLLS = 1
    private const val SELDOM = 1200

    /** Near enough to see and hear, and near enough to be standing on. */
    private const val NEARBY = 48

    /** How far above or below a player's height a cave-in is aimed, and how far from there it looks for a face. */
    private const val WITHIN_HEIGHT = 24

    private const val ONE = 1
}
