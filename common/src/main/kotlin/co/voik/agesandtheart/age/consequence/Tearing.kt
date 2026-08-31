package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import kotlin.math.pow

/**
 * How holed an Age is, and how that grows — wounds at generation (§5.1) and blight thereafter (§5.2.1).
 *
 * **One function, three touchpoints.** A chunk is torn when it generates, again when it loads after time
 * has passed, and a little at a time while somebody stands in it. All three ask [wantedIn] for the same
 * answer, so a chunk generated late, a chunk fast-forwarded on load and a chunk watched in real time agree
 * about how bad the Age has got. Nothing else may decide it.
 *
 * **The count is the state, which is §5.4 satisfied literally.** How far blight has crept is legible from
 * the wounds already there — so the pass places the *difference* between what a chunk holds and what it
 * should, and never has to remember what it did last time. That also makes it idempotent: running it twice
 * places nothing the second time.
 *
 * **Density is a property of the Age, never a lineage.** Wounds do not spawn children and a sealed wound
 * seeds nothing, because there is nothing to seed — the Age itself is unstable, and seals contain what a
 * wound does to its surroundings rather than whether the Age keeps tearing. You can box in every wound in
 * a blighted Age and come back to more of them.
 */
object Tearing {

    /**
     * How many wounds a chunk should hold, given what the Age was written with and how long it has stood.
     *
     * **Blight is a rate, not an amount** (design §5.2.1). [written] is how holed the book made it and does
     * not move; [perDay] is how much worse each of the Age's days makes it.
     *
     * It climbs until [MOST_PER_CHUNK], which is **saturation rather than a budget** — read that constant
     * before concluding the register has a ceiling, because the distinction is the whole of why one is
     * allowed and the other is not.
     *
     * **Quantised to whole days**, which buys two things: the growth is legible as "worse than yesterday"
     * rather than as an imperceptible creep, and two Ages generated seconds apart agree, so `/age compare`
     * is not a coin toss.
     */
    fun densityAt(written: Double, perDay: Double, days: Long): Double =
        (written + perDay * days.coerceAtLeast(0L).toDouble()).coerceAtMost(MOST_PER_CHUNK)

    /**
     * That density as a count for one chunk — a whole number of them, and a fractional chance at one more,
     * so 0.5 is half the chunks holding one.
     *
     * The fractional roll is drawn per chunk and never per density, which is what makes the count
     * **monotone**: as the density climbs past a whole number the floor gains one exactly as the fraction
     * wraps to nothing, so no chunk ever wants fewer wounds than it wanted yesterday. A fast-forward that
     * could ask for fewer would have to take one away, and a wound is not a thing that closes.
     */
    fun wantedIn(here: ChunkPos, worldSeed: Long, density: Double): Int {
        if (density <= NONE) return 0
        val certain = density.toInt()
        val random = WorldgenRandom(LegacyRandomSource(worldSeed))
        random.setLargeFeatureSeed(worldSeed xor SHARE_SALT, here.x, here.z)
        return certain + if (random.nextDouble() < density - certain) 1 else 0
    }

    /**
     * Tear [chunk] open until it holds [wanted] of them, and no further.
     *
     * Reads what is already there rather than being told, so the caller needs no bookkeeping and the pass
     * is safe to run as often as anything likes. Returns how many it opened, which is what a caller reports
     * or animates.
     *
     * **Existing wounds are never touched**, so a sealed one stays sealed and a boxed-in one stays boxed.
     *
     * **[alreadyRunning] decides whether anybody is told**, and the default is that they are. Generation
     * has no clients and wants no neighbour updates, so it writes the block and nothing else; every later
     * caller is tearing a world somebody may be standing in, where a block written without
     * `UPDATE_CLIENTS` reaches no client at all and the wound is invisible until the chunk next loads.
     * Neighbour updates stay off either way — a wound is a hole appearing, not a block placed.
     */
    fun tearInto(
        level: LevelAccessor,
        chunk: ChunkAccess,
        worldSeed: Long,
        wanted: Int,
        atMost: Int = ALL_AT_ONCE,
        already: Int = countIn(chunk),
        alreadyRunning: Boolean = true,
    ): Int {
        val opening = (wanted - already).coerceAtMost(atMost)
        if (opening <= 0) return 0
        val here = chunk.pos
        val update = if (alreadyRunning) Block.UPDATE_CLIENTS else Block.UPDATE_NONE
        // Seeded per index rather than once per pass, so where the tenth wound goes does not depend on how
        // many were opened before it — which is what lets a fast-forward add to a chunk instead of redoing
        // it, and what makes the same chunk come out the same however the count was reached.
        val random = WorldgenRandom(LegacyRandomSource(0L))
        var opened = 0
        for (index in already..<(already + opening)) {
            random.setLargeFeatureSeed(worldSeed xor WOUND_SALT xor index.toLong(), here.x, here.z)
            if (openOne(level, chunk, here, random, update)) opened++
        }
        return opened
    }

