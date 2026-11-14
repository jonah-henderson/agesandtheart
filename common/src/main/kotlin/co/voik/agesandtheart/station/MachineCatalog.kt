package co.voik.agesandtheart.station

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import com.mojang.datafixers.util.Either
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe

/**
 * A crafting recipe of ours that a recipe viewer can show as the plain shapeless one inside it: what it takes is
 * that recipe's, and only what it hands back or what its result carries differs.
 */
interface ShownAsShapeless {
    val shown: ShapelessRecipe
}

/** The same, for a recipe wrapping a shaped one. */
interface ShownAsShaped {
    val shown: ShapedRecipe
}

/** One of those, under the id of the recipe it stands for. */
data class ShownCrafting(val id: Identifier, val shown: Either<ShapelessRecipe, ShapedRecipe>) {
    val key: ResourceKey<Recipe<*>> get() = ResourceKey.create(Registries.RECIPE, id)

    val recipe: CraftingRecipe get() = shown.map({ it }, { it })

    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ShownCrafting> = StreamCodec.composite(
            Identifier.STREAM_CODEC, ShownCrafting::id,
            ByteBufCodecs.either(ShapelessRecipe.STREAM_CODEC, ShapedRecipe.STREAM_CODEC), ShownCrafting::shown,
            ::ShownCrafting,
        )

        /** What [recipe] shows as, or null where it is not one of ours a viewer needs sending. */
        fun of(id: Identifier, recipe: Recipe<*>): ShownCrafting? = when (recipe) {
            is ShownAsShapeless -> ShownCrafting(id, Either.left(recipe.shown))
            is ShownAsShaped -> ShownCrafting(id, Either.right(recipe.shown))
            else -> null
        }
    }
}

/** What one station takes. */
data class StationCatalog(val station: Station, val recipes: List<ListedRecipe>) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StationCatalog> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map({ Station.entries[it] }, Station::ordinal), StationCatalog::station,
            ListedRecipe.STREAM_CODEC.apply(ByteBufCodecs.list()), StationCatalog::recipes,
            ::StationCatalog,
        )
    }
}

/**
 * Every recipe of ours a client is not otherwise sent, for showing outside a machine's own screen — a recipe
 * viewer's categories. A server sends a client only vanilla's own kinds of recipe, so the machines and the
 * crafting recipes with serializers of ours reach it here, as the datapack does: on joining and after a reload.
 */
data class MachineCatalogPayload(
    val stations: List<StationCatalog>,
    val drying: List<ListedRecipe>,
    val compounding: List<CompoundingRecipeDisplay>,
    val crafting: List<ShownCrafting>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<MachineCatalogPayload> = TYPE

    fun recipesOf(station: Station): List<ListedRecipe> = stations.firstOrNull { it.station == station }?.recipes.orEmpty()

    companion object {
        val TYPE: CustomPacketPayload.Type<MachineCatalogPayload> = CustomPacketPayload.Type("machine_catalog".location())

        val EMPTY = MachineCatalogPayload(emptyList(), emptyList(), emptyList(), emptyList())

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MachineCatalogPayload> = StreamCodec.composite(
            StationCatalog.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineCatalogPayload::stations,
            ListedRecipe.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineCatalogPayload::drying,
            CompoundingRecipeDisplay.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineCatalogPayload::compounding,
            ShownCrafting.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineCatalogPayload::crafting,
            ::MachineCatalogPayload,
        )

        /** What [server] takes, leaving out what it has switched off. */
        fun of(server: MinecraftServer): MachineCatalogPayload {
            val recipes = server.recipeManager.getRecipes().sortedBy { it.id().identifier().toString() }
            val compounding = recipes.map { it.value() }.filterIsInstance<CompoundingRecipe>()
                .flatMap { it.display() }.filterIsInstance<CompoundingRecipeDisplay>().filter { it.isAllowed }
            val crafting = recipes.mapNotNull { holder -> ShownCrafting.of(holder.id().identifier(), holder.value()) }
            return MachineCatalogPayload(
                stations = Station.entries.map { station ->
                    StationCatalog(station, MachineRecipeLists.ofStation(server, station).map(MachineRecipe::listed))
                },
                drying = MachineRecipeLists.ofDryingRack(server).map(MachineRecipe::listed),
                compounding = compounding,
                crafting = crafting,
            )
        }
    }
}
