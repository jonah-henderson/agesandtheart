package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lava tubes, seated under the lava a volcano's crater arrived full of (design §7.1.2).
 *
 * **A vent goes where the Age's own lava stands**, which is a fact the shape already holds rather than a
 * relationship a feature has to infer. It was inferred for a while — low, with higher ground around, high
 * above the world — and that read a crater fairly while a crater floor was rumpled and its lowest column
 * sat somewhere near its middle. Cutting the caldera flat so it could hold a level lake took the ground
 * out from under it: with every floor column tied, "the lowest" became "the westernmost", which is the
 * one place in a crater that nothing rings.
 *
 * So a crater is not looked for at all now. The lake **is** the crater, and the chunk that seats the vent
 * is the one holding the lake's middle — a question every chunk over that lake answers the same way, so
 * exactly one of them says yes without any of them comparing itself against the others.
 *
 * **One class, one instance per body of lava** ([body]). A crater lake and a magma chamber's pool want
 * exactly this routine and differ only in which field they are; registering it twice is what lets
 * `volcano` and `magma_chamber` be written for separately without either one's vents going missing.
 */
class VolcanoVents(private val body: String) : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val generator = context.chunkGenerator() as? AgeChunkGenerator ?: return false
        val origin = context.origin()
        val lakes = moltenIn(generator) ?: return false
        val anywhere = someLavaIn(lakes, origin) ?: return false
        val middle = middleOfTheLakeAt(lakes, anywhere)
        if (!inside(origin, middle)) return false
        return seat(context.level(), lakes, middle, context.random())
    }

    /**
     * This feature's own body of lava, or null where the Age carries none.
     *
     * **Found by name and never by substance.** Both bodies are lava, so taking the first one found
     * returns whichever happens to be listed first and leaves the other unreachable — which is what kept
     * every magma chamber empty for as long as the cones were listed ahead of them.
     *
     * Asked of the generator rather than rebuilt here, so the vent and the lake it sits under read one
     * field and cannot come to describe two different craters.
     */
    private fun moltenIn(generator: AgeChunkGenerator): TerrainField? =
        generator.seaFill.carried.firstOrNull { it.named == body }?.where

    /**
     * Any column of this chunk with lava standing over it, on the [STRIDE] lattice.
     *
     * The cheap question that most chunks in an Age answer no to, so nothing below it is paid for except
     * over a crater.
     */
    private fun someLavaIn(lakes: TerrainField, origin: BlockPos): BlockPos? {
        for (offsetX in 0..<CHUNK step STRIDE) {
            for (offsetZ in 0..<CHUNK step STRIDE) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                val surface = surfaceOfLava(lakes, x, z) ?: continue
                return BlockPos(x, surface, z)
            }
        }
        return null
    }

    /**
     * The middle of the lake [from] stands in — the middle of its own extent, found by spreading across
     * it on the [STRIDE] lattice.
     *
     * **Spread rather than a box scan, and the difference is two craters standing near each other.** A
     * height alone is not a name for one body of lava: small craters come in fields, and two of them a
     * few tens of blocks apart on the same ground stand at the same level, so a box would take them for
     * one lake and put the middle in the ridge between them. Spreading only ever crosses lava, so it
     * stops at a shore however close the next lake is — and it is *cheaper* than the box it replaces,
     * since it reads the lake instead of the square that contains it.
     *
     * Every chunk over one lake spreads across the same columns and so computes the same middle, which is
     * what lets the election be "does that middle fall inside me" — a question with exactly one yes.
     */
    private fun middleOfTheLakeAt(lakes: TerrainField, from: BlockPos): BlockPos {
        val found = HashSet<Long>()
        found += keyOf(from.x, from.z)
        val queue = ArrayDeque(listOf(from.x to from.z))
        var leastX = from.x
        var mostX = from.x
        var leastZ = from.z
        var mostZ = from.z
        while (queue.isNotEmpty() && found.size < MOST_IN_A_LAKE) {
            val (x, z) = queue.removeFirst()
            leastX = minOf(leastX, x)
            mostX = maxOf(mostX, x)
            leastZ = minOf(leastZ, z)
            mostZ = maxOf(mostZ, z)
            for ((stepX, stepZ) in BESIDE) {
                val nextX = x + stepX * STRIDE
                val nextZ = z + stepZ * STRIDE
                if (!found.add(keyOf(nextX, nextZ))) continue
                if (surfaceOfLava(lakes, nextX, nextZ) != from.y) continue
                queue += nextX to nextZ
            }
        }
        return BlockPos((leastX + mostX) / 2, from.y, (leastZ + mostZ) / 2)
    }

    /** One long per column, so the spread's seen-set costs no allocation per step. */
    private fun keyOf(x: Int, z: Int): Long = (x.toLong() shl Int.SIZE_BITS) or (z.toLong() and INT_MASK)

    private fun inside(origin: BlockPos, at: BlockPos): Boolean =
        at.x - origin.x in 0..<CHUNK && at.z - origin.z in 0..<CHUNK

    /** How high the lava stands over this column, or null where none does. */
    private fun surfaceOfLava(lakes: TerrainField, x: Int, z: Int): Int? =
        lakes.columnSpans(x, z).highestSolidY

    /**
     * The rock this column's lava is resting on, or null where no body of it reaches [surface] here.
     *
     * **Read off the lava rather than off the land — CORRECTED 2026-09-10 (Jonah, walked).** This asked
     * the *landform* for the top of the rock, which is the same answer for a crater (a lake rests on its
     * own floor) and completely wrong for a magma chamber, where the land is a hundred blocks overhead.
     * `floor` came out above `crown`, the loop that seats the tubes ran zero times, and every chamber in
     * every Age came out as a pool with nothing in it.
     *
     * The bottom of the body of lava is the right question in both cases and needs no second field. It
     * also does the filtering for free: a column with no lava at this level is outside the chamber, so the
     * cap trims itself to whatever shape it is sitting in.
     */
    private fun floorUnderTheLavaAt(lakes: TerrainField, x: Int, z: Int, surface: Int): Int? =
        lakes.columnSpans(x, z).ranges.firstOrNull { surface in it }?.let { it.first - ONE }

    /**
     * **A broad, shallow cap of tubes on a pedestal of rock — CORRECTED 2026-09-10 (Jonah, walked).**
     *
     * This was a cone rising the whole height of the lake, and the reasoning behind it was half right: how
     * often a volcano throws *is* how many tubes it has, but not how many it is *made* of. `LavaTubes.plugged`
     * refuses any tube with a block over it, and `LavaTubeBlock` is a full block — **so a tube with a tube
     * on top of it neither wells nor throws.** What a vent's output is proportional to is its *exposed top
     * surface*, and a cone is the shape with the least of it: a tall stack of rings whose crown is a single
     * column. It looked right and threw almost nothing.
     *
     * So the mass is turned on its side. A disc [VENT_ACROSS] blocks across and only [VENT_DEEP] thick has
     * two hundred-odd tubes with open lava over them instead of a handful, and the same volcano goes from
     * a shot now and then to a barrage — with *fewer* tube blocks than the cone it replaces.
     *
     * **The pedestal is what a shallow cap needs and a tall cone did not.** The top has to stay
     * [LAVA_OVER_THE_VENT] under the surface — a vent in open air over its own lake is a chimney you can
     * stand on, and the force of a caldera comes of it erupting from underneath — but a three-block cap
     * hung there has nothing beneath it. Filling the column under it with the crater's own rock puts the
     * mouths near the top of the lake, where a bomb clears the rim, instead of on a floor fifteen blocks
     * further down where it does not.
     *
     * Columns are filtered once by their own floor: well above the middle's is the crater wall, and tubes
     * up a wall are a seam running out of a hillside rather than a vent under a lake.
     */
    private fun seat(level: WorldGenLevel, lakes: TerrainField, middle: BlockPos, random: RandomSource): Boolean {
        val floor = floorUnderTheLavaAt(lakes, middle.x, middle.z, middle.y) ?: return false
        // **How much lava there is to hide under decides how much has to be left over it.** A caldera has
        // fifteen blocks and can spare three; a maar has two, and asking three of it left the crown below
        // the floor, the loop running zero times and a puddle with no vent in it.
        val over = minOf(LAVA_OVER_THE_VENT, middle.y - floor - ONE)
        if (over < ONE) return false
        val crown = middle.y - over
        val across = VENT_ACROSS + random.nextInt(VENT_SPREAD)
        val capFloor = maxOf(floor + ONE, crown - VENT_DEEP + ONE)
        val mound = across + PEDESTAL_SKIRTS
        val pedestal = pedestalOf(level, middle.x, floor, middle.z)
        var seatedAnything = false
        for ((offsetX, offsetZ) in discOf(mound)) {
            val here = floorUnderTheLavaAt(lakes, middle.x + offsetX, middle.z + offsetZ, middle.y) ?: continue
            if (abs(here - floor) > FLOOR_RELIEF) continue
            val away = sqrt((offsetX * offsetX + offsetZ * offsetZ).toDouble())
            val underTheCap = away <= across
            for (y in floor - ROOTED..rockUpTo(capFloor, floor, away, mound, underTheCap, random)) {
                lay(level, BlockPos(middle.x + offsetX, y, middle.z + offsetZ), pedestal)
            }
            if (!underTheCap) continue
            for (y in capFloor..crown) {
                // Domed a little rather than flat-topped: each layer of the cap is a block narrower than
                // the one under it, so the rim of the disc is where the deepest lava stands over it.
                if (away > across - (crown - y)) continue
                if (lay(level, BlockPos(middle.x + offsetX, y, middle.z + offsetZ), TUBE)) seatedAnything = true
            }
        }
        return seatedAnything
    }

    /**
     * How high the rock stands in this column — **flat under the cap and a rough mound outside it**.
     *
     * A pedestal that was one cylinder read as a plug somebody had dropped in (Jonah, walked 2026-09-10),
     * which is what a shape with one radius and one height always reads as. Under the cap it has no
     * choice: the tubes rest on it and a hole would hang them in the lava. Past the cap it falls away with
     * distance and wanders a block either side of that, so what shows above the lake is a mound with a
     * broken edge rather than a disc.
     */
    private fun rockUpTo(
        capFloor: Int,
        floor: Int,
        away: Double,
        mound: Int,
        underTheCap: Boolean,
        random: RandomSource,
    ): Int {
        val top = capFloor - ONE
        if (underTheCap) return top
        val share = ONE_WHOLE - (away / mound).coerceIn(0.0, ONE_WHOLE)
        val fallen = floor + ((top - floor) * share).roundToInt()
        return (fallen + random.nextInt(ROUGHNESS) - ONE).coerceIn(floor - ROOTED, top)
    }

    /** Writes [what] where there is rock or lava to write it into, and says whether it went in. */
    private fun lay(level: WorldGenLevel, at: BlockPos, what: BlockState): Boolean {
        val standing = level.getBlockState(at)
        if (!standing.isSolidRender && !standing.`is`(Blocks.LAVA)) return false
        level.setBlock(at, what, UPDATE_NONE)
        return true
    }

    /**
     * What the pedestal is built of: **whatever the crater floor is**, so it belongs to the Age rather
     * than to this feature. An Age written on basalt gets a basalt plug and one on sandstone gets its own.
     */
    private fun pedestalOf(level: WorldGenLevel, x: Int, floor: Int, z: Int): BlockState {
        val standing = level.getBlockState(BlockPos(x, floor, z))
        return if (standing.isSolidRender) standing else FALLING_BACK_ON
    }

    /**
     * The columns within [reach] of the middle.
     *
     * **Width is what the mechanic needs, twice over.** A mass buys both the force behind a bomb and how
     * often one is thrown, and a caldera's vent is meant to be at the top of both rather than somewhere
     * on the ramp — which is what leaves the ramp itself to the clusters underground.
     */
    private fun discOf(reach: Int): List<Pair<Int, Int>> =
        (-reach..reach).flatMap { x -> (-reach..reach).map { z -> x to z } }
            .filter { (x, z) -> x * x + z * z <= reach * reach }

    private companion object {

    /**
     * How wide the cap is, drawn per crater so two volcanoes are not the same machine.
     *
     * **This is the output dial**, and it is a square one: every tube with open lava over it wells and
     * throws on its own account, so the mouths go as the square of this. Nine gave about two hundred and
     * fifty and nine bombs in the air at once, which was too many (Jonah, walked 2026-09-10) — halving the
     * radius quarters the area, which is the ask.
     */
    private const val VENT_ACROSS = 4
    private const val VENT_SPREAD = 2

    /** How far the rock skirts out past the cap, giving it a mound to stand on rather than a plinth. */
    private const val PEDESTAL_SKIRTS = 5

    /** A block either way on the mound's own top, which is the whole of what stops it reading as turned. */
    private const val ROUGHNESS = 3

    /** And how thick — shallow, because everything under the top layer is plugged by the layer above it. */
    private const val VENT_DEEP = 3

    /** How far it carries on under the floor, so a drained crater still has a vent in it. */
    private const val ROOTED = 2

    /** How much lava is left standing over the mass, so it always erupts from under its own lake. */
    private const val LAVA_OVER_THE_VENT = 3

    /** How far a vent column may sit off the floor at the middle before it counts as the crater wall. */
    private const val FLOOR_RELIEF = 3

    private val TUBE = AgeContent.LAVA_TUBE_BLOCK.defaultBlockState()

    /** Only where a crater floor turned out not to be solid, which nothing has produced. */
    private val FALLING_BACK_ON = Blocks.STONE.defaultBlockState()

    private val BESIDE = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /**
     * A bound on the spread rather than on a lake — **generous, because clipping it moves the middle**,
     * and a middle that moves with whichever chunk asked is one no chunk holds and a crater with no vent.
     *
     * The broadest crater here is a shield's at its largest pose, a little under ninety blocks across,
     * which is about four hundred columns on the lattice.
     */
    private const val MOST_IN_A_LAKE = 4096

    private const val CHUNK = 16

    /** Coarse: a crater is tens of blocks across, so every fourth column finds it. */
    private const val STRIDE = 4

    private const val UPDATE_NONE = 2

    private const val ONE = 1

    private const val ONE_WHOLE = 1.0

    private const val INT_MASK = 0xFFFF_FFFFL
    }
}