    /**
     * One wound, somewhere in this chunk.
     *
     * **How far it may stray from the surface grows with how many there are**, which is what keeps a badly
     * torn Age from being a slab of them at head height: a lightly flawed world holds a few near where a
     * writer walks, and a holed one is torn from bedrock to sky.
     *
     * Declines a position already holding one, so a saturating chunk stops gaining rather than overwriting
     * itself — the only bound in the register, and it is arithmetic rather than a budget: at the top of it
     * every column is a hole, and there is nowhere left for a wound to be.
     */
    private fun openOne(
        level: LevelAccessor,
        chunk: ChunkAccess,
        here: ChunkPos,
        random: WorldgenRandom,
        update: Int,
    ): Boolean {
        val x = here.minBlockX + random.nextInt(SECTION)
        val z = here.minBlockZ + random.nextInt(SECTION)
        // **Mostly above ground, which is the Riven image**: the striking thing about that tear is that it
        // hangs in the open at about eye level, and a wound always underground never gets to be one.
        //
        // The rest are spread **evenly down the whole column** rather than tucked just beneath the grass,
        // so one is as likely to be met deep in a cave as a spit under the surface. Two different
        // distributions on purpose: the surface ones are *near* it because that is what makes them visible,
        // and the buried ones are anywhere because that is what makes them a surprise.
        val surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z)
        val floor = level.minY + 1
        val top = level.minY + level.height - 1
        val y = if (random.nextDouble() < ABOVE_GROUND) {
            surface + 1 + random.nextInt(OVERHEAD)
        } else {
            val depth = (surface - 1 - floor).coerceAtLeast(1)
            floor + random.nextInt(depth)
        }
        val at = BlockPos(x, y.coerceIn(floor, top), z)
        if (level.getBlockState(at).`is`(AgeContent.WOUND_BLOCK)) return false
        level.setBlock(at, AgeContent.WOUND_BLOCK.defaultBlockState(), update)
        return true
    }

    /**
     * How many wounds a chunk holds, off its own blocks.
     *
     * The palette answers for a whole section at a time, so a chunk that has never held one costs a set
     * lookup per section and no block reads — which is every chunk in every ordinary world.
     */
    fun countIn(chunk: ChunkAccess): Int {
        var found = 0
        for (section in chunk.sections) {
            if (section.hasOnlyAir() || !section.maybeHas { it.`is`(AgeContent.WOUND_BLOCK) }) continue
            for (x in 0..<SECTION) for (y in 0..<SECTION) for (z in 0..<SECTION) {
                if (section.getBlockState(x, y, z).`is`(AgeContent.WOUND_BLOCK)) found++
            }
        }
        return found
    }

    /**
     * How many wounds a chunk holds at each step of [co.voik.agesandtheart.age.Manifestation.WOUNDS] —
     * what the book itself tore, before any time has passed.
     *
     * **It multiplies rather than adds.** Writing an unstable Age should be something a writer *knows*, so
     * the first step already puts one in every other chunk, and from there each step is four times the
     * last: a badly torn world is holed through rather than lightly freckled.
     */
    fun writtenDensityAt(steps: Int): Double {
        if (steps <= 0) return NONE
        return FIRST_STEP_OPENS * CROWDS_BY.pow(steps - 1)
    }

    /**
     * How much worse one of the Age's days makes it, at each step of
     * [co.voik.agesandtheart.age.Manifestation.BLIGHT].
     *
     * The first step is a wound per chunk every four days — slow enough that a visit is not a countdown,
     * fast enough that coming back next session is visibly worse. Each further step is four times that, so
     * the top of the register is an Age holed through inside a week.
     */
    fun blightPerDayAt(steps: Int): Double {
        if (steps <= 0) return NONE
        return FIRST_STEP_CREEPS * CROWDS_BY.pow(steps - 1)
    }

    /**
     * The most wounds a chunk will ever hold, however long the Age has stood.
     *
     * **This is an engineering limit and not a design one, and the distinction is the whole comment**
     * (Jonah, 2026-08-09, walked: "even with the custom renderer, eventually it gets out of hand").
     * §5.2.1 rules that blight is *unbounded* — a bound is a promise the Age can be outlasted — and that
     * ruling stands. What is bounded here is how many of them are **drawn**, which is a different claim:
     * past this many to a chunk the register has said everything it has to say and the rest is frames.
     *
     * Sixty-four is four times what the block-entity era could carry and was chosen to be re-tuned rather
     * than trusted. **The way to raise it is not a bigger number here**: it is an *inert* wound — a plain
     * block with no flicker, which keeps the look at a fraction of the cost and lets the lively ones be the
     * few nearest you. Until that exists, this is the honest ceiling and it should be visible in the code
     * rather than discovered in a frame rate.
     */
    private const val MOST_PER_CHUNK = 64.0

    /** How long one of the Age's days is, in ticks — vanilla's own day. */
    const val TICKS_PER_DAY = 24_000L

    /** A coherent Age, which tears nowhere and never worsens. */
    const val NONE = 0.0

    /** No limit on how many a single pass may open — what generation and a fast-forward both want. */
    const val ALL_AT_ONCE = Int.MAX_VALUE

    /**
     * One to a 64-block square at the first step — **about one to a sightline**.
     *
     * Roughly three in view at any moment: enough that a writer meets them without going looking, and few
     * enough that each one is still an event.
     */
    private const val FIRST_STEP_OPENS = 1.0 / 16.0

    /** And what one day of the mildest blight adds. */
    private const val FIRST_STEP_CREEPS = 0.25

    /** What each further step multiplies either of those by. */
    private const val CROWDS_BY = 4.0

    /** So wounds are decorrelated from everything else the world seed drives. */
    private const val WOUND_SALT = 0x0D_15_EA5EL

    /** And the fractional-count roll from the positions, so changing one does not move the other. */
    private const val SHARE_SALT = 0x5EAL

    /** How often a wound opens above the ground rather than under it. */
    private const val ABOVE_GROUND = 0.75

    /** How far above the surface one may hang — eye level and a little over. */
    private const val OVERHEAD = 4

    private const val SECTION = 16
}
