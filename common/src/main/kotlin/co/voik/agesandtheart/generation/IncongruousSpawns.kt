package co.voik.agesandtheart.generation

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.util.RandomSource
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.Difficulty
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.MobSpawnSettings
import java.math.BigInteger

/**
 * What an `incongruous inhabitants` Age lets live anywhere: every creature its
 * biomes offer is offered everywhere, and turns up past its own placement rules — fish on hillsides, cows in caves,
 * monsters at noon. `NaturalSpawnerMixin` asks [anywhere] at vanilla's two checks and the creature's own.
 *
 * **Scarabs keep their own rules**, and their own places: they are a reward, found where they belong.
 * What vanilla keeps regardless is the peaceful rule and the collision checks, so nothing spawns inside a wall.
 *
 * **A structure keeps half its own** (Jonah, 2026-10-30): inside one that spawns its own creatures, half of what spawns is
 * the structure's and half everything else ([halfAndHalf]), so a fortress still has its blazes. Of the creatures it is
 * built with, half are something else too ([inPlaceOf]): a witch hut's witch and cat, a mansion's illagers.
 *
 * Every kind is as likely as the next ([everyKindAlike]), and a fresh chunk is made mostly of creatures
 * ([madeAnywhere]) (Jonah, 2026-11-06).
 */
object IncongruousSpawns {

    /** Whether [type] may turn up anywhere in [level] — true only in an Age that says so, and never for a scarab. */
    @JvmStatic
    fun anywhere(level: LevelReader, type: EntityType<*>): Boolean {
        val generator = (level as? ServerLevel)?.chunkSource?.generator as? AgeChunkGenerator ?: return false
        return generator.incongruity.spawns && type !== AgeContent.SCARAB
    }

    /** Whether [type] may spawn at all at [level]'s difficulty — the one rule kept past [anywhere]. */
    @JvmStatic
    fun allowedAtThisDifficulty(level: ServerLevel, type: EntityType<*>): Boolean =
        type.isAllowedInPeaceful || level.difficulty != Difficulty.PEACEFUL

    /** Whether a fortress's floor in [level] asks the Age's menu rather than handing out the fortress's own outright. */
    @JvmStatic
    fun reachesIntoFortresses(level: ServerLevel): Boolean =
        (level.chunkSource.generator as? AgeChunkGenerator)?.incongruity?.spawns == true

    /**
     * [own] and [everywhere] weighed to half each, [everywhere] without what [own] already offers, or [own] alone where
     * nothing else is left.
     */
    fun halfAndHalf(
        own: WeightedList<MobSpawnSettings.SpawnerData>,
        everywhere: WeightedList<MobSpawnSettings.SpawnerData>,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        val ownTypes = own.unwrap().map { it.value().type() }.toSet()
        val others = everywhere.unwrap().filter { it.value().type() !in ownTypes }
        val ownWeight = own.unwrap().sumOf { it.weight() }
        val othersWeight = others.sumOf { it.weight() }
        if (ownWeight == 0 || othersWeight == 0) return own
        // Each side scaled by the other's total, over their common factor, so the two come to the same sum.
        val common = BigInteger.valueOf(ownWeight.toLong()).gcd(BigInteger.valueOf(othersWeight.toLong())).toInt()
        fun scaled(entries: List<Weighted<MobSpawnSettings.SpawnerData>>, by: Int) = entries.map { Weighted(it.value(), it.weight() * by) }
        return WeightedList.of(scaled(own.unwrap(), othersWeight / common) + scaled(others, ownWeight / common))
    }

    /** Every creature [biomes] offer for [category], scarabs aside — the menu everywhere in such an Age. */
    fun offeredAnywhere(biomes: Collection<Holder<Biome>>, category: MobCategory): WeightedList<MobSpawnSettings.SpawnerData> =
        everyKindAlike(
            // A biome's spawners are an environment attribute in 26.3, laid over nothing here.
            biomes.flatMap { biome ->
                biome.value().attributes
                    .applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
                    .getMobsToSpawn(category)
                    .unwrap()
            },
        )

    /** Each kind in [entries] once, scarabs aside, and every one as likely as the next. */
    fun everyKindAlike(entries: List<Weighted<MobSpawnSettings.SpawnerData>>): WeightedList<MobSpawnSettings.SpawnerData> =
        WeightedList.of(
            entries.filter { BuiltInRegistries.ENTITY_TYPE.getKey(it.value().type()) != SCARAB }
                .distinctBy { it.value().type() }
                .map { Weighted(it.value(), ALIKE) },
        )

