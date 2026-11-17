package co.voik.agesandtheart.imaging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Imaging {
    const val MOD_ID = "agesandtheart_imaging"
    val LOG: Logger = LoggerFactory.getLogger("AgesAndTheArtImaging")

    fun init() {
        LOG.info("Ages and the Art: Imaging is loaded")
    }
}
