package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.IceBlock

/**
 * A world that burns (design §5.2.2).
 *
 * Two rules and nothing else, and both hold **only while the sun is up**: what can see the sky catches light
 * on its own, and what stands in the open burns. Vanilla does the rest — `FireBlock` spreads and consumes
 * what we light, and puts itself out in rain, which is where the lull comes from without anybody writing one.
 *
 * **The counterplay is deliberately easy and the difficulty is somewhere else.** Any stone answers it and
 * going underground is free, so nobody dies to this twice. What bites is that the rest of nature burns with
 * you: wood, saplings, crops and animals all live in the open, so an inferno Age is one where the ordinary
 * resource loop stops working and you must import through a linking book or build enclosed places to grow
 * things in.
 *
 * **It is the phenomenon that resolves.** Fire runs out of fuel where a sea does not run out of water, so an
 * inferno Age has an arc — dangerous on arrival, answered by building, and afterwards a scarred place where
 * wood is scarce. And since unvisited chunks still generate their forests, home becomes safe while the
 * frontier stays dangerous.
 */
object Inferno {

    /** One tick of it, at whatever strength the claim's rung asked for. */
    fun burn(level: ServerLevel, density: Double) {
        val intensity = Intensity.of(level.server)
        scourTheSurface(level, intensity, density)
        scorchTheOpen(level, intensity)
    }

    /**
     * What the sky does to the ground it can see: **frost comes off it, and what will burn is lit while the
     * sun is up.**
     *
     * The lighting is gated on daylight the way [scorchTheOpen] already was (Jonah, 2026-08-29, walked:
     * "mobs stop taking damage after the sun goes down, but fires are still started"). The sun is what is
     * doing this — it is visibly the hazard, and a night that stops the burning but goes on setting the
     * forest alight is the phenomenon disagreeing with itself in front of you.
     *
     * **Frost still comes off at night**, because that one is about a ground too hot to hold it rather than
     * about the light falling on it, and a world that burns by day does not cool to freezing by night.
     *

     * One sample answers both, because both are questions about the top of a column and asking twice would
     * be paying twice. Thawing wins where they meet, so a snowed-over log is uncovered rather than set
     * alight through the snow.
     *
     * **The heightmap is the sky test**, so this is one lookup rather than a ray: [Sampling.skyward] is by
     * definition the first empty place above everything, and the block below it is the topmost thing there
     * is. It also solves by construction the deadlock this phenomenon would otherwise walk into — only the
     * canopy is ever exposed, so a sapling under a tree, a log in shade and anything in a valley are all
     * safe without a rule saying so, and a writer who arrives empty-handed can still get wood.
     *
     * `ignitedByLava` is vanilla's own declarative "this catches", set on wood, leaves, wool and the rest,
     * which is why nothing here carries a list that could fall out of step with the blocks a pack adds.
     */
    private fun scourTheSurface(level: ServerLevel, intensity: Intensity, density: Double) {
        val sweeps = Happenings.timesFor(density, intensity.sweeps)
        val sunIsUp = level.isBrightOutside
        Sampling.sweep(level, sweeps) { _, column ->
            if (level.random.nextDouble() >= intensity.chance) return@sweep
            val above = Sampling.skyward(level, column)
            // **A thin snow layer does not block motion**, so the heightmap points *at* it rather than above
            // it, where a snow block or ice is the top of the column. Both places have to be looked at or a
            // dusting would be the one thing that survives a burning world.
            if (thaw(level, above) || thaw(level, above.below())) return@sweep
            if (sunIsUp) light(level, above)
        }
    }

