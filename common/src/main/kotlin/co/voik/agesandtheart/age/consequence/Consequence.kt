package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Parameter
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import co.voik.agesandtheart.age.phenomena.OvergrowthPatches
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * Everything an Age's instability bought, as the generator needs it (design §5.0).
 *
 * **One object rather than four fields, because they change together or not at all.** A generator is built
 * once when its Age opens and outlives any number of commands; what the Age *is* can be rewritten under it
 * — by `/age instability`, and by book editing when that exists — and every one of these has to move at once or
 * the world generates against a mixture of two answers.
 *
 * Derived, never stored: a pure function of the recipe and the price list, so it comes out the same on
 * every open and the recipe stays a record of what was *written* rather than of what follows from it.
 */
data class Consequence(
    /** How many wounds a chunk holds from the book alone, before any time has passed (§5.1). */
    val woundsPerChunk: Double,
    /** How many more each of the Age's days adds — the Age going on tearing (§5.2.1). */
    val woundsPerDay: Double,
    /** How many tears the Age's floor is cut with, per cell — collapse (§5.3). They widen themselves. */
    val collapseTears: Int,
    /** The overworld tick the Age was written on, which the two rates are counted from. */
    val writtenAt: Long,
    /** Whether every feature in the Age comes out the same shape — [StrangeGround]. */
    val stampsShapes: Boolean = false,
    /** Whether the Age's trees grow without leaves — [StrangeGround]. */
    val leafless: Boolean = false,
    /** Whether every village in the Age is the abandoned one — [StrangeGround]. */
    val abandonsVillages: Boolean = false,
    /** How many steps of overgrown ground the Age bought, which is how many chunks hold a patch. */
    val overgrowthPatches: Int = 0,
) {
    /** Whether anything at all is wrong, so a coherent Age can skip the whole pass. */
    val isNothing: Boolean
        get() = woundsPerChunk <= Tearing.NONE &&
            woundsPerDay <= Tearing.NONE &&
            collapseTears <= Collapse.NONE &&
            overgrowthPatches <= 0

    /** How long the Age has stood by [now], in its own days — what both rates are multiplied by. */
    fun daysBy(now: Long): Long = (now - writtenAt).coerceAtLeast(0L) / Tearing.TICKS_PER_DAY

    /**
     * Everything this Age's instability bought, written into [chunk] — **the register's own pass** (§5).
     *
     * Here rather than in the generator's decoration hook, which orchestrated the four steps itself. What
     * order they run in is a fact about the consequence and not about chunk generation, so adding a
     * register should mean editing this and not a chunk generator.
     *
     * **The Age's age is read here rather than at open**, so a chunk generated after a week of worsening
     * comes out as torn as the ones beside it. Against the *overworld's* clock: an Age's own only runs
     * while somebody is in it, which is exactly when the worsening is not supposed to be waiting.
     */
    fun writeInto(level: WorldGenLevel, chunk: ChunkAccess) {
        if (isNothing) return
        val days = daysBy(level.level.server.overworld().gameTime)
        // The floor giving way first: a column the Age has already swallowed is not somewhere to put a
        // wound, and carving after would take the wound straight back out again.
        Collapse.carveInto(level, chunk, level.getSeed(), collapseTears)
        val density = Tearing.densityAt(woundsPerChunk, woundsPerDay, days)
        // Nobody to tell and nothing to update: the chunk has not been sent to a client and will not be
        // until it is finished, so a wound here is written into it rather than announced.
        Tearing.tearInto(
            level,
            chunk,
            level.getSeed(),
            Tearing.wantedIn(chunk.pos, level.getSeed(), density),
            alreadyRunning = false,
        )
        OvergrowthPatches.layInto(level, chunk, level.getSeed(), OvergrowthPatches.chanceAt(overgrowthPatches))
    }

    companion object {
        /**
         * The tick an Age that recorded none was written at — the generator's own former default, kept at
         * its value so nothing already serialised reads back differently.
         */
        const val UNWRITTEN = 0L

        /** A coherent Age, which tears nowhere. Nearly all of them. */
        val NONE = Consequence(Tearing.NONE, Tearing.NONE, Collapse.NONE, UNWRITTEN)

        /**
         * The keys a generator writes down, with the defaults a coherent Age omits.
         *
         * **One codec rather than four loose fields on the generator's**, so the object whose whole reason
         * is that these change together is also the object that reads and writes them. The keys and the
         * defaults are the generator's own, unchanged: an Age serialised before this reads back identically.
         */
        val MAP_CODEC: MapCodec<Consequence> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("wounds_per_chunk", Tearing.NONE)
                    .forGetter(Consequence::woundsPerChunk),
                Codec.DOUBLE.optionalFieldOf("wounds_per_day", Tearing.NONE)
                    .forGetter(Consequence::woundsPerDay),
                Codec.INT.optionalFieldOf("collapse_tears", Collapse.NONE)
                    .forGetter(Consequence::collapseTears),
                Codec.LONG.optionalFieldOf("written_at", UNWRITTEN)
                    .forGetter(Consequence::writtenAt),
                Codec.BOOL.optionalFieldOf("stamps_shapes", false)
                    .forGetter(Consequence::stampsShapes),
                Codec.BOOL.optionalFieldOf("leafless", false)
                    .forGetter(Consequence::leafless),
                Codec.BOOL.optionalFieldOf("abandons_villages", false)
                    .forGetter(Consequence::abandonsVillages),
                Codec.INT.optionalFieldOf("overgrowth_patches", 0)
                    .forGetter(Consequence::overgrowthPatches),
            ).apply(instance, ::Consequence)
        }

        /** What [recipe] bought, at the prices [server] is running. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Consequence {
            val spending = Spending.of(server, recipe)
            fun said(flag: Parameter) = recipe.composition?.optionsFor(Aspect.FEATURES)?.isTrue(flag) == true
            return Consequence(
                woundsPerChunk = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS)),
                woundsPerDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS)),
                collapseTears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE)),
                writtenAt = recipe.writtenAt,
                stampsShapes = spending.bought(Manifestation.STAMPED_SHAPES) > 0 || said(Features.UNIFORM),
                leafless = spending.bought(Manifestation.LEAFLESS_TREES) > 0 || said(Features.BARE),
                abandonsVillages = spending.bought(Manifestation.ABANDONED_VILLAGES) > 0,
                overgrowthPatches = spending.bought(Manifestation.OVERGROWN_GROUND),
            )
        }
    }
}
