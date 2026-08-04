package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.field.Slab
import com.mojang.datafixers.util.Pair
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.OverworldBiomeBuilder
import java.util.function.Consumer
import kotlin.math.abs
import net.minecraft.world.level.biome.Biomes as VanillaBiomes

/**
 * Vanilla's own climate-to-biome table, built without a server. `OverworldBiomeBuilder` is final and its
 * `addBiomes` is protected, so this is the only way to ask the real one — and asking the real one is the
 * point, since a copy of its numbers would agree with the tests forever and with Minecraft never.
 */
private fun vanillasTable(): Climate.ParameterList<ResourceKey<Biome>> {
    val entries = mutableListOf<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>()
    val addBiomes = OverworldBiomeBuilder::class.java
        .getDeclaredMethod("addBiomes", Consumer::class.java)
        .apply { isAccessible = true }
    addBiomes.invoke(
        OverworldBiomeBuilder(),
        Consumer<Any> {
            @Suppress("UNCHECKED_CAST")
            entries.add(it as Pair<Climate.ParameterPoint, ResourceKey<Biome>>)
        },
    )
    return Climate.ParameterList(entries)
}

/**
 * The option that lets an Age's biomes agree with its shape (see [Grounding]).
 *
 * These are pure arithmetic over vanilla's climate axes, which is what makes them worth asserting: the
 * failures are all *plausible worlds*. A curve that dipped somewhere would put an ocean biome on high
 * ground and read as one of this mod's intended oddities; a river whose bed reads as ocean grows kelp in
 * it and looks like weather. Neither is something a render distinguishes from a decision.
 */
