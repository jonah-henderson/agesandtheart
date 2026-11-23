package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * An astrite dispersion lens: light reaching its back, by a beam or through the conduit there, spread out of its front
 * over a cone as real light, laid in the air before every surface the cone reaches ([DispersedLightBlock]). Spreading
 * costs half the light ([Dispersion.dispersed]), and how far the cone reaches follows what is left and how wide its
 * aperture is, which an empty hand turns. An armed laser hurts what stands in the cone and a burning one burns every
 * surface it lights, each more gently than the laser would. A redstone signal puts it out.
 *
 * Set ahead of a slide carousel as its lens, its aperture counts as that many layers of glass, and the distance from the
 * carousel sizes the picture as glass's does; that is the carousel's light, drawn on the client, and lays no light of
 * its own.
 */
class DispersionLensBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(APERTURE, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, APERTURE)
    }

    /** Shining away from its placer, so its back is toward them. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DispersionLensBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Optics.DISPERSION_LENS_ENTITY) { tickedIn, _, _, lens -> lens.tick(tickedIn as ServerLevel) }

    /** An empty hand opens the aperture a step wider, or a step narrower while sneaking, round and round. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (!player.mainHandItem.isEmpty) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val turnedTo = Dispersion.nextAperture(state.getValue(APERTURE), wider = !player.isSecondaryUseActive)
        level.setBlock(pos, state.setValue(APERTURE, turnedTo), UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, 1f, 1f)
        player.sendOverlayMessage(Component.translatable(APERTURE_MESSAGE, turnedTo + 1, Dispersion.APERTURES))
        return InteractionResult.SUCCESS
    }

    companion object {
        val FACING: EnumProperty<Direction> = DirectionalBlock.FACING

        /** How far it is open, from nought, the narrowest. */
        val APERTURE: IntegerProperty = IntegerProperty.create("aperture", 0, Dispersion.APERTURES - 1)

        private const val APERTURE_MESSAGE = "block.agesandtheart.dispersion_lens.aperture"
    }
}

class DispersionLensBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Optics.DISPERSION_LENS_ENTITY, pos, state), TakesBeams {

    private val atItsBack = IncomingBeams()

    private val burning = ArmedBeams.Burning()

    /** The lights it has laid, as last cast. Never saved: a light no lens holds goes out by itself. */
    private var laid: Set<BlockPos> = emptySet()

    /** The surfaces its cone reached, as last cast. */
    private var lit: List<LitSurface> = emptyList()

    private var isShining = false

    /** A beam travelling the way it faces has come in at its back. */
    override fun takeBeam(source: BlockPos, travelling: Direction, light: Light, diffuse: Boolean, until: Long) {
        if (travelling == blockState.getValue(DispersionLensBlock.FACING)) atItsBack.take(source, travelling, light, diffuse, until)
    }

    fun tick(level: ServerLevel) {
        val fed = Light.together(listOfNotNull(atItsBack.lightAt(level.gameTime), LightNetworks.lightFor(level, worldPosition)))
        if (fed == null || level.hasNeighborSignal(worldPosition)) return putOut(level)
        val isDue = !isShining || (level.gameTime + worldPosition.asLong()) % Dispersion.CAST_EVERY == 0L
        if (isDue) cast(level, fed)
        isShining = true
        burnOn(level, fed)
    }

    private fun cast(level: ServerLevel, fed: Light) {
        val cone = Dispersion.coneOf(worldPosition, blockState, Dispersion.dispersed(fed))
        val cast = Dispersion.cast(level, cone)
        val holdUntil = level.gameTime + Dispersion.HELD_FOR
        for (gone in laid - cast.spots) DispersedLights.letGo(level, gone, worldPosition)
        laid = cast.spots.filter { spot -> DispersedLights.lay(level, spot, fed.colour, worldPosition, holdUntil) }.toSet()
        lit = cast.surfaces
        if (fed.harms) harm(level, cone, fed)
    }

