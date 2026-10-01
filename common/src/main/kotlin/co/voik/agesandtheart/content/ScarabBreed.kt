package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * Two scarabs of a colony making a third — **only where a free site remains for it**, the way villagers need
 * a free bed (design §7.1.2).
 *
 * The colony decides this itself; nothing a player holds starts it. Both parents must be adult, housed,
 * fed and rested, and the site is found by the claim test every nest was made by. **The young one is given
 * that site at birth**, so the site is spoken for the moment it is counted, and unclaimed mud caps the
 * colony exactly — two pairings can never be promised the same ground.
 *
 * Mushrooms make it happen sooner: a mushroom-fed scarab looks for a partner more often and rests less
 * between broods.
 */
class ScarabBreed(private val scarab: Scarab) : Goal() {

    private var partner: Scarab? = null
    private var site: BlockPos? = null
    private var courting = 0
    private var together = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        val oneInThisMany = if (scarab.isMushroomFed) MUSHROOM_FED_ODDS else ODDS
        val feelsLikeIt = scarab.random.nextInt(oneInThisMany) == 0
        if (!feelsLikeIt || !scarab.canBreedNow) return false
        val home = scarab.home ?: return false
        partner = nearestWilling(level) ?: return false
        site = freeSiteFor(level, home)
        return site != null
    }

    override fun canContinueToUse(): Boolean {
        val mate = partner ?: return false
        return mate.canBreedNow && scarab.canBreedNow && courting < GIVES_UP_AFTER
    }

    override fun start() {
        courting = 0
        together = 0
    }

    override fun stop() {
        partner = null
        site = null
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        val mate = partner ?: return
        courting++
        scarab.lookControl.setLookAt(mate)
        scarab.headFor(mate.position())
        mate.headFor(scarab.position())
        if (!scarab.isNear(mate.position(), CLOSE_ENOUGH)) return
        together++
        if (together % HEARTS_EVERY == 0) level.broadcastEntityEvent(scarab, EntityEvent.IN_LOVE_HEARTS)
        if (together >= TOGETHER_FOR) brood(level, mate)
    }

    private fun brood(level: ServerLevel, mate: Scarab) {
        val claimed = site ?: return
        // Asked again: a stray may have settled there while the two were courting.
        if (ScarabHabitat.freeSiteAt(level, claimed.x, claimed.z, claimed.y + 1) != claimed) {
            partner = null
            return
        }
        val young = scarab.getBreedOffspring(level, mate) ?: return
        young.isBaby = true
        young.snapTo(scarab.x, scarab.y, scarab.z, 0.0f, 0.0f)
        young.bornToAColony()
        level.addFreshEntityWithPassengers(young)
        level.setBlockAndUpdate(claimed, AgeContent.SCARAB_NEST_BLOCK.defaultBlockState())
        (level.getBlockEntity(claimed) as? ScarabNestBlockEntity)?.let { nest ->
            nest.claimFor(young, young.random)
            young.settleIn(claimed)
        }
        val rest = if (scarab.isMushroomFed || mate.isMushroomFed) MUSHROOM_FED_REST else REST
        scarab.age = rest
        mate.age = rest
        partner = null
    }

    private fun nearestWilling(level: ServerLevel): Scarab? =
        level.getEntitiesOfClass(Scarab::class.java, scarab.boundingBox.inflate(PARTNER_REACH)) { other ->
            other !== scarab && other.canBreedNow
        }.minByOrNull(scarab::distanceToSqr)

    private fun freeSiteFor(level: ServerLevel, home: BlockPos): BlockPos? {
        val random = scarab.random
        repeat(SITE_SAMPLES) {
            val x = home.x + random.nextIntBetweenInclusive(-BROOD_REACH, BROOD_REACH)
            val z = home.z + random.nextIntBetweenInclusive(-BROOD_REACH, BROOD_REACH)
            ScarabHabitat.freeSiteAt(level, x, z, home.y + 1)?.let { return it }
        }
        return null
    }

    private companion object {
        /** Asked each tick of an eligible scarab: about one pairing attempt a minute, a third of that fed. */
        const val ODDS = 1200
        const val MUSHROOM_FED_ODDS = 400

        /** How long parents rest between broods, as a cow's five minutes; halved by mushrooms. */
        const val REST = 6000
        const val MUSHROOM_FED_REST = 3000

        const val PARTNER_REACH = 12.0
        const val BROOD_REACH = 16
        const val SITE_SAMPLES = 128
        const val CLOSE_ENOUGH = 2.0
        const val TOGETHER_FOR = 60
        const val HEARTS_EVERY = 10
        const val GIVES_UP_AFTER = 600
    }
}
