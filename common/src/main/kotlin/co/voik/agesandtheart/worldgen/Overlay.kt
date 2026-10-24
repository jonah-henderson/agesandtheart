package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.StandingFluid
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/** Lazy so this file stays loadable without a bootstrapped registry — see `Collapse`'s own note. */
private val AIR: BlockState by lazy { Blocks.AIR.defaultBlockState() }

/**
 * Shape of **ours** laid over whatever rock an Age happens to wear.
 *
 * **The problem this exists for: an Age that names no landform wears vanilla's rock, and everything of
 * ours that is a *shape* was silently absent from it.** A writer could say `volcano`, the Art would take
 * the word, charge for it, score it for danger — and the mountains would never arrive, because the field
 * tree they are unioned into is only built for a landform of ours. The word was accepted and ignored,
 * which is the worst of both.
 *
 * **So a shape is expressed once and applied two ways.** An overlay is a statement of intent — this rock
 * is raised, this rock is taken, these fluids stand in what is left — and the two paths honour it
 * differently: a landform of ours folds it into its own field tree, where it is analytic and free, and a
 * vanilla-rock Age has it written into the chunk after vanilla's own fill. Both read the same object, so
 * they cannot come to disagree about what a volcano is. That is the rule `VolcanoField.over` already
 * records for its own two layers, one level up.
 *
 * **Written at the noise stage, not as a feature**, which is what makes the vanilla path worth having: a
 * feature may only write within one chunk of its own and would hand back bare rock, where anything laid
 * during `fillFromNoise` is surfaced by the biome's own rules exactly as vanilla's terrain is. A cone comes
 * out with grass or snow or netherrack on it without this knowing which.
 *
 * Nothing here is volcano-shaped. Anything of ours that wants terrain — a spire, a bridge, a wound with a
 * lip — says it the same way.
 */
