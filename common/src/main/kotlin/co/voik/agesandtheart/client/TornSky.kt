package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.TearsPayload
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyMoment

/**
 * The tears in the sky of the Age you stand in, drawn as shapes of ours on Ephemeris's sky — an overlay, as a
 * meteor storm is, so the Age keeps whatever sky it was written with underneath. Weather dims them.
 */
object TornSky {

    private var told: TearsPayload? = null
    private var laidOutFor: TearsPayload? = null
    private var tears: List<Tear> = emptyList()

    fun register() {
        LevelRendering.skyOverlay(::draw)
    }

    fun remember(payload: TearsPayload) {
        told = payload
    }

    fun forget() {
        told = null
    }

    private fun draw(moment: SkyMoment) {
        val torn = told?.takeIf { it.count > 0 && it.age == moment.level.dimension().identifier() } ?: return
        if (torn != laidOutFor) {
            tears = TearShapes.layout(torn.seed, torn.count)
            laidOutFor = torn
        }
        val ticks = moment.level.gameTime + moment.partOfATickOn.toDouble()
        for ((blend, quads) in TearShapes.quadsAt(tears, ticks, torn.style, moment.rainBrightness)) {
            Blaze3dSkyCanvas.drawShapes(quads, blend)
        }
    }
}
