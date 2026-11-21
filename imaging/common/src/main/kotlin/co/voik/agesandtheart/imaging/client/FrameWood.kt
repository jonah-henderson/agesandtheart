package co.voik.agesandtheart.imaging.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.world.level.block.Block

/** The texture a picture's frame is drawn in: its planks' own, as their block model draws its particles. */
object FrameWood {
    fun spriteOf(planks: Block): TextureAtlasSprite =
        Minecraft.getInstance().modelManager.blockStateModelSet.getParticleMaterial(planks.defaultBlockState()).sprite()
}
