package co.voik.agesandtheart.age.aspect

import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * **The air an Age takes from the world it was written over**, laid under whatever its book said.
 *
 * A template contributes its biomes, its rock, its structures, its climate and its skin. Its *air* is the
 * last of those and the only one that could not simply be read where it was wanted: a runtime level wears a
 * `DimensionType` written before it existed, and ours are shared — every roofed Age wears the same one, so
 * the nether's fog distances cannot be baked into it without giving them to a sealed overworld Age too.
 *
 * So the world is **named** rather than copied. One `ResourceKey<DimensionType>` crosses on the level's
 * look ([co.voik.ephemeris.sky.LevelLook.airFrom]) and both sides resolve it from a registry they already
 * have. No number of vanilla's is ever written down here.
 *
 * **Only what a world answers for, never what a place does.** These layers go on *above* the biomes —
 * that is where the seam is on both sides — so anything a biome might have said would be overruled by one
 * flat answer for the whole Age, which is the bug that flattened the nether's crimson and warped fog twice
 * over. Measured against 26.1.2's sixty-five biomes: between them they set fog colour, sky colour, the
 * water fog colour and distance, ambient particles, two gameplay switches and their audio, and **nothing
 * below**. Adding to these lists means checking that again.
 */
object BorrowedAir {

    /**
     * What the eye sees that belongs to the world rather than to the place — how far the fog reaches, and
     * what colour the light itself arrives as. Read from `ClientLevel`'s own attribute system, which is why
     * this half cannot be settled by a server.
     *
     * Public because `BorrowedAirCheck` reads it: the list *is* the claim that these belong to the world,
     * and the check is what keeps the claim true against every biome in the game.
     */
    val SEEN: List<EnvironmentAttribute<*>> = listOf(
        EnvironmentAttributes.FOG_START_DISTANCE,
        EnvironmentAttributes.FOG_END_DISTANCE,
        EnvironmentAttributes.SKY_FOG_END_DISTANCE,
        EnvironmentAttributes.CLOUD_FOG_END_DISTANCE,
        EnvironmentAttributes.AMBIENT_LIGHT_COLOR,
        EnvironmentAttributes.SKY_LIGHT_COLOR,
        EnvironmentAttributes.SKY_LIGHT_FACTOR,
        EnvironmentAttributes.DEFAULT_DRIPSTONE_PARTICLE,
    )

    /**
     * And what the world *does* to you, which the server settles alone.
     *
     * `SKY_LIGHT_LEVEL` is the one that matters most: the nether's is 4, a dim constant that is why it is
     * never truly black in there, where an Age with nothing overhead pins its own to zero.
     *
     * **How an Age handles a bed or a respawn anchor is deliberately not borrowed.** Our own types answer
     * for that and it is the mod's policy rather than the world's flavour — a book that makes a world
     * nether-shaped is not asking for its beds to explode.
     */
    val PLAYED: List<EnvironmentAttribute<*>> = listOf(
        EnvironmentAttributes.SKY_LIGHT_LEVEL,
        EnvironmentAttributes.WATER_EVAPORATES,
        EnvironmentAttributes.FAST_LAVA,
        EnvironmentAttributes.PIGLINS_ZOMBIFY,
        EnvironmentAttributes.CAN_START_RAID,
    )

    /** [world]'s own look, laid on [layers] — the client's half. */
    fun seen(layers: EnvironmentAttributeSystem.Builder, world: EnvironmentAttributeMap) {
        for (attribute in SEEN) lay(layers, attribute, world)
    }

    /** And what standing in it is like — the server's. */
    fun played(layers: EnvironmentAttributeSystem.Builder, world: EnvironmentAttributeMap) {
        for (attribute in PLAYED) lay(layers, attribute, world)
    }

    /**
     * One attribute as [world] answers it, or nothing where that world says nothing about it.
     *
     * The entry's own modifier is what is laid rather than a value read out of it, so a world that
     * *adjusts* what is below rather than replacing it keeps doing so. It is vanilla's own
     * `addConstantLayer(map)` with the loop over `keySet` replaced by a loop over ours.
     */
    private fun <Value : Any> lay(
        layers: EnvironmentAttributeSystem.Builder,
        attribute: EnvironmentAttribute<Value>,
        world: EnvironmentAttributeMap,
    ) {
        val entry = world.get(attribute) ?: return
        layers.addConstantLayer(attribute) { below -> entry.applyModifier(below) }
    }
}