data class Overlay(
    /** Rock this puts where there was none. */
    val raises: TerrainField? = null,

    /** And rock it takes back out, applied after [raises] so a hollow may be cut in what was just built. */
    val hollows: TerrainField? = null,

    /**
     * Bodies standing inside what the two above left — a caldera's lake, a chamber's pool.
     *
     * Named rather than bare, because a feature that seats something in one of them has to be able to find
     * *its own*: both of a volcano's bodies are lava, so substance can never tell them apart. See
     * [StandingFluid.named].
     */
    val pours: List<StandingFluid> = emptyList(),

    /**
     * Rock taken out **only under the ground**: an underground cut into vanilla's rock, kept [CRUST] under each
     * column's own ground or seabed, so it never opens onto a surface or lets a sea in from above. Only the fill
     * reads it ([beneathAt]); being under the ground, it moves no height a structure places against.
     */
    val beneath: TerrainField? = null,

    /** What stands in [beneath], kept under the ground the same way — a chamber's lake. */
    val poursBeneath: List<StandingFluid> = emptyList(),
) {

    val isEmpty: Boolean get() = raises == null && hollows == null && pours.isEmpty() && beneath == null

    /**
     * What [beneath] and [poursBeneath] make of one column whose ground or seabed tops out at [groundTopY]: the air
     * and the fluids, each cut off [CRUST] under it.
     */
    fun beneathAt(worldX: Int, worldZ: Int, groundTopY: Int): Column? {
        val cut = beneath ?: return null
        val underTheGround = Spans.of(Spans.LOWEST_Y, groundTopY - CRUST)
        val fluids = poursBeneath.mapNotNull { body ->
            body.where.columnSpans(worldX, worldZ).intersect(underTheGround).takeIf { it.ranges.isNotEmpty() }?.let { body.fluid to it }
        }
        return Column(rock = Spans.EMPTY, air = cut.columnSpans(worldX, worldZ).intersect(underTheGround), fluids = fluids)
    }

    /** This overlay with [body] standing in it as well. */
    fun alsoPouring(body: StandingFluid): Overlay = copy(pours = pours + body)

    /** This overlay raising [more] rock as well, or itself where there is none to add. */
    fun alsoRaising(more: TerrainField?): Overlay = when {
        more == null -> this
        raises == null -> copy(raises = more)
        else -> copy(raises = Union(listOf(raises, more)))
    }

    /**
     * What this overlay does to one column, resolved once.
     *
     * **All three of [co.voik.agesandtheart.generation.AgeChunkGenerator]'s terrain exits go through this**
     * — the chunk fill, the height answer and the column answer. The class doc there says why they must
     * agree: structures and features place against the height, so a cone the fill raised and the height did
     * not would put a village inside a mountain, and a tree on the ground beneath it. Answering all three from one object makes them
     * disagreeing impossible rather than merely unlikely, which is the same rule [foldedInto] keeps for the
     * analytic path.
     *
     * **Raise, then hollow, then pour**, the order [foldedInto] composes the fields in: a caldera is cut
     * from the cone only just built, and its lake stands in what that left.
     */
    fun at(worldX: Int, worldZ: Int): Column {
        val raised = raises?.columnSpans(worldX, worldZ) ?: Spans.EMPTY
        val hollowed = hollows?.columnSpans(worldX, worldZ) ?: Spans.EMPTY
        return Column(
            // Subtracting here rather than at each query is what lets the fill walk spans instead of
            // testing every Y, and it is `Spans`' own ordered walk rather than a hand-rolled one.
            rock = raised.subtract(hollowed),
            air = hollowed,
            fluids = pours.mapNotNull { body ->
                body.where.columnSpans(worldX, worldZ).takeIf { it.ranges.isNotEmpty() }?.let { body.fluid to it }
            },
        )
    }

    /**
     * One column's worth of overlay: what it makes rock, what it makes air, and what stands in the result.
     *
     * The three are already resolved against each other — [rock] is what was raised less what was then
     * hollowed — so precedence is only ever *fluid over air over rock*, and both readers apply it the same
     * way. [air] deliberately keeps the whole hollow rather than only the part that met our own rock: on a
     * vanilla-terrain Age a magma chamber has to cut vanilla's stone, which is the entire point of it.
     */
    class Column internal constructor(
        val rock: Spans,
        val air: Spans,
        val fluids: List<Pair<BlockState, Spans>>,
    ) {

        val saysNothing: Boolean
            get() = rock.ranges.isEmpty() && air.ranges.isEmpty() && fluids.isEmpty()

        /**
         * The highest Y this overlay has **any** opinion about, or null where it has none.
         *
         * Not a surface height: a hollow counts, because a hollow is how the overlay can *lower* one. It is
         * the bound a height query scans down from, and the test for whether it need scan at all.
         */
        val topmostY: Int? = listOfNotNull(
            rock.highestSolidY,
            air.highestSolidY,
            fluids.mapNotNull { (_, where) -> where.highestSolidY }.maxOrNull(),
        ).maxOrNull()

        /** What stands at [y], or null where this overlay says nothing and the ground under it stands. */
        fun blockAt(y: Int, rockHere: BlockState): BlockState? {
            fluids.firstOrNull { (_, where) -> where.contains(y) }?.let { (fluid, _) -> return fluid }
            if (air.contains(y)) return AIR
            if (rock.contains(y)) return rockHere
            return null
        }

        /**
         * Every block this column changes, in precedence order so a later write is the right one.
         *
         * Walks the spans rather than the height, which is what keeps writing an overlay into a chunk
         * proportional to the overlay instead of to the world.
         */
        fun forEachBlock(rockHere: BlockState, put: (y: Int, state: BlockState) -> Unit) {
            for (span in rock.ranges) for (y in span) put(y, rockHere)
            for (span in air.ranges) for (y in span) put(y, AIR)
            for ((fluid, where) in fluids) for (span in where.ranges) for (y in span) put(y, fluid)
        }
    }

    /** [base] with this overlay's rock folded in — the analytic path, for a landform of ours. */
    fun foldedInto(base: TerrainField): TerrainField {
        val raised = if (raises == null) base else Union(listOf(base, raises))
        return if (hollows == null) raised else Subtract(raised, hollows)
    }

    companion object {
        val NONE = Overlay()

        /** How much rock is left over [beneath], under each column's ground — deep enough for dirt and roots. */
        const val CRUST = 8

        /** Takes the recursive field codec directly, as `SeaFill` does — it owns no shape of its own. */
        val CODEC: MapCodec<Overlay> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                TerrainField.CODEC.optionalFieldOf("raises")
                    .forGetter { java.util.Optional.ofNullable(it.raises) },
                TerrainField.CODEC.optionalFieldOf("hollows")
                    .forGetter { java.util.Optional.ofNullable(it.hollows) },
                StandingFluid.codec(TerrainField.CODEC).codec().listOf()
                    .optionalFieldOf("pours", emptyList()).forGetter(Overlay::pours),
                TerrainField.CODEC.optionalFieldOf("beneath")
                    .forGetter { java.util.Optional.ofNullable(it.beneath) },
                StandingFluid.codec(TerrainField.CODEC).codec().listOf()
                    .optionalFieldOf("pours_beneath", emptyList()).forGetter(Overlay::poursBeneath),
            ).apply(instance) { raises, hollows, pours, beneath, poursBeneath ->
                Overlay(raises.orElse(null), hollows.orElse(null), pours, beneath.orElse(null), poursBeneath)
            }
        }
    }
}