    /**
     * Sets alight whatever is at the top of a column, if anything there will take it.
     *
     * **Two places, because the heightmap points at different things depending on what is growing**
     * (Jonah, 2026-08-08, walked). `MOTION_BLOCKING` counts what blocks motion or holds fluid, and a dead
     * bush, dry grass, a flower and a sapling do neither — so over a desert the heightmap points *at* the
     * bush and the block below it is sand, which does not burn. Asking only below the mark meant a world of
     * tinder ignoring a burning sky, and only a leaf canopy or bare logs ever caught.
     *
     * So the thing standing there is offered the fire first and is *replaced* by it, which is what vanilla
     * does when fire spreads into a plant; failing that, the fire goes above whatever it is standing on.
     */
    private fun light(level: ServerLevel, above: BlockPos) {
        val standing = level.getBlockState(above)
        // Vanilla's own choice of fire, so soul sand gets soul fire and nothing needs a special case.
        if (standing.ignitedByLava()) {
            level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above))
            return
        }
        if (!standing.isAir) return
        if (!level.getBlockState(above.below()).ignitedByLava()) return
        level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above))
    }

    /**
     * Takes the frost off a place too hot to have it, and says whether there was any.
     *
     * **Vanilla has no lever for this**, checked rather than assumed: `SnowLayerBlock` and `IceBlock` melt on
     * *block light* above eleven and never on temperature, so a torch thaws a glacier and a burning sky does
     * not. Nothing about a hot biome removes frost that is already there, which is why an inferno has to do
     * it itself.
     *
     * **Ice leaves water where snow leaves nothing**, which is vanilla's own melt rather than the Nether's:
     * evaporating a frozen lake would punch a hole through it, and an Age that also evaporates water will
     * take the water in its own time. `IceBlock.meltsInto` so the two cannot disagree about what ice is.
     *
     * This is the contradiction made visible in a fractured Age (§5.2.2): write `frozen inferno` and the
     * climate splits, so one half keeps laying frost and the other keeps taking it off — which is a world at
     * odds with itself doing exactly that, in front of you.
     */
    private fun thaw(level: ServerLevel, at: BlockPos): Boolean {
        val state = level.getBlockState(at)
        return when {
            state.`is`(BlockTags.ICE) -> true.also { level.setBlockAndUpdate(at, IceBlock.meltsInto()) }
            state.`is`(BlockTags.SNOW) -> true.also { level.removeBlock(at, false) }
            else -> false
        }
    }

    /**
     * Burns what stands in the open, **while the sun is up**.
     *
     * Gated on daylight the way `MONSTERS_BURN` already is, which is not a softening: the sun is visibly the
     * thing hurting you, the phenomenon gains the temporal relief its structural relief does not provide,
     * and arriving at night is survivable where arriving at noon is urgent. Rain stops it too, so the lull
     * that puts the fires out is the same lull that lets you walk about.
     *
     * **It sets things alight rather than hurting them** (Jonah, 2026-08-08, walked), which is one call in
     * place of three behaviours: burning is what does the damage, so the rate stays vanilla's; things
     * visibly *catch*, where dealing fire damage directly left mobs dying under a clear sky with no flame
     * on them; and anything that dies of it **drops its meat cooked**, because vanilla checks whether a
     * thing was on fire when it died. Fire resistance still answers the whole of it, since the damage is
     * vanilla's own.
     */
    private fun scorchTheOpen(level: ServerLevel, intensity: Intensity) {
        if (intensity.betweenHarms <= 0 || level.gameTime % intensity.betweenHarms != 0L) return
        if (!level.isBrightOutside || level.isRaining) return
        // A little longer than the gap between passes, so standing in the open is a continuous burn and
        // stepping under a roof lets it go out on its own rather than being put out by us.
        val alight = intensity.harm.toFloat() * intensity.betweenHarms / TICKS_PER_SECOND
        for (living in caughtInTheOpen(level)) living.igniteForSeconds(alight)
    }

    /**
     * Everything near somebody with nothing over it.
     *
     * **A set, because a box is asked around each player** rather than around all of them at once: a union
     * of two distant players' boxes is a query over everything between them, and something standing near
     * both would otherwise be burnt twice in one tick.
     */
    private fun caughtInTheOpen(level: ServerLevel): Set<LivingEntity> =
        level.players()
            .filterNot { it.isSpectator }
            .flatMapTo(HashSet()) { level.getEntitiesOfClass(LivingEntity::class.java, it.boundingBox.inflate(ABOUT)) }
            .filterNotTo(HashSet()) { it.fireImmune() || !Sampling.openToTheSky(level, it.blockPosition()) }

    /** How far around a person the open air burns, in blocks — a little past what they can see happening. */
    private const val ABOUT = 64.0

    private const val TICKS_PER_SECOND = 20.0f
}
