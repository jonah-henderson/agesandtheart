package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.ephemeris.Rgba
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3fc

/**
 * What being near a wound does to the air (design §5.1).
 *
 * **A gradient with a source and a direction**, which is the whole of why it is not just an unpleasant Age:
 * the dread thickens as you approach and thins as you leave, so a player can navigate by it and find the
 * thing that is wrong. An Age uniformly grim would say the same thing everywhere and so say nothing.
 *
 * **Positional layers, not a post-processing filter** (researched 2026-08-07). A post chain is one shader
 * over the whole frame: it cannot know how near the wound is without uniforms driven per frame, and
 * `GameRenderer.setPostEffect` is private, so reaching it would cost an access widener on both loaders to
 * buy something that is on or off. `addPositionalLayer` is handed a *position* and returns a value, which
 * is a gradient by construction, and the Age's air is already built out of these ([AgeAir]).
 *
 * **Darker and desaturated**, which are two different attributes doing two different jobs: the fog closes
 * in and goes black so the world shrinks around you, and the light loses its colour so what is left of it
 * looks wrong rather than merely dim.
 */
object Corruption {

    /**
     * Lays the corruption over whatever the Age's own air said.
     *
     * **After [AgeAir], deliberately.** A wound is not part of the Age's weather — it is what the Age could
     * not hold — so it darkens whatever was there rather than being blended into it, and an Age with a
     * lurid sky still goes black at the throat of a tear.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        // The world going black around you — the fog's colour and the sky's, so it closes in rather than
        // merely darkening overhead.
        layers.corrupting(level, EnvironmentAttributes.FOG_COLOR) { was, how -> was.dimmed(1.0f - how).rgb() }
        layers.corrupting(level, EnvironmentAttributes.SKY_COLOR) { was, how -> was.dimmed(1.0f - how).rgb() }

        // And the light losing its *colour*, which is what desaturation actually is: what reaches you is
        // grey rather than tinted, so a place looks wrong before it looks dark.
        layers.corrupting(level, EnvironmentAttributes.SKY_LIGHT_COLOR) { was, how ->
            was.drained(how).dimmed(1.0f - how).rgb()
        }
        layers.corrupting(level, EnvironmentAttributes.BLOCK_LIGHT_TINT) { was, how -> was.drained(how).rgb() }

        // Night vision is not touched at all — see [yieldsToNightVision].

        // The fog coming *in*, which is what makes the world shrink rather than only dim.
        layers.closingIn(level, EnvironmentAttributes.FOG_END_DISTANCE)
        layers.closingIn(level, EnvironmentAttributes.FOG_START_DISTANCE)
        return layers
    }

    /**
     * One colour attribute, bent toward its corrupted self by how near the nearest wound is.
     *
     * The guard matters more than it looks: **almost every position in almost every Age is uncorrupted**,
     * and this runs many times a frame, so the common case has to be one distance check and a return of
     * exactly what vanilla said.
     */
    private fun EnvironmentAttributeSystem.Builder.corrupting(
        level: ClientLevel,
        attribute: net.minecraft.world.attribute.EnvironmentAttribute<Vector3fc>,
        bend: (Rgba, Float) -> Vector3fc,
    ) {
        addPositionalLayer(attribute) { was, at, _ ->
            val how = if (yieldsToNightVision()) Wounds.NONE else Wounds.corruptionAt(level, at)
            if (how <= Wounds.NONE) was else bend(Rgba.of(was), how.toFloat())
        }
    }

    /**
     * Whether the corruption should keep out of the way entirely — **it yields to night vision, completely**
     * (Jonah, 2026-08-09, walked).
     *
     * The previous answer was to drain the night-vision colour along with the other two lights, on the
     * grounds that a light left untouched among drained ones reads as the two fighting for the frame. It
     * does — but draining it reads worse: the walk found "awful flickering and night vision cutting out when
     * nearby", because the effect's own brightening and our darkening argue over the same frames.
     *
     * So the register simply steps aside. A player who has chosen to see in the dark sees in the dark, and
     * loses the visual half of the gradient while it lasts. **Nothing about the danger yields with it** —
     * [co.voik.agesandtheart.age.consequence.Hostility] is server-side and reads the wound, not the picture,
     * so drinking a potion makes a wound less dreadful and no less lethal. That is a fair trade rather than
     * an exploit, and it is the reason this can be as blunt as it is.
     */
    private fun yieldsToNightVision(): Boolean =
        Minecraft.getInstance().player?.hasEffect(MobEffects.NIGHT_VISION) == true

    /** One fog distance, pulled toward the eye — never past it, so a wound blinds nobody outright. */
    private fun EnvironmentAttributeSystem.Builder.closingIn(
        level: ClientLevel,
        attribute: net.minecraft.world.attribute.EnvironmentAttribute<Float>,
    ) {
        addPositionalLayer(attribute) { was, at, _ ->
            val how = if (yieldsToNightVision()) Wounds.NONE else Wounds.corruptionAt(level, at)
            if (how <= Wounds.NONE) was else (was * (1.0 - how) + NEAREST * how).toFloat().coerceAtLeast(NEAREST)
        }
    }

    /**
     * How far you can see at the very throat of a wound, in blocks. Close, and not blind.
     *
     * Back to 6 after the second walk: four was more than the register wanted, and the throat reads as
     * dreadful rather than as blinding at six.
     */
    private const val NEAREST = 6.0f
}
