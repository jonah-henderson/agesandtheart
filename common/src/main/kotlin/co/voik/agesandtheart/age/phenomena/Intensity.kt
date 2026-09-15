package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer

/**
 * How hard an inferno comes — **datapack content** (`art/phenomenon/inferno.json`).
 *
 * Content rather than config (`notes/config-research.md`): a pack where an inferno burns a forest down in a
 * minute is a different mod rather than the same one run differently. It is also the surface another mod
 * reaches for, which is why these are files rather than constants — the numbers here are the ones only a
 * walk can settle, and they should be movable without a build.
 *
 * **Everything is a rate against [Sampling.BETWEEN_CHUNK_SAMPLES]**, so a number here can be reasoned about:
 * `1.0` is "every position vanilla would have offered a snowfall", and the ordinary values are well below it.
 */
data class Intensity(
    /** How many positions per chunk the phenomenon asks for, against vanilla's precipitation rate. */
    val reach: Double = DEFAULT_REACH,
    /** What share of those positions it acts on. */
    val chance: Double = DEFAULT_CHANCE,
    /** What it does to anything it catches, where that means anything — half a heart is `1.0`. */
    val harm: Double = DEFAULT_HARM,
    /** How often it harms, in ticks, so the rate is legible beside the amount. */
    val betweenHarms: Int = DEFAULT_BETWEEN_HARMS,
) {
    /** How many times a chunk is offered up per sweep — at least once, or a small reach would never act. */
    val sweeps: Int get() = reach.toInt().coerceAtLeast(1)

    companion object {
        private const val DEFAULT_REACH = 1.0
        private const val DEFAULT_CHANCE = 0.35
        private const val DEFAULT_HARM = 1.0
        private const val DEFAULT_BETWEEN_HARMS = 20

        /** What a phenomenon nobody tuned comes at — so an absent file is a default, never a dead one. */
        val ORDINARY = Intensity()

        val CODEC: Codec<Intensity> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("reach", DEFAULT_REACH).forGetter(Intensity::reach),
                Codec.DOUBLE.optionalFieldOf("chance", DEFAULT_CHANCE).forGetter(Intensity::chance),
                Codec.DOUBLE.optionalFieldOf("harm", DEFAULT_HARM).forGetter(Intensity::harm),
                Codec.INT.optionalFieldOf("between_harms", DEFAULT_BETWEEN_HARMS).forGetter(Intensity::betweenHarms),
            ).apply(instance, ::Intensity)
        }

        private val FILE = PhenomenonFile(Phenomenon.INFERNO, CODEC, ORDINARY)

        /** What an inferno comes at on this server. */
        fun of(server: MinecraftServer): Intensity = FILE.of(server)
    }
}
