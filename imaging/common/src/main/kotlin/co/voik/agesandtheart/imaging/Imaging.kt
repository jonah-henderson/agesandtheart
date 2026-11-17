package co.voik.agesandtheart.imaging

import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Imaging {
    const val MOD_ID = "agesandtheart_imaging"
    val LOG: Logger = LoggerFactory.getLogger("AgesAndTheArtImaging")

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
