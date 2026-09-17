package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AstriteGolem
import net.minecraft.client.model.animal.golem.IronGolemModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.state.IronGolemRenderState
import net.minecraft.resources.Identifier

/**
 * The golem, drawn as an iron one in a violet cast.
 *
 * **Vanilla's model and vanilla's texture**, which is the same bargain the rime crystal's tint and the
 * skates' leather make: it references Mojang's art rather than shipping any, and the asset pass will give
 * this its own. The tint is what keeps it from reading as an ordinary iron golem in the meantime.
 *
 * The state has to be `IronGolemRenderState` because the model is written against one; the flower and the
 * crackiness on it are simply left alone, this golem having neither.
 */
class AstriteGolemRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<AstriteGolem, IronGolemRenderState, IronGolemModel>(
        context,
        IronGolemModel(context.bakeLayer(ModelLayers.IRON_GOLEM)),
        SHADOW,
    ) {

    override fun createRenderState(): IronGolemRenderState = IronGolemRenderState()

    override fun getTextureLocation(state: IronGolemRenderState): Identifier = BORROWED

    override fun getModelTint(state: IronGolemRenderState): Int = ASTRITE

    companion object {
        private val BORROWED: Identifier = Identifier.withDefaultNamespace("textures/entity/iron_golem/iron_golem.png")

        /**
         * A violet a shade deeper than [co.voik.agesandtheart.content.AgeContent.ASTRITE_TINT], opaque, as an
         * ARGB tint over the borrowed texture.
         */
        private const val ASTRITE = 0xFF8C5CFF.toInt()

        private const val SHADOW = 0.7f
    }
}
