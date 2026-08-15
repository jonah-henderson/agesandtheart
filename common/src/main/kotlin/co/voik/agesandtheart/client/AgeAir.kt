package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Motes
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.Look
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import co.voik.agesandtheart.age.aspect.BorrowedAir
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.dimension.DimensionType
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
        val told = LevelLooks.of(level.dimension()) ?: return layers
        val decks = told.sky.decks
        val world = told.airFrom?.let { worldNamed(level, it) }
        if (world == null && told.air.saysNothing && told.corners.isEmpty() && decks.isEmpty()) return layers
        // **First, so everything the book said sits over it.** This is the air of the world the Age was
        // written over, which the client resolves from a registry it already has — see [BorrowedAir].
        world?.let { BorrowedAir.seen(layers, it) }
        if (!told.air.saysNothing) layers.addConstantLayer(asAttributeMap(told.air))
        for ((biome, look) in told.corners) {
            for (painted in painting(look)) painted.onlyIn(layers, level, biome)
        }
        // Last, so it sits over the flat colour it darkens.
        deepened(told.air, decks)?.let { under -> under.onto(layers) }
        return layers
    }

    /**
     * The attributes of the world named on this level's look, or null where the client has never heard of
     * it — which a renderer must survive rather than throw over.
     */
    private fun worldNamed(level: ClientLevel, world: ResourceKey<DimensionType>): EnvironmentAttributeMap? =
        level.registryAccess().lookup(Registries.DIMENSION_TYPE)
            .flatMap { it.get(world) }
            .map { it.value().attributes() }
            .orElse(null)

    /**
     * The air **under an overcast**, darkening with each deck you drop below.
     *
     * A sky with cloud between you and it should not be the colour of the sky: standing under the Spire's
     * two decks and seeing the same storm-grey you see above them makes the decks read as painted on rather
     * than as something you are beneath. So the air takes the deck's own tone as you pass it, and takes it
     * again — darker — under the next.
     *
     * **Keyed to the decks the Age actually has**, not written down beside them, so this is not the Spire's
     * special case: any sky given cloud layers gets the air that belongs under them, and moving a deck moves
     * its gloom with it.
     *
     * Null where there is nothing to be under.
     */
    private fun deepened(look: Look, decks: List<CloudDeck>): Deepening? {
        if (decks.isEmpty()) return null
        val open = look.sky ?: return null
        val openFog = look.fog ?: open
        // Outermost last, so the highest deck is the first thing you come down through.
        val falling = decks.sortedByDescending { it.height }
        return Deepening(open, openFog, falling)
    }

    /**
     * The sky and the fog as a function of how far below the decks the eye is.
     *
     * A positional layer rather than a constant one, which is the same mechanism a biome-confined colour
     * already uses — it is handed a position and asks what is true there.
     */
    private class Deepening(
        private val openSky: Rgba,
        private val openFog: Rgba,
        private val falling: List<CloudDeck>,
    ) {
        fun onto(layers: EnvironmentAttributeSystem.Builder) {
            layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { _, at, _ -> skyAt(at.y).packed() }
            layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { _, at, _ -> fogAt(at.y).packed() }
        }

        /** The open sky, then each deck's own gloom, each one dimmer than the last. */
        private fun skyAt(eyeY: Double): Rgba = toneAt(eyeY, openSky)

        private fun fogAt(eyeY: Double): Rgba = toneAt(eyeY, openFog)

        private fun toneAt(eyeY: Double, open: Rgba): Rgba {
            var tone = open
            var dimming = 1.0f
            for (deck in falling) {
                // Above it, and the ones below it cannot matter either — they are further down still.
                if (eyeY >= deck.height) break
                // The deck's own dark tone, dimmed once more for every deck already passed.
                dimming *= UNDER_EACH_DECK
                tone = deck.low.dimmed(dimming)
            }
            return tone
        }
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
     * **One knob where vanilla has several**, twice over: fog is a far edge with the near one a fixed share
     * of it, so thickening always draws the fog in rather than inverting somewhere in the middle; and the
     * light is the sky's and the ambient together, because a writer who says the light is green means all
     * of it. Both granular sets are still there for the day a word wants one.
     */
    private fun painting(look: Look): List<Painted<*>> = buildList {
        look.sky?.let { add(Painted(EnvironmentAttributes.SKY_COLOR, it.packed())) }
        look.cloud?.let { add(Painted(EnvironmentAttributes.CLOUD_COLOR, it.packed())) }
        look.fog?.let { add(Painted(EnvironmentAttributes.FOG_COLOR, it.packed())) }
        // One knob for the light, because a writer who says the light is green means all of it.
        look.tint?.let {
            add(Painted(EnvironmentAttributes.SKY_LIGHT_COLOR, it.packed()))
            add(Painted(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, it.packed()))
        }
        // How often is `Motes`', not ours: a lava spark has to be thinner than drifting ash to read as air.
        look.motes?.let { named ->
            Motes.ambient(named)?.let { add(Painted(EnvironmentAttributes.AMBIENT_PARTICLES, it)) }
        }
        look.haze?.let { haze ->
            val far = FURTHEST - haze * (FURTHEST - NEAREST)
            add(Painted(EnvironmentAttributes.FOG_END_DISTANCE, far))
            add(Painted(EnvironmentAttributes.FOG_START_DISTANCE, far * NEAR_SHARE_OF_FAR))
        }
        // Only the far edge: vanilla starts the water's fog at -8, which is behind the camera, and keeping
        // that is what makes a clear sea read as *further* rather than as a fade that begins later.
        look.murk?.let {
            add(Painted(EnvironmentAttributes.WATER_FOG_END_DISTANCE, CLEAREST - it * (CLEAREST - MURKIEST)))
        }
        look.starBrightness?.let { add(Painted(EnvironmentAttributes.STAR_BRIGHTNESS, it)) }
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
     * How far you see underwater at either end of the axis, in blocks. Vanilla's own is 96, which sits
     * inside the band rather than at its middle — the whole point of the clear end is that it is further
     * than any sea you have swum in.
     */
    private const val CLEAREST = 256f
    private const val MURKIEST = 8f

    /**
     * How much dimmer the air gets under each further deck.
     *
     * One multiplication rather than a written-down colour per band: two decks then read as *deeper* rather
     * than as two unrelated greys, and a third deck needs nothing added.
     */
    private const val UNDER_EACH_DECK = 0.62f

    /** The band the cloud deck moves through, in blocks. */
    private const val LOWEST_CLOUD = 96f
    private const val HIGHEST_CLOUD = 256f
}
