package co.voik.agesandtheart.generation

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.Difficulty
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.EntityType
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
 * the structure's and half everything else ([halfAndHalf]), so a fortress still has its blazes.
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
        WeightedList.of(
            // A biome's spawners are an environment attribute in 26.3, laid over nothing here.
            biomes.flatMap { biome ->
                biome.value().attributes
                    .applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
                    .getMobsToSpawn(category)
                    .unwrap()
            }
                .filter { it.value().type() !== AgeContent.SCARAB }
                .distinctBy { it.value().type() },
        )
}
