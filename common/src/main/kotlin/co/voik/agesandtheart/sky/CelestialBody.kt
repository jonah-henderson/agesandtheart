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
 * The three are separate on purpose. Orbits are the interesting part and the part the writer is really choosing
 * between; appearance is the part most likely to change when the mod grows a texture pipeline; and a phase is a
 * property only some bodies have. Keeping them apart is what lets [Appearance] gain a variant without any of the
 * orbital maths, the codec shape or the vocabulary noticing (see the plan's Tier 1 notes).
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
 * **Tier 1 ships [Sprite] only, and it borrows vanilla's own two textures** (Jonah, 2026-07-29: *"can we reuse
 * vanilla's texture for our moons for now?"*). `textures/environment/sun.png` (32×32) and `moon_phases.png`
 * (128×64, a 4×2 grid of 32×32 tiles) both ship in the jar, `RenderSystem.setShaderTexture(int,
 * ResourceLocation)` is public, and `TextureManager.getTexture` loads on demand — so this needs no asset
 * directory, no access widener and no atlas of our own.
 *
 * It also buys the thing a coloured disc could not: **real crescents.** A phase is a *shape*, vanilla makes it by
 * picking a sub-rectangle of the moon atlas, and under additive blending a flat disc has nothing to subtract
 * darkness with. Borrowing the atlas gets the shape for free. See [PhaseCycle].
 *
 * The one consequence to know: a resource pack that retextures the moon retextures ours too. That is arguably
 * right — an Age should look like it belongs to the player's Minecraft — but it is a coupling, not an accident.
 *
 * Kept **sealed** with a dispatching codec even at one variant, because appearance is where growth is expected:
 * our own textures when the asset pipeline exists for custom mobs, and plausibly a procedural form for bodies
 * that are neither sun nor moon. A new variant is a key and a [MapCodec], and the renderer batches by vertex
 * format, so nothing above this type has to change.
 */
sealed interface Appearance {
    val tint: Rgba

    /** Half-extent of the body as drawn, in the same units as [Orbit.distance]. Vanilla's sun is 30, moon 20. */
    val angularSize: Float

    /** The codec dispatch key. Adding a variant means adding a key and a [MapCodec], nothing more. */
    val kindKey: String

    /**
     * A textured quad, tinted.
     *
     * [columns] × [rows] divides the texture into equal cells, which is how a phase picks its shape — vanilla's
     * moon is 4×2 and its sun is the degenerate 1×1. A body whose [CelestialBody.phase] has more steps than
     * there are cells would index past the atlas, so `:common:skycheck` holds the two in agreement.
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
         * Dispatched on a `kind` field, so a future variant is additive rather than a format break.
         *
         * An unknown kind falls back to [Sprite]'s codec, which then fails on the missing fields — a loud
         * failure at the right place, rather than a silent one here.
         */
        val CODEC: Codec<Appearance> = Codec.STRING.dispatch(
            "kind",
            Appearance::kindKey,
        ) { key -> KINDS[key] ?: Sprite.MAP_CODEC }
    }
}

/**
 * A body that waxes and wanes.
 *
 * **A phase is a shape, and borrowing vanilla's moon atlas is what makes that possible.** An earlier draft made
 * a phase a *brightness*, because Tier 1 was going to draw untextured discs and a flat disc under additive
 * blending has nothing to subtract darkness with — a crescent is a disc with a bite taken out of it, and there
 * was nothing to bite with. Reusing `moon_phases.png` dissolved that: the shape is in the texture, so this only
 * has to say *which cell*.
 *
 * [steps] is how many distinct shapes the cycle passes through, and it must match the sprite's cell count or
 * [stepAt] indexes past the atlas — `:common:skycheck` holds them in agreement. Vanilla's eight is a default
 * rather than a rule: nothing outside the renderer can observe a phase, since `getMoonPhase()` is consulted only
 * by `LevelRenderer.renderSky`, so a moon may have its own count once we have a texture with its own grid.
 */
data class PhaseCycle(val periodTicks: Int, val offsetTicks: Int, val steps: Int = VANILLA_PHASES) {

    /**
     * Which shape this body is showing, in `0..<steps`.
     *
     * Quantised rather than continuous so the stepping is legible instead of an imperceptible crawl — the same
     * reason vanilla has eight discrete phases rather than a smooth terminator.
     *
     * A brightness form, if a future untextured body ever wants one, is `0.5 + 0.5·cos(2π · step / steps)` — the
     * continuous shape of vanilla's `MOON_BRIGHTNESS_PER_PHASE` table, which passes through 1.0, 0.5 and 0.0 at
     * the same points its eight entries do. Not written until something needs it.
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
