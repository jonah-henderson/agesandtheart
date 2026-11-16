package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.Ejecta
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.Glow
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyBlend
import co.voik.ephemeris.client.SkyCorner
import co.voik.ephemeris.client.SkyMoment
import co.voik.ephemeris.client.SkyDrift
import co.voik.ephemeris.client.SkyQuad
import co.voik.ephemeris.client.SkyTurn
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.StarField
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An asteroid's end, drawn in the sky of the Age it ends (design §5.3 "The asteroid").
 *
 * **The asteroid is the clock.** It hangs on a real straight entry line toward a point far past the horizon, so for
 * most of its approach it barely moves and only grows, and at the end it sweeps down and out of sight. Then the
 * flash, the fireball rising beyond the horizon, the glow of the debris coming back down climbing over the sky from
 * that bearing until it is overhead, and the dust drawn over all of it.
 *
 * **Everything is arithmetic over the schedule and the clock**, as the meteor storm's lights are: where the
 * asteroid is at any instant is [sightingOf], and the server never says.
 *
 * The world is flat and the impact far off, so it falls **relative to the eye**: wherever you stand, it comes down
 * the same distance away, across the planet.
 */
object ImpactSky {

    fun register() {
        LevelRendering.skyOverlay(::draw)
    }

    /** Drawn in layers, each over the last: the glow of the sky, the trails, the asteroid, the falling lights, the dust, the flash. */
    private fun draw(moment: SkyMoment) {
        val doom = Impacts.comingTo(moment.level) ?: return
        val time = moment.level.gameTime + moment.partOfATickOn.toDouble()
        val phase = doom.phaseAt(time.toLong()) ?: return
        val hasStruck = time >= doom.impactAt
        val light = mutableListOf<SkyQuad>()
        if (hasStruck) {
            gatherTheFireball(doom, time, light)
            gatherTheDebrisGlow(doom, time, light)
        }
        Blaze3dSkyCanvas.drawShapes(light, SkyBlend.LIGHT)
        val horizon = horizonGlowAt(doom, time)
        if (horizon > NOTHING) Blaze3dSkyCanvas.drawHorizonGlow(doom.bearingDegrees, FIRE_ON_THE_HORIZON.copy(alpha = horizon))
        val plumeSmoke = mutableListOf<SkyQuad>()
        val plumeGlow = mutableListOf<SkyQuad>()
        // Their roiling on a clock that only goes forwards, so a server catching up never throws every billow at once.
        val roil = SkyDrift.steady(moment.level, ROIL_DRIFT, time)
        gatherThePlume(doom, time, roil, plumeSmoke, plumeGlow)
        Blaze3dSkyCanvas.drawShapes(plumeSmoke, SkyBlend.SHADE)
        Blaze3dSkyCanvas.drawShapes(plumeGlow, SkyBlend.LIGHT)
        drawTheTrails(doom, time)
        if (phase == ImpactPhase.APPROACH || phase == ImpactPhase.ENTRY) drawTheAsteroid(doom, time, turnedWithTheStars(moment.level, doom, time))
        if (!hasStruck) return
        val heads = mutableListOf<Glow>()
        val comingSolid = mutableListOf<SkyQuad>()
        val comingLight = mutableListOf<SkyQuad>()
        gatherTheFallingHeads(doom, time, heads)
        gatherTheFragmentsComingDown(moment, comingSolid, comingLight)
        Blaze3dSkyCanvas.drawShapes(comingSolid, SkyBlend.SHADE)
        Blaze3dSkyCanvas.drawShapes(comingLight, SkyBlend.LIGHT)
        Blaze3dSkyCanvas.drawGlows(heads)
        val shade = mutableListOf<SkyQuad>()
        gatherTheDust(doom, time, shade)
        Blaze3dSkyCanvas.drawShapes(shade, SkyBlend.SHADE)
        Blaze3dSkyCanvas.drawShapes(ashWallOf(doom, time, roil), SkyBlend.SHADE)
        val flash = mutableListOf<SkyQuad>()
        gatherTheFlash(doom, time, flash)
        Blaze3dSkyCanvas.drawShapes(flash, SkyBlend.LIGHT)
    }

    // ---- The asteroid --------------------------------------------------------------------------------------

    /** Where the asteroid is seen from the eye: bearing and height in radians, and how far off in blocks. */
    private data class Sighting(val azimuth: Double, val elevation: Double, val distance: Double) {
        val direction: Vector3f get() = directionOf(azimuth, elevation)
    }

    /**
     * Where the asteroid is [ticksToImpact] before it strikes.
     *
     * Its path ends at the impact, [Doom.IMPACT_DISTANCE] out on the Age's bearing, and runs back up and away. It
     * comes down it at one steady speed from first sight to the strike, as a body does, so it grows from a speck and
     * sweeps down with no seam between the two. **A straight line all the way**, which is what keeps it from seeming to
     * swerve: the impact lies [IMPACT_BELOW_THE_EYE] below the eye, as ground that far off over a planet's curve would,
     * so the rock sinks out of sight a moment before it strikes without its path bending to do it.
     */
    private fun sightingOf(doom: Doom, ticksToImpact: Double): Sighting =
        sightingAlong(doom, remainingPathAt(doom, ticksToImpact), NOT_TURNED)

    /**
     * Where a point [back] blocks back up the asteroid's path from the impact is seen from the eye, the whole path
     * [turned] as the sky has turned between now and the strike.
     */
    private fun sightingAlong(doom: Doom, back: Double, turned: Quaternionf): Sighting {
        val bearing = Math.toRadians(doom.bearingDegrees.toDouble())
        val alongX = sin(bearing)
        val alongZ = -cos(bearing)
        val acrossX = cos(bearing)
        val acrossZ = sin(bearing)
        val climb = Math.toRadians(ENTRY_CLIMB_DEGREES)
        val slant = Math.toRadians(ENTRY_SLANT_DEGREES)
        val outward = cos(climb) * cos(slant) * back
        val sideways = cos(climb) * sin(slant) * back
        val x = alongX * (Doom.IMPACT_DISTANCE + outward) + acrossX * sideways
        val z = alongZ * (Doom.IMPACT_DISTANCE + outward) + acrossZ * sideways
        val seen = turned.transform(Vector3d(x, sin(climb) * back - IMPACT_BELOW_THE_EYE, z))
        val horizontal = hypot(seen.x, seen.z)
        return Sighting(atan2(seen.x, -seen.z), atan2(seen.y, horizontal), seen.length())
    }

    /**
     * How the sky turns between [time] and the strike, which carries the asteroid with the stars until it comes: far
     * off it rises and sets as they do, and nearing the strike the turn left shrinks to nothing, so it comes in on its
     * path as it lies over the ground and always down out of the sky. A turn at the stars' own pace, about their axis.
     */
    private fun turnedWithTheStars(level: ClientLevel, doom: Doom, time: Double): Quaternionf {
        val pace = LevelLooks.of(level.dimension())?.sky?.stars?.pace ?: StarField.VANILLAS_PACE
        val turnsStillToCome = pace * (doom.impactAt - time) / TICKS_PER_DAY
        val turnedBackBy = (-turnsStillToCome * WHOLE_TURN).toFloat()
        return SkyTurn.byVanillasAxis(turnedBackBy).mul(SkyTurn.byVanillasAxis(NOT_AT_ALL).invert())
    }

    /** How far up its path the asteroid still is [ticksToImpact] before it strikes: as far as it goes in that time. */
    private fun remainingPathAt(doom: Doom, ticksToImpact: Double): Double =
        PATH_AT_ENTRY / doom.timeline.entry * ticksToImpact.coerceAtLeast(0.0)

