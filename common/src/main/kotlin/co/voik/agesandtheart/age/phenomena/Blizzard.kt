package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import co.voik.agesandtheart.location
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.roundToInt

/**
 * Snow driven sideways (design §5.2) — the Age buried from one direction rather than dusted from above.
 *
 * **The wind is the whole character of it** (Jonah, 2026-09-05). Snow that settles evenly is weather; snow
 * that piles against one face of everything, drifts up a wall and scours the other side is a *storm*, and
 * the difference is one vector. So a blizzard picks a bearing and lays its snow along it: deep where the
 * wind is stopped, thin where it runs free.
 *
 * **The bearing is derived, never stored** (§5.4). It comes from the Age's seed and the day, so every
 * client and every reload agree about which way the wind is blowing without anything being written down,
 * and a new day brings a new storm rather than the same one for ever.
 *
 * **A drift is a record of itself.** Snow piles into a layer, a full layer becomes a block and takes the
 * next drift on top of it, and three of any stage standing together harden the bottom one — snow to ice,
 * ice to packed, packed to blue, which is terminal. So a column grows while each block in it stops, and
 * what is left is a bank with fresh snow at the top and blue ice at the bottom: how long a blizzard has
 * worked this place, readable from the side, with nothing written down (§5.4).
 *
 * **Light is the answer and it is vanilla's own.** `Biome.shouldFreeze` and `shouldSnow` have both tested
 * `getBrightness(BLOCK) < 10` since the game had snow, so lighting your ground already stops it piling up;
 * nothing here re-states that rule, it simply obeys it. What is ours is holding the player to the same
 * threshold, which is what makes a lit path home worth building.
 */
object Blizzard {

    /**
     * One tick of it, over the ground near whoever is in the Age.
     *
     * Does nothing at all unless the Age is actually in weather: a blizzard *is* the storm, so what makes
     * it come and go is [shareOfTheTime] steering the Age's own weather rather than anything decided here.
     */
    fun blow(level: ServerLevel, density: Double, fury: Double) {
        if (!level.isRaining) return
        // How *hard*, not how often: by the time this runs the storm is already here, and what is left to
        // decide is what it does while it lasts.
        val severity = hardnessIn(level, fury)
        val bearing = bearingIn(level)
        val cursor = BlockPos.MutableBlockPos()
        for (player in level.players()) {
            val around = BlockPos.containing(player.position())
            repeat(driftsPerTick(severity)) {
                val x = around.x + level.random.nextInt(-REACH, REACH)
                val z = around.z + level.random.nextInt(-REACH, REACH)
                driftAt(level, cursor, x, z, bearing, severity)
            }
            chill(level, around, severity)
        }
    }

    /**
     * What standing out in it does to you — the second of the three things a blizzard denies (§5.2).
     *
     * **Vanilla's freezing, driven rather than reimplemented.** `ticksFrozen` already gives the shivering,
     * the ice vignette and the damage, and `canFreeze` already refuses anybody wearing
     * `#minecraft:freeze_immune_wearables` — leather, and a deretheni suit. So the counterplay costs
     * nothing here and a player who owns either is simply not cold.
     *
     * **It has to out-pace the thaw.** `LivingEntity` sheds two ticks of frost every tick you are not in
     * powder snow, so anything under three is a blizzard that never bites; three is vanilla's own pace in
     * powder snow, and a fiercer storm gains faster.
     */
    private fun chill(level: ServerLevel, around: BlockPos, severity: Double) {
        val nearby = AABB(around).inflate(REACH.toDouble())
        for (living in level.getEntitiesOfClass(LivingEntity::class.java, nearby) { it.canFreeze() }) {
            val at = living.blockPosition()
            val onYou = exposureAt(level, at) * (ALL_OF_IT - warmthAt(level, at))
            if (onYou <= NOTHING) continue
            val bite = (BITES_BY * severity * onYou).roundToInt().coerceAtLeast(1)
            val gaining = THAWS_BY + bite
            living.ticksFrozen = (living.ticksFrozen + gaining).coerceAtMost(living.ticksRequiredToFreeze * DEEPEST_CHILL)
        }
    }

