package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.Wind
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wind turned up until it pushes ([co.voik.agesandtheart.age.aspect.Phenomenon.GALE], `notes/wind-plan.md` §2).
 *
 * **Spells of its own, as a fog's are**: time is cut into spells of [SPELL_TICKS], each windy or calm by a draw off
 * the seed at the share the Age's gale reaches, so every client and every reload agree with nothing written down. A
 * gale rises over the last [ROLLING_TICKS] of the spell before a windy one and dies over the last of a windy one.
 * Each windy spell blows on a bearing of its own, turning to the next over the same stretch where two run together.
 *
 * **While it blows it is the Age's wind**, so the clouds race along it, the rain leans with it and the light
 * particles go its way ([LevelWind.blowInstead]). What it does besides is lean on everything light out in it: the
 * players on their own clients, which move them, and here the creatures, the dropped items and whatever is thrown.
 */
object Gale {

    /** A gale over an Age at one moment: how much of one is [lying] over it, which way it drives, and its [fury]. */
    data class Blowing(val lying: Double, val bearingDegrees: Double, val fury: Double) {
        val towardsX: Double get() = cos(Math.toRadians(bearingDegrees))
        val towardsZ: Double get() = sin(Math.toRadians(bearingDegrees))
    }

    /** The gale over an Age of [seed] at [gameTime], blowing this often and this hard, or null where none does. */
    fun blowingAt(seed: Long, gameTime: Long, recurrence: Recurrence, dials: GaleDials): Blowing? {
        val spell = gameTime / SPELL_TICKS
        val intoIt = gameTime % SPELL_TICKS
        val share = shareOfTheTime(recurrence, dials.often)
        val blowsNow = isWindy(seed, spell, share)
        val blowsNext = isWindy(seed, spell + 1, share)
        val intoTheTurn = ((intoIt - (SPELL_TICKS - ROLLING_TICKS)).toFloat() / ROLLING_TICKS).coerceIn(0.0f, 1.0f)
        val turning = Mth.smoothstep(intoTheTurn).toDouble()
        val lying = Mth.lerp(turning, if (blowsNow) WHOLLY else NONE, if (blowsNext) WHOLLY else NONE)
        if (lying <= NONE) return null
        val bearing = when {
            blowsNow && blowsNext -> turnedTowards(bearingOf(seed, spell), bearingOf(seed, spell + 1), turning)
            blowsNow -> bearingOf(seed, spell)
            else -> bearingOf(seed, spell + 1)
        }
        return Blowing(lying, bearing, furyOf(dials.push))
    }

    /**
     * How much of the time a gale blows over an Age this windy: an ordinary one about a quarter of it, one bought in
     * full more often than not. The book's frequency scales it, and a constant gale never drops.
     */
    fun shareOfTheTime(recurrence: Recurrence, often: Double): Double =
        if (recurrence.isConstant) WHOLLY
        else ((SOMETIMES + often * (MOSTLY - SOMETIMES)) * recurrence.often).coerceIn(NONE, MOSTLY)

    /**
     * How hard a gale blows, from [GENTLEST] to one: half for an ordinary gale, all of it with its push bought in
     * full, and gentler where the book wrote it so.
     */
    fun furyOf(push: Double): Double = (ORDINARY_FURY + push * (WHOLLY - ORDINARY_FURY)).coerceIn(GENTLEST, WHOLLY)

    /**
     * How hard [blowing] leans, from nought to one, with [scale] the server's dial: as the square of its fury, so an
     * ordinary gale leans and a fierce one shoves, and only as much as has risen.
     */
    fun pushStrength(scale: Double, blowing: Blowing): Double = scale * blowing.fury * blowing.fury * blowing.lying

    /**
     * The wind [blowing] makes the Age's while it lasts: as hard as it blows, from a strong wind for the gentlest gale
     * to the strongest for the fiercest, and rising and dying with it.
     */
    fun windOf(blowing: Blowing): Wind = Wind(
        bearingDegrees = blowing.bearingDegrees.toFloat(),
        strength = (blowing.lying * (CALMEST_LOOK + (WHOLLY - CALMEST_LOOK) * blowing.fury)).toFloat(),
        wobbleDegrees = 0.0f,
    )

    /** One tick of a gale this often and this hard over [level]. */
    fun blow(level: ServerLevel, recurrence: Recurrence, dials: GaleDials) {
        val blowing = blowingAt(level.seed, level.gameTime, recurrence, dials)
        LevelWind.blowInstead(level, blowing?.let(::windOf))
        if (blowing != null) leanOnWhatIsOutInIt(level, blowing)
    }

    /** No gale over [level]: its own wind blows again. */
    fun lull(level: ServerLevel) = LevelWind.blowInstead(level, null)

