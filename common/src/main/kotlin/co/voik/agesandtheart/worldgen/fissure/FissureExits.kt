package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.FissureExit
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.generation.Ages
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * Where what falls out of an Age's fissures comes down — players, mobs, items and rubble alike.
 *
 * **Home**, the overworld's spawn, unless a bound book thrown into one of the Age's fissures has led every one of
 * them somewhere else ([FissureExit], kept in [AgeSavedData]). A led exit that no longer resolves — an Age since
 * deleted — leads home again.
 */
object FissureExits {

    /** A world, and the column over which what falls out of the fissures comes down. */
    data class Exit(val level: ServerLevel, val at: BlockPos)

    /** Where what falls out of [from]'s fissures comes down. */
    fun of(from: ServerLevel): Exit {
        val server = from.server
        val led = AgeSavedData.get(server).fissureExitOf(from.dimension().identifier())
        return led?.let { resolve(server, it) } ?: home(server)
    }

    /** The overworld's spawn: where every fissure leads until a book says otherwise. */
    fun home(server: MinecraftServer): Exit = server.overworld().let { Exit(it, it.levelData.respawnData.pos()) }

    /**
     * The exit [book] names, for a fissure in [from]: a linking book's place, or a descriptive book's Age. Null
     * for a book bound to nothing, to an Age that does not exist, or to [from] itself.
     */
    fun namedBy(book: ItemStack, from: ServerLevel): FissureExit? {
        val named = when {
            book.`is`(AgeContent.LINKING_BOOK) -> book.get(AgeComponents.LINK_TARGET)?.let { target ->
                FissureExit(target.dimension.identifier(), BlockPos.containing(target.position))
            }
            book.`is`(AgeContent.DESCRIPTIVE_BOOK) -> book.get(AgeComponents.AGE_ID)
                ?.takeIf { AgeSavedData.get(from.server).recipe(it) != null }
                ?.let { FissureExit(it, null) }
            else -> null
        }
        return named?.takeIf { it.dimension != from.dimension().identifier() }
    }

    private fun resolve(server: MinecraftServer, exit: FissureExit): Exit? {
        val level = levelOf(server, exit.dimension) ?: return null
        return Exit(level, exit.at ?: Ages.arrivalIn(level))
    }

    /** The world [id] names, opening it where it is an Age not running yet. */
    private fun levelOf(server: MinecraftServer, id: Identifier): ServerLevel? =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, id))
            ?: AgeSavedData.get(server).recipe(id)?.let { Ages.ensure(server, id, it) }
}
