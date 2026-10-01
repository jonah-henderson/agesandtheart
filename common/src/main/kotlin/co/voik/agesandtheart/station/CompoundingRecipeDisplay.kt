package co.voik.agesandtheart.station

import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplay
import java.util.Optional

/**
 * What the compounder's recipe book shows of a [CompoundingRecipe]: each input with its count, the result,
 * and what must stand beside the machine. A display of its own, since no vanilla one carries counts.
 */
data class CompoundingRecipeDisplay(
    val inputs: List<SlotDisplay>,
    val counts: List<Int>,
    val resultShown: SlotDisplay,
    val stationShown: SlotDisplay,
    val needs: List<CompounderNeed>,
    val allowedBy: Optional<CompoundingSwitch>,
) : RecipeDisplay {

    override fun result(): SlotDisplay = resultShown

    override fun craftingStation(): SlotDisplay = stationShown

    override fun type(): RecipeDisplay.Type<CompoundingRecipeDisplay> = TYPE

    /** Whether the server lets it run; the config is synced, so a client can ask too. */
    val isAllowed: Boolean get() = allowedBy.map(CompoundingSwitch::isOn).orElse(true)

    companion object {
        val ID: Identifier = "compounding".location()

        private val MAP_CODEC: MapCodec<CompoundingRecipeDisplay> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                SlotDisplay.CODEC.listOf().fieldOf("inputs").forGetter { it.inputs },
                Codec.INT.listOf().fieldOf("counts").forGetter { it.counts },
                SlotDisplay.CODEC.fieldOf("result").forGetter { it.resultShown },
                SlotDisplay.CODEC.fieldOf("crafting_station").forGetter { it.stationShown },
                CompounderNeed.CODEC.listOf().fieldOf("needs").forGetter { it.needs },
                CompoundingSwitch.CODEC.optionalFieldOf("allowed_by").forGetter { it.allowedBy },
            ).apply(instance, ::CompoundingRecipeDisplay)
        }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CompoundingRecipeDisplay> = StreamCodec.composite(
            SlotDisplay.STREAM_CODEC.apply(ByteBufCodecs.list()), CompoundingRecipeDisplay::inputs,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), CompoundingRecipeDisplay::counts,
            SlotDisplay.STREAM_CODEC, CompoundingRecipeDisplay::resultShown,
            SlotDisplay.STREAM_CODEC, CompoundingRecipeDisplay::stationShown,
            CompounderNeed.STREAM_CODEC.apply(ByteBufCodecs.list()), CompoundingRecipeDisplay::needs,
            ByteBufCodecs.optional(CompoundingSwitch.STREAM_CODEC), CompoundingRecipeDisplay::allowedBy,
            ::CompoundingRecipeDisplay,
        )

        val TYPE: RecipeDisplay.Type<CompoundingRecipeDisplay> = RecipeDisplay.Type(MAP_CODEC, STREAM_CODEC)
    }
}
