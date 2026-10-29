package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.state.BlockState

/**
 * A part of a landform's rock made of blocks of its own rather than the Age's: a tree's bark and heartwood, a reef's
 * coral. Laid wherever [where] and the rock are both solid, so it never adds rock or takes any away.
 *
 * [blocks] mingle as the Age's rock does. Where [topping] is set, the top [toppingDepth] blocks of each run of rock
 * inside the layer are made of it instead — the soil on a shelf, the sand on a bar.
 *
 * A landform's own layers stand only where its book named no material of its rock; a named one is the whole of it.
 */
data class MaterialLayer(
    val where: TerrainField,
    val blocks: List<BlockState>,
    val topping: List<BlockState> = emptyList(),
    val toppingDepth: Int = 0,
) {
    /** The same layer laid over [field] in place of its own, for a landform moved or bounded after it was built. */
    fun over(field: TerrainField): MaterialLayer = copy(where = field)

    companion object {
        val CODEC: Codec<MaterialLayer> = RecordCodecBuilder.create { instance ->
            instance.group(
                TerrainField.CODEC.fieldOf("where").forGetter(MaterialLayer::where),
                BlockState.CODEC.listOf().fieldOf("blocks").forGetter(MaterialLayer::blocks),
                BlockState.CODEC.listOf().optionalFieldOf("topping", emptyList()).forGetter(MaterialLayer::topping),
                Codec.INT.optionalFieldOf("topping_depth", 0).forGetter(MaterialLayer::toppingDepth),
            ).apply(instance, ::MaterialLayer)
        }
    }
}