    /**
     * How much of the storm is on this spot, from none of it to all of it — **the one definition**, read
     * by the cold here and by the wind's crossfade on the client.
     *
     * **Cover, and graded** (Jonah, 2026-09-07). Sky light is the answer because it walks round an overhang
     * and down through a canopy, so a lip of rock slows the cold, a stand of trees halves it and a cave or
     * a roofed room stops it. There is nothing here about what a roof *is*; the lighting engine knows.
     *
     * **Block light is deliberately not consulted, and that is a change.** It used to stop the freezing
     * outright, on the argument that what keeps the drift off your ground keeps the cold off you — but a
     * field of torches is not warm, it is a lit field you are still standing in the wind of (Jonah). Light
     * still keeps your *ground*; see [driftAt], which is vanilla's own rule and keeps it. What answers the
     * cold is cover, a real fire, or the leather the design always meant to be the portable answer.
     */
    fun exposureAt(level: ServerLevel, at: BlockPos): Float =
        (level.getBrightness(LightLayer.SKY, at).toFloat() / OPEN_TO_THE_SKY).coerceIn(NOTHING, ALL_OF_IT)

    /**
     * How much a real fire nearby takes off the cold, from none of it to [MOST_A_FIRE_GIVES].
     *
     * **Never all of it, which is the whole distinction being drawn.** A campfire in the open is warmth in
     * a place you are still exposed, so it slows the freezing and cannot stop it; only cover does that.
     * That is what keeps a ring of campfires from becoming the field of torches this replaced.
     *
     * **Dismissed off the section palette before a single position is read** — the trick `Wounds` and
     * `Lures` use, and it is what makes this affordable at all: this runs for every freezable entity every
     * tick, and almost none of them are ever near a fire. A section holding nothing warm answers in one
     * predicate over a handful of palette entries.
     */
    private fun warmthAt(level: ServerLevel, at: BlockPos): Float {
        if (!anythingWarmNear(level, at)) return NOTHING
        val cursor = BlockPos.MutableBlockPos()
        var closest = Int.MAX_VALUE
        for (x in -WARMED_WITHIN..WARMED_WITHIN) {
            for (y in -WARMED_WITHIN..WARMED_WITHIN) {
                for (z in -WARMED_WITHIN..WARMED_WITHIN) {
                    cursor.setWithOffset(at, x, y, z)
                    if (!level.getBlockState(cursor).`is`(WARMS_YOU)) continue
                    closest = minOf(closest, x * x + y * y + z * z)
                }
            }
        }
        if (closest == Int.MAX_VALUE) return NOTHING
        val away = kotlin.math.sqrt(closest.toDouble()).toFloat()
        return MOST_A_FIRE_GIVES * (ALL_OF_IT - away / (WARMED_WITHIN + ONE_MORE))
    }

    /** Whether any section this spot's reach touches holds anything warm at all. */
    private fun anythingWarmNear(level: ServerLevel, at: BlockPos): Boolean {
        val chunk = level.getChunk(SectionPos.blockToSectionCoord(at.x), SectionPos.blockToSectionCoord(at.z))
        val lowest = level.getSectionIndex(at.y - WARMED_WITHIN)
        val highest = level.getSectionIndex(at.y + WARMED_WITHIN)
        for (index in lowest..highest) {
            val section = chunk.sections.getOrNull(index) ?: continue
            if (!section.hasOnlyAir() && section.maybeHas { it.`is`(WARMS_YOU) }) return true
        }
        return false
    }

