package co.voik.agesandtheart.client

import co.voik.agesandtheart.sky.KnownLooks
import co.voik.agesandtheart.sky.Look
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * How an Age's air is painted, on the side that draws it.
 *
 * The server settles every attribute it can (`Atmosphere`), and these are the ones it cannot: a colour is
 * read from `ClientLevel`'s own attribute system, which is private, final, and built in its constructor.
 * So the look crosses on a payload and is laid on here as one more layer over vanilla's stack — which is
 * what an Age's air *is*, rather than a replacement for the world it stands in.
 *
 * **A colour nobody named is left alone.** Every field of a [Look] is null until a sentence sets it, and a
 * null lays no layer, so an Age that repaints its sky keeps whatever fog the biome under it had.
 */
object AgeAir {

    /** [layers] with this Age's own on top, or [layers] itself where this is not an Age of ours. */
    @JvmStatic
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val told = KnownLooks.airOf(level.dimension()) ?: return layers
        if (told.look.saysNothing && told.corners.isEmpty()) return layers
        if (!told.look.saysNothing) layers.addConstantLayer(asAttributeMap(told.look))
        for ((biome, look) in told.corners) {
            for (painted in painting(look)) painted.onlyIn(layers, level, biome)
        }
        return layers
    }

    /** One attribute and the value asked of it, kept together so the pair stays typed — as `Atmosphere` does. */
    private class Painted<Value : Any>(
        private val attribute: EnvironmentAttribute<Value>,
        private val value: Value,
    ) {
        fun into(air: EnvironmentAttributeMap.Builder) {
            air.set(attribute, value)
        }

        fun onlyIn(layers: EnvironmentAttributeSystem.Builder, level: ClientLevel, biome: net.minecraft.resources.Identifier) {
            layers.addPositionalLayer(attribute) { below, at, _ ->
                val here = level.getBiome(BlockPos.containing(at)).unwrapKey().orElse(null)?.identifier()
                if (here == biome) value else below
            }
        }
    }

    /**
     * What one [Look] paints.
     *
     * [Look.haze] and [Look.ceiling] arrive as fractions of their own axis rather than distances, because a
     * writer says how thick the air is and only the client knows how far it can see. **Fog is one knob for
     * vanilla's two distances**: the far edge runs from close to far across the axis, and the near edge is
     * a fixed share of it, so thickening the air always draws it in rather than inverting at some point.
     */
    private fun painting(look: Look): List<Painted<*>> = buildList {
        look.sky?.let { add(Painted(EnvironmentAttributes.SKY_COLOR, it.packed())) }
        look.cloud?.let { add(Painted(EnvironmentAttributes.CLOUD_COLOR, it.packed())) }
        look.fog?.let { add(Painted(EnvironmentAttributes.FOG_COLOR, it.packed())) }
        look.haze?.let { haze ->
            val far = FURTHEST - haze * (FURTHEST - NEAREST)
            add(Painted(EnvironmentAttributes.FOG_END_DISTANCE, far))
            add(Painted(EnvironmentAttributes.FOG_START_DISTANCE, far * NEAR_SHARE_OF_FAR))
        }
        look.ceiling?.let { add(Painted(EnvironmentAttributes.CLOUD_HEIGHT, LOWEST_CLOUD + it * (HIGHEST_CLOUD - LOWEST_CLOUD))) }
    }

    private fun asAttributeMap(look: Look): EnvironmentAttributeMap {
        val air = EnvironmentAttributeMap.builder()
        painting(look).forEach { it.into(air) }
        return air.build()
    }

    /** How far the fog's far edge stands at either end of the axis, in blocks. */
    private const val FURTHEST = 192f
    private const val NEAREST = 24f

    /** Where the fog begins, as a share of where it ends — vanilla's own ratio is about this. */
    private const val NEAR_SHARE_OF_FAR = 0.25f

    /** The band the cloud deck moves through, in blocks. */
    private const val LOWEST_CLOUD = 96f
    private const val HIGHEST_CLOUD = 256f
}
