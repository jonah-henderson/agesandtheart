package co.voik.agesandtheart.age.aspect

import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.world.attribute.AmbientParticle
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.block.Blocks

/**
 * What hangs in an Age's air — vanilla's own particles, named.
 *
 * **Enumerated rather than open**, unlike a block or a biome, and the line is not "vanilla already uses this
 * as weather" but **is this something the air does, continuously, with no cause you could point at**. An
 * explosion, a crit and a damage indicator fail that test — they are *events*, and hanging one in the air of
 * a world would be a category error rather than an unusual Age. A lava spark passes it: vanilla only ever
 * throws one off a lava block, but a world whose *air* throws them is exactly the scorching Age a writer
 * would want to say (Jonah, 2026-08-04).
 *
 * The value a recipe holds is the name, so a pack that adds a mote needs no code of ours the day this
 * opens up.
 *
 * **Resolved lazily**, which is load-bearing rather than tidy: [ALL] is *vocabulary* and is read while the
 * corpus is built, long before a registry is bootstrapped, so the names must not drag `ParticleTypes` or
 * `Blocks` in behind them.
 */
object Motes {
    /**
     * How often a mote appears, per eligible position per tick — vanilla's own ambient rates sit here.
     *
     * A share rather than a rate per mote, because what varies is **how much room a particle takes up**
     * rather than how often the air should do something: ash drifts and reads as weather at the ordinary
     * rate, where a lava spark is bright, fast and large, and at the same rate reads as being on fire
     * (Jonah, 2026-08-05, walked).
     */
    private const val ORDINARY_CHANCE = 0.118f

    private val THINNED = mapOf("embers" to LOUD, "flames" to LOUD)

    /**
     * What a mote that takes up a lot of room appears at, against [ORDINARY_CHANCE].
     *
     * Both of the burning ones need it and for the same reason (Jonah, 2026-08-05 and 2026-08-08, walked):
     * a lava spark and a flame are bright, fast and large, and at the ordinary rate an Age wearing either
     * reads as being *on fire* rather than as having fire in its air.
     */
    private const val LOUD = 0.0625f

    private val NAMED: Map<String, () -> ParticleOptions> = linkedMapOf(
        // What the air carries.
        "ash" to { ParticleTypes.ASH },
        "white_ash" to { ParticleTypes.WHITE_ASH },
        "grit" to { BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()) },
        "smoke" to { ParticleTypes.LARGE_SMOKE },
        "snow" to { ParticleTypes.SNOWFLAKE },
        // What grows in it.
        "warped_spore" to { ParticleTypes.WARPED_SPORE },
        "crimson_spore" to { ParticleTypes.CRIMSON_SPORE },
        "spore_blossom" to { ParticleTypes.SPORE_BLOSSOM_AIR },
        "mycelium" to { ParticleTypes.MYCELIUM },
        "cherry_petals" to { ParticleTypes.CHERRY_LEAVES },
        "pale_leaves" to { ParticleTypes.PALE_OAK_LEAVES },
        // What burns in it.
        "embers" to { ParticleTypes.LAVA },
        "flames" to { ParticleTypes.FLAME },
        "soul_flames" to { ParticleTypes.SOUL_FIRE_FLAME },
        "sparks" to { ParticleTypes.ELECTRIC_SPARK },
        // What haunts it.
        "souls" to { ParticleTypes.SOUL },
        "sculk" to { ParticleTypes.SCULK_SOUL },
        // What lights it.
        "firefly" to { ParticleTypes.FIREFLY },
        "starlight" to { ParticleTypes.END_ROD },
        "glimmer" to { ParticleTypes.GLOW },
        // What fills a sea.
        "underwater" to { ParticleTypes.UNDERWATER },
    )

    /** Every mote a writer may hang in the air, in the order they read. */
    val ALL: List<String> = NAMED.keys.toList()

    /** The particle [named], or null where the word is not one of ours. */
    fun named(name: String): ParticleOptions? = NAMED[name]?.invoke()

    /** [named] and how often it should appear, ready to hang in an Age's air. */
    fun ambient(name: String): List<AmbientParticle>? =
        named(name)?.let { AmbientParticle.of(it, ORDINARY_CHANCE * (THINNED[name] ?: 1.0f)) }
}