    /**
     * Far off, a pale speck of light. Nearer, it becomes [solid]: a tumbling cube of the moon's pixels
     * ([AsteroidBody.cubeOf]) covering the sky behind it, and through the entry wrapped in a sheath of flame
     * ([AsteroidBody.sheathOf]) with a long tail of flames streaming back along its path.
     *
     * Drawn in layers, each over the last: the speck, the tail, the cube, the sheath.
     */
    private fun drawTheAsteroid(doom: Doom, time: Double, turned: Quaternionf) {
        val ticksToImpact = doom.impactAt - time
        val back = remainingPathAt(doom, ticksToImpact)
        val sighting = sightingAlong(doom, back, turned)
        val burning = doom.progressThrough(ImpactPhase.ENTRY, time).toFloat()
        val nearing = doom.progressThrough(ImpactPhase.APPROACH, time).toFloat()
        val bodySize = bodySizeAt(sighting.distance)
        val size = maxOf(speckSizeAt(nearing), bodySize)
        // Drawn on into the strike, cut off at the horizon as it goes down behind it, until the last of it has gone.
        val isWhollyGone = sighting.elevation < -angularRadiusOf(size) * GONE_BELOW_BY
        if (isWhollyGone) return
        val solid = smoothStep(SPECK_UNTIL, SOLID_BY, bodySize.toDouble())
        val frame = DiscFrame(sighting.direction, angularRadiusOf(size))
        val tail = tailOf(frame, sightingAlong(doom, back + TAIL_BEHIND, turned).direction)
        val corona = coronaAt(burning, time)
        val centre = Vector3f(sighting.direction).mul(GLOW_DISTANCE.toFloat())
        val halfEdge = GLOW_DISTANCE.toFloat() * tan(angularRadiusOf(size)) * CUBE_HALF_EDGE
        val travel = Vector3f(positionAlong(doom, back - TRAVEL_STEP, turned)).sub(positionAlong(doom, back, turned)).normalize()

        val speckShows = (FULL - solid) * AsteroidBody.aboveTheHorizon(sighting.direction) * speckLightAt(nearing)
        val speck = PALE_ROCK.lerp(BURNING, sqrt(burning)).dimmed(speckShows)
        Blaze3dSkyCanvas.drawShapes(discAt(sighting.direction, size, speck, haloStrength = BODY_HALO + BURNING_HALO * burning), SkyBlend.LIGHT)
        if (corona > NOTHING) {
            val outline = outlineOf(frame, centre, AsteroidBody.cornersOf(halfEdge, time))
            // Laid over the sky by alpha first, so a day sky does not wash them white, and then shining.
            Blaze3dSkyCanvas.drawShapes(streamersOf(frame, tail, outline, corona, time, StreamerLook.COVERING), SkyBlend.SHADE)
            Blaze3dSkyCanvas.drawShapes(streamersOf(frame, tail, outline, corona, time, StreamerLook.SHINING), SkyBlend.LIGHT)
        }
        if (solid > NOTHING) {
            Blaze3dSkyCanvas.drawShapes(AsteroidBody.cubeOf(centre, halfEdge, travel, solid, burning, corona, time), SkyBlend.SHADE)
        }
        if (corona > NOTHING) {
            val sheath = corona * solid
            Blaze3dSkyCanvas.drawShapes(AsteroidBody.sheathOf(centre, halfEdge, travel, sheath, time, AsteroidBody.Look.COVERING), SkyBlend.SHADE)
            Blaze3dSkyCanvas.drawShapes(AsteroidBody.sheathOf(centre, halfEdge, travel, sheath, time, AsteroidBody.Look.SHINING), SkyBlend.LIGHT)
        }
    }

    /**
     * How far out the cube's outline stands from its middle at each turn round its frame, in radii: as far as its
     * farthest corner reaches that way, the eight corners seen where they lie on the sky.
     */
    private fun outlineOf(frame: DiscFrame, centre: Vector3f, corners: List<Vector3f>): (Double) -> Double {
        val seen = corners.map { frame.planeOf(Vector3f(centre).add(it).normalize()) }
        return { turn -> seen.maxOf { (x, y) -> x * cos(turn) + y * sin(turn) } }
    }

    /** Where a point [back] blocks back up the asteroid's path is from the eye, in blocks. */
    private fun positionAlong(doom: Doom, back: Double, turned: Quaternionf): Vector3f {
        val sighting = sightingAlong(doom, back, turned)
        return Vector3f(sighting.direction).mul(sighting.distance.toFloat())
    }

    /**
     * The way the rock's tail runs over the sky from it, which is back along its path as it lies in the sky: [heading]
     * is the way the rock goes, round its own frame, and [length] how far in radii a point [TAIL_BEHIND] back along
     * the path is seen from it, short where it comes toward the eye or goes from it. [sideOn] is that length against
     * what it would be seen across the path.
     */
    private class Tail(val heading: Double, val length: Double) {
        val sideOn: Double get() = (length / (TAIL_BEHIND / ASTEROID_RADIUS)).coerceIn(0.0, 1.0)
    }

    private fun tailOf(frame: DiscFrame, behindTheRock: Vector3f): Tail {
        val (backX, backY) = frame.planeOf(behindTheRock)
        return Tail(heading = atan2(-backY, -backX), length = hypot(backX, backY))
    }

    /** How brightly the corona burns: flaring through the first part of the entry, and never quite steady. */
    private fun coronaAt(burning: Float, time: Double): Float {
        val flaring = smoothStep(0.0, CORONA_FULL_BY, burning.toDouble())
        val unsteady = FULL - CORONA_FLICKER * (FULL + sin(time * CORONA_FLICKER_PACE).toFloat()) / 2.0f
        return flaring * unsteady
    }

    /** Whether a streamer is laid over the sky by its alpha or added to it as light. */
    private enum class StreamerLook { COVERING, SHINING }

    /**
     * The flames streaming back off the rock: each a broad wedge from a point just inside its [outline] running
     * straight back along its path, so that all of them trail off behind as one long tail. Each flickers on a pace of
     * its own.
     */
    private fun streamersOf(frame: DiscFrame, tail: Tail, outline: (Double) -> Double, strength: Float, time: Double, look: StreamerLook): List<SkyQuad> {
        val heading = tail.heading
        val halfWidth = PI / STREAMERS * STREAMER_WIDENS
        val flares = STREAMER_FLARES + (1.0 - tail.sideOn) * STREAMER_FLARES_HEAD_ON
        val backX = -cos(heading)
        val backY = -sin(heading)
        val sideX = -sin(heading)
        val sideY = cos(heading)
        return (0..<STREAMERS).map { number ->
            val turn = 2.0 * PI * (number + unitAt(number.toLong(), STREAMER_PLACE_SALT)) / STREAMERS
            val behind = (FULL - cos(turn - heading)) / 2.0
            val pace = STREAMER_SLOWEST + unitAt(number.toLong(), STREAMER_PACE_SALT) * STREAMER_PACE_SPREAD
            val wave = sin(time * pace + 2.0 * PI * unitAt(number.toLong(), STREAMER_PHASE_SALT))
            val flicker = STREAMER_STEADY + (FULL - STREAMER_STEADY) * wave
            val reach = maxOf(tail.length * (STREAMER_SHORTEST + STREAMER_LONGEST * behind) / (STREAMER_SHORTEST + STREAMER_LONGEST), STREAMER_LEAST)
            val length = reach * (STREAMER_SHORTENS + (FULL - STREAMER_SHORTENS) * flicker)
            val root = outline(turn) * STREAMER_ROOTED_AT
            val aside = root * (cos(turn) * sideX + sin(turn) * sideY)
            val tipX = root * cos(turn) + length * (backX + flares * aside * sideX)
            val tipY = root * sin(turn) + length * (backY + flares * aside * sideY)
            val share = (strength * flicker * (STREAMER_AHEAD + (FULL - STREAMER_AHEAD) * behind)).toFloat()
            val colour = when (look) {
                StreamerLook.COVERING -> FLAME.copy(alpha = share * FLAME_COVERS)
                StreamerLook.SHINING -> FLAME.dimmed(share).copy(alpha = FULL)
            }
            val nothing = when (look) {
                StreamerLook.COVERING -> Rgba.CLEAR
                StreamerLook.SHINING -> DARK
            }
            fun rootAt(at: Double): SkyCorner {
                val reach = outline(at) * STREAMER_ROOTED_AT
                val direction = frame.atPlane(reach * cos(at), reach * sin(at))
                val seen = AsteroidBody.aboveTheHorizon(direction)
                val shown = when (look) {
                    StreamerLook.COVERING -> colour.copy(alpha = colour.alpha * seen)
                    StreamerLook.SHINING -> colour.dimmed(seen).copy(alpha = FULL)
                }
                return SkyCorner(direction, shown)
            }
            val tip = SkyCorner(frame.atPlane(tipX, tipY), nothing)
            SkyQuad(rootAt(turn - halfWidth), rootAt(turn + halfWidth), tip, tip)
        }
    }

    private fun turnOf(segment: Int): Double = 2.0 * PI * segment / DISC_SEGMENTS

    /** How big a body of [ASTEROID_RADIUS] looks [distance] blocks off, in the units `drawGlow` takes. */
    private fun sizeAt(distance: Double): Float = maxOf(SPECK, bodySizeAt(distance))

    /** The same, as the rock itself would look, however small. */
    private fun bodySizeAt(distance: Double): Float = (GLOW_DISTANCE * ASTEROID_RADIUS / distance).toFloat()

    /**
     * How big and how bright the speck is [nearing] of the way through the approach: growing steadily from a star's
     * size and light at the bind to well past any star's by the end, so each night it is plainly larger than the last.
     * Not as it would truly look, which would be the same point of light for days and then all at once a rock; the
     * rock's own size takes over in the last minute, where it outgrows this.
     */
    private fun speckSizeAt(nearing: Float): Float = SPECK_FARTHEST + (SPECK_NEAREST - SPECK_FARTHEST) * nearing

