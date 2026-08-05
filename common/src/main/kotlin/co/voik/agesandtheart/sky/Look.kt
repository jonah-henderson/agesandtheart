package co.voik.agesandtheart.sky

import co.voik.agesandtheart.math.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * What an Age looks like to the eye — the half of `Atmosphere` a server cannot decide alone.
 *
 * Every field is **null where the writer said nothing**, and a null is not a colour: it means "whatever the
 * layer below produced", which is how one Age can repaint its sky and leave its fog exactly as vanilla
 * lit it.
 *
 * [haze] and [ceiling] are fractions of their own axis rather than distances in blocks. A writer says how
 * thick the air is; how many blocks that is belongs to the client, which is the only place that knows what
 * the render distance is.
 */
data class Look(
    val sky: Rgba? = null,
    val fog: Rgba? = null,
    val cloud: Rgba? = null,
    val tint: Rgba? = null,
    /** The particle that hangs in the air, by its registry id — see `Motes`. */
    val motes: String? = null,
    val haze: Float? = null,
    val ceiling: Float? = null,
    val murk: Float? = null,
) {
    val saysNothing: Boolean
        get() = sky == null && fog == null && cloud == null && tint == null &&
            motes == null && haze == null && ceiling == null && murk == null

    /**
     * This look over [under] — every colour of ours that was named, and [under]'s where it was not.
     *
     * Which way round matters: a preset's palette is what an Age looks like *before* anyone said anything,
     * so it goes underneath, and a writer who repaints the sky of a Spire-skied Age keeps its clouds.
     */
    fun over(under: Look): Look = Look(
        sky = sky ?: under.sky,
        fog = fog ?: under.fog,
        cloud = cloud ?: under.cloud,
        tint = tint ?: under.tint,
        motes = motes ?: under.motes,
        haze = haze ?: under.haze,
        ceiling = ceiling ?: under.ceiling,
        murk = murk ?: under.murk,
    )

    companion object {
        val NOTHING = Look()

        val CODEC: Codec<Look> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.optionalFieldOf("sky").forGetter { java.util.Optional.ofNullable(it.sky) },
                Rgba.CODEC.optionalFieldOf("fog").forGetter { java.util.Optional.ofNullable(it.fog) },
                Rgba.CODEC.optionalFieldOf("cloud").forGetter { java.util.Optional.ofNullable(it.cloud) },
                Rgba.CODEC.optionalFieldOf("tint").forGetter { java.util.Optional.ofNullable(it.tint) },
                Codec.STRING.optionalFieldOf("motes").forGetter { java.util.Optional.ofNullable(it.motes) },
                Codec.FLOAT.optionalFieldOf("haze").forGetter { java.util.Optional.ofNullable(it.haze) },
                Codec.FLOAT.optionalFieldOf("ceiling").forGetter { java.util.Optional.ofNullable(it.ceiling) },
                Codec.FLOAT.optionalFieldOf("murk").forGetter { java.util.Optional.ofNullable(it.murk) },
            ).apply(instance) { sky, fog, cloud, tint, motes, haze, ceiling, murk ->
                Look(
                    sky.orElse(null),
                    fog.orElse(null),
                    cloud.orElse(null),
                    tint.orElse(null),
                    motes.orElse(null),
                    haze.orElse(null),
                    ceiling.orElse(null),
                    murk.orElse(null),
                )
            }
        }
    }
}
