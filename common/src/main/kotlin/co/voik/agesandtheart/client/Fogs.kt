package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.FogPayload
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import kotlin.math.exp

/**
 * What the client has been told about the fog over the Age it is standing in, and the fog itself.
 *
 * Server-owned, as [Storms] is: whether an Age has a fog and when a bank lies over it are facts about a recipe
 * and a seed the client never has.
 */
object Fogs {

    /** What each Age's fog was last told as — the one stood in, and any a panel looks into. */
    private val told = mutableMapOf<Identifier, FogPayload>()

    fun remember(payload: FogPayload) {
        told[payload.age] = payload
    }

    /** For a client leaving a server, whose Age ids mean nothing on the next. */
    fun forget() {
        told.clear()
        eased.clear()
    }

    /**
     * Pulls the view in and greys the air while a bank lies over the Age — in caves and under roofs as much as
     * out of them, since a fog fills a world rather than falling on it.
     *
     * Every layer is laid unconditionally and asks about the fog when it is sampled, as [Storms.paint] explains:
     * this runs once, when the level's attribute system is built.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, _, _ ->
            ARGB.srgbLerp(lyingIn(level) * GREYED_BY, was, BANK.rgb())
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, _, _ ->
            ARGB.srgbLerp(lyingIn(level) * GREYED_BY, was, BANK.rgb())
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, _, _ ->
            closedIn(was, level) * BEGINS_AT
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, _, _ -> closedIn(was, level) }
        // Upward too, or looking straight up out of a bank shows a clear sky (`Storms.paint` paid for this).
        layers.addPositionalLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE) { was, _, _ -> closedIn(was, level) }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE) { was, _, _ -> closedIn(was, level) }
        return layers
    }

    /** [was] drawn in towards a bank's own distance by as much of a bank as lies here. */
    private fun closedIn(was: Float, level: ClientLevel): Float {
        val lying = lyingIn(level)
        if (lying <= NONE) return was
        val thickness = told[level.dimension().identifier()]?.thickness ?: Rung.ORDINARY
        val inTheBank = (ORDINARY_BANK_BLOCKS / thickness).toFloat().coerceAtMost(was)
        return was + (inTheBank - was) * lying
    }

    /** How much of a bank lies over [level], eased towards what the server last said. */
    private fun lyingIn(level: ClientLevel): Float {
        val age = level.dimension().identifier()
        val target = told[age]?.lying?.toFloat() ?: NONE
        val now = System.nanoTime()
        val was = eased[age]
        val lying = if (was == null) target else {
            val elapsed = (now - was.at) / NANOS_PER_SECOND
            was.lying + (target - was.lying) * (1.0 - exp(-elapsed / EASE_SECONDS)).toFloat()
        }
        eased[age] = Eased(lying, now)
        return lying
    }

    /** How much of a bank was lying over an Age when it was last eased, and when — each Age's own. */
    private data class Eased(val lying: Float, val at: Long)

    private val eased = mutableMapOf<Identifier, Eased>()

    /** How far you can see in an ordinary bank: a couple of dozen blocks, and half that bought in full. */
    private const val ORDINARY_BANK_BLOCKS = 24.0

    /** Where the fog starts, as a share of where it ends: a bank is thick from almost at your feet. */
    private const val BEGINS_AT = 0.05f

    /** How far towards the bank's grey the air goes. */
    private const val GREYED_BY = 0.8f

    /** A cool, pale grey: fog, not snow. */
    private val BANK = Rgba.of(0xFFB4B9BEu.toInt())

    private const val NONE = 0.0f
    private const val EASE_SECONDS = 2.0
    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
