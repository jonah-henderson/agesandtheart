package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation

/**
 * What an Age *is*, as data — the description its world is rebuilt from every time it opens.
 *
 * An Age is not a saved world so much as a saved *sentence*: [AgeSavedData] keeps the recipe, and the
 * dimension is regenerated from it on every open, including after a restart. So this type is the
 * mod's most permanent shape. Everything the Art will eventually resolve — landform, medium, dressing,
 * sky, phenomena — lands here, and a recipe already written must keep meaning what it meant.
 *
 * Today it holds barely more than the generator-kind string it replaces, which is the point: the
 * refactor that introduced it is checkable against terrain that must not move (see
 * `notes/the-art-implementation-plan.md`, Phase 1).
 *
 * **The resolved recipe is what persists, never the words that produced it** (design §4.6). Words will
 * be kept alongside as provenance, but re-resolving them on load would let a change to tag data or
 * preset tuning silently rewrite somebody's existing world.
 */
data class AgeRecipe(
    val preset: AgePreset,
    val seed: Long,
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,
) {
    companion object {
        /**
         * Bumped by hand whenever a change to generation would make the same recipe produce different
         * terrain.
         *
         * Nothing reads it yet. It is stamped now because book editing (design §6.5) reconstructs an
         * Age's *pristine* terrain in order to work out which blocks the player placed, and that
         * subtraction is only meaningful if the generator has not moved underneath it. An Age carrying
         * no stamp could never be told apart from one that matches, so the stamp has to predate the
         * feature that needs it.
         */
        const val CURRENT_GENERATOR_VERSION = 1

        val MAP_CODEC: MapCodec<AgeRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                AgePreset.CODEC.fieldOf("preset").forGetter(AgeRecipe::preset),
                Codec.LONG.fieldOf("seed").forGetter(AgeRecipe::seed),
                // Required, not defaulted, and deliberately so: `optionalFieldOf(name, default)` omits
                // the field whenever it equals the default, so a recipe written today would be read
                // back claiming whatever version is current when it is *read* — which is exactly the
                // question the stamp exists to answer. Ages predating the stamp get one in migration.
                Codec.INT.fieldOf("generator_version").forGetter(AgeRecipe::generatorVersion),
            ).apply(instance, ::AgeRecipe)
        }

        val CODEC: Codec<AgeRecipe> = MAP_CODEC.codec()

        /** A fresh recipe for [preset], seeded from the Age's own id. */
        fun forPreset(preset: AgePreset, id: ResourceLocation): AgeRecipe = AgeRecipe(preset, seedFor(id))

        /**
         * The seed an Age gets when nothing has chosen one for it.
         *
         * Derived from the id because that is what the Fabric backend did before seeds were recipe
         * data, so every Age written under the old scheme keeps the world it already had. Once books
         * carry words, the seed becomes part of what is written rather than a function of the name.
         */
        fun seedFor(id: ResourceLocation): Long = id.hashCode().toLong()
    }
}
