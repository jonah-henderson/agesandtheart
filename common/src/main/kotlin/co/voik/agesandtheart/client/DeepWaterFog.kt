package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.DeepWater
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.phys.Vec3

/**
 * What being *in* deep water does to what you can see (design §7.1.2).
 *
 * **The abyss biome cannot carry this on its own, measured 2026-09-10.** A biome's attributes reach the
 * renderer through `EnvironmentAttributeProbe`, which Gaussian-samples the biomes in a **6×6×6 block of
 * quarts** — eight blocks out along every axis, the vertical included — and blends what it finds. Half of
 * that neighbourhood at the floor of an abyss is the rock *under* the sea bed, whose biomes are ordinary
 * cave biomes carrying no water fog at all, so vanilla's defaults are blended back in at roughly their
 * weight: a diver eight blocks off the bottom was seeing about `0.58 × 10 + 0.42 × 96` ≈ **46 blocks** of
 * visibility where the biome asked for ten. No pitching of the biome's own numbers can fix that, because
 * the dilution scales with them.
 *
 * A positional layer is asked at the eye and answers for the eye, so nothing is blended into it.
 *
 * **Keyed on the fluid rather than on the abyss line**, which is both exact and free: the client already
 * has the block it is standing in, where the line would have to cross on a payload and then be re-derived.
 * It is also the rule as stated — enter the deep water, get the deep water (Jonah, walked 2026-09-10).
 *
 * **The sky is not this file's to put out, and it is put out anyway.** Vanilla draws its sun, moon, stars
 * and sunset fan on pipelines that carry no fog uniform at all, so an abyss closing at [NOTHING_BEYOND] was
 * still showing a whole unfogged sunset straight through itself (Jonah, 2026-09-11). Ephemeris's
 * `SkyThroughFog` declines the sky wherever the medium closes inside a hundred blocks — which this does, by
 * a wide margin — so there is nothing to register here and nothing to keep in step.
 */
object DeepWaterFog {

    /** Lays the abyss over whatever the biome and the Age's own air had made of the water. */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.WATER_FOG_COLOR) { was, at, _ ->
            if (deepAt(level, at)) ABYSSAL else was
        }
        layers.addPositionalLayer(EnvironmentAttributes.WATER_FOG_START_DISTANCE) { was, at, _ ->
            if (deepAt(level, at)) FROM_THE_EYE else was
        }
        layers.addPositionalLayer(EnvironmentAttributes.WATER_FOG_END_DISTANCE) { was, at, _ ->
            if (deepAt(level, at)) NOTHING_BEYOND else was
        }
        return layers
    }

    /** Whether the eye is in an abyss — the same block `Camera.getFluidInCamera` reads to choose water fog. */
    fun deepAt(level: ClientLevel, at: Vec3): Boolean =
        level.getFluidState(BlockPos.containing(at)).`is`(DeepWater.DEEP_WATER)

    /**
     * Black, near enough, and **untinted** — green and blue equal so nothing reads as blue, red absent.
     *
     * The look is submersible footage: a lit near field and then nothing, rather than a coloured sea
     * (Jonah, 2026-09-10). At ten units of anything the hue is below what can be told apart from black, so
     * what the equal channels buy is the absence of a cast rather than a colour of their own.
     *
     * **The zero channel is load-bearing, not a spare unit.** `FogRenderer.computeFogColor` brightens the
     * water's fog by `LocalPlayer.getWaterVision`, which climbs to 1.0 over thirty seconds under (three in
     * spectator) and there rescales the colour so its brightest channel is full: any dark colour, however
     * dark, comes out at maximum brightness in its own hue. That is what turned `#02060a` into a vivid
     * `#3399ff`. The whole of that step is guarded by `fogRed != 0 && fogGreen != 0 && fogBlue != 0`, so a
     * colour with one channel at zero is never touched — and a *neutral grey* is the worst case there is,
     * every channel rescaling to full and the fog coming out white. Red is the one to spend, water eating
     * it first anyway.
     */
    private const val ABYSSAL = 0x000A0A

    /**
     * No clear water between you and the murk: it begins at the eye rather than eight blocks behind it.
     *
     * **It cannot be moved out in front of you**, however much a clear near field would suit the look:
     * vanilla scales the *end* by `max(0.25, waterVision)` and leaves the start alone, so a start of six
     * would meet the end during the ramp and the fog term would divide by nothing.
     */
    private const val FROM_THE_EYE = 0.0f

    /**
     * How far you see down there, after half a minute under.
     *
     * Multiplied by `max(0.25, waterVision)`, so it is a quarter of this the moment you drop in and opens
     * out as the eye adjusts. Ten was the first walk's value and read as opaque with no light down there to
     * pick anything out (Jonah, 2026-09-10); the Age has no dynamic lighting to see by, so the visibility
     * has to do that work instead.
     */
    const val NOTHING_BEYOND = 24.0f
}
