package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.aspect.AgeParts
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.ephemeris.RuntimeLevelEvents
import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelLook
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * What the skies of this server's Ages look like — **the Art's thin end of `LevelAppearance`**.
 *
 * Telling clients is the library's, and so is remembering: what is left here is deciding, which is the only
 * part that is the Art's business. `LevelAppearance.give` both records an answer and sends it, so there is no
 * separate "tell them" call at each travel site to keep in step with the ones that decide.
 *
 * Derived, never stored: every look here comes from the recipe through pure functions, so an Age rebuilt on
 * the next boot looks the same and two clients told at different moments are told the same thing.
 */
object Skies {

    /**
     * Dress every Age as it opens, however it came to be open.
     *
     * On the library's own event rather than at each call site, which is what makes the replay on boot and
     * the first write take exactly the same path — the case `reloadSaved` used to miss by opening the
     * backend directly.
     */
    fun attach() {
        RuntimeLevelEvents.whenOpened(::describe)
    }

    /** Says what an Age looks like, and tells everyone who should know. Silent for a level that is not ours. */
    fun describe(level: ServerLevel) {
        val look = lookOf(level.server, level.dimension()) ?: return
        LevelAppearance.give(level, look)
    }

    /**
     * How the Age at [dimension] looks, or null when that dimension is not an Age of ours. An ordinary sky is
     * still an answer, because the renderer treats an unknown Age and an ordinary one differently and
     * conflating them would make a lost packet look deliberate.
     */
    fun lookOf(server: MinecraftServer, dimension: ResourceKey<Level>): LevelLook? {
        val saved = AgeSavedData.get(server)
        val id = dimension.identifier()
        if (id !in saved.ages) return null
        val recipe = saved.recipe(id)
        // One reader over the whole composition, because the look is assembled from several aspects now —
        // the water's clarity, the air's fog and tint, the vault's colour and cloud.
        val parts = recipe.composition ?: AgeParts.NONE
        // The sky preset's own palette goes **underneath**: it is what the Age looks like before anyone said
        // anything, so a writer who repaints one colour of a Spire-skied Age keeps the rest. And under *that*
        // whatever the Age's own switches insist on — a lightless Age is dark to look at as well as to stand in.
        val painted = AgeGeneration.presetLook(recipe).over(Atmosphere.unlitLook(parts, recipe.template))
        return LevelLook(
            AgeGeneration.skySpec(recipe),
            Atmosphere.lookIn(parts, recipe.seed).over(painted),
            Atmosphere.cornersOf(parts).associateWith { Atmosphere.lookIn(parts, recipe.seed, it).over(painted) },
        )
    }
}
