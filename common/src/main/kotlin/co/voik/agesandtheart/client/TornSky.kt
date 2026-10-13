package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.TearsPayload
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyMoment
import co.voik.ephemeris.client.SkyShapes

/**
 * The tears in the sky of the Age you stand in, drawn as shapes of ours on Ephemeris's sky — a backdrop, so they
 * hang far off behind the sun, moon and stars and turn with the stars, whatever sky the Age was written with.
 * Weather dims them.
 */
object TornSky {

    private var told: TearsPayload? = null
    private var laidOutFor: TearsPayload? = null
    private var tears: List<Tear> = emptyList()

    fun register() {
        LevelRendering.skyBackdrop(::shapesFor)
    }

    fun remember(payload: TearsPayload) {
        told = payload
    }

    fun forget() {
        told = null
    }

    private fun shapesFor(moment: SkyMoment): List<SkyShapes> {
        val torn = told?.takeIf { it.count > 0 && it.age == moment.level.dimension().identifier() } ?: return emptyList()
        if (torn != laidOutFor) {
            tears = TearShapes.layout(torn.seed, torn.count)
            laidOutFor = torn
        }
        val ticks = moment.level.gameTime + moment.partOfATickOn.toDouble()
        return TearShapes.shapesAt(tears, ticks, torn.style, moment.rainBrightness, moment.starTurn)
    }
}
