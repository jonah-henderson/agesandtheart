package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.ThrowableProjectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * One body coming in out of a meteor storm (design §5.2).
 *
 * **Not a volcanic bomb with a different texture**, though they share a renderer. A bomb arcs slowly on
 * purpose so you can read where it will land and move; a meteor comes in fast and nearly straight, and its
 * counterplay is not dodging the one you can see but being somewhere the storm is not. So they differ in
 * exactly the thing an entity is: how it flies.
 *
 * **What happens when it lands is the whole material design** (§7.1.2). Three blocks of cushion catch it
 * whole; slime hands all of its energy back; anything else and it shatters into a crater. The depth is the
 * same for every cushion on the reasoning that these arrive from space, and it is what makes the rule
 * teach itself — a body that comes down at sea is caught, and one that comes down on the shore digs a
 * crater the water fills deeper than three, so the next one there is caught too.
 */
class Meteor(type: EntityType<out Meteor>, level: Level) : ThrowableProjectile(type, level) {

    /**
     * Whether this one has been caught and come to rest.
     *
     * **One flag, four behaviours**, which is why it is a state and not four. Before it, a body flies
     * through fluid, glows, cannot be kept, and is landed by [overdue] if the world stopped ticking under
     * it. After it, the same body floats, cools, persists indefinitely and can be broken open. Watched,
     * because the cooling is drawn.
     */
    var settled: Boolean
        get() = entityData.get(SETTLED)
        set(value) = entityData.set(SETTLED, value)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(SETTLED, false)
    }

    /**
     * Hittable, in flight as well as at rest.
     *
     * A caught body has to be, or there would be no breaking it open. **In flight it is a stunt**: a
     * player who can hit a thing crossing twenty blocks a tick has earned what falls out of it, and has
     * bought off the crater into the bargain.
     */
    override fun isPickable(): Boolean = true

    /** But never by another body of the same storm, or a shower would knock itself down mid-air. */
    override fun canHitEntity(target: Entity): Boolean = target !is Meteor && super.canHitEntity(target)

    /** It came in through an atmosphere. Fire is not what breaks one. */
    override fun fireImmune(): Boolean = true

    /**
     * Drawn from as far off as it is tracked.
     *
     * **Vanilla would draw one for the last forty-five blocks of a two-hundred-block flight**, because its
     * rule is the entity's own width times sixty-four and a meteor is a small thing carrying a very large
     * streak. So a body landing anywhere but at your feet arrived with no trail at all, which is exactly
     * what a walk reported (Jonah). Nothing about the rule is wrong in general; it is wrong for a thing
     * whose drawn size has nothing to do with its hitbox.
     */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < SEEN_FROM * SEEN_FROM

    /**
     * Barely any, and it hardly matters: a body arrives at a speed that makes its own path nearly straight,
     * so gravity here is a lean rather than a fall. It is [MeteorStorm] that decides how hard one comes in.
     */
    override fun getDefaultGravity(): Double = ALMOST_NONE

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        hit.entity.hurt(damageSources().thrown(this, getOwner()), STRUCK)
    }

    /**
     * What breaks one open, and what merely happens to it.
     *
     * **A pickaxe does what a pickaxe does — its own digging speed** (`ToolMaterial.speed`, two for wood
     * and nine for netherite), rather than a multiplier on whatever the swing was worth. Multiplying would
     * have a diamond *sword* nearly halving a meteor, which is silly; reading the digging speed gives the
     * ladder for nothing and needs no tier of our own to author. Anything else in hand is a few hits, bare
     * hands are a long job, and an arrow is worth exactly what an arrow is worth — which is what makes
     * shooting one down possible at all.
     */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (shieldedFrom(source)) return false
        toughness -= biteOf(source, amount)
        if (toughness > NOTHING_LEFT) return true
        breakOpen(level)
        return true
    }

    /**
     * **Its own kind, and the things it arrived through.**
     *
     * A storm is hundreds of blasts among hundreds of bodies, so a body that could be broken by another
     * body's explosion is a shower that knocks itself out of the sky. Nothing else is excused — and
     * *deliberately* nothing else: a blast that is not a meteor's still tells, so anyone who works out how
     * to put TNT where a meteor is going to be has earned what falls out of it (Jonah).
     */
    private fun shieldedFrom(source: DamageSource): Boolean {
        val oneOfItsOwn = source.directEntity is Meteor
        val whatItCameThrough = source.`is`(DamageTypeTags.IS_FIRE) || source.`is`(DamageTypeTags.IS_DROWNING)
        return oneOfItsOwn || whatItCameThrough
    }

    private fun biteOf(source: DamageSource, amount: Float): Float {
        // Not a swing at all — an arrow, or a blast. Those are worth what they are worth.
        val swinging = source.directEntity as? LivingEntity ?: return amount
        val holding = swinging.mainHandItem
        if (holding.isEmpty) return BARE_HANDED
        if (!holding.`is`(ItemTags.PICKAXES)) return THE_WRONG_TOOL
        return holding.getDestroySpeed(Blocks.STONE.defaultBlockState())
    }

    /**
     * Broken open, wherever that happened.
     *
     * **The shards keep most of what it was doing** (Jonah), so one broken on the ground spills at your
     * feet and one shot out of the sky throws its astrite along the way it was going. Tracking them down
     * is the price of the shot.
     */
    private fun breakOpen(level: ServerLevel) {
        val flung = deltaMovement.scale(SHARDS_KEEP)
        repeat(worthBreaking(level)) {
            val shard = ItemEntity(level, x, y, z, ItemStack(AgeContent.ASTRITE_SHARD))
            shard.deltaMovement = flung.add(scatterOf(level), scatterOf(level), scatterOf(level))
            level.addFreshEntity(shard)
        }
        level.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS)
        discard()
    }

    private fun scatterOf(level: ServerLevel): Double = (level.random.nextDouble() - HALF) * SCATTERS_BY

    /**
     * What a body is worth: **nought to two always, and up to three more the harder it lands.**
     *
     * Without the second half a weak storm is *strictly* the better farm (Jonah): every body paid the
     * same whatever it hit like, while a gentle one leaves a hole you patch and a fierce one leaves a base
     * you rebuild. Danger paying nothing inverts the whole premise of §7.
     *
     * **Each extra shard is its own roll against how fierce the body was**, which is the shape vanilla
     * uses for a bonus and is why it *ramps*. Sizing the ceiling instead made it step: with the ceiling
     * rounded, everything below half fury bought nothing at all and then it jumped, so half the range was
     * dead. This way every degree of fury is worth something.
     */
    private fun worthBreaking(level: ServerLevel): Int {
        var shards = level.random.nextInt(MOST_SHARDS + ONE_MORE)
        val fierce = MeteorStorm.fiercenessOf(blast)
        repeat(MOST_BESIDES) { if (level.random.nextDouble() < fierce) shards++ }
        return shards
    }

    /** What it has left before it comes apart. Saved, so a half-broken one stays half-broken. */
    private var toughness: Float = WHOLE

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level()
        if (level !is ServerLevel) return
        val at = if (hit is BlockHitResult) hit.blockPos else blockPosition()

        if (hit is BlockHitResult && level.getBlockState(at).`is`(REBOUNDS)) {
            reboundOff(level, hit)
            return
        }
        landOn(level, at)
    }

    /** Caught or shattered, whichever the ground it came to rest on deserves. */
    private fun landOn(level: ServerLevel, at: BlockPos) {
        if (cushionAround(level, at) >= CAUGHT_BY) {
            settle(level, at)
            return
        }
        shatter(level)
        discard()
    }

    /**
     * A body whose flight outlasted the world it was flying through comes down where it is, at once.
     *
     * **Because a chunk that stops ticking stops a meteor mid-air.** Run far enough from a storm and its
     * bodies are simply paused; walk back and they resume, arriving out of a clear sky seconds or minutes
     * after the storm that threw them (Jonah, walked). Neither leaving them hanging nor deleting them is
     * right — the crater is the reward, and coming back to a landscape nothing happened to is the loop
     * broken. So it lands: by the time you return, the thing has been and gone, which is the truth.
     *
     * Measured against the **world's** clock rather than its own, since its own is what stopped.
     */
    private fun overdue(level: ServerLevel): Boolean = level.gameTime - thrownAt > A_WHOLE_FLIGHT

    private fun comeDownNow(level: ServerLevel) {
        // **On its way up, it is not late — it is bouncing** (Jonah, walked). A body climbing off a slime
        // block has done nothing wrong and there is no sense in snapping it to the ground and setting it
        // off; it simply goes, the way it would have if the chunk had held on a moment longer.
        if (deltaMovement.y > STILL_CLIMBING) {
            discard()
            return
        }
        // **Where it is, before where the column ends.** Deep water slows a body to a stop long before it
        // reaches the bed, so one that ran out of time is often already lying in three blocks of the very
        // thing that would have caught it.
        if (cushionAround(level, blockPosition()) >= CAUGHT_BY) {
            settle(level, blockPosition())
            return
        }
        val surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPosition())
        setPos(surface.x + MIDDLE, surface.y.toDouble(), surface.z + MIDDLE)
        // The heightmap answers with the free space *above* the column, and the cushion has to be read
        // from the block itself — testing the air is how one that came down on water or on a wool pad
        // went off anyway (Jonah, walked).
        landOn(level, surface.below())
    }

    /**
     * Sent back the way it came with everything it arrived with.
     *
     * **A hundred per cent of the energy, which is not physical and is the point** (Jonah): slime is the
     * one block in the game built to give energy back, and something arriving at this speed should do
     * something absurd when it meets one. Not discarded, so it goes on being a meteor until it finds
     * somewhere that is not slime.
     */
    private fun reboundOff(level: ServerLevel, hit: BlockHitResult) {
        val face = hit.direction
        val arriving = deltaMovement
        // Reflected about the face it struck, which for an axis-aligned face is one component negated.
        deltaMovement = arriving.with(face.axis, -arriving.get(face.axis))
        level.playSound(null, hit.blockPos, SoundEvents.SLIME_BLOCK_HIT, SoundSource.BLOCKS)
    }

    /**
     * Caught whole — and it stays, which is the point of catching one.
     *
     * **It does not become a block and it does not spill items** (Jonah). Either would end the thing as an
     * object, and the object is the reward: what a pond you dug and stocked with three blocks of water
     * gets you is a meteor lying in it, visibly the one you watched come down. Turning it into astrite is
     * a second act, and one you have to go and do.
     */
    private fun settle(level: ServerLevel, at: BlockPos) {
        settled = true
        // **Its momentum is not taken off it**, which is what buys the rolling and the cooling both: it
        // ploughs into what caught it and is slowed by drag rather than by decree, and the glow is drawn
        // off how fast it is going, so it dims as it comes to rest instead of switching off.
        //
        // **Unless honey caught it** (`#agesandtheart:grips_a_meteor`), which stops one dead where it
        // landed — the property honey already has over everything else in the game, so it needs no
        // teaching. The cost is that such a body goes cold at once rather than fading, the glow being
        // read off speed; a thing gripped by honey having stopped instantly is the point of it.
        if (level.getBlockState(at).`is`(GRIPS)) deltaMovement = Vec3.ZERO
        level.playSound(null, at, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS)
    }

    /**
     * How it lies once it has been caught: floating where it was caught, or rolling where it was not.
     *
     * **Water and lava hold one up rather than swallowing it** (Jonah), which is also the picture the
     * catching rule was always painting — a cushion deep enough to take the fall is deep enough to float
     * what it caught. It bobs because the push does not stop the instant it breaks the surface.
     */
    private fun bobAbout() {
        deltaMovement = deltaMovement.add(NO_DRIFT, liftHere(), NO_DRIFT).scale(SLOWS_BY)
        // Once the bobbing has damped down to nothing worth drawing, stop it outright — otherwise a
        // rounding error keeps a rock trembling in a pond for ever (Jonah, walked).
        if (abs(deltaMovement.y) < COME_TO_REST) deltaMovement = deltaMovement.multiply(KEPT, NONE, KEPT)
        move(MoverType.SELF, deltaMovement)
        if (onGround()) deltaMovement = deltaMovement.multiply(ROLLS_ON, NONE, ROLLS_ON)
    }

    /**
     * The push up on it where it is: none in air, and in fluid **as much as it is still submerged**.
     *
     * A push that is simply on below the surface and off above it can only ever overshoot and come back,
     * which is a bob that never ends. Fading it out as the thing surfaces gives it a level to settle at —
     * where the lift and the weight cancel — and the damping does the rest.
     */
    private fun liftHere(): Double {
        val here = blockPosition()
        // The block below as well, or a body riding exactly at the line falls out of the fluid it is
        // floating in for a tick, is told to sink, and flickers between the two for ever.
        val surface = surfaceAt(here) ?: surfaceAt(here.below()) ?: return -WEIGHS
        // **Zero where the surface is level with the entity's own origin**, which is where the drawn lump
        // is centred — so it comes to rest half in and half out rather than just under (Jonah, walked).
        // Signed, so it is pulled down as readily as up and settles on the line from either side.
        return (surface - y).coerceIn(-A_WHOLE_BLOCK, A_WHOLE_BLOCK) * FLOATS
    }

    private fun surfaceAt(at: BlockPos): Double? {
        val fluid = level().getFluidState(at)
        if (fluid.isEmpty) return null
        return at.y + fluid.getHeight(level(), at).toDouble()
    }

    private fun shatter(level: ServerLevel) {
        // Never `MOB` or `BLOCK`. `MOB` respects `mobGriefing`, and a server with the rule off would get a
        // meteor storm that leaves no craters at all — an Age was written with this in it, so it is not a
        // setting. Both also resolve through a drop-decay rule that defaults *on*, where `TNT`'s defaults
        // off: this drops everything it breaks, which is what makes a lured storm over bare rock a mining
        // rig rather than a hole (design §7.1.2).
        level.explode(this, x, y, z, blast, true, Level.ExplosionInteraction.TNT)
    }

    /**
     * How deep the cushion is where this landed, counted through the column it came down.
     *
     * **Both directions, because a fluid and a pad are caught differently by the same rule.** A projectile
     * has no collision with water, so a body that came down at sea flies *through* it and stops on the
     * seabed — the cushion is everything above where it stopped. A wool or honey pad is solid, so the body
     * stops at its surface and the cushion is that block and what is under it. Counting up from above the
     * impact and down from the impact itself covers the two without asking which happened.
     */
    private fun cushionAround(level: ServerLevel, at: BlockPos): Int {
        var depth = 0
        var below = at
        while (depth < CAUGHT_BY && level.getBlockState(below).`is`(CATCHES)) {
            depth++
            below = below.below()
        }
        var above = at.above()
        while (depth < CAUGHT_BY && level.getBlockState(above).`is`(CATCHES)) {
            depth++
            above = above.above()
        }
        return depth
    }

    override fun tick() {
        val level = level()
        // A caught body has stopped being a projectile: it ages, it floats, and nothing else about a
        // flight applies to it — least of all [overdue], which exists to stop one hanging in the air and
        // would otherwise quietly resolve away the very thing a player built a pond to keep.
        if (settled) {
            baseTick()
            bobAbout()
            return
        }
        if (level is ServerLevel) {
            if (thrownAt == NOT_YET_THROWN) thrownAt = level.gameTime
            if (overdue(level)) {
                comeDownNow(level)
                return
            }
            // **The cushion catches it, not the bed under the cushion.** This used to happen only where a
            // body struck a block, which quietly made being caught a question about the seabed you
            // eventually reached rather than about the water you were in — and a body coming in at twenty
            // blocks a tick reached that bed in a way that did not catch (Jonah, walked). Asked every tick
            // instead, so the rule is what §7.1.2 says it is: three blocks of the stuff, and you are held.
            if (cushionAround(level, blockPosition()) >= CAUGHT_BY) {
                settle(level, blockPosition())
                return
            }
        }
        super.tick()
        if (!level.isClientSide) return
        shedSparks()
    }

    /**
     * When the world was, when this was *due* — see [overdue].
     *
     * Set by the storm rather than read off the clock here, because a body whose moment went by while
     * nobody was near has to arrive already too old to fly: backdating it is the whole of that.
     */
    var thrownAt: Long = NOT_YET_THROWN

    /**
     * The sparks it sheds coming in — an accent on the drawn streak, not the streak itself.
     *
     * **Laid along where it actually went, not at where it is.** One particle a tick at these speeds is a
     * dot every ten blocks or so, which reads as a dotted line rather than as anything burning.
     *
     * **Forced past the limiter**, because the ordinary call drops anything more than thirty-two blocks
     * from the camera — which is nearly the whole of a flight that starts a hundred and fifty out.
     */
    private fun shedSparks() {
        val travelled = position().subtract(xOld, yOld, zOld)
        val steps = travelled.length().toInt().coerceAtLeast(AT_LEAST_ONE)
        for (step in 0..<steps) {
            val along = step.toDouble() / steps
            val at = position().subtract(travelled.scale(along))
            level().addParticle(EMBER, FORCED, SHOW_ANYWAY, at.x, at.y, at.z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
            if (step % EVERY_FEW != NONE_LEFT) continue
            level().addParticle(ParticleTypes.END_ROD, FORCED, SHOW_ANYWAY, at.x, at.y, at.z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
        }
    }

    /** How hard this one goes off, set by the storm that threw it. */
    var blast: Float = TWICE_TNT

    override fun addAdditionalSaveData(output: net.minecraft.world.level.storage.ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putFloat(BLAST_KEY, blast)
        output.putLong(THROWN_KEY, thrownAt)
        output.putBoolean(SETTLED_KEY, settled)
        output.putFloat(TOUGHNESS_KEY, toughness)
    }

    override fun readAdditionalSaveData(input: net.minecraft.world.level.storage.ValueInput) {
        super.readAdditionalSaveData(input)
        blast = input.getFloatOr(BLAST_KEY, TWICE_TNT)
        thrownAt = input.getLongOr(THROWN_KEY, NOT_YET_THROWN)
        settled = input.getBooleanOr(SETTLED_KEY, false)
        toughness = input.getFloatOr(TOUGHNESS_KEY, WHOLE)
    }

    companion object {
        private val SETTLED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Meteor::class.java, EntityDataSerializers.BOOLEAN)

        /** Three blocks of it, whatever it is made of — these are arriving from space. */
        const val CAUGHT_BY = 3

        /**
         * How a caught one lies: buoyed in fluid, dropping in air, and slowed either way so it settles.
         *
         * [FLOATS] is a spring toward the waterline rather than a lift against a weight, so where it
         * settles is not a balance of the two — it is exactly the line, and the line is level with the
         * entity's own origin. [WEIGHS] is only what pulls one down in air.
         */
        private const val FLOATS = 0.12
        private const val WEIGHS = 0.04
        private const val SLOWS_BY = 0.88
        private const val ROLLS_ON = 0.7

        /** Below this the bobbing is over and is stopped, rather than left to tremble. */
        private const val COME_TO_REST = 0.006

        private const val A_WHOLE_BLOCK = 1.0
        private const val KEPT = 1.0
        private const val NONE = 0.0

        /**
         * What it takes to break one open, against `ToolMaterial.speed` — two for wood, nine for
         * netherite. So the best pickaxe does it in a blow and the worst takes five.
         */
        private const val WHOLE = 9.0f
        private const val NOTHING_LEFT = 0.0f
        private const val THE_WRONG_TOOL = 3.0f
        private const val BARE_HANDED = 1.0f

        /** How much of what it was doing the shards carry away, and how far they spread off it. */
        private const val SHARDS_KEEP = 0.4
        private const val SCATTERS_BY = 0.3
        private const val HALF = 0.5

        /** Nought to two (design §7.1.2): a catch is worth something, and not always. */
        private const val MOST_SHARDS = 2
        private const val ONE_MORE = 1

        /**
         * How many extra a body may be worth on top, one roll each against how hard it lands — so the
         * most destructive meteor an Age can throw is worth **five** (Jonah).
         *
         * Worth knowing that this multiplies with something that already scales: a fierce Age throws
         * about two and a half times the bodies as well, so the top of the ladder is roughly ten times a
         * resting Age's yield before a lure concentrates it. That is the apocalypse case and wants
         * watching rather than fixing.
         */
        private const val MOST_BESIDES = 3

        /** What will take the fall out of one: water, wool, honey. */
        val CATCHES: TagKey<Block> = TagKey.create(Registries.BLOCK, "catches_a_meteor".location())

        /** And what will not so much catch it as return it. */
        val REBOUNDS: TagKey<Block> = TagKey.create(Registries.BLOCK, "rebounds_a_meteor".location())

        /** And which of the things that catch one also hold it exactly where it landed. */
        val GRIPS: TagKey<Block> = TagKey.create(Registries.BLOCK, "grips_a_meteor".location())

        private const val ALMOST_NONE = 0.01
        private const val STRUCK = 6.0f
        /** Twice TNT — see [MeteorStorm]. Only ever used by a body somebody summoned without a storm. */
        private const val TWICE_TNT = 8.0f

        /**
         * A violet mote, which is the one particle in the game that takes a colour it is told.
         *
         * Redstone dust as a shape and nothing of redstone as a colour — the alternative was a bespoke
         * particle type, and that needs a texture the asset pass has not written.
         */
        private val EMBER = DustParticleOptions(0x9E72FF, 0.9f)

        /** How far off one is still drawn, in blocks — its whole flight, and then some. */
        private const val SEEN_FROM = 320.0

        /**
         * How long one may be in the air before it is simply put on the ground, in ticks.
         *
         * A real flight is fifteen to thirty; anything past this is a body that spent the difference in an
         * unloaded chunk, and it has no business finishing that arrival in front of somebody.
         */
        private const val A_WHOLE_FLIGHT = 80

        private const val NOT_YET_THROWN = -1L
        private const val MIDDLE = 0.5

        /** Any upward movement at all counts as still bouncing rather than still falling. */
        private const val STILL_CLIMBING = 0.0

        private const val FORCED = true
        private const val SHOW_ANYWAY = true
        private const val EVERY_FEW = 4
        private const val AT_LEAST_ONE = 1
        private const val NONE_LEFT = 0
        private const val NO_DRIFT = 0.0
        private const val BLAST_KEY = "blast"
        private const val THROWN_KEY = "thrown_at"
        private const val SETTLED_KEY = "settled"
        private const val TOUGHNESS_KEY = "toughness"

        val ID: Identifier = "meteor".location()
    }
}
