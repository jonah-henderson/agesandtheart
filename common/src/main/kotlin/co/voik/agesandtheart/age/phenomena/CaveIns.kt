package co.voik.agesandtheart.age.phenomena

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
     */
    fun stir(level: ServerLevel, density: Double) {
        val watching = level.players()
        if (watching.isEmpty()) return
        val random = level.random
        repeat(Happenings.timesFor(density, ROLLS)) {
            if (random.nextInt(SELDOM) != 0) return@repeat
            val near = watching[random.nextInt(watching.size)].blockPosition()
            val x = near.x + random.nextInt(NEARBY * 2 + 1) - NEARBY
            val z = near.z + random.nextInt(NEARBY * 2 + 1) - NEARBY
            if (!level.isLoaded(BlockPos(x, near.y, z))) return@repeat
            CaveIn.begin(level, groundAt(level, x, z), random.nextLong())
        }
    }

    /**
     * Where a cave-in begins, which is **at the surface rather than at the player's own height**.
     *
     * A swathe centred on somebody standing on a hillside would cut half its shape through open air. Put on
     * the ground, the crack's plan lies where there is rock to take, and [CaveIn] eats down from there.
     */
    private fun groundAt(level: ServerLevel, x: Int, z: Int): BlockPos =
        BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - ONE, z)

    /**
     * How many chances a pass gets and how rarely each one takes.
     *
     * Split in two on purpose: the rolls are what a density scales, and [SELDOM] is what keeps a tectonic
     * Age to a collapse every minute or so rather than a landscape dissolving. One cave-in is a long,
     * loud event — this is not a tempest, which wants to be happening constantly and faintly.
     */
    private const val ROLLS = 1
    private const val SELDOM = 1200

    /** Near enough to see and hear, and near enough to be standing on. */
    private const val NEARBY = 48

    private const val ONE = 1
}