    /**
     * Tell everyone in [level] whether a blizzard blows here and how hard.
     *
     * Sent even where there is none, because a client that is only told when there *is* one keeps the last
     * storm it heard about after its owner links somewhere calm.
     */
    fun tellTheClients(
        level: ServerLevel,
        befalls: Map<Phenomenon, Double>,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ) {
        val age = level.dimension().identifier()
        val density = befalls[Phenomenon.BLIZZARD]
        val telling = if (density == null) {
            BlizzardPayload.noneIn(age)
        } else {
            val severity = hardnessIn(level, Happenings.furyOf(spending, prices, Phenomenon.BLIZZARD))
            BlizzardPayload(age, severity, bearingIn(level).get2DDataValue())
        }
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    /**
     * How much of the time an Age at this severity is in a storm, as [AgeWeather.Conditions] wants it.
     *
     * **One of the two things severity drives**, and the one that decides how much of your life is spent
     * in a storm: an ordinary blizzard comes about as often as vanilla's rain and passes in a few minutes,
     * where a furious Age is scarcely ever out of one.
     *
     * The other is how hard it blows while it is here — [driftsPerTick] and [inTheLee] on this side, and
     * the visibility, the wind and the speed of the snow on the client's. A blizzard bought with
     * instability or asked for at a rung is fiercer *and* more constant, because a storm that came more
     * often without getting worse would only be tedious.
     */
    fun shareOfTheTime(howOften: Double): Double =
        (AS_OFTEN_AS_RAIN + (howOften - Rung.ORDINARY) * MORE_OF_THE_TIME)
            .coerceIn(AS_OFTEN_AS_RAIN, ALMOST_ALWAYS)

    /**
     * A fierceness set by hand, for looking at one — `/age weather blizzard <intensity>`.
     *
     * **Transient and per Age.** It is a debug tool, so it survives no reload and is written nowhere; and
     * it *summons* a blizzard as well as setting its strength, because the alternative is finding an Age
     * that already has one before you can look at the thing you are tuning.
     */
    private val forced = ConcurrentHashMap<Identifier, Double>()

    fun force(level: ServerLevel, hardness: Double) {
        forced[level.dimension().identifier()] = hardness
    }

    fun release(level: ServerLevel) {
        forced.remove(level.dimension().identifier())
    }

    /** What was set by hand here, or null where nothing was. */
    fun forcedIn(level: ServerLevel): Double? = forced[level.dimension().identifier()]

    /** How hard it blows here: what somebody asked for, else what the Age's own instability bought. */
    fun hardnessIn(level: ServerLevel, fury: Double): Double = forcedIn(level) ?: howHardOf(fury)

    /**
     * **How often it blows** — the rung and the instability together.
     *
     * A rung is *how much of a thing there is* (`Rung`), which for weather is how much of the time it is
     * happening: `teeming blizzard` is an Age that is often in one, not an Age whose storms are worse.
     * That distinction is Jonah's (2026-09-05) and it is what keeps the quantifiers meaning one thing
     * across every aspect they reach.
     */
    fun howOftenOf(density: Double, fury: Double): Double =
        (density / Rung.ORDINARY) + fury * FURY_ALSO_LINGERS

    /**
     * **How hard it blows while it is here** — instability, and nothing a quantifier can say.
     *
     * Deliberately not the rung: asking for *more* blizzard is asking for more of the time in one. What
     * makes a storm worse is an Age coming apart — and, when there is a word for it, a modifier of its own
     * (`fierce`, `strong`; Jonah, 2026-09-05, not yet written). Both would raise this and nothing else.
     */
    fun howHardOf(fury: Double): Double = Rung.ORDINARY + fury * FURY_DRIVES

    /**
     * Which way the wind blows here today — the same answer for everyone, from nothing written down.
     *
     * Keyed on the Age's seed and its day, so a storm has a direction that holds while it blows and the
     * next one does not inherit it.
     */
    fun bearingIn(level: ServerLevel): Direction {
        val day = level.gameTime / TICKS_PER_DAY
        val drawn = XoroshiroRandomSource(level.seed xor WIND_SALT xor day).nextInt(HORIZONS)
        return Direction.from2DDataValue(drawn)
    }

    /**
     * One drift, at the column over [x], [z].
     *
     * **Deeper where the wind is stopped.** A column with rock immediately downwind is in the lee of it,
     * which is where a drift actually builds — so that is where the snow goes several layers at a time,
     * and open ground gets one layer at a time like ordinary weather. That is the whole of "driving": the
     * same snowfall, distributed by what is in its way.
     */
    private fun driftAt(
        level: ServerLevel,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        z: Int,
        bearing: Direction,
        severity: Double,
    ) {
        // **`WORLD_SURFACE`, not `MOTION_BLOCKING`**, and the difference is the whole of why nothing used
        // to stack. A one-layer snow does not block motion and a two-layer one does, so the motion
        // heightmap jumped above the drift the moment it reached two — and `SnowLayerBlock.canSurvive`
        // refuses a layer on top of anything but a full face or a full eight, so every column in the Age
        // stopped dead at two layers and the ground read as noise. `NOT_AIR` puts this above the drift
        // whatever depth it is.
        val open = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        cursor.set(x, open, z)
        if (!level.isLoaded(cursor)) return
        if (level.getBrightness(LightLayer.BLOCK, cursor) >= KEEPS_ITS_GROUND) return
        if (!level.canSeeSky(cursor)) return

        // **Snow runs downhill before it piles up**, which is what stops a storm laying spikes and pits.
        // Each drift is placed at random and independently, so left alone the depths are Poisson noise;
        // real snow finds the low ground first, and one look at the neighbours is the whole of that.
        //
        // **Except in the lee, which is the whole point of a wind.** Levelling everything flattened exactly
        // the banks the bearing exists to build, so a column with something solid downwind of it is left
        // alone to climb. Open ground smooths; sheltered ground piles.
        if (!inTheLeeOfSomething(level, cursor, x, open, z, bearing)) {
            val (atX, atZ) = downhillOf(level, x, z, open)
            if (atX != x || atZ != z) {
                driftOnto(level, cursor, atX, atZ, bearing, severity)
                return
            }
        }
        driftOnto(level, cursor, x, z, bearing, severity)
    }

    /**
     * Whether something solid stands downwind of this column, at the height the drift is building.
     *
     * **Asked at the open air rather than at the ground**, which is where a wall actually is: a column
     * beside a cliff has rock next to its *snow*, and asking one block lower found the ground the cliff
     * stands on instead and answered no everywhere flat.
     */
    private fun inTheLeeOfSomething(
        level: ServerLevel,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        open: Int,
        z: Int,
        bearing: Direction,
    ): Boolean {
        cursor.set(x + bearing.stepX, open, z + bearing.stepZ)
        val downwind = level.getBlockState(cursor).isSolidRender
        cursor.set(x, open, z)
        return downwind
    }

    /**
     * The column a flake laid here would actually come to rest on — this one, or a markedly lower neighbour.
     *
     * A snow bank has an angle of repose; a column of noise does not. Nothing here models an angle, it
     * simply refuses to build a tower beside a hollow, which is enough for a drift to read as one.
     */
    private fun downhillOf(level: ServerLevel, x: Int, z: Int, open: Int): Pair<Int, Int> {
        var lowestX = x
        var lowestZ = z
        var lowest = open
        for (way in Direction.Plane.HORIZONTAL) {
            val overX = x + way.stepX
            val overZ = z + way.stepZ
            val theirs = level.getHeight(Heightmap.Types.WORLD_SURFACE, overX, overZ)
            // **Any lower at all, not merely a step lower.** Tolerating a block of difference let a column
            // that had won a coin toss keep winning, and the Age grew spires; snow that always takes the
            // low ground fills a hollow before it raises anything.
            if (theirs < lowest) {
                lowest = theirs
                lowestX = overX
                lowestZ = overZ
            }
        }
        return lowestX to lowestZ
    }

    /**
     * Lay this drift on the column at [x], [z].
     *
     * **Deeper where the wind is stopped**, which is what "driving" means mechanically: the same snowfall,
     * distributed by what is in its way. A column in the lee grows faster, and because a full column
     * becomes a block and takes another on top of it, that growth *climbs* the wall rather than only
     * thickening at its foot.
     */
    private fun driftOnto(
        level: ServerLevel,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        z: Int,
        bearing: Direction,
        severity: Double,
    ) {
        // **Asked again here, and not only where the column was chosen.** A drift runs downhill onto a
        // *neighbour*, and that neighbour has passed none of these: left unchecked, an unlit column rolled
        // its snow onto the lit one beside it and took the ground back that a torch had bought, which is
        // the counterplay the whole phenomenon is built around. The chunk is asked for first, since
        // reading a heightmap out of one that is not loaded would fetch it.
        if (!level.isLoaded(cursor.set(x, level.minY, z))) return
        val open = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        cursor.set(x, open, z)
        if (level.getBrightness(LightLayer.BLOCK, cursor) >= KEEPS_ITS_GROUND) return
        if (!level.canSeeSky(cursor)) return
        if (depthOfDriftAt(level, cursor, x, open - 1, z) >= DEEPEST_DRIFT) return

        val laying = if (inTheLeeOfSomething(level, cursor, x, open, z, bearing)) {
            inTheLee(severity)
        } else {
            ONE_BLOCK
        }
        for (course in 0..<laying) {
            cursor.set(x, open + course, z)
            if (!level.getBlockState(cursor).isAir) break
            level.setBlock(cursor, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_ALL)
        }
        // **Asked of the heightmap rather than assumed.** The loop above stops early wherever something
        // was in the way, so counting on every course having been laid would have walked the ladder from a
        // block of air and found no run to compact.
        settle(level, cursor, x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z)
    }

    /**
     * How deep the drift standing over this column already is, in blocks of ours.
     *
     * Counts down from the top through anything the storm could have laid, so a column that has been
     * compressing for a while is measured by what it has become rather than by what fell last.
     */
    private fun depthOfDriftAt(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int): Int {
        var depth = 0
        while (depth < DEEPEST_DRIFT) {
            cursor.set(x, top - depth, z)
            if (!laidByAStorm(level.getBlockState(cursor))) break
            depth++
        }
        cursor.set(x, top, z)
        return depth
    }

    /**
     * The compression ladder: **what is on top of you is what packs you**.
     *
     * A block three deep is snow, six deep is ice, nine is packed and twelve is blue — so a drift is a
     * gradient with powder at the surface and blue ice at its floor, and its depth *is* its history,
     * readable from the side with nothing written down (§5.4).
     *
     * **Keyed on depth rather than on runs of three, and the difference is not cosmetic.** Hardening the
     * bottom of every run of three anywhere in the column cascades: the run resets, the next deposit
     * remakes it, and a simulation of the old rule had a drift more than half blue ice after nine
     * deposits, with the gradient gone. Depth cannot cascade — a block is exactly as pressed as what lies
     * on it, which is also what the physical thing does.
     */
    private fun settle(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int) {
        for (depth in 0..<DEEPEST_DRIFT) {
            cursor.set(x, top - depth, z)
            val standing = level.getBlockState(cursor)
            val stage = PACKING.indexOfFirst { standing.`is`(it) }
            if (stage < 0) return
            val pressed = (depth / A_DEEP_ENOUGH_RUN).coerceAtMost(PACKING.lastIndex)
            if (pressed > stage) level.setBlock(cursor, PACKING[pressed].defaultBlockState(), Block.UPDATE_ALL)
        }
    }

    /** Whether a blizzard could have put this here, which is what a drift is measured through. */
    private fun laidByAStorm(state: BlockState): Boolean = PACKING.any { state.`is`(it) }

    /**
     * What each stage of the drift hardens into, once three of it stand together.
     *
     * **Powder snow is what falls**, which is the whole difference between this and weather: a fresh drift
     * is something you fall into and freeze in rather than something you walk over, and leather boots are
     * already the answer. What it compacts into is walkable, so a bank reads as dangerous on top and solid
     * underneath — the gradient a player learns to trust.
     */
    private val PACKING = listOf(
        Blocks.POWDER_SNOW,
        Blocks.SNOW_BLOCK,
        Blocks.ICE,
        Blocks.PACKED_ICE,
        Blocks.BLUE_ICE,
    )

    /** What `LivingEntity` sheds every tick you are not freezing, and so what a storm must first replace. */
    private const val THAWS_BY = 2

    /** How fast an ordinary blizzard gains on that — vanilla's own pace in powder snow. */
    private const val BITES_BY = 1.0

    /** How far past frozen the cold is allowed to bank up, so stepping inside is not instant relief. */
    private const val DEEPEST_CHILL = 2

    /** How many blocks have to lie on a block before it packs down a stage. */
    private const val A_DEEP_ENOUGH_RUN = 3

    /**
     * How deep a drift may get before the storm stops adding to it.
     *
     * **Not a fence against burial**, which is the hazard and is meant to happen — only against a column
     * that would otherwise climb to the top of the world.
     */
    private const val DEEPEST_DRIFT = 24

    /** How many courses a sheltered column takes at once — more of them the fiercer the storm. */
    private fun inTheLee(severity: Double): Int =
        (LEE_COURSES * severity).roundToInt().coerceIn(LEE_COURSES, MOST_AT_ONCE)

    /** How many columns are touched a tick, which is how fast the Age fills in. */
    private fun driftsPerTick(severity: Double): Int =
        (DRIFTS_ORDINARILY * severity).roundToInt().coerceIn(1, MOST_DRIFTS)

    /** Vanilla's threshold, and the whole of the counterplay. */
    private const val KEEPS_ITS_GROUND = 10

    /** How far from a player the storm is worked, in blocks. */
    private const val REACH = 48

    /** The sky light of open ground, so the first block of cover is already worth something. */
    private const val OPEN_TO_THE_SKY = 15.0f

    /**
     * What may warm you, as a tag — genuinely hot things, never merely bright ones.
     *
     * A tag rather than a list so a pack can add its own hearth, and so the line between "gives light" and
     * "gives heat" is one a datapack can move. Minecraft has no heat model, which is why this is the
     * smallest possible one: a set of blocks and a radius.
     */
    val WARMS_YOU: TagKey<Block> = TagKey.create(Registries.BLOCK, "warms_you".location())

    /** How far a fire carries, in blocks, and the most of the cold it can ever take off. */
    private const val WARMED_WITHIN = 3
    private const val MOST_A_FIRE_GIVES = 0.6f

    private const val ONE_MORE = 1
    private const val NOTHING = 0.0f
    private const val ALL_OF_IT = 1.0f

    private const val ONE_BLOCK = 1
    private const val LEE_COURSES = 1
    private const val MOST_AT_ONCE = 2

    /**
     * How many columns are touched a tick.
     *
     * **An eighth of what it was, because a block is eight layers.** These numbers were written when a
     * drift laid one *layer* of snow; laying a whole powder-snow block at the same rate buried the Age
     * eight times too fast (Jonah, 2026-09-05, walked).
     */
    private const val DRIFTS_ORDINARILY = 3
    private const val MOST_DRIFTS = 20

    /** About as much of the time as vanilla rains, which is what an ordinary blizzard should feel like. */
    private const val AS_OFTEN_AS_RAIN = 0.3

    /** Scarcely ever out of one, which is the top of the ladder rather than a value a writer reaches. */
    private const val ALMOST_ALWAYS = 0.98

    /** How much of the axis a full rung or a full fury covers. */
    private const val MORE_OF_THE_TIME = 0.34

    /** What a full reach of the manifestation adds to how hard a storm blows. */
    private const val FURY_DRIVES = 2.0

    /** And how much it adds to how often one comes, on top of whatever rung was written. */
    private const val FURY_ALSO_LINGERS = 2.0

    private const val HORIZONS = 4
    private const val TICKS_PER_DAY = 24000L

    /** So the wind is decorrelated from everything else the seed drives. */
    private const val WIND_SALT = 0xB112_2A2DL
}
