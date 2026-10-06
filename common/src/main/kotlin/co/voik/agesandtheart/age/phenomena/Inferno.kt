package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.platform.Services
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.IceBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.WeakHashMap

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

    /** One tick of it, at whatever strength the claim's rung asked for and the [dials] instability bought. */
    fun burn(level: ServerLevel, density: Double, dials: InfernoDials) {
        val intensity = Intensity.of(level.server)
        val sun = sunStrength(level, dials.lightIntensity)
        scourTheSurface(level, intensity, density, sun)
        scorchTheOpen(level, intensity, sun, dials.burnDamage)
    }

    /**
     * **How much burn the sun may apply**, from none to all of it — the one number both halves share, so the
     * fires and the burning in the open never disagree about when the sun is doing it (Jonah, 2026-09-23).
     *
     * **Suns only, never moons.** So it is asked of the sky's own bodies rather than of how bright the sky
     * is — a full moon lights a night, and a night must stay the relief. Any sun will do, and the highest one
     * decides; an Age with no sun has nothing overhead to burn it.
     *
     * Nothing until a sun reaches the lowest height it burns from, then climbing to all of it at
     * [FULL_STRENGTH_AT]. Unbought, that lowest height is the horizon, which is about where vanilla's
     * `isBrightOutside` falls; [InfernoDials.lightIntensity] carries it past sunset into the sun's afterglow,
     * twelve degrees down when bought in full. Where the burn lands, cover takes it off again — see
     * [scorchTheOpen].
     *
     * A level Ephemeris holds no sky for is not an Age, and keeps vanilla's own daytime at full strength.
     */
    private fun sunStrength(level: ServerLevel, lightIntensity: Double): Double {
        if (level.dimensionType().hasFixedTime()) return NONE_OF_IT
        val sky = LevelLooks.anywhere(level) ?: return if (level.isBrightOutside) ALL_OF_IT else NONE_OF_IT
        val highest = sky.readAt(level.defaultClockTime).suns.maxOfOrNull { it.altitudeDegrees } ?: return NONE_OF_IT
        val lowestThatBurns = OVER_THE_HORIZON + (IN_ITS_AFTERGLOW - OVER_THE_HORIZON) * lightIntensity
        return Mth.clampedMap(highest.toDouble(), lowestThatBurns, FULL_STRENGTH_AT, NONE_OF_IT, ALL_OF_IT)
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
    private fun scourTheSurface(level: ServerLevel, intensity: Intensity, density: Double, sun: Double) {
        val sweeps = Happenings.timesFor(density, intensity.sweeps)
        Sampling.sweep(level, sweeps) { _, column ->
            if (level.random.nextDouble() >= intensity.chance) return@sweep
            val above = Sampling.skyward(level, column)
            // **A thin snow layer does not block motion**, so the heightmap points *at* it rather than above
            // it, where a snow block or ice is the top of the column. Both places have to be looked at or a
            // dusting would be the one thing that survives a burning world.
            if (thaw(level, above) || thaw(level, above.below())) return@sweep
            // The top of a column is open by definition, so the sun's strength alone is how likely it catches.
            if (level.random.nextDouble() < sun) light(level, above)
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
        if (catchesFire(level, above, standing)) {
            level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above))
            return
        }
        if (!standing.isAir) return
        val under = above.below()
        if (!catchesFire(level, under, level.getBlockState(under))) return
        level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above))
    }

    /**
     * Whether this block takes the sky's fire — **asked of the loader**, because NeoForge lets a block
     * answer per face and per position where vanilla has only a flag. See
     * [co.voik.agesandtheart.platform.services.Flammability].
     *
     * The fire comes down out of the sky, so the face it arrives at is the top one.
     */
    private fun catchesFire(level: ServerLevel, at: BlockPos, state: BlockState): Boolean =
        Services.FLAMMABILITY.catchesFire(level, at, state, Direction.UP)

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
    private fun scorchTheOpen(level: ServerLevel, intensity: Intensity, sun: Double, burnDamage: Double) {
        if (intensity.betweenHarms <= 0 || level.gameTime % intensity.betweenHarms != 0L) return
        if (sun <= NONE_OF_IT || level.isRaining) return
        // A little longer than the gap between passes, so standing in the open is a continuous burn and
        // stepping under a roof lets it go out on its own rather than being put out by us.
        val alight = intensity.harm * intensity.betweenHarms / TICKS_PER_SECOND
        // [InfernoDials.burnDamage] on top of vanilla's burning, and still fire damage: fire resistance
        // answers it, and a thing that dies of it still drops its meat cooked.
        val extra = burnDamage * MOST_EXTRA_BURN_PER_SECOND * intensity.betweenHarms / TICKS_PER_SECOND
        for (living in nearSomebody(level)) {
            // **The sun says how much burn there may be, and cover takes it off** (Jonah, 2026-09-23), as it
            // takes off a blizzard's cold: a lip of rock shortens it, a cave or a roof ends it. Read the
            // blizzard's way too — a ray cone, here straight up, capped by sky light between readings.
            val overhead = sunReadings.getOrPut(living, ConeExposure::Remembered)
                .read(level, living.eyePosition, ConeExposure.OVERHEAD)
            val burn = sun * minOf(Sampling.exposureAt(level, living.blockPosition()), overhead)
            if (burn < LEAST_WORTH_A_BURN) continue
            living.igniteForSeconds((alight * burn).toFloat())
            if (extra > NONE_OF_IT) living.hurtServer(level, level.damageSources().onFire(), (extra * burn).toFloat())
        }
    }

    /** Each burnable thing's last [ConeExposure] reading, held weakly so it goes with the entity. */
    private val sunReadings = WeakHashMap<LivingEntity, ConeExposure.Remembered>()

    /**
     * Everything near somebody that fire can hurt.
     *
     * **A set, because a box is asked around each player** rather than around all of them at once: a union
     * of two distant players' boxes is a query over everything between them, and something standing near
     * both would otherwise be burnt twice in one tick.
     */
    private fun nearSomebody(level: ServerLevel): Set<LivingEntity> =
        Sampling.watchers(level)
            .flatMapTo(HashSet()) { level.getEntitiesOfClass(LivingEntity::class.java, it.boundingBox.inflate(ABOUT)) }
            .filterNotTo(HashSet()) { it.fireImmune() }

    /** How far around a person the open air burns, in blocks — a little past what they can see happening. */
    private const val ABOUT = 64.0

    private const val TICKS_PER_SECOND = 20.0

    /** How high a sun has to stand to burn, in degrees: over the horizon unbought, into its afterglow in full. */
    private const val OVER_THE_HORIZON = 0.0
    private const val IN_ITS_AFTERGLOW = -12.0

    /** How high a sun stands when it burns with all it has, in degrees — see [sunStrength]. */
    private const val FULL_STRENGTH_AT = 45.0

    private const val NONE_OF_IT = 0.0
    private const val ALL_OF_IT = 1.0

    /**
     * Less burn than this sets nothing alight — deep under cover, where a trickle of sky light gets in, a burn
     * of a tick or two would be a flicker of flame rather than any shelter being too little.
     */
    private const val LEAST_WORTH_A_BURN = 0.1

    /** What [InfernoDials.burnDamage] in full adds, in health a second — a heart, on top of vanilla's burn. */
    private const val MOST_EXTRA_BURN_PER_SECOND = 2.0
}
