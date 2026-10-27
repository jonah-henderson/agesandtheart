package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.aspect.AgeSpawner
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Spawning
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.WhereThingsGrow
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.worldgen.biome.BiomeHome
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.biome.TheEndBiomeSource

/**
 * How often a player meets each kind of monster in an Age, and in the world it was written over — an approximation
 * read off the same spawn menus the Age's generator hands vanilla, never measured in play.
 *
 * A biome's share of the ground is its share of the climate table, after the book's preferences; a monster's share
 * of a biome is its weight times its usual group, against everything the biome offers in the monster pass.
 */
class MonsterCensus(
    /** The ground of an Age, with its book's biome preferences applied. */
    private val groundOf: (preferences: List<BiomePreference>, keepsOnlyNamed: Boolean, seed: Long) -> List<Patch>,
    private val spawning: Spawning,
    /** Every creature the pack offers somewhere — [WhereThingsGrow.creaturesListed]. */
    private val livesSomewhere: Set<Identifier>,
) {
    /** One biome of an Age, the share of its ground it covers, and the monsters it offers there. */
    data class Patch(val biome: Identifier, val share: Double, val monsters: WeightedList<MobSpawnSettings.SpawnerData>)

    /** Each kind of monster as a share of the monster cap, by creature id. */
    data class Meetings(
        /** Those the natural spawner brings, which the cap the book set scales. */
        val spawned: Map<String, Double>,
        /** Those the Age places itself, which no cap holds. */
        val placed: Map<String, Double>,
        /** Those the same world brings as nobody wrote it. */
        val ownWorlds: Map<String, Double>,
    )

    /** Who is met in the Age [composition] describes, and in its world as nobody wrote it. */
    fun of(composition: AgeComposition, seed: Long): Meetings {
        val spawns = composition.optionsFor(Aspect.SPAWNS, 0)
        val biomes = composition.optionsFor(Aspect.BIOMES, 0)
        val ground = groundOf(Biomes.preferencesIn(biomes), Biomes.keepsOnlyNamed(biomes), seed)
        val living = Spawns.livingIn(spawns, spawning, livesSomewhere)
        val offered = if (spawns.isTrue(Spawns.INCONGRUOUS)) offeredAnywhere(ground) else ground
        return Meetings(
            spawned = presenceOver(offered) { patch ->
                IN_THE_DARK.map { where -> living.at(patch.biome, MobCategory.MONSTER, where, patch.monsters) }
            },
            placed = placedBy(spawns),
            // Asked in the same two places, so a book that says nothing of its creatures meets exactly its own.
            ownWorlds = presenceOver(groundOf(emptyList(), false, seed)) { patch -> IN_THE_DARK.map { patch.monsters } },
        )
    }

    /** What the Age places itself, each at most a few to the ground its spacing holds them apart over. */
    private fun placedBy(spawns: Options): Map<String, Double> {
        val placing = AgeSpawner.placing(spawns, spawning)?.placedCreatures.orEmpty()
        return placing.associate { placement ->
            val apart = if (placement.spacing > 0) placement.spacing.toDouble() else PLACED_WITHOUT_SPACING
            val aroundAPlayer = AROUND_A_PLAYER / (apart * apart) * placement.arrival.most
            BuiltInRegistries.ENTITY_TYPE.getKey(placement.type).toString() to aroundAPlayer / VANILLAS_MONSTER_CAP
        }
    }

    companion object {
        /** Where a spawn attempt is made: in the open and under the rock, both dark enough for a monster. */
        private val IN_THE_DARK = listOf(true, false).map { skyIsOpen ->
            Spawns.Situation(BlockPos.ZERO, skyIsOpen, brightness = 0)
        }

        /** The blocks around a player the monster cap is counted over: vanilla's seventeen chunks square. */
        private const val AROUND_A_PLAYER = 17.0 * 16.0 * 17.0 * 16.0

        private const val VANILLAS_MONSTER_CAP = 70.0

        /** How far apart the Age keeps a placed creature held apart from nothing: twice the reach it counts within. */
        private const val PLACED_WITHOUT_SPACING = 128.0

        /** The census on [server], for an Age written over [template]. */
        fun of(server: MinecraftServer, template: AgeTemplate): MonsterCensus = over(
            server.registryAccess(),
            template,
            Vocabulary.of(server).spawning,
            BiomeHome.of(server),
        )

        /** The same over [registries] — offline, and what the checks use. */
        fun over(
            registries: HolderLookup.Provider,
            template: AgeTemplate,
            spawning: Spawning,
            homes: Map<Identifier, BiomeHome> = emptyMap(),
        ): MonsterCensus = MonsterCensus(
            { preferences, keepsOnlyNamed, seed -> groundOf(registries, template, preferences, keepsOnlyNamed, seed, homes) },
            spawning,
            WhereThingsGrow.creaturesListed(registries),
        )

        /**
         * [template]'s biomes and the share each covers: its entries in the climate table, the book's own weight on
         * any it scaled where it stands. The End has no table, and its biomes cover alike.
         */
        private fun groundOf(
            registries: HolderLookup.Provider,
            template: AgeTemplate,
            preferences: List<BiomePreference>,
            keepsOnlyNamed: Boolean,
            seed: Long,
            homes: Map<Identifier, BiomeHome>,
        ): List<Patch> {
            val lookup = registries.lookupOrThrow(Registries.BIOME)
            val table = template.biomeTable(registries, preferences, keepsOnlyNamed, seed, homes)
                ?: return TheEndBiomeSource.create(lookup).possibleBiomes().mapNotNull { patchOf(it, 1.0) }
            val scaled = preferences.filterNot { it.removes }.associate { it.biome to it.weight }
            val entries = table.values().groupingBy { it.second }.eachCount()
            return entries.mapNotNull { (biome, count) ->
                val weight = biome.unwrapKey().orElse(null)?.identifier()?.let(scaled::get) ?: BiomePreference.ORDINARY
                patchOf(biome, count * weight)
            }
        }

        private fun patchOf(biome: Holder<Biome>, share: Double): Patch? {
            val id = biome.unwrapKey().orElse(null)?.identifier() ?: return null
            val monsters = biome.value().attributes
                .applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
                .getMobsToSpawn(MobCategory.MONSTER)
            return Patch(id, share, monsters)
        }

        /** Every biome offering what every biome of [ground] offers, as an `incongruous inhabitants` Age's do. */
        private fun offeredAnywhere(ground: List<Patch>): List<Patch> {
            val anywhere = WeightedList.of(ground.flatMap { it.monsters.unwrap() }.distinctBy { it.value().type() })
            return ground.map { it.copy(monsters = anywhere) }
        }

        /**
         * Each monster's share of what [ground] spawns, as many as come together times how often they are drawn, over
         * the ground each patch covers. A patch offering nothing still covers its ground, so a world of mushroom
         * fields holds less of everything.
         */
        private fun presenceOver(
            ground: List<Patch>,
            menusOf: (Patch) -> List<WeightedList<MobSpawnSettings.SpawnerData>>,
        ): Map<String, Double> {
            val whole = ground.sumOf { it.share }
            if (whole <= 0.0) return emptyMap()
            return ground.flatMap { patch ->
                val menus = menusOf(patch)
                menus.flatMap { menu ->
                    sharesOf(menu).map { (creature, share) -> creature to share * patch.share / whole / menus.size }
                }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, shares) -> shares.sum() }
        }

        /** Each creature's share of those [menu] brings: how often it is drawn, times how many come together. */
        private fun sharesOf(menu: WeightedList<MobSpawnSettings.SpawnerData>): List<Pair<String, Double>> {
            fun arriving(entry: Weighted<MobSpawnSettings.SpawnerData>): Double {
                val together = entry.value().count()
                return entry.weight() * (together.minInclusive() + together.maxInclusive()) / 2.0
            }
            val entries = menu.unwrap()
            val all = entries.sumOf(::arriving)
            if (all <= 0.0) return emptyList()
            return entries.map { entry -> BuiltInRegistries.ENTITY_TYPE.getKey(entry.value().type()).toString() to arriving(entry) / all }
        }
    }
}
