package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.book.panel.PanelViews
import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.Spending
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
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wind turned up until it pushes ([co.voik.agesandtheart.age.aspect.Phenomenon.GALE], `notes/the-art-design.md` §5.2.2, "Gale").
 *
 * **Spells of its own, as a fog's are**: time is cut into spells of [SPELL_TICKS], each windy or calm by a draw off
 * the seed at the share the Age's gale reaches, so every client and every reload agree with nothing written down. A
 * gale rises over the last [ROLLING_TICKS] of the spell before a windy one and dies over the last of a windy one.
 * Each windy spell blows on a bearing of its own, turning to the next over the same stretch where two run together.
 *
 * **While it blows it is the Age's wind**, so the clouds race along it, the rain leans with it and the light
 * particles go its way ([LevelWind.blowInstead]). What it does besides is lean on everything light out in it: the
 * players on their own clients, which move them, and here the creatures, the dropped items and whatever is thrown.
 * It gusts ([Gusts]).
 *
 * **A blizzard's wind is a gale too**: [blowingOver] takes the Age's own gale and its blizzard's together, so
 * everything here a blizzard does as well, and an Age with both has one wind, the blizzard's, made fiercer.
 */
object Gale {

    /** A gale over an Age at one moment: how much of one is [lying] over it, which way it drives, and its [fury]. */
    data class Blowing(val lying: Double, val bearingDegrees: Double, val fury: Double)

