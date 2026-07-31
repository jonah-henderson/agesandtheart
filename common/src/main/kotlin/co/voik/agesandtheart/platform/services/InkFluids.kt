package co.voik.agesandtheart.platform.services

import co.voik.agesandtheart.age.word.InkTier
import net.minecraft.world.item.Item
import net.minecraft.world.level.material.Fluid

/**
 * The registered ink fluids, which only a loader can build.
 *
 * A service for the same reason [AgeBackend] is: the *objects* are loader-specific even though everything
 * about them that matters — ids, colours, capacity — is not. See [co.voik.agesandtheart.content.AgeFluids]
 * for why common cannot hold a `Fluid` subclass.
 */
interface InkFluids {
    /**
     * What one bucket counts as here — Fabric's droplet (81,000) or NeoForge's millibucket (1,000).
     *
     * The desk stores ink in whatever this loader's own API counts in, so nothing converts at the
     * boundary and a pipe sees exactly the number it expects.
     */
    val unitsPerBucket: Long

    /**
     * What one bottle counts as, per each loader's own convention.
     *
     * **They genuinely differ, and that is accepted** (Jonah, 2026-07-31): Fabric's `FluidConstants.BOTTLE`
     * is a third of a bucket, from vanilla's cauldron holding three, while the NeoForge side follows the
     * modded 250 mB quarter. A Fabric bottle of ink is therefore worth slightly more than a NeoForge one.
     */
    val unitsPerBottle: Long

    fun still(tier: InkTier): Fluid

    fun flowing(tier: InkTier): Fluid

    fun bucket(tier: InkTier): Item

    /** Which ink this fluid is, or null if it is not one of ours — for a tank deciding what it accepts. */
    fun tierOf(fluid: Fluid): InkTier?
}
