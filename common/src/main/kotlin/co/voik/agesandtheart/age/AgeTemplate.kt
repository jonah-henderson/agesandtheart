package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import com.mojang.serialization.Codec
import net.minecraft.resources.ResourceKey
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings

/**
 * **The world a book starts from** (`the-world-model.md` §4).
 *
 * Every property has an answer before a sentence is read, and this is where it comes from. That is what
 * lets `An Age` be a complete book: silence is not neutral, it is overworld-shaped, and a world nobody
 * described is a world like the one they came from.
 *
 * **A template is a whole world we wrote; the catalogue is what a writer can reach**, and only the second
 * has to be complete. The nether's terrain is not one of the shapes a page can name and need not become
 * one — which is what lets a template be arbitrarily bespoke without every part of it becoming vocabulary.
 * The three below are built out of what the toolkit has today and will get their own generators in time;
 * being recognisable is the bar, not being vanilla.
 *
 * **It does not persist**, and that is §4.6 rather than an omission: what a template supplies is merged
 * into the composition before the recipe is written, so an Age is rebuilt from the answer rather than from
 * the question. Retuning a template therefore changes what *new* Ages are like and can never reach one
 * already written.
 */
enum class AgeTemplate(val key: String, val rock: ResourceKey<NoiseGeneratorSettings>) : StringRepresentable {
    /** What a world is like when nobody said otherwise. No word names it; it is what you get. */
    OVERWORLD("overworld", NoiseGeneratorSettings.OVERWORLD) {
        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.OVERWORLD),
            seas = listOf(Sea.WATER),
            carvers = listOf(Carvers.CAVES),
        )
    },

    /**
     * A world that burns, sealed over and lit by nothing — vanilla's own nether rock under it.
     */
    INFERNAL("infernal", NoiseGeneratorSettings.NETHER) {
        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.CAVERNS),
            seas = listOf(Sea.LAVA),
            carvers = listOf(Carvers.SOLID),
        )
            .withOptions(Aspect.SKY, Sky.SEALED.name, listOf("always"))
            .withOptions(Aspect.SUN, Sky.SHINING.name, listOf(Sky.NEVER))
            .withOptions(Aspect.AIR, Atmosphere.FOG.name, listOf("red"))
            .withOptions(Aspect.CLIMATE, ClimateAxis.TEMPERATURE.key, listOf("0.7..1.0"))
    },

    /**
     * Islands in nothing, under a sky with no sun in it — vanilla's own end rock under it.
     *
     * Not sealed: the end is open overhead and simply has nothing up there, which is a different fact from
     * the nether's and one the derived rules keep apart (`Sky.dimensionType`).
     */
    DARK_VOID("dark_void", NoiseGeneratorSettings.END) {
        override fun world(): AgeComposition = AgeComposition(
            terrains = listOf(Terrain.ISLANDS),
            seas = listOf(Sea.NONE),
            carvers = listOf(Carvers.SOLID),
        )
            .withOptions(Aspect.SUN, Sky.SHINING.name, listOf(Sky.NEVER))
            .withOptions(Aspect.SKY, Atmosphere.SKY.name, listOf("black"))
            .withOptions(Aspect.AIR, Atmosphere.FOG.name, listOf("purple"))
    },
    ;

    /** The world this starts from. Built on demand, so no two Ages can share a mutable one. */
    abstract fun world(): AgeComposition

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AgeTemplate> = StringRepresentable.fromEnum(AgeTemplate::values)

        /** The template a book that named none starts from. */
        val ORDINARY = OVERWORLD

        /** The template [key] names, or null where it names none. */
        fun named(key: String): AgeTemplate? = entries.firstOrNull { it.key == key }
    }
}
