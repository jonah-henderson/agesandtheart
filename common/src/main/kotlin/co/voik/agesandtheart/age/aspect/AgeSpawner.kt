package co.voik.agesandtheart.age.aspect

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntitySpawnReason
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
        val arrival: Arrival,
        /** How far apart these stand, with the rung the book asked for already in it. */
        val spacing: Int,
        /** Its share of one attempt, against the others this Age places. */
        val weight: Int,
    )

    private val choosing: WeightedList<Placement> =
        WeightedList.of(placedCreatures.map { Weighted(it, it.weight) })

    override fun tick(level: ServerLevel, spawnEnemies: Boolean) {
        if (placedCreatures.isEmpty()) return
        val chosen = choosing.getRandom(level.random).orElse(null) ?: return
        if (!spawnEnemies && chosen.type.category == MobCategory.MONSTER) return
        val at = somewhereIn(level) ?: return
        if (!chosen.arrival.mayBeTriedAt(at.x, at.z, chosen.spacing)) return
        val standing = standingRoomFor(chosen, level, at) ?: return
        if (alreadyEnoughAround(chosen, level, standing)) return
        place(chosen, level, standing)
    }

    /**
     * A column somewhere in the simulated area, chosen the way vanilla chooses one — anywhere among the
     * chunks being ticked rather than anywhere near one player in particular.
     *
     * Null where the roll landed on ground the server is not holding, which is most of a large area and is
     * why this is cheap: an unloaded chunk is not somewhere to spawn and generating one to find out would
     * be a spawner that drives world generation.
     */
    private fun somewhereIn(level: ServerLevel): BlockPos? {
        val players = level.players().filterNot { it.isSpectator }
        if (players.isEmpty()) return null
        val around = players[level.random.nextInt(players.size)].blockPosition()
        val reach = level.server.playerList.simulationDistance * BLOCKS_PER_CHUNK
        val x = around.x + level.random.nextInt(-reach, reach + 1)
        val z = around.z + level.random.nextInt(-reach, reach + 1)
        if (!level.hasChunkAt(BlockPos(x, level.minY, z))) return null
        return BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z)
    }

    /**
     * Where in that column this creature would stand, or null where it has no room.
     *
     * The ground rule decides which way to look and vanilla's own tests decide whether the answer will
     * hold, which is the whole of the imitation: a golem is put where a golem could have stood.
     */
    private fun standingRoomFor(chosen: Placement, level: ServerLevel, column: BlockPos): BlockPos? {
        val candidate = when (chosen.ground) {
            Ground.IN_THE_AIR -> column.above(ALOFT_LEAST + level.random.nextInt(ALOFT_SPREAD))
            Ground.UNDERGROUND -> openSpotBelow(level, column) ?: return null
            else -> column
        }
        if (candidate.y >= level.maxY || candidate.y <= level.minY) return null
        if (!SpawnPlacements.isSpawnPositionOk(chosen.type, level, candidate)) return null
        if (!SpawnPlacements.checkSpawnRules(chosen.type, level, EntitySpawnReason.NATURAL, candidate, level.random)) {
            return null
        }
        // **The test that refused the dragon**, kept rather than dodged: it is what stops a creature being
        // put inside the world. Aloft it is satisfiable, which is the whole reason a dragon goes up there.
        val room = chosen.type.getSpawnAABB(candidate.x + HALF_A_BLOCK, candidate.y.toDouble(), candidate.z + HALF_A_BLOCK)
        if (!level.noCollision(room)) return null
        return candidate
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
            mob.finalizeSpawn(level, difficulty, EntitySpawnReason.NATURAL, null)
            level.addFreshEntityWithPassengers(mob)
        }
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
            return Placement(type, spawning.groundOf(id), arrival, spacing, often.coerceAtLeast(1))
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
