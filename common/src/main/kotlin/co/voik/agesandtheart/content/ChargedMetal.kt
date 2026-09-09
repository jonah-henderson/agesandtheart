package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import kotlin.math.roundToLong
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What a block of arc crystal actually *does* to the metal around it (design §7.1.2) — iron pulls, gold
 * pushes, and bare copper bites.
 *
 * **The reckoning is [Arcs] and this is only the applying**, which is the split worth keeping: what a run
 * is and how well fed it is can be asked of any `BlockGetter` with no level ticking, and everything here
 * needs a server, a clock and entities to move.
 *
 * **Driven from the players outward, not from the blocks in.** A machine matters where somebody can feel
 * it, and chunks only tick near players anyway — so the search starts at each player, dismisses nearly
 * every section off its palette ([Arcs.crystalsNear]), and the machines it finds then reach out to
 * whatever is in range. That way the cost scales with how many people are about rather than with how much
 * crystal has ever been laid.
 *
 * **A field is the strongest run over you, never the sum of them** ([Arcs.strongest]'s rule, pooled here
 * across crystals): two arrays crossing are two descriptions of one charge reaching one place, and adding
 * them would pay a builder twice for a field they can only see once.
 */
object ChargedMetal {

    /**
     * One turn of every charged machine anybody is standing near.
     *
     * Called from both loaders' end-of-tick beside `Happenings.tick`; there is no shared event. Not inside
     * `Happenings`, deliberately — that walks Ages only, and a machine built at home out of crystal
     * carried back through a book has to work exactly as well as one built where the crystal fell.
     */
    fun stir(server: MinecraftServer) {
        if (server.tickCount % STIRRED_EVERY != 0L) return
        for (level in server.allLevels) {
            // Deduplicated across players before anything is driven, or a machine two people are standing
            // near would pull twice as hard as the one machine a builder laid.
            val crystals = LinkedHashSet<BlockPos>()
            for (player in level.players()) {
                if (player.isSpectator) continue
                crystals += Arcs.crystalsNear(level, player.position(), LOOKED_FOR_WITHIN)
            }
            if (crystals.isNotEmpty()) drive(level, crystals)
        }
    }

    private fun drive(level: ServerLevel, crystals: Set<BlockPos>) {
        val pulling = mutableListOf<Arcs.Run>()
        val pushing = mutableListOf<Arcs.Run>()
        val biting = mutableListOf<Arcs.Run>()
        for (at in crystals) {
            // **A redstone signal switches a crystal off**, which is the whole of the control surface: the
            // machine is the arrangement, so the only thing left to say about it is whether it is live.
            if (level.hasNeighborSignal(at)) continue
            pulling += Arcs.runsDrivenFrom(level, at, Arcs::attracts)
            pushing += Arcs.runsDrivenFrom(level, at, Arcs::repels)
            Arcs.massDrivenFrom(level, at)?.let { biting += it }
            // And a charged pile throws through its mast — see [liveThroughAMast].
            liveThroughAMast(level, at)?.let { biting += it }
        }
        field(level, pulling, TOWARD)
        field(level, pushing, AWAY)
        for (run in biting) bite(level, run)
    }

    /**
     * A charged pile read as a machine, or null where it has no charge or **no mast to throw it through**.
     *
     * **The rod is the only way the charge gets out** (Jonah, 2026-09-09), and that is what keeps the
     * copper apparatus worth building. A charged pile that bit whatever touched it would have been a
     * complete machine made of one material — no run to lay, no ratio to tune, no oxide to maintain — and
     * every reason to build in copper would have gone with it. Bare, a charged pile is *stored* charge and
     * nothing else; it is inert until somebody stands a rod on it.
     *
     * That is also the design's scene arriving exactly as written: the rod you put up to draw the bolt is
     * the turret you then have to walk into to collect the pile.
     *
     * Its own worth over its own blocks, so a charged pile comes out at twice the anchor whatever its
     * size — two damage every ten ticks. Bigger buys duration and reach, never a harder bite.
     */
    private fun liveThroughAMast(level: ServerLevel, at: BlockPos): Arcs.Run? {
        if (level.getBlockState(at).getValue(ArcCrystalBlock.CHARGE) <= ArcCrystalBlock.FLAT) return null
        val pile = Arcs.pileStruckAt(level, at).filter { charged(level, it) }
        // One run per pile, by the driver rule every other shape here uses: without it a pile of thirty
        // would be thirty machines standing in the same place.
        if (pile.minWithOrNull(POSITION_ORDER) != at) return null
        if (Arcs.rodsOn(level, pile).isEmpty()) return null
        return Arcs.Run(along = null, blocks = pile, crystal = pile.sumOf { Arcs.worthOf(level.getBlockState(it)) })
    }