    /** The gale over an Age of [seed] at [gameTime], blowing this often and this hard, or null where none does. */
    fun blowingAt(seed: Long, gameTime: Long, recurrence: Recurrence, dials: GaleDials, written: Double? = null): Blowing? {
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
            blowsNow && blowsNext -> turnedTowards(bearingOf(seed, spell, written), bearingOf(seed, spell + 1, written), turning)
            blowsNow -> bearingOf(seed, spell, written)
            else -> bearingOf(seed, spell + 1, written)
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
     * How hard [blowing] leans with [gust] in it: from its steady lean up to the gust's strength and back as the gust
     * comes and goes, never under the steady lean, and only as much as the gale has risen.
     */
    fun pushStrength(scale: Double, blowing: Blowing, gusts: Gusts, gust: Gust?): Double {
        val steady = pushStrength(scale, blowing)
        val gusting = gust?.gusting ?: return steady
        val gustingAt = maxOf(steady, scale * gusts.strength * blowing.lying)
        return steady + (gustingAt - steady) * gusting
    }

    /** The bearing [blowing] drives along with [gust] in it, swung toward the gust's own as it blows. */
    fun drivingDegrees(blowing: Blowing, gust: Gust?): Double =
        blowing.bearingDegrees + (gust?.let { it.veerDegrees * it.gusting } ?: NONE)

    /**
     * The wind [blowing] makes the Age's while it lasts: as hard as it blows, from a strong wind for the gentlest gale
     * to the strongest for the fiercest, rising and dying with it, and harder still while [gust] blows.
     */
    fun windOf(blowing: Blowing, gust: Gust? = null): Wind {
        val steady = blowing.lying * (CALMEST_LOOK + (WHOLLY - CALMEST_LOOK) * blowing.fury)
        val gusting = blowing.lying * GUST_LOOK * (gust?.gusting ?: NONE)
        return Wind(
            bearingDegrees = drivingDegrees(blowing, gust).toFloat(),
            strength = (steady + gusting).coerceAtMost(WHOLLY).toFloat(),
            wobbleDegrees = 0.0f,
        )
    }

    /**
     * What blows over [level] now: its own [gale], the wind of its [blizzard], or both as one. Where both blow, the
     * storm's bearing holds, since its drifts are laid along it, and the gale adds half its fury to the storm's.
     */
    fun blowingOver(level: ServerLevel, gale: Befalling?, blizzard: Befalling?, spending: Spending): Blowing? {
        val written = PrevailingWind.writtenIn(level)
        val own = gale?.let { blowingAt(level.seed, level.gameTime, it.recurrence, GaleDials.of(spending, it.strength), written) }
        val storm = blizzard?.let { Blizzard.windIn(level, BlizzardDials.of(spending, it.strength)) }
        return together(own, storm)
    }

    /** An Age's own gale and its storm's wind as one wind. */
    fun together(own: Blowing?, storm: Blowing?): Blowing? = when {
        storm == null -> own
        own == null -> storm
        else -> Blowing(
            lying = maxOf(own.lying, storm.lying),
            bearingDegrees = storm.bearingDegrees,
            fury = (storm.fury + own.fury * own.lying * STORM_TAKES).coerceAtMost(WHOLLY),
        )
    }

    /** When whatever blows over an Age gusts: as its own gale's dials say, at the mild end where it has none. */
    fun gustsOver(level: ServerLevel, gale: Befalling?, spending: Spending): Gusts =
        Gusts.of(level.seed, GaleDials.of(spending, gale?.strength ?: UNSAID))

    /** One tick of [blowing] over [level], gusting as [gusts] say, or a lull where nothing blows. */
    fun blow(level: ServerLevel, blowing: Blowing?, gusts: Gusts) {
        if (blowing == null) return lull(level)
        val gust = gusts.at(level.gameTime)
        LevelWind.blowInstead(level, windOf(blowing, gust))
        leanOnWhatIsOutInIt(level, blowing, gusts, gust)
    }

    /** Nothing blowing over [level]: its own wind blows again. */
    private fun lull(level: ServerLevel) = LevelWind.blowInstead(level, null)

    /**
     * Everything light near a player, leaned on — once each, however many players it is near. Players are leaned on
     * by their own clients, which move them.
     */
    private fun leanOnWhatIsOutInIt(level: ServerLevel, blowing: Blowing, gusts: Gusts, gust: Gust?) {
        val strength = pushStrength(AgeConfig.galeWind.get(), blowing, gusts, gust)
        if (strength <= NONE) return
        val driving = drivingDegrees(blowing, gust)
        val leanedOn = HashSet<Entity>()
        for (player in Sampling.watchers(level)) {
            val around = AABB(player.blockPosition()).inflate(REACH)
            for (entity in level.getEntities(player, around, ::isLightEnough)) {
                if (leanedOn.add(entity)) leanOn(level, entity, driving, strength)
            }
        }
    }

    /** What a gale moves besides a player: what lives, what was dropped and what was thrown, while it is in the air. */
    private fun isLightEnough(entity: Entity): Boolean {
        val isMovable = entity is LivingEntity || entity is ItemEntity || entity is Projectile
        val isHeldOrSheltered = entity.isPassenger || entity.isInWater || entity.isNoGravity || entity.isSpectator
        return isMovable && entity !is Player && entity !is ArmorStand && !isHeldOrSheltered
    }

    private fun leanOn(level: ServerLevel, entity: Entity, drivingDegrees: Double, strength: Double) {
        val exposed = Sampling.exposureAt(level, entity.blockPosition())
        if (exposed <= 0.0f) return
        val friction = if (entity.onGround()) {
            level.getBlockState(entity.blockPosBelowThatAffectsMyMovement).block.friction
        } else {
            ORDINARY_FRICTION
        }
        val carried = if (entity is Projectile) THROWN_SHARE else WHOLLY
        val push = windPush(strength, exposed, friction) * carried
        val driving = Math.toRadians(drivingDegrees)
        entity.push(cos(driving) * push, 0.0, sin(driving) * push)
    }

    /**
     * Tell everyone in [level] how much of a gale blows over it, which way, how hard and when it gusts. Sent where
     * there is none too, so a client leaving a gale for a calm Age does not carry the wind with it.
     */
    fun tellTheClients(level: ServerLevel, blowing: Blowing?, gusts: Gusts) {
        val age = level.dimension().identifier()
        val telling = if (blowing == null) GalePayload.noneIn(age)
        else GalePayload(age, blowing.lying, blowing.bearingDegrees, blowing.fury, gusts)
        for (player in PanelViews.audienceOf(level)) Services.NETWORK.sendToPlayer(player, telling)
    }

    private fun isWindy(seed: Long, spell: Long, share: Double): Boolean =
        XoroshiroRandomSource(seed xor WINDY_SALT xor spell).nextDouble() < share

    /** A spell's bearing: anywhere, or about the bearing a book [written] for the Age's wind. */
    private fun bearingOf(seed: Long, spell: Long, written: Double?): Double =
        PrevailingWind.drawnAbout(written, XoroshiroRandomSource(seed xor BEARING_SALT xor spell).nextDouble())

    /**
     * How hard the wind leans on whatever is out in it, added to its motion each tick along the bearing, at
     * [strength] from nought to one, as [exposed] as it stands, on ground as slippery as [friction] — ordinary ground
     * in the air.
     *
     * **Capped below a sprint on the ground underfoot**, so sprinting into the wind always gets somewhere, ice
     * included; walking into the fiercest of it may not. In the air a player gains far less and loses far less to
     * drag, so the same push carries them much further, and a jump or a bridge's edge is where it is felt.
     */
    fun windPush(strength: Double, exposed: Float, friction: Float): Double =
        MOST_OF_A_SPRINT * sprintGainOn(friction) * strength.coerceIn(NONE, WHOLLY) * exposed

    /** What a sprint gains each tick on ground of [friction], as `LivingEntity.getFrictionInfluencedSpeed` has it. */
    fun sprintGainOn(friction: Float): Double {
        if (friction <= ORDINARY_FRICTION) return SPRINT_SPEED
        return SPRINT_SPEED * ORDINARY_FRICTION_CUBED / (friction * friction * friction)
    }

    /**
     * How much of the wind is on this spot, from none of it to all of it — **the one definition**, read by the push,
     * a blizzard's cold here and its whiteout on the client.
     *
     * [inTheWind] is a [ConeExposure] reading, which is only taken every [ConeExposure.BETWEEN_READINGS] ticks; sky
     * light, read every time, can only lower it. So walking into cover is felt at once and walking out of it is up to
     * a second late.
     */
    fun exposureAt(level: Level, at: BlockPos, inTheWind: Float): Float =
        minOf(Sampling.exposureAt(level, at), inTheWind)

    /** [from] turned [share] of the short way round to [to], in degrees. */
    fun turnedTowards(from: Double, to: Double, share: Double): Double =
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

    /** How much harder a gale looks at the height of a gust. */
    private const val GUST_LOOK = 0.35

    /** How much of a gale's lean what is thrown takes: it is in the air for a moment, and a moment is enough. */
    private const val THROWN_SHARE = 0.5

    /** How far from a player the gale is felt, in blocks. */
    private const val REACH = 32.0

    /** How much of a gale's fury a storm blowing with it takes on. */
    private const val STORM_TAKES = 0.5

    /** How much of a sprint's gain the fiercest wind takes back. */
    private const val MOST_OF_A_SPRINT = 0.75

    /** A player's sprinting speed — vanilla's walking 0.1 and its sprint's 30% — and ordinary ground's friction. */
    private const val SPRINT_SPEED = 0.13
    const val ORDINARY_FRICTION = 0.6f
    private const val ORDINARY_FRICTION_CUBED = 0.21600002
    private const val UNSAID = 0.0

    private const val NONE = 0.0
    private const val WHOLLY = 1.0
    private const val WINDY_SALT = 0x6A1E_5B10_3C27_9D4FL
    private const val BEARING_SALT = 0x0B3A_7C55_E219_D68AL
}

/**
 * How much of a gale blows over the Age you are standing in, which way, how hard, and when it gusts.
 *
 * **Sent rather than derived**, as [FogPayload] is: whether an Age has a gale is a fact about a recipe that never
 * leaves the server, and the schedule is drawn off a seed a `ClientLevel` lacks. [age] lets a client refuse a
 * message meant for the world it just left.
 */
data class GalePayload(
    val age: Identifier,
    val lying: Double,
    val bearingDegrees: Double,
    val fury: Double,
    val gusts: Gusts,
) : CustomPacketPayload {

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
            Gusts.STREAM_CODEC,
            GalePayload::gusts,
            ::GalePayload,
        )

        /** What an Age with no gale says, which is most of them. */
        fun noneIn(age: Identifier) = GalePayload(age, lying = 0.0, bearingDegrees = 0.0, fury = 0.0, gusts = Gusts.NONE)
    }
}
