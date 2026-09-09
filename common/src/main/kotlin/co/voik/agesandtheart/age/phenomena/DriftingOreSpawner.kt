package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What puts a charged Age's ore in its sky (design §7.1.2).
 *
 * **A `CustomSpawner`, for the reason [co.voik.agesandtheart.age.aspect.AgeSpawner] is one**: vanilla's
 * natural spawner declines a `MobCategory.MISC` entity outright, and this is not a `Mob` — one would eat
 * the mob cap, so a sky full of ore would quietly suppress the Age's actual creatures.
 *
 * **A tier is a band, so choosing the tier is the whole placement.** There is nothing to weight and no
 * scarcity dial: the bottom band is poor because only the smallest bodies belong there, and the top is
 * rich for the same reason read the other way. An Age that rolled two bands only ever puts up two sizes —
 * which is what "two climbs rather than three" means literally rather than as a figure of speech.
 *
 * **It puts up one body at a time and stops when the sky is full**, both counted near the spot rather than
 * over the level: what a player should see is a scattering that stays a scattering however far they
 * travel, and bodies far from every player discard themselves ([DriftingOre]).
 */
class DriftingOreSpawner : CustomSpawner {

    override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
        if (level.gameTime % TRIED_EVERY != 0L) return
        val tier = tierFor(level)
        val at = somewhereIn(level, ChargedBands.homeFor(level, tier), tier) ?: return
        val body = AgeContent.DRIFTING_ORE.create(level, EntitySpawnReason.NATURAL) ?: return
        body.tier = tier
        body.shape = level.random.nextInt(OreClusters.SHAPES)
        body.snapTo(at)
        level.addFreshEntity(body)
    }

    /**
     * Which band this one goes to — **the lowest most of the time, and rarer with every climb**.
     *
     * Only the tiers this Age has a band for, so a two-band Age never puts up a body with nowhere of its
     * own to go: `ChargedBands.homeFor` would coerce it onto the top band beside the tier below.
     *
     * **Weighted rather than even** (Jonah, 2026-09-09). An even roll made the top band as busy as the
     * bottom, which reads backwards twice over — the richest bodies should be the ones you go looking for,
     * and the lowest band is the signpost that has to be *seen* from the ground to do its job.
     */
    private fun tierFor(level: ServerLevel): Int {
        val bands = ChargedBands.tiersIn(level)
        var tier = 0
        while (tier < bands - 1 && level.random.nextInt(RARER_EACH_BAND) == 0) tier++
        return tier
    }

    /**
     * Somewhere in the simulated area at this band's height, or null where there is no room for a body.
     *
     * The column is chosen the way [co.voik.agesandtheart.age.aspect.AgeSpawner] chooses one — anywhere
     * among the chunks being ticked rather than over one player's head, which is what keeps these scenery
     * rather than something following you.
     */
    private fun somewhereIn(level: ServerLevel, band: Double, tier: Int): Vec3? {
        val players = level.players().filterNot { it.isSpectator }
        if (players.isEmpty()) return null
        val around = players[level.random.nextInt(players.size)].blockPosition()
        val reach = level.server.playerList.simulationDistance * BLOCKS_PER_CHUNK
        val x = around.x + level.random.nextInt(-reach, reach + 1) + HALF
        val z = around.z + level.random.nextInt(-reach, reach + 1) + HALF
        val y = band + (level.random.nextDouble() - HALF) * BAND_THICKNESS
        val at = Vec3(x, y, z)
        // Null where the roll landed on ground the server is not holding, which is most of a large area
        // and is why this is cheap: generating a chunk to find out would be a spawner driving worldgen.
        if (!level.hasChunk(SectionPos.blockToSectionCoord(at.x), SectionPos.blockToSectionCoord(at.z))) return null
        if (tooManyAround(level, at)) return null
        val span = OreClusters.spanOf(tier)
        // Room for the whole rock rather than for a point in it: these are up to six blocks across, and a
        // body put half inside a floating island would spend its life being shoved out of it.
        return if (level.noCollision(AABB.ofSize(at, span, span, span))) at else null
    }

    /** Whether this stretch of sky already holds as many bodies as it is meant to. */
    private fun tooManyAround(level: ServerLevel, at: Vec3): Boolean {
        val around = AABB.ofSize(at, CROWDED_WITHIN, CROWDED_WITHIN, CROWDED_WITHIN)
        return level.getEntities(AgeContent.DRIFTING_ORE, around) { true }.size >= MOST_IN_SIGHT
    }

    private companion object {
        /**
         * How often a body is tried at all.
         *
         * Slow, because the sky is meant to fill and then stay filled: a player flying to the top band
         * passes bodies that were put up long before they arrived, and [MOST_IN_SIGHT] is what actually
         * decides how many they see.
         */
        private const val TRIED_EVERY = 60L

        /** How many bodies may stand within [CROWDED_WITHIN] of one another. */
        /**
         * **Thinned 2026-09-09**: a sky is meant to be a scattering you go to, and every one of these is
         * an entity ticking a block scan. A crowd of them was the likeliest thing behind a walk reporting
         * jittery motion.
         */
        private const val MOST_IN_SIGHT = 4

        /** How much rarer each band up is than the one below it — see [tierFor]. */
        private const val RARER_EACH_BAND = 3
        private const val CROWDED_WITHIN = 160.0

        /** How far off its band a body may be put. It will seek the band itself; this only stops a row. */
        private const val BAND_THICKNESS = 14.0

        private const val BLOCKS_PER_CHUNK = 16
        private const val HALF = 0.5
    }
}
