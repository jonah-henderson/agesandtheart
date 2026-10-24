package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.age.reward.PaperTreeWindow
import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.worldgen.ruins.CavernRuins
import net.minecraft.server.MinecraftServer

/**
 * The rewards an Age earns, by name — each read the way generation reads it, so this cannot promise what the
 * ground does not hold.
 *
 * **Asked of every book the Art writes**, which must earn none (Jonah, 2026-10-23): a found Age differs from a
 * written one only in what its grammar could say, so a reward is kept from loot by no generated book ever meeting
 * its conditions, never by who wrote the book. The drying skies are not among them: they give nothing to a player
 * who has not already made the wet paper or the ink cake, and working out what they need is the puzzle.
 */
object Rewards {

    /** Every reward [recipe] earns on [server], in no particular order of worth. */
    fun earnedBy(server: MinecraftServer, recipe: AgeRecipe): List<String> {
        val composition = recipe.composition ?: return emptyList()
        val danger = Danger.of(server, recipe)
        val sky = AgeGeneration.skySpec(recipe)
        return buildList {
            if (danger.paysOut) add(DERETHENI)
            if (CavernRuins.qualifies(composition, danger)) add(CITY)
            if (PaperTreeWindow.read(recipe, sky).isMet) add(YEMA)
            if (ScarabHabitat.colonyLayer(server.registryAccess(), recipe) != null) add(SCARABS)
        }
    }

    private const val DERETHENI = "deretheni"
    private const val CITY = "d'ni city"
    private const val YEMA = "yema"
    private const val SCARABS = "scarab colonies"
}
