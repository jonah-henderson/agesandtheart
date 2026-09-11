package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity

/**
 * What the abyss does to a body in it, as an effect rather than as bare damage (design §7.1.2).
 *
 * **The HUD icon is the smaller half of why.** The larger is that an effect is a *surface*: the deep can
 * press on what you are able to do and not only on how long you last, and mining fatigue is the candidate
 * that decided it. Raw damage in a block's `entityInside` had nowhere to hang a second consequence.
 *
 * **It ends the moment you are out of the water.** [applyEffectTick] answering false is what removes an
 * effect, which is checked every tick — so surfacing clears it within one rather than leaving a timer
 * running on dry land. Whether pressure should carry a few seconds out with you is a design question that
 * was asked and closed the other way (Jonah, 2026-09-10).
 *
 * **Nothing about the damage moved with it**: the rate, the source in `#minecraft:bypasses_armor`, and the
 * turtle helmet and deretheni suit that answer it are all still [DeepWater]'s, which is where knowing about
 * the deep belongs. This is the shape of the consequence, not its terms.
 */
class PressureEffect : MobEffect(MobEffectCategory.HARMFUL, COLOUR) {

    /**
     * Every tick, which is about leaving rather than about hurting.
     *
     * The damage keeps its own once-a-second beat inside [applyEffectTick]; being asked every tick is what
     * lets the effect notice it is over the moment it is.
     */
    override fun shouldApplyEffectTickThisTick(duration: Int, amplifier: Int): Boolean = EVERY_TICK

    override fun applyEffectTick(level: ServerLevel, body: LivingEntity, amplifier: Int): Boolean {
        if (!DeepWater.stillUnderPressure(level, body)) return GONE
        DeepWater.crush(level, body)
        return STANDS
    }

    private companion object {
        /** The colour of the abyss it is the feeling of, which is the only thing the icon has to say. */
        const val COLOUR = 0x1B3A5C

        const val EVERY_TICK = true

        /** What the return of [applyEffectTick] means — false takes the effect off. */
        const val GONE = false
        const val STANDS = true
    }
}
