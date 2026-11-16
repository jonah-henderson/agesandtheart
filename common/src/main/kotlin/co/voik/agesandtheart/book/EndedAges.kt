package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.cataclysm.Cataclysms
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack

/**
 * Whether a book leads into an Age that has ended (design §5.3 "The dead link"), which every link and panel into it
 * refuses. Asked of the Age the book **would** reach: the one saved, or where none is — swept, or never yet opened —
 * the one the book would make, so neither a swept Age nor a book bound days ago and opened late is a way back in.
 */
object EndedAges {

    fun leadsToAnEndedAge(server: MinecraftServer, stack: ItemStack): Boolean {
        val recipe = recipeBehind(server, stack) ?: return false
        return Cataclysms.hasEnded(server, recipe, server.overworld().gameTime)
    }

    private fun recipeBehind(server: MinecraftServer, stack: ItemStack): AgeRecipe? {
        val saved = AgeSavedData.get(server)
        return when {
            stack.item === AgeContent.DESCRIPTIVE_BOOK -> {
                val id = stack.get(AgeComponents.AGE_ID)
                id?.let(saved::recipe) ?: wouldBeMadeBy(server, stack)
            }
            stack.item === AgeContent.LINKING_BOOK -> {
                val target = stack.get(AgeComponents.LINK_TARGET) ?: return null
                saved.recipe(target.dimension.identifier()) ?: target.recipe
            }
            else -> null
        }
    }

    /**
     * The Age a descriptive book would make, where it carries its bind time; one without is timed from when it is first
     * opened, so it cannot have ended before then.
     */
    private fun wouldBeMadeBy(server: MinecraftServer, stack: ItemStack): AgeRecipe? {
        if (!stack.has(AgeComponents.BOOK_WRITTEN_AT)) return null
        return DescriptiveBookRecipe.of(stack, server, stack.get(AgeComponents.AGE_ID) ?: NOT_YET_NAMED)
    }

    /** Stands in for the id of a book never opened, which only a book saying nothing readable would draw from. */
    private val NOT_YET_NAMED = "not_yet_named".location()
}