    private fun speckLightAt(nearing: Float): Float = SPECK_FAINTEST + (FULL - SPECK_FAINTEST) * nearing

    /** How far a thing of [size] reaches from its centre, in radians. */
    private fun angularRadiusOf(size: Float): Float = atan(size / GLOW_DISTANCE.toFloat())

    // ---- After the impact ----------------------------------------------------------------------------------

    /**
     * The plume the strike threw up: rock turned to vapour rising beyond the horizon where it struck, out past the
     * air and ballooning there into a spreading cap, white-hot at first and cooling through orange and dull red to
     * dark smoke, its foot staying hot longest. It hangs there, slowly spreading, until the ash covers it. Its sides
     * billow, each part on a pace of its own.
     *
     * A surface turned about the upright over where it struck, as wide as [plumeRadiusAt] says at each height, on
     * ground that curves away so its foot is out of sight. Laid over the sky as smoke, then shining as far as it is
     * still hot.
     */
    private fun gatherThePlume(doom: Doom, time: Double, roil: Double, smoke: MutableList<SkyQuad>, glow: MutableList<SkyQuad>) {
        val since = time - doom.impactAt
        if (since <= 0.0) return
        val struckAt = Vector3f(directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)).mul(Doom.IMPACT_DISTANCE.toFloat())
        val foot = -(struckAt.lengthSquared() / (2.0 * ASH_GROUND_CURVES))
        val top = PLUME_HIGHEST * (1.0 - exp(-since / PLUME_RISES_TICKS))
        val spread = PLUME_WIDEST * (1.0 - exp(-since / PLUME_SPREADS_TICKS))
        val cooled = exp(-since / PLUME_COOLS_TICKS)
        val fadingIn = smoothStep(0.0, PLUME_GATHERS_TICKS, since).toFloat()
        fun point(level: Int, sector: Int): Vector3f {
            val share = level.toDouble() / PLUME_LEVELS
            val turn = 2.0 * PI * sector / PLUME_SECTORS
            val billow = PLUME_BILLOWS * sin(turn * PLUME_BILLOW_LOBES + level * PLUME_BILLOW_STEP + roil * PLUME_BILLOW_PACE) +
                PLUME_PUFFS * sin(turn * PLUME_PUFF_LOBES - level * PLUME_BILLOW_RISE + roil * PLUME_BILLOW_PACE * 2.0)
            val bulge = PLUME_BULGES * abs(sin(level * PLUME_BULGE_STEP - roil * PLUME_BILLOW_PACE))
            val radius = plumeRadiusAt(share, spread) * (1.0 + billow + bulge)
            val height = foot + top * share
            return Vector3f(struckAt).add((cos(turn) * radius).toFloat(), height.toFloat(), (sin(turn) * radius).toFloat()).normalize()
        }
        fun heatAt(level: Int): Float = (cooled * (PLUME_TOP_COOLER + (1.0 - PLUME_TOP_COOLER) * (1.0 - level.toDouble() / PLUME_LEVELS))).toFloat()
        fun smokeAt(level: Int): Rgba {
            val thinning = if (level == PLUME_LEVELS) NOTHING else FULL
            return PLUME_SMOKE.copy(alpha = PLUME_THICKNESS * fadingIn * thinning * (FULL - heatAt(level) * PLUME_HEAT_SHOWS_THROUGH))
        }
        fun glowAt(level: Int): Rgba {
            val heat = heatAt(level)
            val colour = DULL_RED.lerp(FIREBALL_ORANGE, (heat * 2.0f).coerceAtMost(FULL)).lerp(WHITE_HOT, ((heat - HALF) * 2.0f).coerceAtLeast(NOTHING))
            return colour.dimmed(heat * fadingIn).copy(alpha = FULL)
        }
        for (level in 0..<PLUME_LEVELS) {
            for (sector in 0..<PLUME_SECTORS) {
                smoke += SkyQuad(
                    SkyCorner(point(level, sector), smokeAt(level)), SkyCorner(point(level, sector + 1), smokeAt(level)),
                    SkyCorner(point(level + 1, sector + 1), smokeAt(level + 1)), SkyCorner(point(level + 1, sector), smokeAt(level + 1)),
                )
                glow += SkyQuad(
                    SkyCorner(point(level, sector), glowAt(level)), SkyCorner(point(level, sector + 1), glowAt(level)),
                    SkyCorner(point(level + 1, sector + 1), glowAt(level + 1)), SkyCorner(point(level + 1, sector), glowAt(level + 1)),
                )
            }
        }
    }

    /**
     * How wide the plume is [share] of the way up it, in blocks: a column at its foot, opening fast toward the top into a
     * cap [spread] wide that rounds over at its crown.
     */
    private fun plumeRadiusAt(share: Double, spread: Double): Double {
        val opening = PLUME_FOOT + (spread - PLUME_FOOT) * share.pow(PLUME_OPENS)
        val crown = sqrt((1.0 - share).coerceAtLeast(0.0) / PLUME_CROWN).coerceAtMost(1.0)
        return opening * crown
    }

    /** The fireball rising beyond the horizon where it struck, white-hot and then dull red. */
    private fun gatherTheFireball(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val strength = horizonGlowAt(doom, time)
        if (strength <= NOTHING) return
        val rising = ((time - doom.impactAt) / FIREBALL_RISES_TICKS).coerceIn(0.0, 1.0)
        val elevation = Math.toRadians(FIREBALL_LOWEST + (FIREBALL_HIGHEST - FIREBALL_LOWEST) * rising.pow(FIREBALL_EASING))
        val direction = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), elevation)
        val size = (FIREBALL_SMALLEST + (FIREBALL_LARGEST - FIREBALL_SMALLEST) * rising).toFloat()
        val cooling = rising.toFloat()
        val colour = WHITE_HOT.lerp(FIREBALL_ORANGE, (cooling * FIREBALL_COOLS_FASTER).coerceAtMost(1.0f)).lerp(DULL_RED, cooling)
        into += discAt(direction, size, colour.dimmed(strength), haloStrength = FIREBALL_HALO_STRENGTH)
    }

    /** How strongly the horizon burns where it struck: from the impact, through the heat, fading into the dust. */
    private fun horizonGlowAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.ENTRY -> GLOW_BEFORE_THE_STRIKE * smoothStep(BEYOND_THE_HORIZON_FROM, 1.0, doom.progressThrough(ImpactPhase.ENTRY, time))
        ImpactPhase.TRAVEL, ImpactPhase.HEAT -> FULL
        ImpactPhase.DUST -> Impacts.heatAt(doom, time)
        else -> NOTHING
    }

    /** How far, in degrees from the horizon where it struck, the debris coming back down has reached over the sky. */
    private fun frontAt(doom: Doom, time: Double): Double =
        OVERHEAD * (time - doom.impactAt) / doom.timeline.travel

    /** How brightly the sky glows where the debris has reached. */
    private fun skyGlowAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL -> (GLOW_AT_IMPACT + (FULL - GLOW_AT_IMPACT) * doom.progressThrough(ImpactPhase.TRAVEL, time)).toFloat()
        ImpactPhase.HEAT -> FULL - FLICKER * (FULL + sin(time * FLICKER_PACE).toFloat()) / 2.0f
        ImpactPhase.DUST -> Impacts.heatAt(doom, time)
        else -> NOTHING
    }

    /** How much of the debris' glow lies over [corner]: all of it behind the front, none ahead, a soft edge between. */
    private fun litAt(corner: GridCorner, impactDirection: Vector3f, front: Double): Float {
        val fromTheImpact = Math.toDegrees(acos(corner.direction.dot(impactDirection).toDouble().coerceIn(-1.0, 1.0)))
        return smoothStep(front + FRONT_SOFTNESS, front - FRONT_SOFTNESS, fromTheImpact)
    }

    private fun gatherTheDebrisGlow(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        val impactDirection = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)
        val front = frontAt(doom, time)
        into += quadsOverTheSky { corner ->
            val lit = litAt(corner, impactDirection, front) * strength
            val height = (corner.elevationDegrees / OVERHEAD).toFloat().coerceIn(0.0f, 1.0f)
            LOW_GLOW.lerp(HIGH_GLOW, height).dimmed(lit * GLOW_BRIGHTNESS).copy(alpha = FULL)
        }
    }

    // ---- The trails -------------------------------------------------------------------------------------------

    /** What burning things leave behind them, drawn over the sky's glow: smoke laid over by alpha, then the fresh glow. */
    private fun drawTheTrails(doom: Doom, time: Double) {
        val smoke = mutableListOf<SkyQuad>()
        val glow = mutableListOf<SkyQuad>()
        gatherTheTrain(doom, time, smoke, glow)
        gatherTheFallingTrails(doom, time, smoke, glow)
        Blaze3dSkyCanvas.drawShapes(smoke, SkyBlend.SHADE)
        Blaze3dSkyCanvas.drawShapes(glow, SkyBlend.LIGHT)
    }

    /** One point a trail passes through: which way it lies, which way is across it, and how wide it is there. */
    private class TrailPoint(val direction: Vector3f, val across: Vector3f, val halfWidth: Float, val smoke: Rgba, val glow: Rgba)

    /** A ribbon through [points], coloured by [colourOf] down its middle and fading to [edge] at either side. */
    private fun ribbonThrough(points: List<TrailPoint>, colourOf: (TrailPoint) -> Rgba, edge: Rgba): List<SkyQuad> {
        fun sideOf(point: TrailPoint, side: Float): Vector3f =
            Vector3f(point.across).mul(side * tan(point.halfWidth)).add(point.direction).normalize()
        return buildList {
            for (index in 0..<points.lastIndex) {
                val here = points[index]
                val next = points[index + 1]
                val colourHere = colourOf(here)
                val colourNext = colourOf(next)
                val isFaded = !colourHere.isVisible() && !colourNext.isVisible()
                if (isFaded) continue
                for (side in listOf(-1.0f, 1.0f)) {
                    add(
                        SkyQuad(
                            SkyCorner(here.direction, colourHere),
                            SkyCorner(sideOf(here, side), edge),
                            SkyCorner(sideOf(next, side), edge),
                            SkyCorner(next.direction, colourNext),
                        ),
                    )
                }
            }
        }
    }

    private fun gatherTrail(points: List<TrailPoint>, smoke: MutableList<SkyQuad>, glow: MutableList<SkyQuad>) {
        if (points.size < 2) return
        smoke += ribbonThrough(points, { it.smoke }, Rgba.CLEAR)
        glow += ribbonThrough(points, { it.glow }, DARK)
    }

    /**
     * The train the asteroid leaves through its entry: burning just behind it, then smoke that hangs where it passed,
     * spreading and thinning, long after it has gone below the horizon.
     *
     * Its points are fixed moments on the asteroid's path, so the train stays where it was laid.
     */
    private fun gatherTheTrain(doom: Doom, time: Double, smoke: MutableList<SkyQuad>, glow: MutableList<SkyQuad>) {
        val entryBegan = doom.startOf(ImpactPhase.ENTRY).toDouble()
        val impact = doom.impactAt.toDouble()
        val isLaidOrLingering = time > entryBegan && time < impact + TRAIN_LINGERS_TICKS
        if (!isLaidOrLingering) return
        val spacing = doom.timeline.entry.toDouble() / TRAIN_POINTS
        val newest = minOf(time, impact)
        val first = ceil((time - TRAIN_LINGERS_TICKS - entryBegan) / spacing).toInt().coerceAtLeast(0)
        val last = floor((newest - entryBegan) / spacing).toInt()
        val passedAt = (first..last).map { entryBegan + it * spacing }.toMutableList()
        val headIsApart = passedAt.isEmpty() || newest - passedAt.last() > SAME_MOMENT
        if (headIsApart) passedAt += newest
        if (passedAt.size < 2) return
        val sightings = passedAt.map { sightingOf(doom, impact - it) }
        val directions = sightings.map { it.direction }
        val acrosses = acrossesOf(directions)
        val points = passedAt.indices.map { index ->
            val age = time - passedAt[index]
            val burningThen = doom.progressThrough(ImpactPhase.ENTRY, passedAt[index])
            val laid = smoothStep(0.0, TRAIN_LAID_BY, burningThen)
            val fading = (FULL - (age / TRAIN_LINGERS_TICKS).toFloat()).coerceAtLeast(NOTHING)
            val gathering = smoothStep(0.0, SMOKE_GATHERS_TICKS, age)
            val body = angularRadiusOf(sizeAt(sightings[index].distance))
            TrailPoint(
                direction = directions[index],
                across = acrosses[index],
                halfWidth = body * TRAIN_WIDTH_OF_THE_BODY + (TRAIN_SPREADS_PER_TICK * age).toFloat(),
                smoke = TRAIN_SMOKE.copy(alpha = TRAIN_SMOKE_THICKNESS * laid * gathering * fading),
                glow = BURNING.dimmed(laid * exp(-age / TRAIN_GLOW_FADES_TICKS).toFloat()).copy(alpha = FULL),
            )
        }
        gatherTrail(points, smoke, glow)
    }

    /** Which way is across a line through [directions] at each of them, from its neighbours on either side. */
    private fun acrossesOf(directions: List<Vector3f>): List<Vector3f> {
        var lastGood = UP
        return directions.indices.map { index ->
            val ahead = directions[minOf(index + 1, directions.lastIndex)]
            val behind = directions[maxOf(index - 1, 0)]
            val across = Vector3f(ahead).sub(behind).cross(directions[index])
            val isATurn = across.lengthSquared() > NO_TURN
            if (isATurn) lastGood = across.normalize()
            lastGood
        }
    }

    /**
     * One fragment of the debris coming back down, as a body would: from [start], a point in the layer the debris
     * burns in, along [travel] over [burnTicks] from [startedAt], both in blocks from the eye. Seen from below, those
     * overhead are near and cross the sky wide and fast, and those low in the sky far off, thin and slow.
     */
    private class Fall(val start: Vector3f, val travel: Vector3f, val burnTicks: Double, val startedAt: Double) {
        fun positionAt(share: Double): Vector3f = Vector3f(travel).mul(share.toFloat()).add(start)

        fun at(share: Double): Vector3f = positionAt(share).normalize()

        fun distanceAt(share: Double): Double = positionAt(share).length().toDouble()

        /** Which way is across its path, as it is seen at [share] of the way along it. */
        fun acrossAt(share: Double): Vector3f = Vector3f(travel).cross(positionAt(share)).normalize()
    }

    /**
     * Fragment [number]'s fall in the cycle [time] lies in, or null where the glow had not yet reached its start when
     * it began. It starts where its sightline meets the layer the debris burns in, and runs outward from where the
     * asteroid struck and down, so all of them seem to come from that quarter of the sky.
     */
    private fun fallOf(doom: Doom, number: Int, time: Double): Fall? {
        val offset = unitAt(number.toLong(), OFFSET_SALT) * FALL_CYCLE_TICKS
        val cycle = floor((time + offset) / FALL_CYCLE_TICKS).toLong()
        val startedAt = cycle * FALL_CYCLE_TICKS - offset
        fun drawn(salt: Long) = unitAt(number.toLong(), cycle xor salt)
        val elevation = FALL_LOWEST + drawn(HEIGHT_SALT) * (FALL_HIGHEST - FALL_LOWEST)
        val sightline = directionOf(drawn(AZIMUTH_SALT) * 2.0 * PI, Math.toRadians(elevation))
        val impactDirection = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)
        val hadReachedIt = litAt(GridCorner(sightline, elevation), impactDirection, frontAt(doom, startedAt)) >= HALF
        if (!hadReachedIt) return null
        val layer = DEBRIS_LAYER * (1.0 + (drawn(LAYER_SALT) * 2.0 - 1.0) * DEBRIS_LAYER_SPREAD)
        val start = Vector3f(sightline).mul((layer / sin(Math.toRadians(elevation))).toFloat())
        val struckAt = Vector3f(impactDirection).mul(Doom.IMPACT_DISTANCE.toFloat())
        val outward = Vector3f(start.x - struckAt.x, 0.0f, start.z - struckAt.z)
        if (outward.lengthSquared() < NO_TURN) return null
        outward.normalize()
        val swerve = (drawn(SWERVE_SALT) * 2.0 - 1.0) * Math.toRadians(FALL_SWERVES_DEGREES)
        val aside = Vector3f(outward).cross(UP)
        val heading = Vector3f(outward).mul(cos(swerve).toFloat()).add(aside.mul(sin(swerve).toFloat()))
        val dip = Math.toRadians(FALL_SHALLOWEST + drawn(DIP_SALT) * (FALL_STEEPEST - FALL_SHALLOWEST))
        val length = FALL_SHORTEST + drawn(LENGTH_SALT) * (FALL_LONGEST - FALL_SHORTEST)
        val travel = heading.mul((cos(dip) * length).toFloat()).add(0.0f, (-sin(dip) * length).toFloat(), 0.0f)
        val burnTicks = FALL_QUICKEST_TICKS + drawn(BURN_SALT) * (FALL_SLOWEST_TICKS - FALL_QUICKEST_TICKS)
        return Fall(start, travel, burnTicks, startedAt)
    }

    /**
     * How much of a light lying [direction] from the eye is seen through the air: all of it overhead, and less the
     * lower it lies and the more air it is seen through, fading into the sky near the horizon.
     */
    private fun seenThroughTheAir(direction: Vector3f): Float {
        val air = FULL / maxOf(direction.y, THINNEST_SLICE)
        return exp(-(air - FULL) * HAZE)
    }

    /**
     * The trails the fragments leave: fixed where each was laid, glowing just behind its head, then smoke spreading
     * and thinning until the fragment's next fall, each as wide as it is near.
     */
    private fun gatherTheFallingTrails(doom: Doom, time: Double, smoke: MutableList<SkyQuad>, glow: MutableList<SkyQuad>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        for (number in 0..<FALLS) {
            val fall = fallOf(doom, number, time) ?: continue
            val sinceItBegan = time - fall.startedAt
            val reached = (sinceItBegan / fall.burnTicks).coerceAtMost(1.0)
            fun pointAt(share: Double): TrailPoint {
                val age = sinceItBegan - share * fall.burnTicks
                val direction = fall.at(share)
                val seen = strength * seenThroughTheAir(direction)
                val entering = smoothStep(0.0, FALL_BRIGHTENS_OVER, share)
                val fading = (FULL - (age / FALL_TRAIL_LINGERS_TICKS).toFloat()).coerceAtLeast(NOTHING)
                val gathering = smoothStep(0.0, SMOKE_GATHERS_TICKS, age)
                val halfWidth = FALL_TRAIL_WIDTH + FALL_TRAIL_SPREADS_PER_TICK * age
                val thinning = sqrt(FALL_TRAIL_WIDTH / halfWidth).toFloat()
                return TrailPoint(
                    direction = direction,
                    across = fall.acrossAt(share),
                    halfWidth = (halfWidth / fall.distanceAt(share)).toFloat(),
                    smoke = FALL_SMOKE.copy(alpha = FALL_SMOKE_THICKNESS * seen * entering * gathering * fading * thinning),
                    glow = FALL_GLOW.dimmed(seen * entering * exp(-age / FALL_GLOW_FADES_TICKS).toFloat()).copy(alpha = FULL),
                )
            }
            val laid = (0..FALL_TRAIL_POINTS).map { it.toDouble() / FALL_TRAIL_POINTS }.filter { it < reached }
            val isBurning = reached < 1.0
            val shares = if (isBurning) laid + reached else laid + 1.0
            gatherTrail(shares.map(::pointAt), smoke, glow)
        }
    }

    /** The heads of the fragments still burning: brightening as each comes in, gone as it burns out, as big as each is near. */
    private fun gatherTheFallingHeads(doom: Doom, time: Double, into: MutableList<Glow>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        for (number in 0..<FALLS) {
            val fall = fallOf(doom, number, time) ?: continue
            val reached = (time - fall.startedAt) / fall.burnTicks
            if (reached >= 1.0) continue
            val direction = fall.at(reached)
            val brightness = strength * seenThroughTheAir(direction) *
                smoothStep(0.0, FALL_BRIGHTENS_OVER, reached) * smoothStep(1.0, FALL_BURNS_OUT_FROM, reached)
            val size = (GLOW_DISTANCE * FALL_HEAD_ACROSS / fall.distanceAt(reached)).toFloat()
            into += glowAt(direction, size, FALL_HEAD.dimmed(brightness))
        }
    }

    /**
     * Each fragment of the asteroid still held before it falls near the eye ([Ejecta.isHeld]), seen coming down its
     * own path from high up and slowing as it comes. Drawn as the fragment draws itself once it is let go — the lump,
     * its bloom and its streak, from the same measures in [MoltenLumpRenderer] and [AddedLight] — only far off, so
     * where the one hands over to the other nothing changes but the distance.
     */
    private fun gatherTheFragmentsComingDown(moment: SkyMoment, solid: MutableList<SkyQuad>, light: MutableList<SkyQuad>) {
        val eye = moment.camera.position()
        val lump = MoltenLumpRenderer.EJECTA.toDouble()
        val bloom = lump * MoltenLumpRenderer.BLOOM_ACROSS * MoltenLumpRenderer.EJECTA_BLOOM
        val streakWidth = lump * MoltenLumpRenderer.STREAK_ACROSS
        val streakTicks = AddedLight.TICKS_OF_TRAIL * MoltenLumpRenderer.EJECTA_STREAK
        val burns = MoltenLumpRenderer.EJECTA_BURNS
        for (fragment in moment.level.entitiesForRendering()) {
            if (fragment !is Ejecta || !fragment.isHeld) continue
            val going = fragment.deltaMovement.normalize()
            val travel = Vector3f(going.x.toFloat(), going.y.toFloat(), going.z.toFloat())
            val ticksLeft = fragment.ticksStillHeld(moment.partOfATickOn)
            fun positionAt(ticksStillToGo: Double): Vector3f {
                val back = Ejecta.backAlongItsPath(ticksStillToGo)
                return Vector3f(
                    (fragment.x - going.x * back - eye.x).toFloat(),
                    (fragment.y - going.y * back - eye.y).toFloat(),
                    (fragment.z - going.z * back - eye.z).toFloat(),
                )
            }
            val head = positionAt(ticksLeft)
            val distance = head.length().toDouble()
            val direction = Vector3f(head).normalize()
            val coming = smoothStep(Ejecta.HELD_TICKS.toDouble(), Ejecta.HELD_TICKS * (1.0 - COMING_BRIGHTENS_OVER), ticksLeft)
            val strength = burns.alpha * coming * seenThroughTheAir(direction)
            fun across(blocks: Double) = atan(blocks / distance).toFloat()
            solid += squareAt(direction, across(lump / 2.0), MAGMA.copy(alpha = coming))
            light += bloomAt(direction, across(bloom * AddedLight.SPREAD), burns.dimmed(strength * AddedLight.HALO_KEEPS))
            light += bloomAt(direction, across(bloom), burns.dimmed(strength))
            for ((width, keeps) in listOf(streakWidth * AddedLight.SPREAD to AddedLight.HALO_KEEPS, streakWidth to FULL)) {
                val points = (0..COMING_TAIL_POINTS).map { step ->
                    val share = step.toDouble() / COMING_TAIL_POINTS
                    val position = positionAt(minOf(ticksLeft + share * streakTicks, Ejecta.HELD_TICKS.toDouble()))
                    val left = (1.0 - share).toFloat()
                    TrailPoint(
                        direction = Vector3f(position).normalize(),
                        across = Vector3f(travel).cross(position).normalize(),
                        halfWidth = atan(width * left / position.length()).toFloat(),
                        smoke = Rgba.CLEAR,
                        glow = burns.dimmed(strength * keeps * left * left).copy(alpha = FULL),
                    )
                }
                light += ribbonThrough(points, { it.glow }, DARK)
            }
        }
    }

    /** A soft round light about [direction], [radius] radians across: [colour] at the middle, nothing at the rim. */
    private fun bloomAt(direction: Vector3f, radius: Float, colour: Rgba): List<SkyQuad> {
        val frame = DiscFrame(direction, radius)
        val middle = SkyCorner(direction, colour.copy(alpha = FULL))
        return (0..<BLOOM_SEGMENTS).map { segment ->
            val turn = 2.0 * PI * segment / BLOOM_SEGMENTS
            val next = 2.0 * PI * (segment + 1) / BLOOM_SEGMENTS
            SkyQuad(middle, middle, SkyCorner(frame.at(turn, FULL), DARK), SkyCorner(frame.at(next, FULL), DARK))
        }
    }

    /** A small square about [direction], [halfSide] radians from its middle to an edge, laid over the sky. */
    private fun squareAt(direction: Vector3f, halfSide: Float, colour: Rgba): List<SkyQuad> {
        val frame = DiscFrame(direction, halfSide)
        return listOf(
            SkyQuad(
                SkyCorner(frame.atPlane(-1.0, -1.0), colour), SkyCorner(frame.atPlane(1.0, -1.0), colour),
                SkyCorner(frame.atPlane(1.0, 1.0), colour), SkyCorner(frame.atPlane(-1.0, 1.0), colour),
            ),
        )
    }

    private fun gatherTheFlash(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val flash = Impacts.flashAt(doom, time)
        if (flash <= NOTHING) return
        val white = Rgba.WHITE.dimmed(flash * FLASH_BRIGHTNESS).copy(alpha = FULL)
        into += quadsOverTheSky { white }
    }

    /** The pall over the whole sky: the heat's smoke, and all of it once the wall of ash has rolled over the eye. */
    private fun gatherTheDust(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val engulfed = smoothStep(1.0 - ASH_ENGULFS_OVER, 1.0 + ASH_ENGULFS_OVER, doom.ashReachedAt(time))
        val dust = maxOf(Impacts.dustAt(doom, time), engulfed)
        if (dust <= NOTHING) return
        val pall = DUST_PALL.copy(alpha = dust * DUST_COVERS)
        into += quadsOverTheSky { pall }
    }

    /**
     * The wall of ash rolling out from where the asteroid struck: a ring about the impact, roiling cloud
     * [ASH_WALL_HEIGHT] tall, rising as it sets out and coming on fast, towering faster and faster as it nears until
     * it rolls over the eye and the pall takes over.
     *
     * Two banks, the one behind standing taller, each topped with rounded billows that swell and sink each on a pace
     * of its own, their crests lit red by the burning sky behind the eye and dark down to the foot.
     */
    private fun ashWallOf(doom: Doom, time: Double, roil: Double): List<SkyQuad> {
        val reached = doom.ashReachedAt(time)
        val isRolling = reached > NOTHING && reached < 1.0 + ASH_ENGULFS_OVER
        if (!isRolling) return emptyList()
        val struckAt = Vector3f(directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)).mul(Doom.IMPACT_DISTANCE.toFloat())
        val radius = reached * Doom.IMPACT_DISTANCE
        val rising = smoothStep(0.0, ASH_RISES_BY, reached)
        val crest = ASH_WALL.lerp(ASH_LIT, Impacts.heatAt(doom, time) * ASH_LIT_BY)
        return ASH_BANKS.flatMap { bank ->
            bankOf(struckAt, radius - bank.behind, ASH_WALL_HEIGHT * bank.taller * rising, bank, crest.dimmed(bank.shade), roil)
        }
    }

    /** One bank of the wall of ash at [radius] about [struckAt], its billows [height] tall at their highest, roiling by [roil]. */
    private fun bankOf(struckAt: Vector3f, radius: Double, height: Double, bank: AshBank, crest: Rgba, roil: Double): List<SkyQuad> {
        if (radius <= NOTHING) return emptyList()
        val body = ASH_WALL.dimmed(bank.shade)
        // The ground curving away under it, so it comes up over the horizon as it nears rather than standing on it.
        fun at(segment: Int, rise: Double): Vector3f {
            val turn = 2.0 * PI * segment / ASH_SEGMENTS
            val ground = Vector3f(struckAt).add((cos(turn) * radius).toFloat(), 0.0f, (sin(turn) * radius).toFloat())
            val sunk = ground.lengthSquared() / (2.0 * ASH_GROUND_CURVES)
            return ground.add(0.0f, (rise - sunk).toFloat(), 0.0f).normalize()
        }
        /**
         * How tall the wall stands at [segment]: a billow, rounded, of a size and width of its own and the odd one
         * towering, swelling and sinking; smaller puffs riding on it; and a ragged fringe over all.
         */
        fun topAt(segment: Int): Double {
            val turn = 2.0 * PI * segment / ASH_SEGMENTS
            // Warped round the ring, so the billows come in uneven widths that shift as they roll.
            val warped = turn * ASH_BILLOWS / (2.0 * PI) + ASH_WARP * sin(turn * ASH_WARP_LOBES + roil * ASH_WARP_PACE + bank.salt)
            val billow = floor(warped).toLong()
            val across = (warped - billow) * 2.0 - 1.0
            val salt = bank.salt + billow
            val pace = ASH_ROILS_SLOWEST + unitAt(salt, ROIL_PACE_SALT) * ASH_ROIL_SPREAD
            val swelling = sin(roil * pace + 2.0 * PI * unitAt(salt, ROIL_PHASE_SALT))
            val drawn = unitAt(salt, BILLOW_SIZE_SALT)
            val towers = if (unitAt(salt, TOWER_SALT) > ASH_TOWERS_OVER) ASH_TOWER else 0.0
            val size = ASH_SMALLEST_BILLOW + drawn * drawn * (1.0 - ASH_SMALLEST_BILLOW) + towers + ASH_ROILS * swelling
            val rounded = ASH_CUSP + (1.0 - ASH_CUSP) * sqrt((1.0 - across * across).coerceAtLeast(0.0))
            val puffs = ASH_PUFFS * abs(sin(turn * ASH_PUFF_COUNT + roil * ASH_PUFF_PACE + unitAt(salt, PUFF_SALT) * PI))
            val fringe = ASH_FRINGE * (unitAt(segment.toLong() + bank.salt, FRINGE_SALT) - 0.5)
            return height * (ASH_BODY + (1.0 - ASH_BODY) * size * rounded + puffs * rounded + fringe)
        }
        return (0..<ASH_SEGMENTS).flatMap { segment ->
            val next = segment + 1
            val top = topAt(segment)
            val nextTop = topAt(next)
            val shoulder = top * ASH_DARK_TO
            val nextShoulder = nextTop * ASH_DARK_TO
            listOf(
                SkyQuad(
                    SkyCorner(at(segment, -ASH_WALL_FOOT), body), SkyCorner(at(next, -ASH_WALL_FOOT), body),
                    SkyCorner(at(next, nextShoulder), body), SkyCorner(at(segment, shoulder), body),
                ),
                SkyQuad(
                    SkyCorner(at(segment, shoulder), body), SkyCorner(at(next, nextShoulder), body),
                    SkyCorner(at(next, nextTop), crest), SkyCorner(at(segment, top), crest),
                ),
            )
        }
    }

    /** One bank of the wall of ash: how far [behind] the front it rolls, how much [taller] it stands, how dark it is. */
    private class AshBank(val behind: Double, val taller: Double, val shade: Float, val salt: Long)

    // ---- The sky as a grid ---------------------------------------------------------------------------------

    /** One corner of the grid laid over the sky: which way it lies, and how high, in degrees. */
    private class GridCorner(val direction: Vector3f, val elevationDegrees: Double)

    /** The sky from a little below the horizon to overhead, as rows of corners — the same every frame. */
    private val GRID: List<List<GridCorner>> by lazy {
        (0..ROWS).map { row ->
            val elevation = LOWEST_ROW + (OVERHEAD - LOWEST_ROW) * row / ROWS
            (0..COLUMNS).map { column ->
                val azimuth = 2.0 * PI * column / COLUMNS
                GridCorner(directionOf(azimuth, Math.toRadians(elevation)), elevation)
            }
        }
    }

    private fun quadsOverTheSky(colourAt: (GridCorner) -> Rgba): List<SkyQuad> {
        val colours = GRID.map { row -> row.map(colourAt) }
        return buildList {
            for (row in 0..<ROWS) {
                for (column in 0..<COLUMNS) {
                    fun corner(atRow: Int, atColumn: Int) = SkyCorner(GRID[atRow][atColumn].direction, colours[atRow][atColumn])
                    val quad = SkyQuad(
                        corner(row, column),
                        corner(row, column + 1),
                        corner(row + 1, column + 1),
                        corner(row + 1, column),
                    )
                    if (quad.corners.any { it.colour.isVisible() }) add(quad)
                }
            }
        }
    }

    private fun Rgba.isVisible() = alpha > NOTHING && (red > NOTHING || green > NOTHING || blue > NOTHING || alpha < FULL)

    // ---- Helpers -------------------------------------------------------------------------------------------

    private fun directionOf(azimuth: Double, elevation: Double): Vector3f = Vector3f(
        (sin(azimuth) * cos(elevation)).toFloat(),
        sin(elevation).toFloat(),
        (-cos(azimuth) * cos(elevation)).toFloat(),
    )

    /**
     * A round light of [size] (in the units `drawGlow` takes) about [direction]: a solid core with a soft rim, and a
     * faint halo out to [HALO_SPREAD] times as far at [haloStrength] of its colour. A glow is a square quad, which a
     * point of light hides and a body the size of the moon does not.
     */
    private fun discAt(direction: Vector3f, size: Float, colour: Rgba, haloStrength: Float): List<SkyQuad> {
        val frame = DiscFrame(direction, angularRadiusOf(size))
        fun rimAt(segment: Int, reach: Float): Vector3f = frame.at(turnOf(segment), reach)
        val solid = colour.copy(alpha = FULL)
        val none = DARK
        val halo = colour.dimmed(haloStrength).copy(alpha = FULL)
        val core = DISC_CORE
        val rim = DISC_RIM
        val outer = HALO_SPREAD
        return buildList {
            for (segment in 0..<DISC_SEGMENTS) {
                val next = segment + 1
                add(SkyQuad(SkyCorner(direction, solid), SkyCorner(direction, solid), SkyCorner(rimAt(segment, core), solid), SkyCorner(rimAt(next, core), solid)))
                add(SkyQuad(SkyCorner(rimAt(segment, core), solid), SkyCorner(rimAt(segment, rim), halo), SkyCorner(rimAt(next, rim), halo), SkyCorner(rimAt(next, core), solid)))
                if (haloStrength > NOTHING) {
                    add(SkyQuad(SkyCorner(rimAt(segment, rim), halo), SkyCorner(rimAt(segment, outer), none), SkyCorner(rimAt(next, outer), none), SkyCorner(rimAt(next, rim), halo)))
                }
            }
        }
    }

    /** The sky about [centre] laid flat, for drawing round it: reaches in multiples of [radius] (radians) out, turns in radians round. */
    private class DiscFrame(val centre: Vector3f, val radius: Float) {
        private val across: Vector3f = Vector3f(centre).cross(if (abs(centre.y) > NEARLY_UPRIGHT) EAST else UP).normalize()
        private val along: Vector3f = Vector3f(centre).cross(across).normalize()

        private val radiusOnThePlane = tan(radius).toDouble()

        fun at(turn: Double, reach: Float): Vector3f = atPlane(reach * cos(turn), reach * sin(turn))

        /** The point [x] radii across and [y] along, on the plane touching the sky at the centre. */
        fun atPlane(x: Double, y: Double): Vector3f = Vector3f(across).mul((x * radiusOnThePlane).toFloat())
            .add(Vector3f(along).mul((y * radiusOnThePlane).toFloat()))
            .add(centre)
            .normalize()

        /** Where [direction] falls on the plane touching the sky at the centre, in radii across and along. */
        fun planeOf(direction: Vector3f): Pair<Double, Double> {
            val towardTheCentre = maxOf(direction.dot(centre), BARELY_IN_FRONT).toDouble()
            return direction.dot(across) / towardTheCentre / radiusOnThePlane to direction.dot(along) / towardTheCentre / radiusOnThePlane
        }

        /** Which way round [offset] points, as it lies across this patch of sky. */
        fun turnOf(offset: Vector3f): Double = atan2(offset.dot(along).toDouble(), offset.dot(across).toDouble())
    }

    private fun glowAt(direction: Vector3f, size: Float, tint: Rgba): Glow = Glow(
        orientation = Quaternionf().rotateTo(0.0f, 1.0f, 0.0f, direction.x, direction.y, direction.z),
        distance = GLOW_DISTANCE.toFloat(),
        angularSize = size,
        tint = tint,
    )

    /** Rises from nought at [from] to one at [to]; either way round, so it can fall as well. */
    private fun smoothStep(from: Double, to: Double, at: Double): Float {
        val along = ((at - from) / (to - from)).coerceIn(0.0, 1.0)
        return (along * along * (3.0 - 2.0 * along)).toFloat()
    }

    /** A number in `0..1` fixed by [number] and [salt]. */
    private fun unitAt(number: Long, salt: Long): Double {
        var mixed = number * GOLDEN_GAMMA + salt
        mixed = (mixed xor (mixed ushr 30)) * MIX_ONE
        mixed = (mixed xor (mixed ushr 27)) * MIX_TWO
        mixed = mixed xor (mixed ushr 31)
        return (mixed ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private const val NOTHING = 0.0f

    /** The name the plume's and the ash's roiling go by on [SkyDrift]. */
    private const val ROIL_DRIFT = "agesandtheart:impact_roil"
    private const val FULL = 1.0f
    private const val HALF = 0.5f
    private const val OVERHEAD = 90.0

    // The path. A world of these proportions is not ours to see whole, so they are chosen for how it looks.

    /** How far below the eye the impact lies, in blocks: far enough that the rock sinks from sight a second before it. */
    private const val IMPACT_BELOW_THE_EYE = 13_000.0
    private const val ENTRY_CLIMB_DEGREES = 40.0
    private const val ENTRY_SLANT_DEGREES = 65.0
    private const val PATH_AT_ENTRY = 120_000.0

    /** The sky as it stands now, and how far it turns in a day at a pace of one. */
    private val NOT_TURNED = Quaternionf()
    private const val NOT_AT_ALL = 0.0f
    private const val TICKS_PER_DAY = 24_000.0
    private const val WHOLE_TURN = 2.0 * PI

    /** The speck at the bind and at the end of the approach: a star's size and light, and well past any star's. */
    private const val SPECK_FARTHEST = 0.2f
    private const val SPECK_NEAREST = 0.9f
    private const val SPECK_FAINTEST = 0.3f

    /** The fire beyond the horizon as the rock goes down behind it: from how far through the entry, and how bright. */
    private const val BEYOND_THE_HORIZON_FROM = 0.7
    private const val GLOW_BEFORE_THE_STRIKE = 0.6f
    private const val ASTEROID_RADIUS = 4_500.0
    /** How many of its own radii below the horizon the asteroid's middle is before nothing of it or its fire shows. */
    private const val GONE_BELOW_BY = 4.0f

    // The body.
    private const val GLOW_DISTANCE = 100.0
    private const val SPECK = 0.35f
    private val PALE_ROCK = Rgba(0.95f, 0.93f, 0.88f, 0.95f)
    private val BURNING = Rgba(1.0f, 0.62f, 0.28f, 0.95f)
    private const val BODY_HALO = 0.15f
    private const val BURNING_HALO = 0.45f
    private const val SPECK_UNTIL = 0.8
    private const val SOLID_BY = 2.5

    // The cube, as a share of the body's radius, and how far along its path its way is read.
    private const val CUBE_HALF_EDGE = 0.62f
    private const val TRAVEL_STEP = 1_000.0

    // The corona.
    private const val CORONA_FULL_BY = 0.6
    private const val CORONA_FLICKER = 0.12f
    private const val CORONA_FLICKER_PACE = 1.3
    private const val STREAMER_ROOTED_AT = 0.92
    private val FLAME = Rgba(1.0f, 0.45f, 0.12f)
    private const val STREAMERS = 72
    private const val STREAMER_WIDENS = 2.5
    private const val STREAMER_FLARES = 0.04

    /** How much more the streamers flare out round a rock coming at the eye, whose tail is hidden behind it. */
    private const val STREAMER_FLARES_HEAD_ON = 0.6
    private const val STREAMER_LEAST = 0.8

    /** How far back along the path the tail is seen to reach, in blocks: about eleven of the rock's radii. */
    private const val TAIL_BEHIND = 50_000.0
    private const val STREAMER_SLOWEST = 0.5
    private const val STREAMER_PACE_SPREAD = 1.0
    private const val STREAMER_STEADY = 0.75
    private const val STREAMER_SHORTEST = 2.0
    private const val STREAMER_LONGEST = 9.0
    private const val STREAMER_SHORTENS = 0.6
    private const val STREAMER_AHEAD = 0.4
    private const val FLAME_COVERS = 0.75f
    private const val STREAMER_PLACE_SALT = 0x6E1D_4A93_02F7_B85CL
    private const val STREAMER_PACE_SALT = 0x1B84_C7E2_5D30_A961L
    private const val STREAMER_PHASE_SALT = 0x7C29_F05B_E816_43D2L

    // The train.
    private const val TRAIN_POINTS = 40
    private const val TRAIN_LINGERS_TICKS = 1800.0
    private const val TRAIN_WIDTH_OF_THE_BODY = 0.7f
    private const val TRAIN_SPREADS_PER_TICK = 0.00004
    private val TRAIN_SMOKE = Rgba(0.32f, 0.29f, 0.27f)
    private const val TRAIN_SMOKE_THICKNESS = 0.7f
    private const val TRAIN_GLOW_FADES_TICKS = 100.0
    private const val TRAIN_LAID_BY = 0.15
    private const val SMOKE_GATHERS_TICKS = 15.0
    private const val SAME_MOMENT = 0.01
    private const val NO_TURN = 1.0e-12f

    // The fireball and the horizon.
    private val FIRE_ON_THE_HORIZON = Rgba(1.0f, 0.45f, 0.12f)
    private val WHITE_HOT = Rgba(1.0f, 0.95f, 0.80f)
    private val FIREBALL_ORANGE = Rgba(1.0f, 0.50f, 0.15f)
    private val DULL_RED = Rgba(0.60f, 0.12f, 0.04f)

    /**
     * The plume, in blocks and ticks: how high it climbs and how fast, how wide its cap spreads and how fast, how wide
     * its foot is, how sharply it opens and how its crown rounds, how fast it cools and how much cooler its top is,
     * and how it billows.
     */
    private const val PLUME_HIGHEST = 90_000.0
    private const val PLUME_RISES_TICKS = 260.0
    private const val PLUME_WIDEST = 70_000.0
    private const val PLUME_SPREADS_TICKS = 900.0
    private const val PLUME_FOOT = 10_000.0
    private const val PLUME_OPENS = 1.3
    private const val PLUME_CROWN = 0.12
    private const val PLUME_COOLS_TICKS = 500.0
    private const val PLUME_TOP_COOLER = 0.35
    private const val PLUME_GATHERS_TICKS = 20.0
    private const val PLUME_LEVELS = 32
    private const val PLUME_SECTORS = 48
    private const val PLUME_BILLOWS = 0.14
    private const val PLUME_BILLOW_LOBES = 5.0
    private const val PLUME_PUFFS = 0.07
    private const val PLUME_PUFF_LOBES = 13.0
    private const val PLUME_BULGES = 0.18
    private const val PLUME_BULGE_STEP = 0.9
    private const val PLUME_BILLOW_STEP = 1.7
    private const val PLUME_BILLOW_RISE = 2.3
    private const val PLUME_BILLOW_PACE = 0.02
    private val PLUME_SMOKE = Rgba(0.22f, 0.17f, 0.14f)
    private const val PLUME_THICKNESS = 0.85f
    private const val PLUME_HEAT_SHOWS_THROUGH = 0.6f
    private const val FIREBALL_RISES_TICKS = 800.0
    private const val FIREBALL_COOLS_FASTER = 6.0f
    private const val FIREBALL_LOWEST = 1.0
    private const val FIREBALL_HIGHEST = 10.0
    private const val FIREBALL_EASING = 0.6
    private const val FIREBALL_SMALLEST = 6.0
    private const val FIREBALL_LARGEST = 12.0
    private const val FIREBALL_HALO_STRENGTH = 0.35f

    // The debris coming back down.
    private val LOW_GLOW = Rgba(1.0f, 0.45f, 0.12f)
    private val HIGH_GLOW = Rgba(0.70f, 0.12f, 0.04f)
    private const val GLOW_BRIGHTNESS = 0.8f
    private const val GLOW_AT_IMPACT = 0.55
    private const val FRONT_SOFTNESS = 15.0
    private const val FLICKER = 0.15f
    private const val FLICKER_PACE = 0.7

    // The fragments falling through it.
    private const val FALLS = 320
    private const val FALL_CYCLE_TICKS = 200.0
    private const val FALL_LOWEST = 8.0
    private const val FALL_HIGHEST = 85.0
    private const val FALL_SWERVES_DEGREES = 12.0

    /**
     * The debris as bodies, in blocks: the height of the layer it burns in and how far above or below it each
     * begins, as a share of it, how far each runs, how steeply it dips in degrees, and how wide its head and trail are.
     * A world of these proportions is not ours to see whole, so they are chosen for how it looks.
     */
    private const val DEBRIS_LAYER = 80_000.0
    private const val DEBRIS_LAYER_SPREAD = 0.25
    private const val FALL_SHORTEST = 8_000.0
    private const val FALL_LONGEST = 60_000.0
    private const val FALL_SHALLOWEST = 15.0
    private const val FALL_STEEPEST = 60.0
    private const val FALL_HEAD_ACROSS = 320.0
    private const val FALL_QUICKEST_TICKS = 6.0
    private const val FALL_SLOWEST_TICKS = 14.0
    private const val FALL_BRIGHTENS_OVER = 0.15
    private const val FALL_BURNS_OUT_FROM = 0.7
    private val FALL_HEAD = Rgba(1.0f, 0.88f, 0.6f, 0.95f)
    private const val FALL_TRAIL_POINTS = 6
    private const val FALL_TRAIL_WIDTH = 240.0
    private const val FALL_TRAIL_SPREADS_PER_TICK = 16.0
    private const val FALL_TRAIL_LINGERS_TICKS = 160.0
    private val FALL_GLOW = Rgba(1.0f, 0.62f, 0.28f)
    private const val FALL_GLOW_FADES_TICKS = 12.0
    private val FALL_SMOKE = Rgba(0.2f, 0.16f, 0.14f)
    private const val FALL_SMOKE_THICKNESS = 0.8f
    private const val OFFSET_SALT = 0x51A7_E3C0_9D21_44B7L
    private const val HEIGHT_SALT = 0x2C6B_8F10_E5A3_7D49L
    private const val AZIMUTH_SALT = 0x4F0E_92A7_1C6D_B835L
    private const val SWERVE_SALT = 0x0D73_B5E9_48A2_6C1FL
    private const val DIP_SALT = 0x27E1_9C4B_F058_3AD6L
    private const val LAYER_SALT = 0x5A0C_E39F_71B4_2D86L

    /** How thick the air is seen to be toward the horizon, against overhead, and the thinnest slice of it asked. */
    private const val HAZE = 0.12f
    private const val THINNEST_SLICE = 0.04f

    /** A fragment seen coming down: how soon it brightens, and how finely its streak and bloom are drawn. */
    private const val COMING_BRIGHTENS_OVER = 0.25
    private const val COMING_TAIL_POINTS = 6
    private const val BLOOM_SEGMENTS = 20

    /** The colour of a lump of magma, seen far off. */
    private val MAGMA = Rgba(0.55f, 0.22f, 0.07f)
    private const val LENGTH_SALT = 0x63C8_1F4A_D097_2EB5L
    private const val BURN_SALT = 0x3B5D_E762_8A04_F19CL

    // The flash and the dust.
    private const val FLASH_BRIGHTNESS = 0.9f
    private val DUST_PALL = Rgba(0.17f, 0.15f, 0.14f)

    /**
     * The wall of ash: how tall, in blocks, how far below the horizon its foot is drawn, how far out it has rolled
     * before it stands at its full height, and over how much of its way either side of arriving it engulfs the eye.
     */
    private const val ASH_WALL_HEIGHT = 6_000.0
    private const val ASH_WALL_FOOT = 2_000.0
    private const val ASH_RISES_BY = 0.3
    private const val ASH_ENGULFS_OVER = 0.02

    /** How sharply the ground curves away under the wall, as the radius of a planet that would: in blocks. */
    private const val ASH_GROUND_CURVES = 30_000.0

    /** Its two banks: the front, and one rolling a little behind it, taller and darker. */
    private val ASH_BANKS = listOf(
        AshBank(behind = 900.0, taller = 1.3, shade = 0.75f, salt = 7_919L),
        AshBank(behind = 0.0, taller = 1.0, shade = 1.0f, salt = 104_729L),
    )

    /**
     * Its billows: how many round the ring, how finely the ring is drawn, the share of the wall that is body below
     * them, how small the least is, how often one towers and by how much, how deep the dip between two, how much each
     * swells and sinks and how fast, how unevenly wide they come, and how far up the wall stays dark before the crest
     * catches the light.
     */
    private const val ASH_BILLOWS = 44
    private const val ASH_SEGMENTS = 960
    private const val ASH_BODY = 0.68
    private const val ASH_SMALLEST_BILLOW = 0.3
    private const val ASH_TOWERS_OVER = 0.85
    private const val ASH_TOWER = 0.55
    private const val ASH_CUSP = 0.3
    private const val ASH_ROILS = 0.18
    private const val ASH_ROILS_SLOWEST = 0.08
    private const val ASH_ROIL_SPREAD = 0.14
    private const val ASH_WARP = 0.35
    private const val ASH_WARP_LOBES = 9.0
    private const val ASH_WARP_PACE = 0.03

    /** The smaller puffs riding the billows, and the ragged fringe over all, as shares of the wall's height. */
    private const val ASH_PUFFS = 0.07
    private const val ASH_PUFF_COUNT = 150.0
    private const val ASH_PUFF_PACE = 0.06
    private const val ASH_FRINGE = 0.03
    private const val TOWER_SALT = 0x0E47_B9D2_6A15_C38FL
    private const val PUFF_SALT = 0x7B30_E6A4_1D89_F25CL
    private const val FRINGE_SALT = 0x4C91_2F7E_B06D_853AL
    private const val ASH_DARK_TO = 0.8
    private const val ROIL_PACE_SALT = 0x6F2B_A951_0CD4_83E7L
    private const val ROIL_PHASE_SALT = 0x19D5_4E8B_F327_A06CL
    private const val BILLOW_SIZE_SALT = 0x52A8_0F6D_C13B_94E1L
    private val ASH_WALL = Rgba(0.13f, 0.115f, 0.105f)
    private val ASH_LIT = Rgba(0.62f, 0.22f, 0.08f)
    private const val ASH_LIT_BY = 0.6f
    private const val DUST_COVERS = 0.9f

    // A disc.
    private const val DISC_SEGMENTS = 48
    private const val DISC_CORE = 0.8f
    private const val DISC_RIM = 1.0f
    private const val HALO_SPREAD = 2.5f
    private const val NEARLY_UPRIGHT = 0.95f
    private val UP = Vector3f(0.0f, 1.0f, 0.0f)
    private const val BARELY_IN_FRONT = 0.05f
    private val EAST = Vector3f(1.0f, 0.0f, 0.0f)
    private val DARK = Rgba(0.0f, 0.0f, 0.0f, FULL)

    // The grid.
    private const val ROWS = 12
    private const val COLUMNS = 36
    private const val LOWEST_ROW = -12.0

    private const val GOLDEN_GAMMA = -0x61c8_8646_80b5_83ebL
    private const val MIX_ONE = -0x40a7_b892_e31b_1a47L
    private const val MIX_TWO = -0x6b2f_b644_ecce_ee15L
}
