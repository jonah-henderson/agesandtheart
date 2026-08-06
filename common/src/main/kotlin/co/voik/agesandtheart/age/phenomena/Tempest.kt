package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.LightningRodBlock

/**
 * An Age in permanent storm, struck far more often than weather alone would strike it — and hit, where it
 * is struck, hard enough to take the ground with it.
 *
 * **Nothing here makes lightning.** `ServerLevel.tickThunder` is public and already does the whole of it —
 * a position in the chunk, vanilla's own targeting, the lightning-rod search, the skeleton-horse trap and
 * the bolt — so a tempest is that *asked for more often*, and every behaviour hanging off a strike comes
 * along without being reimplemented or kept in step.
 *
 * That only works because an Age owns its weather ([AgeWeather]): `tickThunder` gates on `isRaining()` and
 * `isThundering()`, which before 26.1's schedule became per-Age would have meant storming the overworld to
 * storm an Age.
 *
 * **[strike] is not the only source of the strikes it gets, which is why [struck] answers the bolt rather
 * than the asking.** Vanilla calls `tickThunder` itself once per entity-ticking chunk per tick, and a
 * tempest is raining and thundering permanently, so at an ordinary simulation distance roughly as many
 * bolts come down on vanilla's own rolls as on ours. Anything that only hardened the strikes [strike] asked
 * for would leave half the storm ordinary — two identical bolts, one cratering the ground and one not.
 */
object Tempest {

    fun strike(level: ServerLevel, density: Double) {
        val watching = level.players()
        if (watching.isEmpty()) return

        val random = level.random
        repeat(Happenings.timesFor(density, ORDINARY_VISITS)) {
            val near = watching[random.nextInt(watching.size)].chunkPosition()
            val chunk = level.chunkSource.getChunkNow(
                near.x + random.nextInt(NEARBY * 2 + 1) - NEARBY,
                near.z + random.nextInt(NEARBY * 2 + 1) - NEARBY,
            ) ?: return@repeat
            level.tickThunder(chunk)
        }
    }

    /**
     * One bolt, called down at [near] — **aimed the way vanilla aims one**, and the returned position is
     * where it actually landed.
     *
     * The aiming is the point. `findLightningTargetAround` snaps to the surface, moves the strike onto a
     * lightning rod within 128 blocks, and failing that onto somebody standing in the open — all *before*
     * a bolt exists. Anything that builds a bolt at a coordinate and adds it to the world has quietly
     * refused every one of those, which is how `/age strike` came to test a path no tempest bolt takes:
     * [strike] goes through `tickThunder` and gets the redirect for free, and this had to ask for it.
     *
     * A rod only answers if it is the **topmost block in its column**, which is vanilla's rule and worth
     * knowing before concluding the copper failed: `findLightningRod` requires `y == getHeight(WORLD_SURFACE) - 1`.
     */
    fun callDown(level: ServerLevel, near: BlockPos, reason: EntitySpawnReason): BlockPos {
        val target = level.findLightningTargetAround(near)
        val bolt = EntityType.LIGHTNING_BOLT.create(level, reason) ?: return target
        bolt.snapTo(Vec3.atBottomCenterOf(target))
        level.addFreshEntity(bolt)
        return target
    }

    /**
     * A bolt has come down on [struckPosition] — the instant vanilla lights its own fire and powers a rod
     * (`co.voik.agesandtheart.mixin.LightningBoltMixin`). In a tempest it lands like a creeper.
     *
     * **Offered every bolt in the game**, so the first thing it does is decline: the level has to be an Age
     * ([Happenings.claimFor] settles that on a string comparison) and the Age has to have been written with
     * a tempest. Lightning anywhere else is left exactly as vanilla made it.
     *
     * **A rod catches the strike and grounds it.** `findLightningTargetAround` already redirects any bolt
     * within 128 blocks onto a lightning rod, so a roof of copper is how a writer answers an Age they wrote
     * a storm into — under one, a tempest is merely a thunderstorm. Nothing here suppresses the bolt, only
     * the blast, so the rod still powers what it is wired to and vanilla's own strike is unchanged.
     *
     * **That is the whole of what copper buys, and a blast is not asked about it** (Jonah, 2026-08-06). A
     * rod that a strike landed *near* rather than *on* is blown apart like anything else in the radius:
     * what protects it in the ordinary case is that the explosion never happened, which is not a property
     * of the rod at all.
     */
    @JvmStatic
    fun struck(level: ServerLevel, bolt: LightningBolt, struckPosition: BlockPos) {
        if (Happenings.claimFor(level, Phenomenon.TEMPEST) == null) return
        if (level.getBlockState(struckPosition).block is LightningRodBlock) return
        level.explode(
            bolt,
            null,
            null,
            bolt.x,
            bolt.y,
            bolt.z,
            BLAST_RADIUS,
            true,
            // Not `MOB`, which is a creeper's and which `mobGriefing` switches off. An Age is written on
            // purpose and a tempest in it was asked for, so it is not a setting.
            Level.ExplosionInteraction.BLOCK,
        )
        setFiresAround(level, bolt.blockPosition(), level.random)
    }

    /**
     * Fire scattered around where the bolt came down, on top of the blast's own.
     *
     * Vanilla's `LightningBolt.spawnFire` is this at four tries in a 3×3×3 and only above Easy; a tempest
     * reaches further and does not ask the difficulty, because the Age it burns is one somebody chose to
     * write. `canSpreadFireAround` is asked once, as vanilla asks it: 26.1 fences lightning fire to
     * `fire_spread_radius_around_player` blocks of somebody, which a strike this near a player passes.
     */
    private fun setFiresAround(level: ServerLevel, around: BlockPos, random: RandomSource) {
        if (!level.canSpreadFireAround(around)) return
        repeat(FIRE_ATTEMPTS) {
            val at = around.offset(scatter(random), scatter(random), scatter(random))
            if (!level.getBlockState(at).isAir) return@repeat
            val fire = BaseFireBlock.getState(level, at)
            if (fire.canSurvive(level, at)) level.setBlockAndUpdate(at, fire)
        }
    }

    private fun scatter(random: RandomSource): Int = random.nextInt(FIRE_REACH * 2 + 1) - FIRE_REACH

    /**
     * How many chunks a tempest looks at per tick, at an ordinary rung.
     *
     * Each visit is one of vanilla's own rolls, which is `1 in 100000` — so this many, twenty times a
     * second, is a strike somewhere near a player every ten seconds or so, and `teeming` is four times
     * that. The knob is the *number of rolls* rather than a rate of our own, so a tempest can never strike
     * anywhere vanilla would not have.
     */
    private const val ORDINARY_VISITS = 512

    /** How far from a player a strike may land, in chunks — inside a normal render distance. */
    private const val NEARBY = 8

    /**
     * Creeper force, and the rung does not raise it.
     *
     * `Creeper.explosionRadius` is 3, and this is meant to read as one. A rung already multiplies how often
     * a tempest strikes; letting it multiply the crater as well would make `teeming` worse than twice over.
     */
    private const val BLAST_RADIUS = 3.0f

    /** How many places around a strike catch, and how far out to try them. */
    private const val FIRE_ATTEMPTS = 24
    private const val FIRE_REACH = 3
}