    /** Hurts every creature the cone reaches, by [Dispersion.hurtFor] what the laser would. */
    private fun harm(level: ServerLevel, cone: Cone, fed: Light) {
        val arc = fed.arc ?: return
        val source = DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ArmedBeams.LASER))
        for (creature in level.getEntitiesOfClass(LivingEntity::class.java, cone.bounds)) {
            val middle = creature.boundingBox.center
            if (cone.contains(middle) && isInSight(level, cone, middle)) creature.hurtServer(level, source, Dispersion.hurtFor(arc))
        }
    }

    private fun isInSight(level: ServerLevel, cone: Cone, point: Vec3): Boolean {
        val sight = level.clip(ClipContext(cone.origin, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
        return sight.type == HitResult.Type.MISS
    }

    /** Burns every surface it lights, a burning laser's work shared between them and halved. */
    private fun burnOn(level: ServerLevel, fed: Light) {
        val arc = fed.arc
        if (!fed.burns || arc == null || lit.isEmpty()) return burning.burn(level, emptyMap())
        val rate = Dispersion.burnRate(fed, lit.size)
        burning.burn(level, lit.associate { surface -> surface.pos to ArmedBeams.Burn(surface.travelling, arc, rate) })
    }

    private fun putOut(level: ServerLevel) {
        if (!isShining) return
        isShining = false
        for (light in laid) DispersedLights.letGo(level, light, worldPosition)
        laid = emptySet()
        lit = emptyList()
        burning.burn(level, emptyMap())
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        (level as? ServerLevel)?.let(::putOut)
    }
}

/** A surface a lens's cone reached, and the way its light was travelling as it did. */
data class LitSurface(val pos: BlockPos, val travelling: Direction)

/** What a lens's cone reached: the [surfaces], and the [spots] in the air before them where light is laid. */
data class Cast(val surfaces: List<LitSurface>, val spots: Set<BlockPos>)

/** A cone of light from [origin] along [forward], [spread] wide for each block it goes, reaching [reach] blocks. */
class Cone(val origin: Vec3, val forward: Vec3, val right: Vec3, val up: Vec3, val spread: Double, val reach: Double) {

    /** A box round all of it. */
    val bounds: AABB get() = AABB(origin, origin.add(forward.scale(reach))).inflate(spread * reach + REACHES_PAST_ITS_EDGE)

    fun contains(point: Vec3): Boolean {
        val from = point.subtract(origin)
        val depth = from.dot(forward)
        val aside = from.subtract(forward.scale(depth)).length()
        return depth > 0.0 && depth <= reach && aside <= depth * spread + REACHES_PAST_ITS_EDGE
    }

    private companion object {
        const val REACHES_PAST_ITS_EDGE = 0.5
    }
}

/**
 * How a dispersion lens spreads light: its apertures, how far each reaches, and the cone cast through the world.
 *
 * The apertures stand for a slide carousel's one to four layers of glass, each further layer half again as wide. Set
 * alone, a lens spreads as glass one block ahead of a slide would, reaching as a spotlight at its narrowest and a
 * floodlight at its widest.
 */
object Dispersion {
    /** A slide half a block wide, one block behind the glass, spreads a quarter of a block for every block it goes. */
    private const val ONE_LAYER = 0.25
    private const val EACH_LAYER = 1.5

    /** How many apertures there are, one for each layer of glass a carousel's lens may have. */
    const val APERTURES = 4

    /** How far the narrowest and the widest reach on half a light, which is what full light in leaves. */
    private const val NARROWEST_REACH = 24.0
    private const val WIDEST_REACH = 8.0
    private const val HALF_A_LIGHT = Light.FULL / 2

    /** Light past three times full reaches no further. */
    private const val MOST_LIGHT_OUT = Light.FULL * 3 / 2

    /** How often a lens casts its cone again, in ticks, and how long what it laid is held for without one. */
    const val CAST_EVERY = 20L
    const val HELD_FOR = CAST_EVERY * 2

    /** Lights are laid at most one in each cube this many blocks on a side, close enough that every lit face is bright. */
    private const val LIGHTS_APART = 2

    /** Rays across the cone for each block of its width where it ends, and the fewest and most. */
    private const val RAYS_A_BLOCK = 2.0
    private const val FEWEST_RAYS_ACROSS = 5
    private const val MOST_RAYS_ACROSS = 65

    /** An armed laser spread hurts half what it would, and never more than a heart. */
    private const val HURTS_SPREAD = 0.5f
    private const val MOST_HURT = 2f

    /** A burning laser spread burns at half its rate, shared between every surface it lights. */
    private const val BURNS_SPREAD = 0.5f

    private const val OFF_THE_FACE = 0.001

    /** The light a lens sends out for [fed]: half of it, the price of spreading it. */
    fun dispersed(fed: Light): Light = fed.sharedBetween(2)

    /** How wide [aperture] spreads its light for every block it goes. */
    fun spreadAt(aperture: Int): Double = ONE_LAYER * EACH_LAYER.pow(aperture.coerceIn(0, APERTURES - 1))

    /** How far [out] reaches through [aperture]: from the narrowest's reach to the widest's, as the aperture opens. */
    fun reach(aperture: Int, out: Light): Double {
        val opened = aperture.coerceIn(0, APERTURES - 1).toDouble() / (APERTURES - 1)
        val onHalfALight = NARROWEST_REACH + (WIDEST_REACH - NARROWEST_REACH) * opened
        return onHalfALight * out.intensity.coerceAtMost(MOST_LIGHT_OUT) / HALF_A_LIGHT
    }

    fun nextAperture(aperture: Int, wider: Boolean): Int = Math.floorMod(aperture + if (wider) 1 else -1, APERTURES)

    fun hurtFor(arc: Arc): Float = (arc.hurts * HURTS_SPREAD).coerceAtMost(MOST_HURT)

    /** What each of [surfaces] lit surfaces burns by a tick, of what a full laser does. */
    fun burnRate(fed: Light, surfaces: Int): Float = fed.intensity.toFloat() / Light.FULL * BURNS_SPREAD / surfaces.coerceAtLeast(1)

    /** The cone a lens at [lens] in [state] sends [out] over. */
    fun coneOf(lens: BlockPos, state: BlockState, out: Light): Cone {
        val facing = state.getValue(DispersionLensBlock.FACING)
        val aperture = state.getValue(DispersionLensBlock.APERTURE)
        val forward = Vec3.atLowerCornerOf(facing.unitVec3i)
        val (right, up) = acrossOf(facing)
        val origin = Vec3.atCenterOf(lens).add(forward.scale(0.5 + OFF_THE_FACE))
        return Cone(origin, forward, right, up, spreadAt(aperture), reach(aperture, out))
    }

    private fun acrossOf(facing: Direction): Pair<Vec3, Vec3> =
        if (facing.axis == Direction.Axis.Y) Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 0.0, 1.0)
        else Vec3.atLowerCornerOf(facing.clockWise.unitVec3i) to Vec3(0.0, 1.0, 0.0)

    /** Rays across [cone] to what each first reaches, and a light laid in the air before it, one to each cube. */
    fun cast(level: Level, cone: Cone): Cast {
        val surfaces = LinkedHashMap<BlockPos, LitSurface>()
        val spots = LinkedHashMap<BlockPos, BlockPos>()
        for (direction in raysAcross(cone)) {
            val reached = reached(level, cone.origin, direction, cone.reach) ?: continue
            surfaces.putIfAbsent(reached.surface.pos, reached.surface)
            val before = reached.before ?: continue
            spots.putIfAbsent(cubeOf(before), before)
        }
        return Cast(surfaces.values.toList(), spots.values.toSet())
    }

    private fun cubeOf(pos: BlockPos) =
        BlockPos(Math.floorDiv(pos.x, LIGHTS_APART), Math.floorDiv(pos.y, LIGHTS_APART), Math.floorDiv(pos.z, LIGHTS_APART))

    /** The rays of a grid across [cone]'s circle, an odd number across so one runs down its middle. */
    private fun raysAcross(cone: Cone): List<Vec3> {
        val widthWhereItEnds = 2.0 * cone.spread * cone.reach
        val counted = ceil(widthWhereItEnds * RAYS_A_BLOCK).toInt().coerceIn(FEWEST_RAYS_ACROSS, MOST_RAYS_ACROSS)
        val across = counted or 1
        val rays = mutableListOf<Vec3>()
        for (row in 0..<across) {
            for (column in 0..<across) {
                val aside = (2.0 * column / (across - 1) - 1.0)
                val upward = (2.0 * row / (across - 1) - 1.0)
                if (aside * aside + upward * upward > 1.0) continue
                val tilt = cone.right.scale(aside * cone.spread).add(cone.up.scale(upward * cone.spread))
                rays += cone.forward.add(tilt).normalize()
            }
        }
        return rays
    }

    /** What a ray reached, and the last block of open air it crossed before it, where there was one. */
    private data class Reached(val surface: LitSurface, val before: BlockPos?)

    /** The first block a ray from [from] along [direction] cannot pass within [reach], stepping block by block. */
    private fun reached(level: Level, from: Vec3, direction: Vec3, reach: Double): Reached? {
        var x = floor(from.x).toInt()
        var y = floor(from.y).toInt()
        var z = floor(from.z).toInt()
        val stepX = if (direction.x > 0) 1 else -1
        val stepY = if (direction.y > 0) 1 else -1
        val stepZ = if (direction.z > 0) 1 else -1
        val deltaX = if (direction.x == 0.0) Double.MAX_VALUE else abs(1.0 / direction.x)
        val deltaY = if (direction.y == 0.0) Double.MAX_VALUE else abs(1.0 / direction.y)
        val deltaZ = if (direction.z == 0.0) Double.MAX_VALUE else abs(1.0 / direction.z)
        var nextX = (if (stepX > 0) x + 1 - from.x else from.x - x) * deltaX
        var nextY = (if (stepY > 0) y + 1 - from.y else from.y - y) * deltaY
        var nextZ = (if (stepZ > 0) z + 1 - from.z else from.z - z) * deltaZ
        val position = BlockPos.MutableBlockPos(x, y, z)
        var before: BlockPos? = if (isOpenAir(level.getBlockState(position))) position.immutable() else null
        while (true) {
            // The face of the block entered, and how far along the ray it is.
            val (travelled, travelling) = when {
                nextX <= nextY && nextX <= nextZ -> { x += stepX; val at = nextX; nextX += deltaX; at to (if (stepX > 0) Direction.EAST else Direction.WEST) }
                nextY <= nextZ -> { y += stepY; val at = nextY; nextY += deltaY; at to (if (stepY > 0) Direction.UP else Direction.DOWN) }
                else -> { z += stepZ; val at = nextZ; nextZ += deltaZ; at to (if (stepZ > 0) Direction.SOUTH else Direction.NORTH) }
            }
            if (travelled > reach) return null
            position.set(x, y, z)
            if (!level.isLoaded(position)) return null
            val state = level.getBlockState(position)
            if (!Beam.passes(state, level, position)) return Reached(LitSurface(position.immutable(), travelling), before)
            if (isOpenAir(state)) before = position.immutable()
        }
    }

    private fun isOpenAir(state: BlockState): Boolean = state.isAir || state.block is DispersedLightBlock
}