    private fun charged(level: ServerLevel, at: BlockPos): Boolean {
        val state = level.getBlockState(at)
        return state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) &&
            state.getValue(ArcCrystalBlock.CHARGE) > ArcCrystalBlock.FLAT
    }

    /**
     * The pull or the push, applied once to everything either kind of run reaches.
     *
     * **Gathered per entity before anything is moved**, because the rule is the strongest run rather than
     * all of them: applying each run as it is found would have summed them, and a builder cannot see a sum.
     */
    private fun field(level: ServerLevel, runs: List<Arcs.Run>, sense: Double) {
        if (runs.isEmpty()) return
        val strongest = HashMap<Entity, Arcs.Run>()
        for (run in runs) {
            for (entity in level.getEntities(null as Entity?, reachOf(run)) { movedByAField(it) }) {
                val already = strongest[entity]
                if (already == null || run.force > already.force) strongest[entity] = run
            }
        }
        for ((entity, run) in strongest) shove(entity, run, sense)
        for (run in runs) sparkle(level, run, sense)
    }

    /**
     * Toward the nearest metal of the run, or away from it — **and it is the metal that pulls, not the
     * crystal**, which is what makes the shape of a build the shape of its field.
     *
     * It falls off across the run's own reach, so a long array is a broad gentle field and a short one is
     * a hard local yank. That is the same ramp [co.voik.agesandtheart.age.phenomena.DriftingOre] is shoved
     * on, and for the same reason: a wall should push where a pebble nudges.
     */
    private fun shove(entity: Entity, run: Arcs.Run, sense: Double) {
        val from = entity.position()
        val nearest = run.blocks.minByOrNull { it.distToCenterSqr(from) } ?: return
        val toward = Vec3.atCenterOf(nearest).subtract(from)
        val span = toward.length()
        if (span < CLOSEST) return
        val falloff = (1.0 - span / run.reach).coerceAtLeast(NONE)
        if (falloff <= NONE) return
        val strength = PULL_AT_ONE_TO_ONE * run.force.coerceAtMost(MOST_FORCE) * falloff * sense
        entity.push(toward.normalize().scale(strength))
        // A server that moves a player has to say so, or their own client puts them straight back — the
        // same flag an explosion sets.
        if (entity is ServerPlayer) entity.hurtMarked = true
    }

    /**
     * What a charged mass of copper does to whatever is in reach of it.
     *
     * **Both halves scale with the same ratio**, which is what the design asks of it: much crystal over
     * little copper gives fast deadly bursts, and a little spread over a lot gives slow light ones down to
     * a floor. One crystal to one copper block is the anchor — about half a heart a second, noticeably
     * better than a snow golem and no more.
     *
     * **The rate is read off the clock rather than remembered per victim.** A machine that kept a cooldown
     * per entity would need bookkeeping that goes stale the moment something despawns; the world's own
     * clock says the same thing and forgets nothing.
     */
    private fun bite(level: ServerLevel, run: Arcs.Run) {
        val force = run.force.coerceAtMost(MOST_FORCE)
        if (level.gameTime % bitesEvery(force) >= STIRRED_EVERY) return
        val rods = Arcs.rodsOn(level, run.blocks)
        val throws = Arcs.reachOfAMast(rods.size)
        val bitten = if (throws <= NONE) contactWith(run) else fromTheTip(rods, throws)
        val hurt = bitesFor(force)
        val source = biting(level)
        var bit = false
        for (entity in level.getEntities(null as Entity?, bitten) { it is LivingEntity }) {
            if ((entity as LivingEntity).hurtServer(level, source, hurt)) bit = true
        }
        if (!bit) return
        crackle(level, rods, run)
        spendACharge(level, run)
    }

    /**
     * What a bite costs the crystal feeding it — **one discharge from every charged block on the machine**.
     *
     * So a pile lasts the number of discharges the bolt left in it whatever its size, which is what "the
     * whole pile for some number of discharges" means: stacking more crystal buys force, and only another
     * storm buys more time.
     *
     * Nothing is spent by a pull or a push. A field is the charge sitting there being read; a bite is the
     * charge leaving.
     */
    private fun spendACharge(level: ServerLevel, run: Arcs.Run) {
        // **The whole pile, not the block that happened to bite.** Every charged block in a pile offers a
        // bite each turn and vanilla's invulnerability window lets exactly one of them land, so charging
        // only the winner would have made a big pile last as many times longer as it was big — which is
        // the opposite of "the whole pile for some number of discharges".
        val feeding = Arcs.crystalsAround(level, run.blocks) +
            run.blocks.filter { level.getBlockState(it).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) }
                .flatMap { Arcs.pileStruckAt(level, it) }
        for (at in feeding) {
            val state = level.getBlockState(at)
            if (!state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)) continue
            val left = state.getValue(ArcCrystalBlock.CHARGE)
            if (left <= ArcCrystalBlock.FLAT) continue
            level.setBlockAndUpdate(at, state.setValue(ArcCrystalBlock.CHARGE, left - 1))
        }
    }

    /**
     * A bolt coming down on a pile of arc crystal, which fills it (design §7.1.2).
     *
     * Called from `LightningBoltMixin` beside `Tempest.struck` — the one seam that fires once per bolt,
     * server side, with the trap's harmless flashes already filtered out.
     *
     * **A tempest Age charges the crystal and scours the copper in the same stroke**, vanilla's own
     * `clearCopperOnLightningStrike` doing the second half: the world that gives you the material is where
     * the machines run best, with no maintenance economy needing to be written.
     */
    fun struck(level: ServerLevel, at: BlockPos) {
        val pile = Arcs.pileStruckAt(level, at)
        if (pile.isEmpty()) return
        for (block in pile) {
            val state = level.getBlockState(block)
            level.setBlockAndUpdate(block, state.setValue(ArcCrystalBlock.CHARGE, ArcCrystalBlock.FULLY_CHARGED))
        }
        val middle = pile.first()
        level.sendParticles(
            ARC_GREEN,
            middle.x + HALF,
            middle.y + HALF,
            middle.z + HALF,
            SPARKS_ON_A_STRIKE,
            SPARK_SPREAD,
            SPARK_SPREAD,
            SPARK_SPREAD,
            SPARK_SPEED,
        )
    }

    /**
     * How often a machine of this ratio bites, in ticks.
     *
     * **Always a whole number of turns**, and that is not tidiness: the bite is timed off the world clock
     * against [STIRRED_EVERY], so an interval that is not a multiple of it aliases — thirteen ticks lands
     * on a turn only every sixty-five, which is a machine five times weaker than the number says and no
     * way to tell from reading it.
     *
     * Public and pure because it is the calibration surface: one crystal to one copper block is the anchor
     * the whole material is tuned against, and reading it off the mechanic is the only way a check stays
     * true when the mechanic is retuned ([Lures.reachFor] is public for the same reason).
     */
    fun bitesEvery(force: Double): Long {
        val wanted = (ANCHOR_BITE_EVERY / force.coerceAtLeast(CLOSEST)).roundToLong()
        val turns = (wanted.toDouble() / STIRRED_EVERY).roundToLong().coerceAtLeast(1L)
        return (turns * STIRRED_EVERY).coerceIn(FASTEST_BITE, SLOWEST_BITE)
    }

    /** And how hard, on the same ratio and against the same anchor. */
    fun bitesFor(force: Double): Float =
        (ANCHOR_BITE * force).coerceIn(LIGHTEST_BITE, HEAVIEST_BITE).toFloat()

    /** How far out a run's field is worth asking about: the metal itself, and its own length past it. */
    private fun reachOf(run: Arcs.Run): AABB = around(run).inflate(run.reach.toDouble())

    /** Touching the copper itself, which is what a fence with no mast is worth. */
    private fun contactWith(run: Arcs.Run): AABB = around(run).inflate(AN_ARC)

    private fun around(run: Arcs.Run): AABB =
        run.blocks.fold(AABB(run.blocks.first())) { box, block -> box.minmax(AABB(block)) }

    /** And with a mast, measured from its tip — so a stack of rods is height as well as reach. */
    private fun fromTheTip(rods: Set<BlockPos>, throws: Double): AABB {
        val tip = rods.maxBy { it.y }
        return AABB.ofSize(Vec3.atCenterOf(tip), throws * 2, throws * 2, throws * 2)
    }

    /**
     * Whether a field may move this at all.
     *
     * **Chainmail is the shield, and it is a mesh rather than a metaphor** (Jonah, 2026-09-09): a Faraday
     * cage is a conducting mesh, which is what chainmail is, and vanilla's one armour set with no crafting
     * recipe had nothing else to be for. It stops the *field* and not the bite — being inside a cage is no
     * help when you are touching the live thing yourself, which is also what keeps deretheni the answer to
     * a charged pile.
     *
     * Worn by anything, not only a player. A chainmail zombie walking unbothered through a grinder is the
     * kind of oddity worth keeping.
     */
    private fun movedByAField(entity: Entity): Boolean {
        // Spectators only. A creative player is *not* exempt, and that is deliberate: creative is how this
        // gets looked at, and a field somebody cannot feel while testing it is a field nobody can tune.
        if (entity.isSpectator) return false
        val caged = entity is LivingEntity &&
            entity.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.CHAINMAIL_CHESTPLATE)
        return !caged
    }

    /**
     * Arc green at the operative end — **the far end of the run**, which is where a field is doing its
     * work and the only part of a build a player can watch.
     *
     * Drawn moving with the sense of the field, so a puller draws inward and a pusher outward and the two
     * are told apart without a tooltip.
     */
    private fun sparkle(level: ServerLevel, run: Arcs.Run, sense: Double) {
        if (level.random.nextInt(SPARKS_ONE_STIR_IN) != 0) return
        val end = run.blocks.last()
        val along = run.along?.let { Vec3.atLowerCornerOf(it.unitVec3i) } ?: Vec3.ZERO
        val drift = along.scale(SPARK_DRIFT * sense)
        level.sendParticles(
            ARC_GREEN,
            end.x + HALF,
            end.y + HALF,
            end.z + HALF,
            SPARKS_AT_A_TIME,
            drift.x,
            drift.y,
            drift.z,
            SPARK_SPEED,
        )
    }

    /** And a white spark where it bit, which is the one part of this that is not a field. */
    private fun crackle(level: ServerLevel, rods: Set<BlockPos>, run: Arcs.Run) {
        val from = rods.maxByOrNull { it.y } ?: run.blocks.first()
        level.sendParticles(
            ParticleTypes.ELECTRIC_SPARK,
            from.x + HALF,
            from.y + HALF,
            from.z + HALF,
            SPARKS_AT_A_TIME,
            SPARK_SPREAD,
            SPARK_SPREAD,
            SPARK_SPREAD,
            SPARK_SPEED,
        )
    }

    /**
     * The source, remembered against the registries it came out of — `AstriteShardBlock`'s reasoning, for
     * the same reason: a `DamageSource` holds a `Holder` from that set, so a reload replaces it.
     */
    private var biting: Pair<Any, DamageSource>? = null

    private fun biting(level: ServerLevel): DamageSource {
        val registries = level.registryAccess()
        biting?.let { (from, made) -> if (from === registries) return made }
        val made = DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ARC_CURRENT))
        biting = registries to made
        return made
    }

    val ARC_CURRENT: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, "arc_current".location())

    /**
     * How far from a player a machine is looked for.
     *
     * A run may be [Arcs.LONGEST_RUN] long and reaches its own length past its end, so this is what it
     * takes for the furthest machine that could touch somebody to be found. **The first dial if a sky of
     * these ever costs anything**, since the section count goes as its cube.
     */
    private const val LOOKED_FOR_WITHIN = Arcs.LONGEST_RUN * 2.0

    /** How often the machines turn over. Fine enough to feel continuous, coarse enough to be cheap. */
    const val STIRRED_EVERY = 5L

    /**
     * How hard a one-to-one array shoves at point blank, per turn.
     *
     * About a third of gravity, so an ordinary array is a drag you lean against and a well-fed one lifts
     * you. [MOST_FORCE] is what stops an absurd ratio launching anything into orbit.
     */
    private const val PULL_AT_ONE_TO_ONE = 0.12
    private const val MOST_FORCE = 8.0
    private const val TOWARD = 1.0
    private const val AWAY = -1.0

    /** What a one-to-one machine bites for and how often — half a heart a second, and the anchor. */
    private const val ANCHOR_BITE = 1.0
    private const val ANCHOR_BITE_EVERY = 20.0

    /**
     * And the ends of both ramps.
     *
     * [FASTEST_BITE] is ten ticks because that is vanilla's own invulnerability window: anything faster
     * would be swallowed rather than felt, and a machine whose extra crystal did nothing is worse than one
     * that stops improving.
     */
    private const val FASTEST_BITE = 10L
    private const val SLOWEST_BITE = 60L
    private const val LIGHTEST_BITE = 0.5
    private const val HEAVIEST_BITE = 12.0

    /** Brilliant electric green — the set's key colour, as violet is the meteors'. */
    private val ARC_GREEN = DustParticleOptions(0x3C_FF_6A, 1.0f)

    private const val SPARKS_ONE_STIR_IN = 3
    private const val SPARKS_AT_A_TIME = 2

    /** And what a bolt landing on a pile throws, which should be seen from wherever you were sheltering. */
    private const val SPARKS_ON_A_STRIKE = 60
    private const val SPARK_DRIFT = 0.08
    private const val SPARK_SPREAD = 0.25
    private const val SPARK_SPEED = 0.02

    /** Any total order will do, and only agreeing matters — `Arcs.runsDrivenFrom` carries the argument. */
    private val POSITION_ORDER = compareBy<BlockPos>({ it.x }, { it.y }, { it.z })

    /** How far past the copper a bare mass is still touching you. */
    private const val AN_ARC = 0.35

    private const val CLOSEST = 0.001
    private const val NONE = 0.0
    private const val HALF = 0.5
}
