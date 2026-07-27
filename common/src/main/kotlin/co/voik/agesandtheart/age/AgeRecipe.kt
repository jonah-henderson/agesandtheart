package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Landform
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Options
import co.voik.agesandtheart.age.slot.Sky
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.Subsurface
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import java.util.Optional

/**
 * What an Age *is*, as data — the description its world is rebuilt from every time it opens.
 *
 * An Age is not a saved world so much as a saved *sentence*: [AgeSavedData] keeps the recipe, and the
 * dimension is regenerated from it on every open, including after a restart. So this type is the mod's
 * most permanent shape, and a recipe already written must keep meaning what it meant.
 *
 * **The resolved recipe is what persists, never the words that produced it** (design §4.6). Words will
 * be kept alongside as provenance, but re-resolving them on load would let a change to tag data or
 * preset tuning silently rewrite somebody's existing world.
 */
data class AgeRecipe(
    val world: AgeWorld,
    val seed: Long,
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,
) {
    /** The composition this Age was assembled from, or null for the few that are not assembled. */
    val composition: AgeComposition? get() = (world as? AgeWorld.Composed)?.composition

    override fun toString(): String = "$world seed=$seed"

    companion object {
        /**
         * Bumped by hand whenever a change to generation would make the same recipe produce different
         * terrain.
         *
         * Nothing reads it yet. It is stamped because book editing (design §6.5) reconstructs an Age's
         * *pristine* terrain to work out which blocks a player placed, and that subtraction is only
         * meaningful if the generator has not moved underneath it. An Age carrying no stamp could never
         * be told apart from one that matches, so the stamp has to predate the feature that needs it.
         *
         * **2 — slots.** The presets that were whole worlds became compositions of parts, and several
         * demo Ages generate differently as a result.
         */
        const val CURRENT_GENERATOR_VERSION = 2

        val MAP_CODEC: MapCodec<AgeRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                // Optional only so recipes written before slots existed still load; [migrated] supplies
                // the world in that case, and nothing writes a recipe without one.
                AgeWorld.MAP_CODEC.codec().optionalFieldOf("world").forGetter { Optional.of(it.world) },
                // Read for migration only. A recipe written today names its world above.
                AgePreset.CODEC.optionalFieldOf("preset").forGetter { Optional.empty<AgePreset>() },
                Codec.LONG.fieldOf("seed").forGetter(AgeRecipe::seed),
                // Required, not defaulted, and deliberately so: `optionalFieldOf(name, default)` omits
                // the field whenever it equals the default, so a recipe written today would be read
                // back claiming whatever version is current when it is *read* — which is exactly the
                // question the stamp exists to answer.
                Codec.INT.fieldOf("generator_version").forGetter(AgeRecipe::generatorVersion),
            ).apply(instance) { world, legacyPreset, seed, version ->
                AgeRecipe(world.orElseGet { worldFor(legacyPreset.orElse(AgePreset.SPIRE)) }, seed, version)
            }
        }

        val CODEC: Codec<AgeRecipe> = MAP_CODEC.codec()

        /**
         * A fresh recipe for one of the classic demo presets — which now mostly means the composition
         * that describes it. Shares [worldFor] with save migration, so `/age create hills` and an Age
         * written before slots existed cannot drift apart.
         */
        fun of(preset: AgePreset, id: ResourceLocation): AgeRecipe =
            AgeRecipe(worldFor(preset), seedFor(id))

        /**
         * The seed an Age gets when nothing has chosen one for it.
         *
         * Derived from the id because that is what the Fabric backend did before seeds were recipe
         * data, so every Age written under the old scheme keeps the world it already had. Once books
         * carry words, the seed becomes part of what is written rather than a function of the name.
         */
        fun seedFor(id: ResourceLocation): Long = id.hashCode().toLong()

        /**
         * The world a classic preset names — which is also what a pre-slots recipe migrates to.
         *
         * Faithful in structure rather than block-for-block: several of these Ages generate differently
         * now, because composing them honestly means giving each slot the preset that *describes* it
         * rather than reproducing whatever the hand-written bundle happened to do. Jonah's call, on the
         * grounds that all twelve are demos rather than worlds anyone lives in.
         *
         * The three that are not compositions at all stay themselves.
         */
        fun worldFor(preset: AgePreset): AgeWorld {
            val composition = when (preset) {
                AgePreset.SPIRE, AgePreset.VANILLA, AgePreset.VANILLA_BARE -> return AgeWorld.Bespoke(preset)

                // The Spire islands as a field tree: weathered rock over its green sea, under its own sky.
                AgePreset.FIELD -> AgeComposition(
                    landform = Landform.SPIRE_ISLANDS,
                    medium = Medium.SEA,
                    subsurface = Subsurface.WEATHERED,
                    dressing = Dressing.PLASMA,
                    sky = Sky.STORM,
                )
                AgePreset.PYRAMIDS -> pyramids("grid")
                AgePreset.PYRINGS -> pyramids("rings")
                AgePreset.PYRVARIED -> pyramids("varied")
                AgePreset.SHAPES -> AgeComposition(landform = Landform.SHAPES)
                AgePreset.PILLARS -> AgeComposition(landform = Landform.PILLARS, medium = Medium.SEA)
                AgePreset.ERODED -> AgeComposition(landform = Landform.ERODED, medium = Medium.SEA)
                AgePreset.HILLS -> AgeComposition(
                    landform = Landform.HILLS,
                    medium = Medium.SEA,
                    subsurface = Subsurface.CAVES,
                    dressing = Dressing.OVERWORLD,
                    options = SlotOptions().with(
                        Slot.DRESSING,
                        Options(mapOf(Dressing.SETTLEMENT.name to "vanilla")),
                    ),
                )
                // Its caves are its shape, so nothing is carved — but the rock still runs wet and dry.
                AgePreset.CAVERNS -> AgeComposition(
                    landform = Landform.CAVERNS,
                    medium = Medium.SEA,
                    subsurface = Subsurface.POROUS,
                    dressing = Dressing.OVERWORLD,
                )
            }
            return AgeWorld.Composed(composition)
        }

        private fun pyramids(arrangement: String) = AgeComposition(
            landform = Landform.PYRAMIDS,
            options = SlotOptions().with(
                Slot.LANDFORM,
                Options(mapOf(Landform.ARRANGEMENT.name to arrangement)),
            ),
        )
    }
}
