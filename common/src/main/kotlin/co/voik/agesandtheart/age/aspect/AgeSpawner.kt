package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.compat.hasChunkAtColumn
import co.voik.agesandtheart.compat.center
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import kotlin.math.roundToInt

/**
 * **The Age putting there what vanilla's spawner will not** — the golems, and a dragon.
 *
 * Two refusals in `NaturalSpawner.isValidSpawnPostitionForType` are out of reach of any weight, any pass and
 * any tag: it declines a `MobCategory.MISC` entity outright, and it validates every creature against a box
 * it must clear *where it stands*, which for a dragon is sixteen blocks by eight at a height vanilla never
 * draws above the surface. Both are ordinary things for a written world to want.
 *
 * **A `CustomSpawner`, which is vanilla's own answer to its own limitation.** Phantoms arrive this way, and
 * so do patrols, cats, sieges and wandering traders — a level holds a list of them and ticks it once a tick
 * inside the same `doMobSpawning` gate natural spawning runs under. No mixin, and it took two out: the
 * category refusal and the pig swap both existed to force a golem down a path that was never going to
 * carry it.
 *
 * **It imitates natural spawning rather than the phantom.** `PhantomSpawner` puts its swarm twenty blocks
 * over the player's own head, which for a resident creature reads as being followed. This picks a chunk
 * anywhere in the simulated area — which is the same set of chunks vanilla shuffles through — and keeps
 * vanilla's own distance rule, its placement rules, its spawn rules and its collision test. What it does
 * *not* keep is the two refusals, and nothing else.
 */
