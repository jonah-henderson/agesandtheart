package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What the rock **is** — the block an Age's terrain is made of, before anything paints a skin on it.
 *
 * **This is vanilla's `default_block`, and moving here from a surface rule is the whole of Phase 4.5 step 4.**
 * A material used to be painted by [Palette], which meant substituting into a rule tree — and for
 * `dressing=overworld` that tree is vanilla's own, sixty biome branches each naming its own stone, with no seam
 * to substitute at short of maintaining a copy of it forever. That is what `Dressing.ignoresMaterial` existed to
 * report, and it is why "vanilla's biomes, on black rock" was unsayable. A default block sits *underneath* the
 * whole tree instead, so vanilla paints its grass and dirt on top unchanged and the bulk below is ours.
 *
 * **Strata stay surface rules, because that is how vanilla does them** (Jonah, 2026-07-29 — *"if vanilla does it
 * with a surface rule, and reliably fills the lower y levels with deepslate, then I'm not sure how that isn't
 * substance"*). Checked rather than assumed: `SurfaceRuleData` uses `verticalGradient` five times, one of them
 * named `deepslate` over `Blocks.DEEPSLATE`, and `SurfaceSystem.buildSurface` walks a column down to
 * `getMinBuildHeight` — so a surface rule is *not* skin-deep in vanilla and rewrites the bulk at depth quite
 * happily. So this type answers "what is this world made of", and `Palette.fadingBelowY`/`withinDepth` answer
 * "and what does it become far down", exactly as vanilla splits them.
 *
 * **Mingling has to be here rather than in a rule**, though, and for the same reason the material moved at all:
 * as a rule it would have to be *prepended* to vanilla's tree, which is the thing that cannot be done. So
 * several materials mottle at block scale through [MINGLE] here, where a writer's "blackstone and tuff" reaches
 * the whole rock rather than a skin over one of them.
 */
data class Substance(
    /**
     * One block per region of [where], in the order [where] numbers them — so "copper spires and andesite hills"
     * puts copper where the spires are.
     *
     * Each region may name **several**, which mingle rather than divide (design §3.2): division already has a
     * spelling, and it is naming two terrains.
     */
    val blocks: List<List<BlockState>> = listOf(listOf(STONE)),
    /** Which ground each entry governs — **the terrain's own map**, so a material follows the shape it makes. */
    val where: RegionMap = RegionMap.whole(),
    /**
     * How wide a patch of one material runs, in blocks — see [PATCHY_MINGLING] and [FINE_MINGLING].
     *
     * A field rather than a constant since 2026-07-29, so one Age can speckle at block scale while another keeps
     * broad patches. Defaults to what every Age had before, so nothing moves unless it asks to.
     */
    val mingleStretch: Double = PATCHY_MINGLING,
) {
    private val mingle = fieldNoise(MINGLE_SEED, MINGLE_FIRST_OCTAVE, MINGLE_AMPLITUDES)

    // Guarded, because a zero would divide the world by nothing and hand the noise an infinity.
    private val stretch = mingleStretch.coerceAtLeast(FINEST_MINGLING)

    /** The block at a position — asked per block, since mingling varies within a column. */
    fun blockAt(worldX: Int, worldY: Int, worldZ: Int): BlockState {
        val here = blocks.getOrElse(where.memberAt(worldX, worldZ)) { blocks.first() }
        here.singleOrNull()?.let { return it }
        if (here.isEmpty()) return STONE
        // Mottled at block scale, which is what makes two materials read as one rock rather than as layers.
        val sample = mingle.getValue(worldX / stretch, worldY / stretch, worldZ / stretch)
        val band = ((sample + 1.0) / 2.0 * here.size).toInt().coerceIn(0, here.size - 1)
        return here[band]
    }

    /**
     * A representative block, for the questions that are about *opacity* rather than about substance —
     * heightmaps, and the height contract structures place against.
     *
     * Every material an Age can be made of is a full block, so which one is answered here cannot change a
     * heightmap's verdict; asking the region map per query would cost a noise sample for nothing.
     */
    val representative: BlockState get() = blocks.first().firstOrNull() ?: STONE

    companion object {
        // **Declared before [PLAIN], and that ordering is load-bearing.** A companion initialises in source
        // order, and constructing a `Substance` reads these — so a `PLAIN` above them is built while they are
        // still null, which fails at class-init with a bare NullPointerException about `amplitudes`. Exactly the
        // trap `:common:codeccheck` exists to catch, and it caught this one.
        //
        // Its own seed, so what the rock is made of is decorrelated from where the rock is.
        private const val MINGLE_SEED = 0x5704_D1EDL
        private const val MINGLE_FIRST_OCTAVE = -3
        private val MINGLE_AMPLITUDES = listOf(1.0, 1.0)

        /**
         * How wide a patch of one material runs, in blocks — the default, and what every Age had before this
         * became a knob.
         *
         * Small on purpose: this is *mingling*, not division — the two materials should read as one speckled
         * rock, and anything much wider starts reading as two territories, which already has its own spelling.
         */
        const val PATCHY_MINGLING = 7.0

        /**
         * Speckled at the scale of single blocks — *"absurdly fine … almost uniform mixture"* (Jonah, for the
         * Spire's basalt, blackstone and gravel).
         *
         * The noise's coarsest octave has a wavelength of about eight units, so dividing world coordinates by
         * roughly an eighth is what brings a patch down to a block or two. One knob does it: nothing about the
         * octaves has to change, because stretching the *input* is the same as choosing a finer feature size.
         */
        const val FINE_MINGLING = 0.15

        /** A floor, so a stretch of zero cannot hand the noise an infinite coordinate. */
        private const val FINEST_MINGLING = 0.01

        /** What an Age that named no material is made of, and vanilla's own default block. */
        val STONE: BlockState = Blocks.STONE.defaultBlockState()

        val PLAIN = Substance()

        val CODEC: Codec<Substance> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockState.CODEC.listOf().listOf().optionalFieldOf("blocks", listOf(listOf(STONE)))
                    .forGetter(Substance::blocks),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("where", RegionMap.whole())
                    .forGetter(Substance::where),
                Codec.DOUBLE.optionalFieldOf("mingle_stretch", PATCHY_MINGLING)
                    .forGetter(Substance::mingleStretch),
            ).apply(instance, ::Substance)
        }
    }
}
