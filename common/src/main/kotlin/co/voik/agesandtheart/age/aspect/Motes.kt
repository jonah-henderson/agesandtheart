package co.voik.agesandtheart.age.aspect

import net.minecraft.core.particles.ParticleTypes

/**
 * What hangs in an Age's air — vanilla's own ambient particles, named.
 *
 * **Enumerated rather than open**, unlike a block or a biome, and the reason is that most of the particle
 * registry is not ambient at all: an explosion, a damage indicator and a block-break are events, and
 * hanging one in the air of a world would be a category error rather than an unusual Age. These are the
 * ones vanilla itself uses as *weather* — the ash of the basalt deltas, the white ash of soul valleys, the
 * warped and crimson spores.
 *
 * The value a recipe holds is the particle's registry id, so a pack that adds a mote needs no code of ours
 * the day this opens up.
 */
object Motes {
    private val NAMED = linkedMapOf(
        "ash" to ParticleTypes.ASH,
        "white_ash" to ParticleTypes.WHITE_ASH,
        "warped_spore" to ParticleTypes.WARPED_SPORE,
        "crimson_spore" to ParticleTypes.CRIMSON_SPORE,
        "spore_blossom" to ParticleTypes.SPORE_BLOSSOM_AIR,
        "cherry_petals" to ParticleTypes.CHERRY_LEAVES,
        "underwater" to ParticleTypes.UNDERWATER,
        "firefly" to ParticleTypes.FIREFLY,
    )

    /** Every mote a writer may hang in the air, in the order they read. */
    val ALL: List<String> = NAMED.keys.toList()

    /** The particle [named], or null where the word is not one of ours. */
    fun named(name: String) = NAMED[name]
}
