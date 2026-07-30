package co.voik.agesandtheart.sky

import co.voik.agesandtheart.math.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth

/**
 * One sun or moon: where it goes ([orbit]), what it looks like ([appearance]), and whether it waxes and wanes
 * ([phase]).
 *
 * Separate on purpose: appearance is the part most likely to change when the mod grows a texture
 * pipeline, and a phase is a property only some bodies have, so [Appearance] can gain a variant without
 * the orbital maths or the codec shape noticing.
 */
data class CelestialBody(
    val orbit: Orbit,
    val appearance: Appearance,
    /** Null for a body that never changes, which is every sun. */
    val phase: PhaseCycle? = null,
) {
    companion object {
        val CODEC: Codec<CelestialBody> = RecordCodecBuilder.create { instance ->
            instance.group(
                Orbit.CODEC.fieldOf("orbit").forGetter(CelestialBody::orbit),
                Appearance.CODEC.fieldOf("appearance").forGetter(CelestialBody::appearance),
                PhaseCycle.CODEC.optionalFieldOf("phase").forGetter { body -> java.util.Optional.ofNullable(body.phase) },
            ).apply(instance) { orbit, appearance, phase -> CelestialBody(orbit, appearance, phase.orElse(null)) }
        }
    }
}

/**
 * What a body looks like.
 *
 * **[Sprite] borrows vanilla's own two textures** — `sun.png` and `moon_phases.png` both ship in the jar
 * and load on demand, so this needs no asset directory, no access widener and no atlas of our own. It
 * also buys **real crescents**: a phase is a *shape*, and under additive blending a flat disc has nothing
 * to subtract darkness with, so borrowing the atlas gets it for free.
 *
 * One consequence: a resource pack that retextures the moon retextures ours too.
 *
 * **Sealed** with a dispatching codec even at one variant, since a new one is a key and a [MapCodec] and
 * the renderer batches by vertex format.
 */
sealed interface Appearance {
    val tint: Rgba

    /** Half-extent of the body as drawn, in the same units as [Orbit.distance]. Vanilla's sun is 30, moon 20. */
    val angularSize: Float

    /** The codec dispatch key. Adding a variant means adding a key and a [MapCodec], nothing more. */
    val kindKey: String

    /**
     * A textured quad, tinted. [columns] × [rows] divides the texture into equal cells, which is how a
     * phase picks its shape — vanilla's moon is 4×2 and its sun the degenerate 1×1. A body whose
     * [CelestialBody.phase] has more steps than cells would index past the atlas; `SkyCheck` holds them.
     */
    data class Sprite(
        override val tint: Rgba,
        override val angularSize: Float,
        val texture: ResourceLocation,
        val columns: Int = 1,
        val rows: Int = 1,
    ) : Appearance {
        override val kindKey: String get() = SPRITE

        /** How many distinct shapes this texture can show. */
        val cells: Int get() = columns * rows

        companion object {
            val MAP_CODEC: MapCodec<Sprite> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Rgba.CODEC.optionalFieldOf("tint", Rgba.WHITE).forGetter(Sprite::tint),
                    Codec.FLOAT.fieldOf("size").forGetter(Sprite::angularSize),
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(Sprite::texture),
                    Codec.INT.optionalFieldOf("columns", 1).forGetter(Sprite::columns),
                    Codec.INT.optionalFieldOf("rows", 1).forGetter(Sprite::rows),
                ).apply(instance, ::Sprite)
            }
        }
    }

    companion object {
        const val SPRITE = "sprite"

        /** Vanilla's own sun: one cell, no phases. */
        val SUN_TEXTURE: ResourceLocation =
            ResourceLocation.withDefaultNamespace("textures/environment/sun.png")

        /** Vanilla's moon atlas: eight phases as a 4×2 grid, which is why [MOON_COLUMNS] × [MOON_ROWS] is 8. */
        val MOON_TEXTURE: ResourceLocation =
            ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png")

        const val MOON_COLUMNS = 4
        const val MOON_ROWS = 2

        private val KINDS: Map<String, MapCodec<out Appearance>> = mapOf(SPRITE to Sprite.MAP_CODEC)

        /**
         * Dispatched on a `kind` field, so a future variant is additive rather than a format break. An
         * unknown kind falls back to [Sprite]'s codec and fails there on the missing fields, which is a
         * loud failure in the right place rather than a silent one here.
         */
        val CODEC: Codec<Appearance> = Codec.STRING.dispatch(
            "kind",
            Appearance::kindKey,
        ) { key -> KINDS[key] ?: Sprite.MAP_CODEC }
    }
}

/**
 * A body that waxes and wanes. **A phase is a shape, not a brightness** — the shape lives in
 * `moon_phases.png`, so this only says *which cell*.
 *
 * [steps] must match the sprite's cell count or [stepAt] indexes past the atlas; `SkyCheck` holds them.
 * Vanilla's eight is a default rather than a rule, nothing outside the renderer being able to observe a
 * phase.
 */
data class PhaseCycle(val periodTicks: Int, val offsetTicks: Int, val steps: Int = VANILLA_PHASES) {

    /**
     * Which shape this body is showing, in `0..<steps`. Quantised rather than continuous so the stepping
     * is legible instead of an imperceptible crawl, which is why vanilla has eight discrete phases.
     */
    fun stepAt(dayTime: Long): Int {
        if (steps <= 1) return 0
        val revolutions = (dayTime.toDouble() + offsetTicks) / periodTicks
        return (Mth.frac(revolutions) * steps).toInt().coerceIn(0, steps - 1)
    }

    companion object {
        const val VANILLA_PHASES = 8

        val CODEC: Codec<PhaseCycle> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("period").forGetter(PhaseCycle::periodTicks),
                Codec.INT.optionalFieldOf("offset", 0).forGetter(PhaseCycle::offsetTicks),
                Codec.INT.optionalFieldOf("steps", VANILLA_PHASES).forGetter(PhaseCycle::steps),
            ).apply(instance, ::PhaseCycle)
        }
    }
}