    /**
     * What a fresh chunk is made with from [menus], one per pass: the creatures three parts in four, and every other
     * kind — monsters, fish, bats — sharing the fourth, so the land has its animals and the odd fish or zombie among them.
     */
    fun madeAnywhere(menus: Map<MobCategory, WeightedList<MobSpawnSettings.SpawnerData>>): WeightedList<MobSpawnSettings.SpawnerData> {
        val creatures = menus[MobCategory.CREATURE]?.unwrap().orEmpty()
        val rest = menus.filterKeys { it != MobCategory.CREATURE }.values.flatMap { it.unwrap() }
        if (creatures.isEmpty() || rest.isEmpty()) return WeightedList.of(creatures + rest)
        // Each side weighed by the other's count, so the two sides' totals stand in the ratio of their parts.
        fun weighed(entries: List<Weighted<MobSpawnSettings.SpawnerData>>, by: Int) = entries.map { Weighted(it.value(), by) }
        return WeightedList.of(weighed(creatures, CREATURES_PARTS * rest.size) + weighed(rest, THE_REST_PARTS * creatures.size))
    }

    private val settling = ThreadLocal<Settling?>()

    private class Settling(val menus: Map<MobCategory, WeightedList<MobSpawnSettings.SpawnerData>>, val seed: Long) {
        var placingOneInstead = false
    }

    /** Runs [decorating] with this thread putting [menus]' creatures in place of half of those a structure is built with. */
    fun settling(
        incongruous: Boolean,
        menus: () -> Map<MobCategory, WeightedList<MobSpawnSettings.SpawnerData>>,
        seed: Long,
        decorating: () -> Unit,
    ) {
        if (!incongruous) return decorating()
        val was = settling.get()
        settling.set(Settling(menus(), seed))
        try {
            decorating()
        } finally {
            settling.set(was)
        }
    }

    /**
     * What stands in [entity]'s place where a structure puts it into [region] as it is built, or null to keep it: half
     * the time, a creature of the same pass drawn from the Age's menu, where it stood and as it was kept. Asked by
     * `IncongruousResidentsMixin` while [settling].
     */
    @JvmStatic
    fun inPlaceOf(region: WorldGenRegion, entity: Entity): Mob? {
        val now = settling.get() ?: return null
        val ridesOrIsRidden = entity.isPassenger || entity.isVehicle
        if (now.placingOneInstead || entity !is Mob || ridesOrIsRidden) return null
        val menu = now.menus[entity.type.category] ?: return null
        val random = RandomSource.create(now.seed xor entity.blockPosition().asLong() xor RESIDENT_SALT)
        if (random.nextFloat() >= SHARE_PUT_IN_PLACE) return null
        val drawn = menu.getRandom(random).orElse(null)?.type() ?: return null
        @Suppress("DEPRECATION") // Vanilla's own structure pieces still create through the region's level.
        val level = region.level
        if (drawn === entity.type || !allowedAtThisDifficulty(level, drawn)) return null
        val instead = drawn.create(level, EntitySpawnReason.STRUCTURE) as? Mob ?: return null
        instead.snapTo(entity.x, entity.y, entity.z, entity.yRot, entity.xRot)
        // NeoForge deprecates this for an extension of its own, which common cannot call.
        @Suppress("DEPRECATION")
        instead.finalizeSpawn(region, region.getCurrentDifficultyAt(instead.blockPosition()), EntitySpawnReason.STRUCTURE, null)
        if (entity.isPersistenceRequired) instead.setPersistenceRequired()
        return instead
    }

    /** Puts [instead] into [region] in another's place, without its being put in place of in turn. */
    @JvmStatic
    fun placeInstead(region: WorldGenRegion, instead: Mob): Boolean {
        val now = settling.get() ?: return region.addFreshEntity(instead)
        now.placingOneInstead = true
        try {
            return region.addFreshEntity(instead)
        } finally {
            now.placingOneInstead = false
        }
    }

    private val SCARAB = "scarab".location()
    private const val ALIKE = 1
    private const val CREATURES_PARTS = 3
    private const val THE_REST_PARTS = 1
    private const val SHARE_PUT_IN_PLACE = 0.5f
    private const val RESIDENT_SALT = 0x3C71_9E04_B25A_D86BL
}
