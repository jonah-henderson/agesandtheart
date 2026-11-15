package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.Glow
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyBlend
import co.voik.ephemeris.client.SkyCorner
import co.voik.ephemeris.client.SkyMoment
import co.voik.ephemeris.client.SkyQuad
import org.joml.Quaternionf
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
        drawTheTrails(doom, time)
        if (phase == ImpactPhase.APPROACH || phase == ImpactPhase.ENTRY) drawTheAsteroid(doom, time)
        if (!hasStruck) return
        val heads = mutableListOf<Glow>()
        gatherTheFallingHeads(doom, time, heads)
        Blaze3dSkyCanvas.drawGlows(heads)
        val shade = mutableListOf<SkyQuad>()
        gatherTheDust(doom, time, shade)
        Blaze3dSkyCanvas.drawShapes(shade, SkyBlend.SHADE)
        val flash = mutableListOf<SkyQuad>()
        gatherTheFlash(doom, time, flash)
        Blaze3dSkyCanvas.drawShapes(flash, SkyBlend.LIGHT)
    }

    // ---- The asteroid --------------------------------------------------------------------------------------

    /** Where the asteroid is seen from the eye: bearing and height in radians, and how far off in blocks. */
    private data class Sighting(val azimuth: Double, val elevation: Double, val distance: Double) {
        val isAboveTheHorizon: Boolean get() = elevation > LOST_BELOW_THE_HORIZON
        val direction: Vector3f get() = directionOf(azimuth, elevation)
    }

    /**
     * Where the asteroid is [ticksToImpact] before it strikes.
     *
     * Its path ends at the impact, [IMPACT_DISTANCE] out on the Age's bearing, and runs back up and away along
     * [incomingOf]. How far back along it the asteroid is shrinks steadily through the entry and geometrically
     * through the approach, so it grows from a speck rather than appearing half-grown. The horizon is dropped as a
     * planet's would be, so the impact itself is out of sight.
     */
    private fun sightingOf(doom: Doom, ticksToImpact: Double): Sighting {
        val bearing = Math.toRadians(doom.bearingDegrees.toDouble())
        val alongX = sin(bearing)
        val alongZ = -cos(bearing)
        val acrossX = cos(bearing)
        val acrossZ = sin(bearing)
        val climb = Math.toRadians(ENTRY_CLIMB_DEGREES)
        val slant = Math.toRadians(ENTRY_SLANT_DEGREES)
        val back = remainingPathAt(doom, ticksToImpact)
        val outward = cos(climb) * cos(slant) * back
        val sideways = cos(climb) * sin(slant) * back
        val x = alongX * (IMPACT_DISTANCE + outward) + acrossX * sideways
        val z = alongZ * (IMPACT_DISTANCE + outward) + acrossZ * sideways
        val y = sin(climb) * back
        val horizontal = hypot(x, z)
        val dropped = minOf(horizontal / (2.0 * PLANET_RADIUS), MOST_DROPPED)
        return Sighting(atan2(x, -z), atan2(y, horizontal) - dropped, sqrt(horizontal * horizontal + y * y))
    }

    private fun remainingPathAt(doom: Doom, ticksToImpact: Double): Double {
        val entry = doom.timeline.entry.toDouble()
        val approach = doom.timeline.approach.toDouble()
        val left = ticksToImpact.coerceAtLeast(0.0)
        if (left <= entry) return PATH_AT_ENTRY * left / entry
        val throughTheApproach = ((left - entry) / approach).coerceAtMost(1.0)
        return PATH_AT_ENTRY * (PATH_AT_FIRST_SIGHT / PATH_AT_ENTRY).pow(throughTheApproach)
    }

    /**
     * Far off, a pale speck of light. Nearer, it becomes [solid]: a dark lumpy rock covering the sky behind it, lit
     * on its upper limb by day, and through the entry glowing white on the face it leads with, inside a corona that
     * streams back off it.
     *
     * Drawn in layers, each over the last: the corona, the rock, the light on the rock.
     */
    private fun drawTheAsteroid(doom: Doom, time: Double) {
        val ticksToImpact = doom.impactAt - time
        val sighting = sightingOf(doom, ticksToImpact)
        if (!sighting.isAboveTheHorizon) return
        val burning = doom.progressThrough(ImpactPhase.ENTRY, time).toFloat()
        val size = sizeAt(sighting.distance)
        val solid = smoothStep(SPECK_UNTIL, SOLID_BY, size.toDouble())
        val frame = DiscFrame(sighting.direction, angularRadiusOf(size))
        val ahead = sightingOf(doom, ticksToImpact - HEADING_LOOKAHEAD_TICKS).direction
        val heading = frame.turnOf(Vector3f(ahead).sub(sighting.direction))
        val corona = coronaAt(burning, time)

        val glowing = mutableListOf<SkyQuad>()
        val speck = PALE_ROCK.lerp(BURNING, sqrt(burning)).dimmed(FULL - solid)
        glowing += discAt(sighting.direction, size, speck, haloStrength = BODY_HALO + BURNING_HALO * burning)
        if (corona > NOTHING) {
            // Laid over the sky by alpha first, so a day sky does not wash them white, and then shining.
            Blaze3dSkyCanvas.drawShapes(streamersOf(frame, heading, corona, time, StreamerLook.COVERING), SkyBlend.SHADE)
            glowing += streamersOf(frame, heading, corona, time, StreamerLook.SHINING)
            gatherTheShock(frame, heading, corona, time, glowing)
        }
        Blaze3dSkyCanvas.drawShapes(glowing, SkyBlend.LIGHT)
        if (solid <= NOTHING) return
        Blaze3dSkyCanvas.drawShapes(rockOf(frame, solid, burning, time), SkyBlend.SHADE)
        Blaze3dSkyCanvas.drawShapes(lightOnTheRock(frame, heading, solid, corona, burning, time), SkyBlend.LIGHT)
    }

    /** How brightly the corona burns: flaring through the first part of the entry, and never quite steady. */
    private fun coronaAt(burning: Float, time: Double): Float {
        val flaring = smoothStep(0.0, CORONA_FULL_BY, burning.toDouble())
        val unsteady = FULL - CORONA_FLICKER * (FULL + sin(time * CORONA_FLICKER_PACE).toFloat()) / 2.0f
        return flaring * unsteady
    }

    /** The shock glowing about the rock, brightest on the face it leads with. */
    private fun gatherTheShock(frame: DiscFrame, heading: Double, strength: Float, time: Double, into: MutableList<SkyQuad>) {
        fun shockAt(turn: Double): Rgba {
            val leading = maxOf(0.0, cos(turn - heading)).toFloat()
            return SHOCK.dimmed(strength * (SHOCK_ALL_ROUND + (FULL - SHOCK_ALL_ROUND) * leading))
        }
        for (segment in 0..<DISC_SEGMENTS) {
            val turn = turnOf(segment)
            val next = turnOf(segment + 1)
            into += SkyQuad(
                SkyCorner(frame.at(turn, edgeAt(turn, time) * UNDER_THE_RIM), shockAt(turn)),
                SkyCorner(frame.at(turn, edgeAt(turn, time) + SHOCK_REACH), DARK),
                SkyCorner(frame.at(next, edgeAt(next, time) + SHOCK_REACH), DARK),
                SkyCorner(frame.at(next, edgeAt(next, time) * UNDER_THE_RIM), shockAt(next)),
            )
        }
    }

    /** Whether a streamer is laid over the sky by its alpha or added to it as light. */
    private enum class StreamerLook { COVERING, SHINING }

    /**
     * The flames streaming back off the rock: each a broad wedge from a point under its rim running straight back
     * along its path and flaring a little outward, so that those from its face wrap round it and all of them trail
     * off behind as one tail. Each flickers on a pace of its own.
     */
    private fun streamersOf(frame: DiscFrame, heading: Double, strength: Float, time: Double, look: StreamerLook): List<SkyQuad> {
        val halfWidth = PI / STREAMERS * STREAMER_WIDENS
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
            val length = (STREAMER_SHORTEST + STREAMER_LONGEST * behind) * (STREAMER_SHORTENS + (FULL - STREAMER_SHORTENS) * flicker)
            val root = (edgeAt(turn, time) * UNDER_THE_RIM).toDouble()
            val aside = root * (cos(turn) * sideX + sin(turn) * sideY)
            val tipX = root * cos(turn) + length * (backX + STREAMER_FLARES * aside * sideX)
            val tipY = root * sin(turn) + length * (backY + STREAMER_FLARES * aside * sideY)
            val share = (strength * flicker * (STREAMER_AHEAD + (FULL - STREAMER_AHEAD) * behind)).toFloat()
            val colour = when (look) {
                StreamerLook.COVERING -> FLAME.copy(alpha = share * FLAME_COVERS)
                StreamerLook.SHINING -> FLAME.dimmed(share).copy(alpha = FULL)
            }
            val nothing = when (look) {
                StreamerLook.COVERING -> Rgba.CLEAR
                StreamerLook.SHINING -> DARK
            }
            fun rootAt(at: Double) = SkyCorner(frame.atPlane(root * cos(at), root * sin(at)), colour)
            val tip = SkyCorner(frame.atPlane(tipX, tipY), nothing)
            SkyQuad(rootAt(turn - halfWidth), rootAt(turn + halfWidth), tip, tip)
        }
    }

    /** The rock itself, covering the sky behind it as far as it is [solid], and smouldering as it burns. */
    private fun rockOf(frame: DiscFrame, solid: Float, burning: Float, time: Double): List<SkyQuad> {
        val colour = ROCK.lerp(SMOULDERING_ROCK, burning * SMOULDERS).copy(alpha = solid)
        val centre = SkyCorner(frame.centre, colour)
        return (0..<DISC_SEGMENTS).map { segment ->
            val turn = turnOf(segment)
            val next = turnOf(segment + 1)
            SkyQuad(centre, centre, SkyCorner(frame.at(turn, edgeAt(turn, time)), colour), SkyCorner(frame.at(next, edgeAt(next, time)), colour))
        }
    }

    /**
     * The light on the rock, laid in a band inside its edge: daylight on its upper limb, giving way through the
     * entry to the white heat of the face it leads with.
     */
    private fun lightOnTheRock(frame: DiscFrame, heading: Double, solid: Float, corona: Float, burning: Float, time: Double): List<SkyQuad> {
        val upward = frame.turnOf(UP)
        fun lightAt(turn: Double): Rgba {
            val leading = maxOf(0.0, cos(turn - heading)).pow(FACE_SHARPNESS).toFloat()
            val upper = maxOf(0.0, cos(turn - upward)).pow(LIMB_SHARPNESS).toFloat()
            val heat = HOT_FACE.dimmed(corona * leading)
            val day = DAYLIT_LIMB.dimmed(upper * (FULL - burning))
            return Rgba(heat.red + day.red, heat.green + day.green, heat.blue + day.blue).dimmed(solid)
        }
        return (0..<DISC_SEGMENTS).map { segment ->
            val turn = turnOf(segment)
            val next = turnOf(segment + 1)
            SkyQuad(
                SkyCorner(frame.at(turn, edgeAt(turn, time) * LIGHT_REACHES_IN), lightAt(turn).dimmed(LIGHT_INSIDE)),
                SkyCorner(frame.at(turn, edgeAt(turn, time) * ON_THE_RIM), lightAt(turn)),
                SkyCorner(frame.at(next, edgeAt(next, time) * ON_THE_RIM), lightAt(next)),
                SkyCorner(frame.at(next, edgeAt(next, time) * LIGHT_REACHES_IN), lightAt(next).dimmed(LIGHT_INSIDE)),
            )
        }
    }

    /** How far out the rock's edge stands at [turn], in radii: lumpy, and turning slowly as it tumbles. */
    private fun edgeAt(turn: Double, time: Double): Float {
        val spun = turn + time * TUMBLE_PER_TICK
        val lumps = LUMPS.sumOf { it.weight * sin(it.count * spun + it.phase) }
        return (FULL + ROUGHNESS * lumps).toFloat()
    }

    /** One lumpiness of the rock's outline: [count] lumps round it, out of step by [phase], weighing [weight]. */
    private class Lump(val count: Int, val phase: Double, val weight: Double)

    private fun turnOf(segment: Int): Double = 2.0 * PI * segment / DISC_SEGMENTS

    /** How big a body of [ASTEROID_RADIUS] looks [distance] blocks off, in the units `drawGlow` takes. */
    private fun sizeAt(distance: Double): Float =
        maxOf(SPECK, (GLOW_DISTANCE * ASTEROID_RADIUS / distance).toFloat())

    /** How far a thing of [size] reaches from its centre, in radians. */
    private fun angularRadiusOf(size: Float): Float = atan(size / GLOW_DISTANCE.toFloat())

    // ---- After the impact ----------------------------------------------------------------------------------

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
     * One fragment of the debris coming back down: a great circle across the sky from [start] along [heading], run
     * [length] radians in [burnTicks] from [startedAt]. Each is drawn afresh on a cycle of its own.
     */
    private class Fall(val start: Vector3f, val heading: Vector3f, val length: Double, val burnTicks: Double, val startedAt: Double) {
        /** Which way is across its path, the same all along a great circle. */
        val across: Vector3f = Vector3f(start).cross(heading).normalize()

        fun at(along: Double): Vector3f =
            Vector3f(start).mul(cos(along).toFloat()).add(Vector3f(heading).mul(sin(along).toFloat()))
    }

    /**
     * Fragment [number]'s fall in the cycle [time] lies in, or null where the glow had not yet reached its start when
     * it began. They run away from a radiant above where the asteroid struck, as a meteor shower's do from its own.
     */
    private fun fallOf(doom: Doom, number: Int, time: Double): Fall? {
        val offset = unitAt(number.toLong(), OFFSET_SALT) * FALL_CYCLE_TICKS
        val cycle = floor((time + offset) / FALL_CYCLE_TICKS).toLong()
        val startedAt = cycle * FALL_CYCLE_TICKS - offset
        fun drawn(salt: Long) = unitAt(number.toLong(), cycle xor salt)
        val elevation = FALL_LOWEST + drawn(HEIGHT_SALT) * (FALL_HIGHEST - FALL_LOWEST)
        val start = directionOf(drawn(AZIMUTH_SALT) * 2.0 * PI, Math.toRadians(elevation))
        val impactDirection = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)
        val hadReachedIt = litAt(GridCorner(start, elevation), impactDirection, frontAt(doom, startedAt)) >= HALF
        if (!hadReachedIt) return null
        val radiant = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), Math.toRadians(RADIANT_ELEVATION))
        val away = Vector3f(start).mul(start.dot(radiant)).sub(radiant)
        if (away.lengthSquared() < NO_TURN) return null
        away.normalize()
        val swerve = (drawn(SWERVE_SALT) * 2.0 - 1.0) * Math.toRadians(FALL_SWERVES_DEGREES)
        val heading = Vector3f(away).mul(cos(swerve).toFloat()).add(Vector3f(start).cross(away).mul(sin(swerve).toFloat()))
        val length = Math.toRadians(FALL_SHORTEST + drawn(LENGTH_SALT) * (FALL_LONGEST - FALL_SHORTEST))
        val burnTicks = FALL_QUICKEST_TICKS + drawn(BURN_SALT) * (FALL_SLOWEST_TICKS - FALL_QUICKEST_TICKS)
        return Fall(start, heading, length, burnTicks, startedAt)
    }

    /**
     * The trails the fragments leave: fixed where each was laid, glowing just behind its head, then smoke spreading
     * and thinning until the fragment's next fall.
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
                val entering = smoothStep(0.0, FALL_BRIGHTENS_OVER, share)
                val fading = (FULL - (age / FALL_TRAIL_LINGERS_TICKS).toFloat()).coerceAtLeast(NOTHING)
                val gathering = smoothStep(0.0, SMOKE_GATHERS_TICKS, age)
                val halfWidth = FALL_TRAIL_WIDTH + FALL_TRAIL_SPREADS_PER_TICK * age
                val thinning = sqrt(FALL_TRAIL_WIDTH / halfWidth).toFloat()
                return TrailPoint(
                    direction = fall.at(share * fall.length),
                    across = fall.across,
                    halfWidth = halfWidth.toFloat(),
                    smoke = FALL_SMOKE.copy(alpha = FALL_SMOKE_THICKNESS * strength * entering * gathering * fading * thinning),
                    glow = FALL_GLOW.dimmed(strength * entering * exp(-age / FALL_GLOW_FADES_TICKS).toFloat()).copy(alpha = FULL),
                )
            }
            val laid = (0..FALL_TRAIL_POINTS).map { it.toDouble() / FALL_TRAIL_POINTS }.filter { it < reached }
            val isBurning = reached < 1.0
            val shares = if (isBurning) laid + reached else laid + 1.0
            gatherTrail(shares.map(::pointAt), smoke, glow)
        }
    }

    /** The heads of the fragments still burning: brightening as each comes in, and gone as it burns out. */
    private fun gatherTheFallingHeads(doom: Doom, time: Double, into: MutableList<Glow>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        for (number in 0..<FALLS) {
            val fall = fallOf(doom, number, time) ?: continue
            val reached = (time - fall.startedAt) / fall.burnTicks
            if (reached >= 1.0) continue
            val brightness = strength * smoothStep(0.0, FALL_BRIGHTENS_OVER, reached) * smoothStep(1.0, FALL_BURNS_OUT_FROM, reached)
            into += glowAt(fall.at(reached * fall.length), FALL_HEAD_SIZE, FALL_HEAD.dimmed(brightness))
        }
    }

    private fun gatherTheFlash(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val flash = Impacts.flashAt(doom, time)
        if (flash <= NOTHING) return
        val white = Rgba.WHITE.dimmed(flash * FLASH_BRIGHTNESS).copy(alpha = FULL)
        into += quadsOverTheSky { white }
    }

    private fun gatherTheDust(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val dust = Impacts.dustAt(doom, time)
        if (dust <= NOTHING) return
        val pall = DUST_PALL.copy(alpha = dust * DUST_COVERS)
        into += quadsOverTheSky { pall }
    }

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
    private const val FULL = 1.0f
    private const val HALF = 0.5f
    private const val OVERHEAD = 90.0

    // The path. A world of these proportions is not ours to see whole, so they are chosen for how it looks.
    private const val IMPACT_DISTANCE = 30_000.0
    private const val PLANET_RADIUS = 200_000.0
    private const val MOST_DROPPED = 0.1
    private const val ENTRY_CLIMB_DEGREES = 40.0
    private const val ENTRY_SLANT_DEGREES = 25.0
    private const val PATH_AT_ENTRY = 120_000.0
    private const val PATH_AT_FIRST_SIGHT = 8_000_000.0
    private const val ASTEROID_RADIUS = 4_500.0
    private const val LOST_BELOW_THE_HORIZON = -0.005

    // The body.
    private const val GLOW_DISTANCE = 100.0
    private const val SPECK = 0.35f
    private val PALE_ROCK = Rgba(0.95f, 0.93f, 0.88f, 0.95f)
    private val BURNING = Rgba(1.0f, 0.62f, 0.28f, 0.95f)
    private const val BODY_HALO = 0.15f
    private const val BURNING_HALO = 0.45f
    private const val SPECK_UNTIL = 0.8
    private const val SOLID_BY = 2.5
    private const val HEADING_LOOKAHEAD_TICKS = 20.0

    // The rock.
    private val ROCK = Rgba(0.09f, 0.075f, 0.065f)
    private val SMOULDERING_ROCK = Rgba(0.25f, 0.06f, 0.02f)
    private const val SMOULDERS = 0.3f
    private const val ROUGHNESS = 0.13
    private val LUMPS = listOf(Lump(2, 0.7, 0.45), Lump(3, 1.9, 0.3), Lump(5, 0.4, 0.15), Lump(9, 2.6, 0.1))
    private const val TUMBLE_PER_TICK = 0.0015
    private val HOT_FACE = Rgba(1.0f, 0.9f, 0.7f)
    private val DAYLIT_LIMB = Rgba(0.38f, 0.36f, 0.33f)
    private const val FACE_SHARPNESS = 2.0
    private const val LIMB_SHARPNESS = 3.0
    private const val LIGHT_REACHES_IN = 0.45f
    private const val LIGHT_INSIDE = 0.15f
    private const val ON_THE_RIM = 0.99f

    // The corona.
    private const val CORONA_FULL_BY = 0.35
    private const val CORONA_FLICKER = 0.12f
    private const val CORONA_FLICKER_PACE = 1.3
    private val SHOCK = Rgba(1.0f, 0.92f, 0.75f)
    private const val SHOCK_ALL_ROUND = 0.3f
    private const val SHOCK_REACH = 0.4f
    private const val UNDER_THE_RIM = 0.92f
    private val FLAME = Rgba(1.0f, 0.45f, 0.12f)
    private const val STREAMERS = 36
    private const val STREAMER_WIDENS = 2.0
    private const val STREAMER_FLARES = 0.35
    private const val STREAMER_SLOWEST = 0.5
    private const val STREAMER_PACE_SPREAD = 1.0
    private const val STREAMER_STEADY = 0.75
    private const val STREAMER_SHORTEST = 1.2
    private const val STREAMER_LONGEST = 2.8
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
    private const val FALL_LOWEST = 15.0
    private const val FALL_HIGHEST = 80.0
    private const val RADIANT_ELEVATION = 20.0
    private const val FALL_SWERVES_DEGREES = 12.0
    private const val FALL_SHORTEST = 8.0
    private const val FALL_LONGEST = 22.0
    private const val FALL_QUICKEST_TICKS = 18.0
    private const val FALL_SLOWEST_TICKS = 40.0
    private const val FALL_BRIGHTENS_OVER = 0.15
    private const val FALL_BURNS_OUT_FROM = 0.7
    private const val FALL_HEAD_SIZE = 0.4f
    private val FALL_HEAD = Rgba(1.0f, 0.88f, 0.6f, 0.95f)
    private const val FALL_TRAIL_POINTS = 6
    private const val FALL_TRAIL_WIDTH = 0.003
    private const val FALL_TRAIL_SPREADS_PER_TICK = 0.0002
    private const val FALL_TRAIL_LINGERS_TICKS = 160.0
    private val FALL_GLOW = Rgba(1.0f, 0.62f, 0.28f)
    private const val FALL_GLOW_FADES_TICKS = 12.0
    private val FALL_SMOKE = Rgba(0.2f, 0.16f, 0.14f)
    private const val FALL_SMOKE_THICKNESS = 0.8f
    private const val OFFSET_SALT = 0x51A7_E3C0_9D21_44B7L
    private const val HEIGHT_SALT = 0x2C6B_8F10_E5A3_7D49L
    private const val AZIMUTH_SALT = 0x4F0E_92A7_1C6D_B835L
    private const val SWERVE_SALT = 0x0D73_B5E9_48A2_6C1FL
    private const val LENGTH_SALT = 0x63C8_1F4A_D097_2EB5L
    private const val BURN_SALT = 0x3B5D_E762_8A04_F19CL

    // The flash and the dust.
    private const val FLASH_BRIGHTNESS = 0.9f
    private val DUST_PALL = Rgba(0.17f, 0.15f, 0.14f)
    private const val DUST_COVERS = 0.9f

    // A disc.
    private const val DISC_SEGMENTS = 48
    private const val DISC_CORE = 0.8f
    private const val DISC_RIM = 1.0f
    private const val HALO_SPREAD = 2.5f
    private const val NEARLY_UPRIGHT = 0.95f
    private val UP = Vector3f(0.0f, 1.0f, 0.0f)
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
