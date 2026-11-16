package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent

/**
 * Everything an asteroid's end is heard by (design §5.3 "The asteroid"), one event for each, so each can be given a
 * sound of its own. Until the asset pass, `sounds.json` points each at a vanilla sound standing in for it.
 *
 * **Moments**, heard once as the clock passes them: [ENTRY], [RUMBLE], [SHOCK], [ARRIVAL], [ASH_ARRIVAL]; and where a
 * fragment lands, [FRAGMENT_IMPACT]. **Loops**, held while their part of the end lasts and swelling and dying with
 * it: [APPROACH], [BURNING], [BLAST_WIND], [FIRESTORM], [ASH_WALL], [DUST], [WINTER]; and following each fragment
 * down, [FRAGMENT_FALLING].
 */
object ImpactSounds {

    private fun event(name: String): SoundEvent = SoundEvent.createVariableRangeEvent("asteroid_$name".location())

    /** A low drone through the approach, swelling as the speck grows into a rock. */
    val APPROACH: SoundEvent = event("approach")

    /** The moment it begins to burn: the first roar of the air. */
    val ENTRY: SoundEvent = event("entry")

    /** The roar of its burning through the entry, loudest as it goes down behind the horizon. */
    val BURNING: SoundEvent = event("burning")

    /** The rumble through the ground, arriving a few seconds after the flash. */
    val RUMBLE: SoundEvent = event("rumble")

    /** The shock front's slam. */
    val SHOCK: SoundEvent = event("shock")

    /** The blast's wind, past any gale's, howling from the shock front to the dust. */
    val BLAST_WIND: SoundEvent = event("blast_wind")

    /** The debris arriving overhead, as the heat begins. */
    val ARRIVAL: SoundEvent = event("arrival")

    /** The burning sky and everything under it, through the heat. */
    val FIRESTORM: SoundEvent = event("firestorm")

    /** The wall of ash coming on, a roar rising as it nears. */
    val ASH_WALL: SoundEvent = event("ash_wall")

    /** The wall of ash rolling over the eye. */
    val ASH_ARRIVAL: SoundEvent = event("ash_arrival")

    /** The air inside the dust, choked and muffled. */
    val DUST: SoundEvent = event("dust")

    /** The impact winter: a dead world's wind, for ever. */
    val WINTER: SoundEvent = event("winter")

    /** A fragment coming down, heard from it as it falls. */
    val FRAGMENT_FALLING: SoundEvent = event("fragment_falling")

    /** A fragment striking the ground, in place of an explosion's own. */
    val FRAGMENT_IMPACT: SoundEvent = event("fragment_impact")

    val soundEvents: List<Pair<Identifier, SoundEvent>> = listOf(
        APPROACH, ENTRY, BURNING, RUMBLE, SHOCK, BLAST_WIND, ARRIVAL, FIRESTORM, ASH_WALL, ASH_ARRIVAL, DUST, WINTER,
        FRAGMENT_FALLING, FRAGMENT_IMPACT,
    ).map { it.location() to it }
}