/**
 * Light a dispersion lens laid in the air: invisible, passed through and built over, as bright as daylight, and in the
 * colour of the light the lenses holding it were fed, mixed, which [co.voik.agesandtheart.client.light.TintedLights] casts on what it lights.
 * It goes out by itself once no lens holds it ([DispersedLights]).
 */
class DispersedLightBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(COLOUR, RimeColour.WHITE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(COLOUR)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = Shapes.empty()

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        if (!level.isClientSide) level.scheduleTick(pos, this, CHECKED_EVERY)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (DispersedLights.keep(level, pos)) level.scheduleTick(pos, this, CHECKED_EVERY)
        else level.removeBlock(pos, false)
    }

    companion object {
        val COLOUR: EnumProperty<RimeColour> = EnumProperty.create("colour", RimeColour::class.java)

        const val BRIGHTNESS = 15
        private const val CHECKED_EVERY = 40
    }
}

/**
 * Which lenses hold each dispersed light, in what colour and until when. A light cannot keep this itself, being a block
 * with no entity, so it is kept here per level, never saved: after a restart every light goes out unless a lens lays it
 * again first.
 */
object DispersedLights {
    private data class Claim(val colour: RimeColour, val until: Long)

    // Server-thread only, and let go with the level.
    private val held = WeakHashMap<Level, MutableMap<BlockPos, MutableMap<BlockPos, Claim>>>()