class GroundingCheck : FunSpec({

    val waterline = 63

    fun landAt(surfaceY: Int) = Grounding(Slab(lowY = -64, highY = surfaceY), waterline)

    /**
     * Every climate a shore column can be handed, with weirdness run through [grounding] the way a real
     * lookup runs it. Erosion spans what a sandy shore allows — its floor up to a dead flat beach — and
     * continentalness stays inside vanilla's coast band without sitting on its edges, where the nearest
     * entry is as likely to be the sea's as the shore's.
     */
    fun forEveryShoreClimate(
        grounding: Grounding,
        judge: (continentalness: Float, erosion: Float, temperature: Float, humidity: Float, weirdness: Float, vanillas: Float) -> Unit,
    ) {
        val flattest = grounding.erosionOf(0.0)
        var vanillas = -1.0f
        while (vanillas <= 1.0f) {
            val weirdness = grounding.weirdnessAt(0, 0, vanillas)
            var erosion = Grounding.SANDY_ENOUGH
            while (erosion <= flattest) {
                for (continentalness in listOf(-0.18f, -0.15f, -0.12f)) {
                    for (temperature in listOf(-0.9f, -0.4f, 0.0f, 0.4f, 0.9f)) {
                        for (humidity in listOf(-0.9f, -0.3f, 0.0f, 0.3f, 0.9f)) {
                            judge(continentalness, erosion, temperature, humidity, weirdness, vanillas)
                        }
                    }
                }
                erosion += 0.05f
            }
            vanillas += 0.05f
        }
    }

    /**
     * **The one that has to hold.** Higher ground is never *less* inland than lower ground — a curve with a
     * dip in it would file a hilltop as ocean, and the result would look exactly like the uncoupled
     * behaviour this option exists to turn off.
     */
    test("higher ground is never less inland than lower") {
        val ground = landAt(0)
        var previous = ground.continentalnessOf(-200)
        for (surfaceY in -200..320) {
            val here = ground.continentalnessOf(surfaceY)
            check(here >= previous) { "the curve dipped at y=$surfaceY, from $previous to $here" }
            previous = here
        }
    }

    /** And it has to actually span vanilla's axis, or every column reads as the same kind of place. */
    test("deep water reads as ocean and high ground as inland") {
        val ground = landAt(0)
        val deep = ground.continentalnessOf(waterline - 60)
        val shore = ground.continentalnessOf(waterline)
        val upland = ground.continentalnessOf(waterline + 90)
        // Vanilla files deep ocean below -0.455, coast at -0.19..-0.11 and inland above 0.03.
        check(deep < -0.455f) { "sixty blocks under the sea read as $deep, which is not deep ocean" }
        check(shore in -0.4f..0.0f) { "the waterline itself read as $shore, which is neither shore nor sea" }
        check(upland > 0.03f) { "ninety blocks over the sea read as $upland, which is not inland" }
    }

    /**
     * **A river is inland, whatever its bed is doing.** Its channel is cut below the surrounding land and
     * may well sit under the waterline — read off height alone that is an ocean, and vanilla grows kelp in
     * it. The shape knows the difference because it carries the water itself.
     */
    test("a river reads as inland rather than as ocean") {
        // A channel scoured under the waterline, with its own water standing over it — which is exactly
        // the case height alone gets wrong, and exactly what a river cutting through low ground looks like.
        val bedY = waterline - 5
        val dry = Grounding(Slab(lowY = -64, highY = bedY), waterline)
        val wet = Grounding(Slab(lowY = -64, highY = bedY), waterline, rivers = Slab(lowY = -64, highY = waterline + 3))

        val asOcean = dry.continentalnessAt(0, 0)
        val asRiver = wet.continentalnessAt(0, 0)
        check(asOcean < -0.19f) { "a bed under the sea read as $asOcean without its river" }
        check(asRiver > -0.11f) { "the same bed with a river over it read as $asRiver, still an ocean" }
    }

    /**
     * And it has to land in the band vanilla actually files rivers under. Its peaks-and-valleys curve is
     * `-(||w| - 2/3| - 1/3) * 3`, and a valley is that under −0.85 — which solves to `|w| < 0.05`.
     */
    test("a river's weirdness lands in vanilla's valley band") {
        val bedY = waterline - 5
        val wet = Grounding(Slab(lowY = -64, highY = bedY), waterline, rivers = Slab(lowY = -64, highY = waterline + 3))
        val elsewhere = 0.8f

        val inTheRiver = wet.weirdnessAt(0, 0, elsewhere)
        check(abs(inTheRiver) < VALLEY_BAND) { "a river's weirdness came out $inTheRiver, outside the valley band" }

        // And nowhere else: grounding must not flatten weirdness across the whole world, which decides far
        // more than rivers.
        val dry = Grounding(Slab(lowY = -64, highY = waterline + 40), waterline)
        check(dry.weirdnessAt(0, 0, elsewhere) == elsewhere) { "dry ground had its weirdness overwritten" }
    }

    /**
     * **And it stops being one at the sea.** A trunk running down to the waterline carries water over its
     * bed all the way, so "has water on it" alone would file the whole estuary and the open sea beyond it
     * as river — and rivers do not have kelp, coral or the right temperature variants.
     */
    test("a river reaching the waterline reads as ocean rather than river") {
        val bedY = waterline - 18
        // Its surface sits at the waterline: this is where the river meets the sea.
        val mouth = Grounding(Slab(lowY = -64, highY = bedY), waterline, rivers = Slab(lowY = -64, highY = waterline))
        // And this one still stands well over it, so it is a river yet.
        val inland = Grounding(
            Slab(lowY = -64, highY = waterline + 30),
            waterline,
            rivers = Slab(lowY = -64, highY = waterline + 36),
        )
        val elsewhere = 0.8f

        check(mouth.continentalnessAt(0, 0) < -0.19f) { "the river mouth read as ${mouth.continentalnessAt(0, 0)}" }
        check(mouth.weirdnessAt(0, 0, elsewhere) == elsewhere) { "the sea had its weirdness forced to a valley" }
        check(inland.continentalnessAt(0, 0) > -0.11f) { "an inland river read as ${inland.continentalnessAt(0, 0)}" }
        check(abs(inland.weirdnessAt(0, 0, elsewhere)) < VALLEY_BAND) { "an inland river left vanilla's weirdness" }
    }

    /**
     * **A beach is not broken up by rivers.** `OverworldBiomeBuilder` files its whole valley slice at
     * `span(-0.05F, 0.05F)`, and at a coast that slice holds rivers and stony shores rather than beaches.
     * Left to vanilla's own noise a twentieth of every column lands in it, which along a shoreline is sand
     * with patches of something else through it.
     */
    test("weirdness is kept clear of the valley band where no river runs") {
        val dry = Grounding(Slab(lowY = -64, highY = waterline + 4), waterline)
        // Every weirdness vanilla's noise could hand over, including the whole valley slice.
        var vanillas = -1.0f
        while (vanillas <= 1.0f) {
            val grounded = dry.weirdnessAt(0, 0, vanillas)
            check(abs(grounded) >= VALLEY_BAND) {
                "a weirdness of $vanillas came out $grounded, inside vanilla's valley slice"
            }
            // And anything already clear of it is left exactly alone. Measured a hair outside the band,
            // since the nudge itself has to land just past it and cannot leave that sliver untouched.
            if (abs(vanillas) >= VALLEY_BAND + A_HAIR) {
                check(grounded == vanillas) { "a weirdness of $vanillas was moved to $grounded for no reason" }
            }
            vanillas += 0.005f
        }
    }

    /**
     * **And the coast is a plateau, not a slope.** A beach rises a few blocks across its width, so a
     * continentalness that slid through vanilla's coast band over that same rise would flip a column to
     * ocean for being a block lower than its neighbour.
     */
    test("the whole beach reads as the middle of the coast band") {
        val ground = landAt(0)
        // Vanilla's coast band, from `OverworldBiomeBuilder`.
        val coastBand = -0.19f..-0.11f
        for (over in -2..8) {
            val here = ground.continentalnessOf(waterline + over)
            check(here in coastBand) { "$over blocks over the water read as $here, outside the coast band" }
        }
        check(ground.continentalnessOf(waterline) == ground.continentalnessOf(waterline + 4)) {
            "the beach's own rise moved it along the axis, so its two ends can read as different places"
        }
    }

    /**
     * **The number that decides sand from stone.** `OverworldBiomeBuilder` files `stony_shore` at erosion
     * −1.0..−0.2225 and `beach` above it, at the very same continentalness — so a coast is sandy or stony
     * purely by this. Passing vanilla's own erosion through left it to a noise that never saw our terrain,
     * and every island came out stony.
     */
    test("flat ground reads as sand and a slope reads as stone") {
        val ground = landAt(0)
        // A beach rising four blocks in a hundred, which is what `Isle` builds.
        check(ground.erosionOf(4.0 / 100.0) > SAND_FROM_STONE) {
            "a beach read as ${ground.erosionOf(4.0 / 100.0)}, which vanilla files as stony shore"
        }
        // And anything you would have to climb.
        check(ground.erosionOf(0.5) < SAND_FROM_STONE) { "a one-in-two slope read as sandy" }
        check(ground.erosionOf(2.0) < SAND_FROM_STONE) { "a cliff read as sandy" }
    }

    /** And it has to be monotonic, or somewhere steeper reads as flatter and the shore alternates. */
    test("steeper ground never reads as more worn") {
        val ground = landAt(0)
        var previous = ground.erosionOf(0.0)
        var fall = 0.0
        while (fall <= 3.0) {
            val here = ground.erosionOf(fall)
            check(here <= previous) { "the erosion curve rose at a fall of $fall, from $previous to $here" }
            previous = here
            fall += 0.01
        }
    }

    /**
     * **A sandy shore is sand at every climate vanilla can hand it.** Erosion was only half of it:
     * `addInlandBiomes` files a coastal beach in its *low* weirdness slice and the negative half of its
     * *mid* ones, and nowhere else — so left alone, about two coastal columns in three grow a forest down
     * to the water. That is deliberate in vanilla, whose ground was made from these same numbers, and a
     * coin toss for a shape that already has a flat sandy shelf.
     *
     * Asked of vanilla's real table rather than of our own reasoning about it, across every climate a
     * shore can be handed.
     */
    test("a sandy shore grows nothing but sand") {
        val table = vanillasTable()
        val shore = Grounding(Slab(lowY = -64, highY = waterline + 2), waterline, declared = Grounding.Declared(hasSandyShores = true))
        val sand = setOf(VanillaBiomes.BEACH, VanillaBiomes.SNOWY_BEACH, VanillaBiomes.DESERT)

        forEveryShoreClimate(shore) { continentalness, erosion, temperature, humidity, weirdness, vanillas ->
            val grew = table.findValue(
                Climate.target(temperature, humidity, continentalness, erosion, 0.0f, weirdness),
            )
            check(grew in sand) {
                "a shore grew $grew at weirdness $vanillas (squeezed to $weirdness), erosion $erosion, " +
                    "continentalness $continentalness, temperature $temperature"
            }
        }
    }

    /**
     * And the same sweep with the option off must find those forests, or the test above is asserting
     * nothing about the option and everything about the sweep being narrow.
     */
    test("a shore left alone still gets vanilla's mixed coast") {
        val table = vanillasTable()
        val mixed = Grounding(Slab(lowY = -64, highY = waterline + 2), waterline)
        val sand = setOf(VanillaBiomes.BEACH, VanillaBiomes.SNOWY_BEACH, VanillaBiomes.DESERT)

        var notSand = 0
        var columns = 0
        forEveryShoreClimate(mixed) { continentalness, erosion, temperature, humidity, weirdness, _ ->
            val grew = table.findValue(
                Climate.target(temperature, humidity, continentalness, erosion, 0.0f, weirdness),
            )
            columns++
            if (grew !in sand) notSand++
        }
        check(notSand > columns / 4) {
            "only $notSand of $columns unclaimed shores grew something other than sand, so nothing was fixed"
        }
    }

    /** Read against a real island: its beach must come out sandy where its shoulder does not. */
    test("an island's beach reads sandy and its shoulder does not") {
        val extent = Span.NATURAL_MOST
        val world = IslandsField.world(extent)
        val grounded = Grounding(world, IslandsField.SEA_LEVEL)
        fun topAt(out: Int) = world.columnSpans(out, 0).highestSolidY ?: 0

        val reach = IslandsField.widestReach(extent).toInt()
        val coast = (reach downTo 0).first { topAt(it) > IslandsField.SEA_LEVEL }
        // A little inside the coast is beach; well inside it is the climb behind it.
        val onTheBeach = grounded.erosionAt(coast - 20, 0)
        val onTheShoulder = grounded.erosionAt((coast * SHOULDER_WAY_IN).toInt(), 0)
        check(onTheBeach > SAND_FROM_STONE) { "the beach read as $onTheBeach, which is a stony shore" }
        check(onTheShoulder < onTheBeach) { "the shoulder read as $onTheShoulder against the beach's $onTheBeach" }
    }

    /**
     * **And nowhere on a sandy island's coast reads as stone.** The beach itself is flat enough to be sand
     * on its own, so the floor is for the rest of the shoreline — a bay's steeper corner, or where the
     * shoulder happens to reach the water — which is where the stony patches were coming from.
     */
    test("a sandy island has no stony shore anywhere along it") {
        val extent = Span.NATURAL_MOST
        val world = IslandsField.world(extent)
        val sandy = Grounding(world, IslandsField.SEA_LEVEL, declared = Grounding.Declared(hasSandyShores = true))
        val asFound = Grounding(world, IslandsField.SEA_LEVEL)

        // Right round the island, at the resolution a biome is chosen at.
        val reach = IslandsField.widestReach(extent).toInt()
        val shoreline = (-reach..reach step 4).flatMap { x -> (-reach..reach step 4).map { x to it } }
            .filter { (x, z) -> sandy.continentalnessAt(x, z) in -0.19f..-0.11f }
        check(shoreline.size > 200) { "only ${shoreline.size} columns read as coast, so this proves little" }

        val stony = shoreline.count { (x, z) -> sandy.erosionAt(x, z) <= SAND_FROM_STONE }
        val stonyAsFound = shoreline.count { (x, z) -> asFound.erosionAt(x, z) <= SAND_FROM_STONE }
        check(stony == 0) { "$stony of ${shoreline.size} coastal columns still read as stony shore" }
        check(stonyAsFound > 0) { "the same coast had no stony columns to begin with, so nothing was fixed" }
    }

    /**
     * The per-column cache is direct-mapped over sixteen slots, so two columns four quart-cells apart land
     * in the same one. It keys on the column as well, but a cache that did not would answer plausibly and
     * wrongly — one column wearing its neighbour's coastline.
     */
    test("two columns sharing a cache slot keep their own answers") {
        val land = Grounding(RiverlandsField.network(), waterline)
        // Sixteen blocks is four quart cells, which is exactly one lap of the slot index.
        val apart = (0..4000 step 64).firstOrNull { at ->
            land.continentalnessOf(surfaceOf(land, at)) != land.continentalnessOf(surfaceOf(land, at + SLOT_LAP))
        } ?: error("no two colliding columns anywhere differed, so this would have checked nothing")

        val alone = land.continentalnessAt(apart, 0)
        val neighbour = land.continentalnessAt(apart + SLOT_LAP, 0)
        check(alone != neighbour) { "the two columns should differ and both read $alone" }
        check(land.continentalnessAt(apart, 0) == alone) {
            "the first column read $alone, then ${land.continentalnessAt(apart, 0)} after its neighbour displaced it"
        }
        check(land.continentalnessAt(apart + SLOT_LAP, 0) == neighbour) { "and the neighbour lost its own answer" }
    }
}) {
    private companion object {
        const val VALLEY_BAND = 0.05f

        /** Room for the nudge's own margin, which has to sit just outside the band to clear it. */
        const val A_HAIR = 0.002f

        /** Vanilla's own boundary: `erosions[2]` ends here, and `beach` begins. */
        const val SAND_FROM_STONE = -0.2225f

        /** How far in from the coast the climb behind the beach is well under way. */
        const val SHOULDER_WAY_IN = 0.7

        /** Four quart cells, which is one lap of the cache's direct-mapped index. */
        const val SLOT_LAP = 16

        fun surfaceOf(grounding: Grounding, worldX: Int): Int =
            grounding.terrain.columnSpans(worldX, 0).highestSolidY ?: 0
    }
}
