package co.voik.agesandtheart.station

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.location
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.display.SlotDisplay
import java.util.Optional

/**
 * The machine catalog crosses the wire whole. It is sent to every player as they join, so a part of it that would
 * not encode would disconnect everyone.
 */
@Tags(NEEDS_REGISTRIES)
class MachineCatalogCheck : FunSpec({

    test("every part of a catalog arrives as it was sent") {
        // Built by hand, since the recipe's own display names the compounder, which is not registered offline.
        val compounding = CompoundingRecipeDisplay(
            inputs = listOf(Ingredient.of(Items.COAL_BLOCK).display()),
            counts = listOf(COAL_FOR_A_DIAMOND),
            resultShown = SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate(Items.DIAMOND_BLOCK)),
            stationShown = SlotDisplay.ItemSlotDisplay(Items.FURNACE),
            needs = listOf(CompounderNeed.POWER, CompounderNeed.HEAT),
            allowedBy = Optional.of(CompoundingSwitch.BEDROCK),
        )
        val bookAndQuill = ShapelessRecipe(
            Recipe.CommonInfo(true),
            CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
            ItemStackTemplate(Items.WRITABLE_BOOK),
            listOf(Ingredient.of(Items.BOOK), Ingredient.of(Items.FEATHER)),
        )
        val grinding = MachineRecipe(Ingredient.of(Items.COAL), ItemStackTemplate(Items.CHARCOAL)).listed()
        val sent = MachineCatalogPayload(
            stations = listOf(StationCatalog(Station.GRINDER, listOf(grinding))),
            drying = listOf(grinding),
            compounding = listOf(compounding),
            crafting = listOf(ShownCrafting("writable_book_from_ink_bottle".location(), bookAndQuill)),
        )

        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
        MachineCatalogPayload.STREAM_CODEC.encode(buffer, sent)
        val heard = MachineCatalogPayload.STREAM_CODEC.decode(buffer)

        check(heard.stations == sent.stations) { "the stations' recipes changed in transit" }
        check(heard.drying == sent.drying) { "the drying rack's recipes changed in transit" }
        check(heard.compounding == sent.compounding) { "the compounder's recipes changed in transit" }
        val crafted = heard.crafting.single()
        check(crafted.id == sent.crafting.single().id) { "a crafting recipe arrived under ${crafted.id}" }
        check(crafted.recipe.display() == bookAndQuill.display()) { "a crafting recipe arrived as something else" }
        check(buffer.readableBytes() == 0) { "${buffer.readableBytes()} bytes were left unread" }
    }
}) {
    private companion object {
        const val COAL_FOR_A_DIAMOND = 9
    }
}
