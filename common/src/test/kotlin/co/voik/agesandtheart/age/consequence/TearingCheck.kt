package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.ChunkPos

/**
 * How holed an Age gets, and when (design §5.1, §5.2.1, §5.4).
 *
 * Three touchpoints read [Tearing] and they have to agree: generation, the fast-forward as a chunk loads,
 * and the one-at-a-time creep while somebody watches. What makes that safe is not that they share code but
 * that the number they read has two properties — it is **monotone** and it is **quantised** — and those are
 * what this checks. Neither is visible from any one caller, which is why they are checked here rather than
 * discovered in a world.
 */
@Tags(NEEDS_REGISTRIES)
class TearingCheck : FunSpec({

    // `by lazy`, and it has to be: `ChunkPos.<clinit>` reaches `BuiltInRegistries` through `ChunkStatus`,
    // and Kotest builds a spec to *discover* its tests — so one built in the constructor is a bootstrap
    // paid at discovery, which is exactly what the tag exists to schedule.
    val anywhere by lazy { ChunkPos(3, -7) }
    val seed = 0x5EEDL

    /**
     * **A wound is not a thing that closes.** The fast-forward places the difference between what a chunk
     * holds and what it should, so a count that ever fell would ask it to take one away — and it cannot.
     * The fractional roll is drawn per chunk rather than per density precisely so this holds.
     */
    test("what a chunk wants never falls as the Age gets older") {
        for (perDay in listOf(0.0, 0.25, 1.0, 4.0)) {
            var most = 0
            for (days in 0L..400L) {
                val density = Tearing.densityAt(written = 0.5, perDay = perDay, days = days)
                val wanted = Tearing.wantedIn(anywhere, seed, density)
                check(wanted >= most) { "at $perDay/day the count fell from $most to $wanted on day $days" }
                most = wanted
            }
        }
    }

    /**
     * **Quantised to whole days**, which is what stops `/age compare` being a coin toss: two Ages written
     * seconds apart must read the same, or a diff of one against the other is measuring the clock.
     */
    test("a day is the smallest step the density takes") {
        val early = Tearing.densityAt(written = 1.0, perDay = 2.0, days = 3L)
        check(Tearing.densityAt(1.0, 2.0, 4L) > early) { "a further day added nothing" }
    }

    /** The worsening is a rate over an Age's life; without it the density is exactly what the book wrote. */
    test("an Age that does not worsen never gains a wound") {
        val written = Tearing.writtenDensityAt(steps = 2)
        for (days in 0L..1000L) {
            check(Tearing.densityAt(written, Tearing.NONE, days) == written) {
                "a steady Age drifted on day $days"
            }
        }
    }

    /**
     * **It worsens until a chunk is saturated, and the ceiling is that saturation** (§5.2.1, and the walk of
     * 2026-08-09 that added it).
     *
     * The design ruling is that it is unbounded because a bound is a promise the Age can be outlasted.
     * What is bounded is how many wounds are *drawn*, which is a different claim: at the ceiling a chunk
     * holds a hole every couple of blocks, so nothing about the Age is livable and there is nothing left for
     * another wound to say. The check that matters is therefore not "it never stops" but **"it does not stop
     * early"** — a low ceiling would be a budget wearing a saturation's clothes.
     */
    test("an Age worsens until the ground is nothing but holes") {
        val mildest = Tearing.densityAt(written = 0.0, perDay = 0.25, days = 100_000L)
        check(mildest >= 64.0) { "even the mildest worsening should saturate a chunk eventually, and reached $mildest" }
        // And it takes a long time to get there at the bottom of the register, or the ladder says nothing.
        val afterAWeek = Tearing.densityAt(written = 0.0, perDay = 0.25, days = 7L)
        check(afterAWeek < 2.0) { "the mildest worsening reached $afterAWeek in a week, which is not mild" }
    }

    /** A coherent Age tears nowhere, however long it stands — the case almost every Age is. */
    test("a coherent Age never tears") {
        check(Tearing.writtenDensityAt(0) == Tearing.NONE) { "a coherent Age was written holed" }
        check(Tearing.woundsPerDayAt(0) == Tearing.NONE) { "a coherent Age was written worsening" }
        check(Tearing.wantedIn(anywhere, seed, Tearing.NONE) == 0) { "a chunk wanted a wound at zero density" }
    }

    /**
     * Both ladders climb steeply, which is the design: writing an unstable Age should be something a writer
     * *knows*, met as wounds they come across rather than as a curiosity somewhere.
     */
    test("each step bought is a step worse") {
        val written = (0..4).map(Tearing::writtenDensityAt)
        check(written == written.sorted()) { "the written density is not monotone in steps: $written" }
        val creep = (0..3).map(Tearing::woundsPerDayAt)
        check(creep == creep.sorted()) { "the worsening rate is not monotone in steps: $creep" }
    }

    /**
     * A density of one half is half the chunks holding one, not every chunk holding none — which is what
     * makes the first step of the register meet a writer at all.
     */
    test("a fractional density is a share of chunks rather than a rounding") {
        val holding = (0..<400).count { Tearing.wantedIn(ChunkPos(it, it * 7), seed, 0.5) > 0 }
        check(holding in 120..280) { "half a wound per chunk landed in $holding of 400, which is not a half" }
    }
})
