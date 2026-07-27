package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What fills the space the shape leaves — a sea of something, or nothing at all.
 *
 * The slot chooses the **substance** only; the height comes from the [Landform], which is the one thing
 * that knows where its own sea belongs. That split is what makes the slots genuinely independent: any
 * medium can be poured over any landform and land at a sensible level, so "Spire islands over a sea of
 * lava" needs no hand-written combination.
 *
 * [depth] is the writer's one lever over the height, and it is enumerated rather than a number — three
 * steps either side of whatever the landform considers normal (design §3.2).
 *
 * Note that a *plasma* sea is not here. Spire's green sea is ordinary water wearing a green biome, so
 * it belongs to [Dressing] — which is a nice accident of the tag-driven design proving itself early:
 * substance and appearance really are separate axes.
 */
enum class Medium(override val key: String, private val block: () -> BlockState) : SlotPreset {
    /** Nothing. The shape stands in open air. */
    VOID("void", { Blocks.AIR.defaultBlockState() }),

    /** An ordinary sea. */
    SEA("sea", { Blocks.WATER.defaultBlockState() }),

    /** A sea of lava — survivable only from a distance. */
    LAVA("lava", { Blocks.LAVA.defaultBlockState() }),
    ;

    override val slot = Slot.MEDIUM

    override val parameters: List<Parameter>
        get() = if (this == VOID) emptyList() else listOf(DEPTH)

    override fun getSerializedName(): String = key

    /**
     * This medium poured to [waterline] — the height being the landform's to declare, not the medium's.
     * A null waterline is a shape standing in open air, and then nothing is filled whatever is named
     * here: the shape's own contract wins over the sentence, because a sea at no particular height is
     * not a thing the world can be.
     *
     * Where several landforms share an Age they may disagree about the height, or about whether there
     * should be a sea at all. One of them wins, drawn from the Age's seed — see
     * [co.voik.agesandtheart.age.AgeGeneration] — so a half-drowned Age is a real and intended outcome.
     */
    fun over(waterline: Int?, options: Options): AmbientMedium {
        if (waterline == null || this == VOID) return AmbientMedium.VOID
        val shift = when (options.of(DEPTH)) {
            "shallow" -> -DEPTH_STEP
            "deep" -> DEPTH_STEP
            else -> 0
        }
        return AmbientMedium.sea(block(), level = waterline + shift)
    }

    companion object {
        val DEPTH = Parameter("depth", "normal", "shallow", "deep")

        /** Enough to redraw a coastline without drowning or stranding what the landform built. */
        private const val DEPTH_STEP = 12
    }
}
