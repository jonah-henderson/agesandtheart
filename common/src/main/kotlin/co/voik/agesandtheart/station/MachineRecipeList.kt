package co.voik.agesandtheart.station

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.display.SlotDisplay

/** One line of a machine's recipe list, as the screen draws it. */
data class ListedRecipe(val input: SlotDisplay, val result: SlotDisplay) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ListedRecipe> = StreamCodec.composite(
            SlotDisplay.STREAM_CODEC, ListedRecipe::input,
            SlotDisplay.STREAM_CODEC, ListedRecipe::result,
            ::ListedRecipe,
        )
    }
}

/** A recipe a machine takes: what a click on its line looks for in the inventory, and what it makes. */
data class MachineRecipe(val ingredient: Ingredient, val result: ItemStackTemplate) {
    fun listed(): ListedRecipe = ListedRecipe(ingredient.display(), SlotDisplay.ItemStackSlotDisplay(result))
}

/**
 * A machine menu with a list beside it of every recipe the machine takes, shown whether or not the player
 * has learned it, as the stonecutter's are.
 */
interface ListsItsRecipes {
    /** On the client, what the server said the machine takes; empty until it says. */
    var listed: List<ListedRecipe>

    /** On the server, what the machine takes, in the order [listed] holds it. */
    fun recipesOn(server: MinecraftServer): List<MachineRecipe>
}

/** What a machine's screen opens with: every recipe it takes, in the order a click names them by. */
data class MachineRecipesPayload(val containerId: Int, val recipes: List<ListedRecipe>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<MachineRecipesPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MachineRecipesPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "machine_recipes"),
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MachineRecipesPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MachineRecipesPayload::containerId,
            ListedRecipe.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipesPayload::recipes,
            ::MachineRecipesPayload,
        )
    }
}

/** A click on the [recipe]th line of the open machine's list: move its input in from the inventory. */
data class MachineFillPayload(val containerId: Int, val recipe: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<MachineFillPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MachineFillPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "machine_fill"),
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MachineFillPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MachineFillPayload::containerId,
            ByteBufCodecs.VAR_INT, MachineFillPayload::recipe,
            ::MachineFillPayload,
        )
    }
}

object MachineRecipeLists {

    /** Every recipe of [type] that [asMachineRecipe] keeps, ordered by id so both ends count them alike. */
    fun <T : Recipe<*>> of(
        server: MinecraftServer,
        type: RecipeType<T>,
        asMachineRecipe: (T) -> MachineRecipe?,
    ): List<MachineRecipe> =
        server.recipeManager.getRecipes()
            .filter { it.value().type == type }
            .sortedBy { it.id().identifier().toString() }
            .mapNotNull { holder ->
                @Suppress("UNCHECKED_CAST")
                asMachineRecipe(holder.value() as T)
            }

    /** Opens [provider]'s menu for [player], and sends its list if it keeps one. */
    fun open(player: Player, provider: MenuProvider) {
        player.openMenu(provider)
        val serverPlayer = player as? ServerPlayer ?: return
        val menu = serverPlayer.containerMenu
        if (menu !is ListsItsRecipes) return
        val recipes = menu.recipesOn(serverPlayer.level().server).map(MachineRecipe::listed)
        Services.NETWORK.sendToPlayer(serverPlayer, MachineRecipesPayload(menu.containerId, recipes))
    }

    /** Moves the first inventory stack the clicked recipe takes into the machine, as a shift-click would. */
    fun fill(player: ServerPlayer, payload: MachineFillPayload) {
        val menu = player.containerMenu
        if (menu.containerId != payload.containerId || menu !is ListsItsRecipes) return
        val recipe = menu.recipesOn(player.level().server).getOrNull(payload.recipe) ?: return
        fun holdsTheInput(index: Int): Boolean {
            val slot = menu.slots[index]
            return slot.container is Inventory && recipe.ingredient.test(slot.item)
        }
        val from = menu.slots.indices.firstOrNull(::holdsTheInput) ?: return
        menu.quickMoveStack(player, from)
    }
}