class AgeSpawner(
    /** What this Age puts there itself — public so a check can read what a book asked for. */
    val placedCreatures: List<Placement>,
) : CustomSpawner {

    /** One creature the Age puts there itself, with everything decided that a position does not decide. */
    data class Placement(
        val type: EntityType<*>,
        val ground: Ground,
        /** And what light — see [Lit]. */
        val light: Lit,
        val arrival: Arrival,
        /** How far apart these stand, with the rung the book asked for already in it. */
        val spacing: Int,
        /** Its share of one attempt, against the others this Age places. */
        val weight: Int,
        /**
         * **The heightmap this creature's own placement was registered against**, which is the only one
         * its placement check will agree with.
         *
         * Carried here rather than chosen where a column is picked, because two things pick columns — the
         * spawner and `/age spawns` — and they must not each have their own idea of where the ground is.
         * They did, and the census faithfully reproduced the spawner's mistake rather than exposing it.
         */
        val surface: Heightmap.Types,
    )

    private val choosing: WeightedList<Placement> =
        WeightedList.of(placedCreatures.map { Weighted(it, it.weight) })

    override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
        if (placedCreatures.isEmpty()) return
        val chosen = choosing.getRandom(level.random).orElse(null) ?: return
        if (!spawnEnemies && chosen.type.category == MobCategory.MONSTER) return
        val at = somewhereIn(level, chosen) ?: return
        val outcome = tryAt(chosen, level, at)
        if (outcome is Outcome.Standing) place(chosen, level, outcome.at)
    }

    /**
     * **What would happen if this creature were tried at this column** — a placement, or the gate that
     * refused it.
     *
     * Named reasons rather than a null, because a creature that never arrives is the hard thing to
     * diagnose here: every gate below is doing its job when it refuses, and telling them apart is the
     * whole of knowing whether the numbers are wrong or the rule is. `/age spawns` counts them.
     */
    fun tryAt(chosen: Placement, level: ServerLevel, column: BlockPos): Outcome {
        if (!chosen.arrival.mayBeTriedAt(column.x, column.z, chosen.spacing)) return Outcome.HeldApart
        val candidate = when (chosen.ground) {
            Ground.IN_THE_AIR -> column.above(ALOFT_LEAST + level.random.nextInt(ALOFT_SPREAD))
            Ground.UNDERGROUND -> openSpotBelow(level, column) ?: return Outcome.NoOpening
            else -> column
        }
        if (candidate.y >= level.maxY || candidate.y <= level.minY) return Outcome.OutsideTheWorld
        // **Both of vanilla's tests here ask whether there is ground to stand on**, and neither applies to
        // a creature judged to belong aloft. `SpawnPlacements` registers the dragon `ON_GROUND` — it is not
        // an unplaced type, which is the thing that misled this twice — and `ON_GROUND.isSpawnPositionOk`
        // wants a valid spawn block below, as `Mob.checkMobSpawnRules` separately does. Thirty blocks up
        // there is none, so a dragon put where a dragon belongs was refused for not standing on anything
        // (Jonah, 2026-08-26, walked: golems arriving and dragons never).
        //
        // What is left is the test that means something up there — that the space is clear — and it is
        // kept below rather than dropped with them.
        if (chosen.ground != Ground.IN_THE_AIR) {
            if (!SpawnPlacements.isSpawnPositionOk(chosen.type, level, candidate)) return Outcome.WrongPlacement
            val rules = SpawnPlacements.checkSpawnRules(
                chosen.type, level, EntitySpawnReason.NATURAL, candidate, level.random,
            )
            if (!rules) return Outcome.WrongConditions
        } else if (!level.isEmptyBlock(candidate)) {
            return Outcome.NoRoom
        }
        // **The test that refused the dragon** where vanilla was trying, kept rather than dodged: it is
        // what stops a creature being put inside the world. Aloft it is satisfiable, which is the whole
        // reason a dragon goes up there.
        val room = chosen.type.getSpawnAABB(candidate.x + HALF_A_BLOCK, candidate.y.toDouble(), candidate.z + HALF_A_BLOCK)
        if (!level.noCollision(room)) return Outcome.NoRoom
        // **Last, because it is the only one that needs the chosen height.** A golem's own rules test no
        // light at all and an aloft creature has none run over it, so for these two this is the whole of
        // the judgement rather than a second opinion on vanilla's.
        if (!chosen.light.admits(level.getMaxLocalRawBrightness(candidate))) return Outcome.WrongLight
        if (alreadyEnoughAround(chosen, level, candidate)) return Outcome.EnoughAlready
        return Outcome.Standing(candidate)
    }

    /** Why a creature was not put at a column, or where it would go. */
    sealed interface Outcome {
        data class Standing(val at: BlockPos) : Outcome
        /** And the wrong light — see [Lit]. */
        data object WrongLight : Outcome
        /** Not one of the few places this creature may be tried — see [Arrival.mayBeTriedAt]. */
        data object HeldApart : Outcome
        /** Nothing open under the ground for one that belongs there. */
        data object NoOpening : Outcome
        data object OutsideTheWorld : Outcome
        /** Vanilla's own placement rule for the type said no. */
        data object WrongPlacement : Outcome
        /** And its own spawn rule — the light, the block below, the difficulty. */
        data object WrongConditions : Outcome
        /** Its box would not clear where it stands. */
        data object NoRoom : Outcome
        /** There are already as many here as this creature stands in one place. */
        data object EnoughAlready : Outcome
    }

    /**
     * A column somewhere in the simulated area, chosen the way vanilla chooses one — anywhere among the
     * chunks being ticked rather than anywhere near one player in particular.
     *
     * Null where the roll landed on ground the server is not holding, which is most of a large area and is
     * why this is cheap: an unloaded chunk is not somewhere to spawn and generating one to find out would
     * be a spawner that drives world generation.
     */
    fun somewhereIn(level: ServerLevel, chosen: Placement): BlockPos? {
        val players = level.players().filterNot { it.isSpectator }
        if (players.isEmpty()) return null
        val around = players[level.random.nextInt(players.size)].blockPosition()
        val reach = level.server.playerList.simulationDistance * BLOCKS_PER_CHUNK
        val x = around.x + level.random.nextInt(-reach, reach + 1)
        val z = around.z + level.random.nextInt(-reach, reach + 1)
        if (!level.hasChunkAtColumn(x, z)) return null
        return BlockPos(x, level.getHeight(chosen.surface, x, z), z)
    }

    /** The first open block under the ground, for a creature that belongs in the rock. */
    private fun openSpotBelow(level: ServerLevel, column: BlockPos): BlockPos? {
        var falling = column.below(DOWN_TO_LOOK_FROM)
        repeat(HOW_FAR_TO_LOOK) {
            if (falling.y <= level.minY) return null
            if (level.isEmptyBlock(falling) && !level.isEmptyBlock(falling.below())) return falling
            falling = falling.below()
        }
        return null
    }

    /**
     * **Whether there are enough of these here already**, which is what keeps a resident creature resident.
     *
     * The spacing says where one may be tried and this says how many may stand there, and between them a
     * dragon is a thing an Age has rather than a thing it accumulates. Counted within the creature's own
     * spacing, so the two numbers describe one arrangement instead of arguing.
     */
    private fun alreadyEnoughAround(chosen: Placement, level: ServerLevel, at: BlockPos): Boolean {
        val reach = if (chosen.spacing > 0) chosen.spacing.toDouble() else ORDINARY_REACH
        val around = AABB.ofSize(at.center, reach * 2, reach * 2, reach * 2)
        val near = level.getEntities(chosen.type, around) { true }
        return near.size >= chosen.arrival.most
    }

    private fun place(chosen: Placement, level: ServerLevel, at: BlockPos) {
        val many = chosen.arrival.least + level.random.nextInt(1 + chosen.arrival.most - chosen.arrival.least)
        val difficulty = level.getCurrentDifficultyAt(at)
        repeat(many) {
            val mob = chosen.type.create(level, EntitySpawnReason.NATURAL) as? Mob ?: return
            mob.snapTo(at.x + HALF_A_BLOCK, at.y.toDouble(), at.z + HALF_A_BLOCK, level.random.nextFloat() * A_FULL_TURN, 0.0f)
            madeAtHome(mob, at)
            mob.finalizeSpawn(level, difficulty, EntitySpawnReason.NATURAL, null)
            level.addFreshEntityWithPassengers(mob)
        }
    }

    /**
     * **Waking a creature that is asleep until a ritual wakes it, and telling it where home is.**
     *
     * A dragon needs two things said to it, and neither is said by spawning one.
     *
     * **It starts in `HOVERING` and nothing moves it on.** `EnderDragonPhaseManager`'s constructor sets
     * that phase, `DATA_PHASE` defaults to it, and `DragonHoverPhase.doServerTick` sets a target location
     * once and then does nothing whatever — so it hangs where it appeared, for ever. The only thing in the
     * game that starts a dragon flying is `EnderDragonFight`, on the line that respawns one, and a dragon
     * an Age wrote has no fight. So the Age says it instead (Jonah, 2026-08-27, walked: phase 10 every
     * time it was asked).
     *
     * **And its whole behaviour orbits a *fight origin* that defaults to `BlockPos.ZERO`.** Everything the
     * holding pattern does is relative to that point — where it lays its flight ring, where it looks for a
     * player — so a dragon that was flying at all would fly to the world origin to do it.
     *
     * The fight itself stays null, which the holding pattern guards for: it counts no crystals, which is
     * the right answer for a dragon that is a resident rather than a boss ritual.
     */
    private fun madeAtHome(mob: Mob, at: BlockPos) {
        if (mob !is EnderDragon) return
        mob.fightOrigin = at
        mob.phaseManager.setPhase(EnderDragonPhase.HOLDING_PATTERN)
    }

    companion object {
        /**
         * What an Age places itself, read off the same claim the natural spawner reads — or null where
         * this Age asked for none of them, which is nearly every Age.
         */
        fun placing(options: Options, spawning: Spawning): AgeSpawner? {
            val wanted = Spawns.claimedCreatures(options)
                .mapNotNull { (id, density) -> placementOf(id, density, spawning) }
            return if (wanted.isEmpty()) null else AgeSpawner(wanted)
        }

        private fun placementOf(id: Identifier, density: Double, spawning: Spawning): Placement? {
            if (!spawning.isPlacedByTheAge(id)) return null
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null
            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return null
            val arrival = spawning.of(id)
            val spacing = arrival.spacedAt(density)
            // **No thinning to hand back here.** The natural spawner's weights are shares of a draw against
            // a biome's whole list, so a rule about *where* had to be repaid in weight or the creature
            // vanished. This draw is among the few creatures one Age places, and the spacing is enforced by
            // asking the world rather than by shrinking a share, so the weight means what it says.
            val often = if (spacing > 0) arrival.weight else (arrival.weight * density).roundToInt()
            return Placement(
                type, spawning.groundOf(id), spawning.lightOf(id),
                arrival, spacing, often.coerceAtLeast(1),
                // **Vanilla's own answer for this creature, never a heightmap of our choosing.** An Age
                // written for golems had almost none because this was `WORLD_SURFACE` for everything
                // (Jonah, 2026-08-31, walked; `/age spawns` counted `WrongPlacement` on 169 of 169). An
                // iron golem is registered `ON_GROUND` against `MOTION_BLOCKING_NO_LEAVES`, and the two
                // disagree wherever anything grows: `WORLD_SURFACE` counts a grass tuft where the other
                // does not, so on vegetated ground the column came back a block high, `ON_GROUND` looked
                // underneath it, found the tuft rather than the soil, and refused. Bare rock and sand
                // worked, which is exactly the scatter that reads as "they hardly ever spawn".
                SpawnPlacements.getHeightmapType(type),
            )
        }

        private const val BLOCKS_PER_CHUNK = 16
        private const val HALF_A_BLOCK = 0.5
        private const val A_FULL_TURN = 360.0f

        /** How high a creature that belongs aloft is put — well clear of anything the ground reaches. */
        private const val ALOFT_LEAST = 30
        private const val ALOFT_SPREAD = 30

        /** Where to start looking for a cave, and how far down to keep looking. */
        private const val DOWN_TO_LOOK_FROM = 8
        private const val HOW_FAR_TO_LOOK = 96

        /** How far around a creature held apart from nothing counts as here. */
        private const val ORDINARY_REACH = 64.0
    }
}