    /**
     * Everything light near a player, leaned on — once each, however many players it is near. Players are leaned on
     * by their own clients, which move them.
     */
    private fun leanOnWhatIsOutInIt(level: ServerLevel, blowing: Blowing) {
        val strength = pushStrength(AgeConfig.galeWind.get(), blowing)
        if (strength <= NONE) return
        val leanedOn = HashSet<Entity>()
        for (player in Sampling.watchers(level)) {
            val around = AABB(player.blockPosition()).inflate(REACH)
            for (entity in level.getEntities(player, around, ::isLightEnough)) {
                if (leanedOn.add(entity)) leanOn(level, entity, blowing, strength)
            }
        }
    }

    /** What a gale moves besides a player: what lives, what was dropped and what was thrown, while it is in the air. */
    private fun isLightEnough(entity: Entity): Boolean {
        val isMovable = entity is LivingEntity || entity is ItemEntity || entity is Projectile
        val isHeldOrSheltered = entity.isPassenger || entity.isInWater || entity.isNoGravity || entity.isSpectator
        return isMovable && entity !is Player && entity !is ArmorStand && !isHeldOrSheltered
    }

    private fun leanOn(level: ServerLevel, entity: Entity, blowing: Blowing, strength: Double) {
        val exposed = Sampling.exposureAt(level, entity.blockPosition())
        if (exposed <= 0.0f) return
        val friction = if (entity.onGround()) {
            level.getBlockState(entity.blockPosBelowThatAffectsMyMovement).block.friction
        } else {
            Blizzard.ORDINARY_FRICTION
        }
        val carried = if (entity is Projectile) THROWN_SHARE else WHOLLY
        val push = Blizzard.windPush(strength, exposed, friction) * carried
        entity.push(blowing.towardsX * push, 0.0, blowing.towardsZ * push)
    }

    /**
     * Tell everyone in [level] how much of a gale blows over it, which way and how hard. Sent where there is none
     * too, so a client leaving a gale for a calm Age does not carry the wind with it.
     */
    fun tellTheClients(level: ServerLevel, recurrence: Recurrence?, dials: GaleDials) {
        val age = level.dimension().identifier()
        val blowing = recurrence?.let { blowingAt(level.seed, level.gameTime, it, dials) }
        val telling = if (blowing == null) GalePayload.noneIn(age)
        else GalePayload(age, blowing.lying, blowing.bearingDegrees, blowing.fury)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    private fun isWindy(seed: Long, spell: Long, share: Double): Boolean =
        XoroshiroRandomSource(seed xor WINDY_SALT xor spell).nextDouble() < share

    private fun bearingOf(seed: Long, spell: Long): Double =
        XoroshiroRandomSource(seed xor BEARING_SALT xor spell).nextDouble() * FULL_TURN

    /** [from] turned [share] of the short way round to [to], in degrees. */
    private fun turnedTowards(from: Double, to: Double, share: Double): Double =
        from + Mth.wrapDegrees(to - from) * share

    /** Three minutes: long enough to be caught out in, short enough to wait out. */
    private const val SPELL_TICKS = 3600L

    /** Half a minute to rise or die. */
    private const val ROLLING_TICKS = 600L

    private const val SOMETIMES = 0.25
    private const val MOSTLY = 0.6
    private const val ORDINARY_FURY = 0.5
    private const val GENTLEST = 0.25

    /** How hard the gentlest gale looks: a strong wind, and past where the ground's debris lifts. */
    private const val CALMEST_LOOK = 0.6

    /** How much of a gale's lean what is thrown takes: it is in the air for a moment, and a moment is enough. */
    private const val THROWN_SHARE = 0.5

    /** How far from a player the gale is felt, in blocks. */
    private const val REACH = 32.0

    private const val FULL_TURN = 360.0
    private const val NONE = 0.0
    private const val WHOLLY = 1.0
    private const val WINDY_SALT = 0x6A1E_5B10_3C27_9D4FL
    private const val BEARING_SALT = 0x0B3A_7C55_E219_D68AL
}

/**
 * How much of a gale blows over the Age you are standing in, which way, and how hard.
 *
 * **Sent rather than derived**, as [FogPayload] is: whether an Age has a gale is a fact about a recipe that never
 * leaves the server, and the schedule is drawn off a seed a `ClientLevel` lacks. [age] lets a client refuse a
 * message meant for the world it just left.
 */
data class GalePayload(val age: Identifier, val lying: Double, val bearingDegrees: Double, val fury: Double) :
    CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<GalePayload> = TYPE

    /** The gale this says blows, or null where none does. */
    val blowing: Gale.Blowing? get() = if (lying <= 0.0) null else Gale.Blowing(lying, bearingDegrees, fury)

    companion object {
        val TYPE: CustomPacketPayload.Type<GalePayload> = CustomPacketPayload.Type("gale".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, GalePayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            GalePayload::age,
            ByteBufCodecs.DOUBLE,
            GalePayload::lying,
            ByteBufCodecs.DOUBLE,
            GalePayload::bearingDegrees,
            ByteBufCodecs.DOUBLE,
            GalePayload::fury,
            ::GalePayload,
        )

        /** What an Age with no gale says, which is most of them. */
        fun noneIn(age: Identifier) = GalePayload(age, lying = 0.0, bearingDegrees = 0.0, fury = 0.0)
    }
}
