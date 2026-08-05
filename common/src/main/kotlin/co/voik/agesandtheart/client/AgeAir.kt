package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Motes
import co.voik.agesandtheart.sky.KnownLooks
import co.voik.agesandtheart.sky.Look
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.attribute.AmbientParticle
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

        fun onlyIn(layers: EnvironmentAttributeSystem.Builder, level: ClientLevel, biome: Identifier) {
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
     * writer says how thick the air is and only the client knows how far it can see.
     *
     * **One knob where vanilla has several**, three times over: fog is a far edge with the near one a fixed
     * share of it, so thickening always draws the fog in rather than inverting somewhere in the middle;
     * water is its colour and how far you see through it; and the light is the sky's and the ambient
     * together, because a writer who says the light is green means all of it. Each of the granular sets is
     * still there for the day a word wants one.
     */
    private fun painting(look: Look): List<Painted<*>> = buildList {
        look.sky?.let { add(Painted(EnvironmentAttributes.SKY_COLOR, it.packed())) }
        look.cloud?.let { add(Painted(EnvironmentAttributes.CLOUD_COLOR, it.packed())) }
        look.fog?.let { add(Painted(EnvironmentAttributes.FOG_COLOR, it.packed())) }
        // One knob for the water, on the same argument as the fog: there is no world worth writing where
        // the water is one colour and what you see through it another.
        look.water?.let {
            add(Painted(EnvironmentAttributes.WATER_FOG_COLOR, it.packed()))
            add(Painted(EnvironmentAttributes.WATER_FOG_END_DISTANCE, WATER_CLOSES_IN))
            add(Painted(EnvironmentAttributes.WATER_FOG_START_DISTANCE, WATER_CLOSES_IN * NEAR_SHARE_OF_FAR))
        }
        // And one for the light, because a writer who says the light is green means all of it.
        look.tint?.let {
            add(Painted(EnvironmentAttributes.SKY_LIGHT_COLOR, it.packed()))
            add(Painted(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, it.packed()))
        }
        look.motes?.let { named ->
            Motes.named(named)?.let {
                add(Painted(EnvironmentAttributes.AMBIENT_PARTICLES, AmbientParticle.of(it, MOTE_CHANCE)))
            }
        }
        look.haze?.let { haze ->
            val far = FURTHEST - haze * (FURTHEST - NEAREST)
            add(Painted(EnvironmentAttributes.FOG_END_DISTANCE, far))
            add(Painted(EnvironmentAttributes.FOG_START_DISTANCE, far * NEAR_SHARE_OF_FAR))
        }
        look.ceiling?.let {
            add(Painted(EnvironmentAttributes.CLOUD_HEIGHT, LOWEST_CLOUD + it * (HIGHEST_CLOUD - LOWEST_CLOUD)))
        }
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

    /**
     * How far you see underwater in a coloured sea, in blocks. Fixed rather than a knob of its own: the
     * colour is what a writer is asking for, and water that is red *and* clear is a distinction nobody has
     * wanted yet.
     */
    private const val WATER_CLOSES_IN = 48f

    /** How often a mote appears, per eligible position per tick — vanilla's own ambient rates sit here. */
    private const val MOTE_CHANCE = 0.118f

    /** The band the cloud deck moves through, in blocks. */
    private const val LOWEST_CLOUD = 96f
    private const val HIGHEST_CLOUD = 256f
}
