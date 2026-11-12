package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.worldgen.Perforation
import co.voik.agesandtheart.worldgen.feature.PerforatedFeature
import co.voik.agesandtheart.worldgen.feature.SkippingLevel
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.levelgen.Heightmap

/**
 * What an Age's features build of rock above the ground, holed through — `porous features` ([Features.POROSITY]): the
 * same rock and the same ground [CrumbledFeatures] crumbles, cut in round holes rather than scattered. A feature the
 * book named porous is holed whole instead ([PerforatedFeature]).
 *
 * Set around the Age's decoration by its generator, and asked of each feature's placement by `FeaturePlacerMixin`; it
 * costs one thread-local read anywhere it is not set.
 */
object PorousFeatures {

    private val holing = ThreadLocal<Perforation?>()

    /** The holes said of every feature in the Age, or null where none were. */
    fun of(options: Options, salt: Long): Perforation? {
        if (!options.holdersOfAny(Features.POROSITY).everyone) return null
        val porosity = (options.steer(Features.POROSITY, salt) ?: return null).coerceIn(0.0, 1.0)
        return Perforation(salt xor HOLES_SALT, PerforatedFeature.HOLE_SIZE, porosity).takeUnless { it.isNothing }
    }

    /** Runs [decorate] with the features' rock holed by [holes], or untouched where there are none. */
    fun decorating(holes: Perforation?, decorate: () -> Unit) {
        if (holes == null) return decorate()
        val before = holing.get()
        holing.set(holes)
        try {
            decorate()
        } finally {
            holing.set(before)
        }
    }

    /** [level], or a view of it that lays nothing in a hole, while an Age's features are holed. */
    @JvmStatic
    fun levelFor(level: WorldGenLevel): WorldGenLevel {
        val holes = holing.get() ?: return level
        return SkippingLevel.of(level) { at, state ->
            val isRock = state.`is`(CrumbledFeatures.CRUMBLES)
            val standsOverTheGround = at.y >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, at.x, at.z)
            isRock && standsOverTheGround && holes.isHoleAt(at.x, at.y, at.z)
        }
    }

    private const val HOLES_SALT = 0x504F_5245_53L
}
