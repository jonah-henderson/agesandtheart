package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SunLight
import co.voik.ephemeris.sky.SkySpec
import com.mojang.serialization.Codec
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import io.netty.buffer.ByteBuf

/**
 * What an Age must be for the drying rack to finish a masterwork grade in it (design §7.1.2): something no
 * vanilla dimension is, so the last step of the finest ink and paper is a world written for it. Read off
 * the recipe, like every other reward, and only of an Age a player wrote.
 */
enum class DryingSky(private val key: String) : StringRepresentable {
    /** Ink cakes cure under a black sun (Jonah): the ink takes its colour from the light it cures under. */
    BLACK_SUN("black_sun"),

    /**
     * Wet sheets dry in the air of an Age with an open lava sea, anywhere in it — not beside the lava, as
     * temperstone bakes (Jonah). An unroofed lava sea is in no vanilla dimension.
     */
    LAVA_SEA("lava_sea"),
    ;

    fun isMetBy(recipe: AgeRecipe, sky: SkySpec): Boolean {
        if (!recipe.authored) return false
        val composition = recipe.composition ?: return false
        return when (this) {
            BLACK_SUN -> hasABlackSun(sky)
            LAVA_SEA -> composition.seas.any { sea -> !sea.isEmpty && sea.id == LAVA } && !Sky.isRoofed(composition)
        }
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<DryingSky> = StringRepresentable.fromEnum(DryingSky::values)

        val STREAM_CODEC: StreamCodec<ByteBuf, DryingSky> = ByteBufCodecs.VAR_INT.map({ entries[it] }, DryingSky::ordinal)

        private val LAVA: Identifier = Identifier.withDefaultNamespace("lava")

        /** Whether the deciding sun — the first, which daylight follows — burns black. */
        fun hasABlackSun(sky: SkySpec): Boolean = SunLight.burnsBlack(sky)
    }
}