    private fun heldIn(level: Level) = held.getOrPut(level) { mutableMapOf() }

    /**
     * Lays a light at [at] held by [lens] in [colour] until [until], unless something other than air stands there,
     * mixed with the colours of the other lenses holding it.
     */
    fun lay(level: ServerLevel, at: BlockPos, colour: RimeColour, lens: BlockPos, until: Long): Boolean {
        val standing = level.getBlockState(at)
        if (!standing.isAir && standing.block !is DispersedLightBlock) return false
        val holders = heldIn(level).getOrPut(at) { mutableMapOf() }
        holders[lens] = Claim(colour, until)
        show(level, at, holders.values)
        return true
    }

    /** [lens] no longer holds the light at [at], which goes out where nothing else does and takes on what is left. */
    fun letGo(level: ServerLevel, at: BlockPos, lens: BlockPos) {
        val holders = heldIn(level)[at]
        holders?.remove(lens)
        if (holders != null && holders.isNotEmpty()) return show(level, at, holders.values)
        heldIn(level).remove(at)
        if (level.getBlockState(at).block is DispersedLightBlock) level.removeBlock(at, false)
    }

    /** Whether the light at [at] is still held, letting go of the claims past their time and taking on what is left. */
    fun keep(level: ServerLevel, at: BlockPos): Boolean {
        val holders = heldIn(level)[at] ?: return false
        val hadLapsed = holders.values.removeIf { claim -> claim.until < level.gameTime }
        if (holders.isEmpty()) heldIn(level).remove(at)
        else if (hadLapsed) show(level, at, holders.values)
        return holders.isNotEmpty()
    }

    private fun show(level: ServerLevel, at: BlockPos, claims: Collection<Claim>) {
        val colour = Light.mixed(claims.map(Claim::colour))
        val light = Optics.DISPERSED_LIGHT.defaultBlockState().setValue(DispersedLightBlock.COLOUR, colour)
        if (level.getBlockState(at) != light) level.setBlock(at, light, Block.UPDATE_CLIENTS)
    }
}
