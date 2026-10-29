package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What the rock **is** — the block an Age's terrain is made of, before anything paints a skin on it.
 *
 * **Vanilla's `default_block`, rather than a surface rule.** Painting a material into a rule tree means
 * substituting into vanilla's own — sixty biome branches each naming their stone, with no seam to reach —
 * which is why "vanilla's biomes, on black rock" was unsayable. A default block sits *underneath* the
 * whole tree, so vanilla paints grass and dirt on top unchanged and the bulk below is ours.
 *
 * **Strata stay surface rules, because that is how vanilla does them**: `SurfaceSystem.buildSurface` walks
 * a column to `getMinBuildHeight`, so a rule is not skin-deep and rewrites the bulk at depth quite
 * happily. This answers "what is this world made of"; a [SurfacingStrategy] answers "what does it show,
 * and what does it become far down".
 *
 * **Mingling has to be here** for the reason the material moved at all: as a rule it would need
 * *prepending* to vanilla's tree, which cannot be done.
 */
data class TerrainFill(
    /**
     * One block per region of [where], in the order [where] numbers them. Each region may name several,
     * which mingle rather than divide (§3.2) — division already has a spelling: naming two terrains.
     */
    val blocks: List<List<BlockState>> = listOf(listOf(STONE)),
    /** Which ground each entry governs — **the terrain's own map**, so a material follows the shape it makes. */
    val where: RegionMap = RegionMap.whole(),
    /** How wide a patch of one material runs — see [PATCHY_MINGLING] and [FINE_MINGLING]. */
    val mingleStretch: Double = PATCHY_MINGLING,
    /** Parts of each region's rock made of blocks of the landform's own, index for index with [blocks]; see [MaterialLayer]. */
    val layers: List<List<MaterialLayer>> = emptyList(),
) {
    private val mingle = fieldNoise(MINGLE_SEED, MINGLE_FIRST_OCTAVE, MINGLE_AMPLITUDES)

    // Guarded, because a zero would divide the world by nothing and hand the noise an infinity.
    private val stretch = mingleStretch.coerceAtLeast(FINEST_MINGLING)

    /** The block at a position — asked per block, since mingling varies within a column. */
    fun blockAt(worldX: Int, worldY: Int, worldZ: Int): BlockState =
        mingled(blocks.getOrElse(where.memberAt(worldX, worldZ)) { blocks.first() }, worldX, worldY, worldZ)

    /** Whether any region lays a part of its rock in blocks of its own, so a fill need ask [columnAt] at all. */
    val isLayered: Boolean get() = layers.any { it.isNotEmpty() }

    /** One column's rock, read once: its region, and where each of that region's layers stands in it. */
    fun columnAt(worldX: Int, worldZ: Int): Column {
        val member = where.memberAt(worldX, worldZ)
        val own = layers.getOrElse(member) { emptyList() }
        return Column(blocks.getOrElse(member) { blocks.first() }, own, own.map { it.where.columnSpans(worldX, worldZ) })
    }

    inner class Column(
        private val rock: List<BlockState>,
        private val layers: List<MaterialLayer>,
        private val layerSpans: List<Spans>,
    ) {
        /** The block at [worldY], of whichever layer stands there first, else the region's rock; [solid] is the column's rock. */
        fun blockAt(worldX: Int, worldY: Int, worldZ: Int, solid: Spans): BlockState {
            for ((index, layer) in layers.withIndex()) {
                if (!layerSpans[index].contains(worldY)) continue
                val runTop = solid.ranges.firstOrNull { worldY in it }?.last ?: worldY
                val isTopping = layer.topping.isNotEmpty() && runTop - worldY < layer.toppingDepth
                return mingled(if (isTopping) layer.topping else layer.blocks, worldX, worldY, worldZ)
            }
            return mingled(rock, worldX, worldY, worldZ)
        }
    }

    private fun mingled(here: List<BlockState>, worldX: Int, worldY: Int, worldZ: Int): BlockState {
        here.singleOrNull()?.let { return it }
        if (here.isEmpty()) return STONE
        // Mottled at block scale, which is what makes two materials read as one rock rather than as layers.
        val sample = mingle.get(worldX / stretch, worldY / stretch, worldZ / stretch).toDouble()
        val band = ((sample + 1.0) / 2.0 * here.size).toInt().coerceIn(0, here.size - 1)
        return here[band]
    }

    /**
     * A representative block, for questions about *opacity* rather than substance — heightmaps, and the
     * height contract structures place against. Every material is a full block, so which one is answered
     * cannot change a heightmap's verdict, and asking the region map would cost a noise sample for nothing.
     */
    val representative: BlockState get() = blocks.first().firstOrNull() ?: STONE

    companion object {
        // **Declared before [PLAIN], and that ordering is load-bearing**: a companion initialises in source
        // order and constructing a `TerrainFill` reads these, so a `PLAIN` above them fails at class-init.
        //
        // Its own seed, so what the rock is made of is decorrelated from where the rock is.
        private const val MINGLE_SEED = 0x5704_D1EDL
        private const val MINGLE_FIRST_OCTAVE = -3
        private val MINGLE_AMPLITUDES = listOf(1.0, 1.0)

        /**
         * How wide a patch of one material runs, in blocks — the default. Small on purpose: this is
         * *mingling*, not division, and anything much wider reads as two territories, which has its own
         * spelling.
         */
        const val PATCHY_MINGLING = 7.0

        /**
         * Speckled at the scale of single blocks. The noise's coarsest octave has a wavelength of about
         * eight units, so stretching the input by roughly an eighth brings a patch down to a block or two.
         */
        const val FINE_MINGLING = 0.15

        /** A floor, so a stretch of zero cannot hand the noise an infinite coordinate. */
        private const val FINEST_MINGLING = 0.01

        /** What an Age that named no material is made of, and vanilla's own default block. */
        val STONE: BlockState = Blocks.STONE.defaultBlockState()

        val PLAIN = TerrainFill()

        val CODEC: Codec<TerrainFill> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockState.CODEC.listOf().listOf().optionalFieldOf("blocks", listOf(listOf(STONE)))
                    .forGetter(TerrainFill::blocks),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("where", RegionMap.whole())
                    .forGetter(TerrainFill::where),
                Codec.DOUBLE.optionalFieldOf("mingle_stretch", PATCHY_MINGLING)
                    .forGetter(TerrainFill::mingleStretch),
                MaterialLayer.CODEC.listOf().listOf().optionalFieldOf("layers", emptyList())
                    .forGetter(TerrainFill::layers),
            ).apply(instance, ::TerrainFill)
        }
    }
}
