package co.voik.agesandtheart.age.aspect

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.biome.MobSpawnSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * What lives here (design §3.1, vanilla's `MobSpawnSettings`) — **whatever the biomes would hold**, until a
 * sentence says otherwise.
 *
 * No preset, like the other three populations: an Age starts from what its biomes spawn and a sentence
 * adjusts it. [LIVES] is where the writing happens — naming a creature asks for it, a rung says how often,
 * `except` strikes one out, `only` keeps just what was named.
 *
 * **Natural spawning only, which is vanilla's own line.** `EntitySpawnReason` separates `NATURAL`,
 * `CHUNK_GENERATION` and `SPAWNER` from `BREEDING`, `SPAWN_EGG`, `BUCKET` and `COMMAND`; this reads the
 * first group's source — the weighted list a biome offers — and never the second. So an Age that grows no
 * life is still an Age a writer can carry a cow into, which is the rule §7.2's economy needs.
 *
 * **The seam is a plain override.** `ChunkGenerator.getMobsAt` is public, takes the biome and the position,
 * and already resolves a structure's own spawn overrides before the biome's — so filtering *its* answer
 * costs no widening and covers a fortress as well as a field.
 */
object Spawns {

    /**
     * What lives here — populative, with `only`/`except` to narrow and a rung to say how often (§3.2,
     * [Claim]). Its values are entity types. Named `lives` to avoid `spawns.spawns`.
     *
     * A mention is worth the **ordinary** amount, like a structure set and a feature: naming a creature
     * asks for one that was not there rather than for more of one that was. And it may be emptied, a
     * lifeless world being a world a writer might well want.
     */
    val LIVES = Parameter.population("lives", leastKept = NOTHING_AT_ALL, emptiedBy = NOTHING).perBiome()

    /** How an Age says nothing lives here: no natural spawning at all, whatever its biomes would hold. */
    const val NOTHING = "nothing"

    private const val NOTHING_AT_ALL = 0.0

    /**
     * This Age's answer for one biome's weighted list, or the list itself where nothing was said.
     *
     * Takes the list vanilla resolved rather than the biome's own, so a structure that overrides spawning
     * inside itself is narrowed by the same sentence as the open ground around it.
     *
     * The biome arrives as an **id** rather than a holder, which is what every claim in this layer is
     * already made of — and it is what `in <biome>` (§4.3.1) will compare against when a sentence can
     * scope a claim to one.
     */
    fun livingIn(options: Options): (Identifier?, WeightedList<MobSpawnSettings.SpawnerData>) ->
    WeightedList<MobSpawnSettings.SpawnerData> {
        val claims = options.claimsOn(LIVES)
        if (Skew.of(claims).isSilent && claims.none { it.confinedTo != null }) {
            return { _, offered -> offered }
        }
        // **Asked per biome, because a claim may be confined to one** (§4.3.1). Remembered for the same
        // reason the feature settings are: the answer is the same every time and the question is asked
        // once per spawn attempt.
        val here = ConcurrentHashMap<Identifier, Skew>()
        return { biome, offered ->
            val asked = biome?.let { here.computeIfAbsent(it) { where -> Skew.of(claims, where) } }
                ?: Skew.of(claims)
            narrowed(offered, asked)
        }
    }

    /**
     * One weighted list with the sentence applied: struck creatures dropped, named ones weighted by the
     * rung they were asked at, and everything unnamed dropped where the writer said `only` or [NOTHING].
     *
     * **A creature the sentence asked for is not added to a list that lacks it.** Where a thing spawns is
     * a fact about the biome — a squid wants water and a strider wants lava — so asking for one in a
     * biome that has no place for it would put it somewhere it cannot live. Naming it strengthens it
     * wherever it already belongs; a biome the Age does not have is a biome the sentence should have asked
     * for (§3.3 charges the word rather than inventing a home for it).
     */
    private fun narrowed(
        offered: WeightedList<MobSpawnSettings.SpawnerData>,
        asked: Skew,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        if (asked.isSilent) return offered
        val struck = asked.struck.mapNotNull(Identifier::tryParse).toSet()
        val weights = asked.wanted.filterNot { it.value == NOTHING }
            .mapNotNull { claim -> Identifier.tryParse(claim.value)?.let { it to claim.density } }
            .toMap()
        val emptied = asked.exclusive || asked.wanted.any { it.value == NOTHING }
        val kept = offered.unwrap().mapNotNull { entry ->
            val id = idOf(entry.value().type())
            val asked = weights[id]
            val leftOutOfAnOnly = emptied && asked == null
            if (id in struck || leftOutOfAnOnly) return@mapNotNull null
            if (asked == null || Rung.isOrdinary(asked)) entry else Weighted(entry.value(), howOften(entry, asked))
        }
        return WeightedList.of(kept)
    }

    /** A weight scaled by the rung, never to nothing: an entry at zero would never be drawn at all. */
    private fun howOften(entry: Weighted<MobSpawnSettings.SpawnerData>, rung: Double): Int =
        (entry.weight() * rung).roundToInt().coerceIn(1, MOST_OFTEN)

    private const val MOST_OFTEN = 1000

    private fun idOf(type: EntityType<*>): Identifier = BuiltInRegistries.ENTITY_TYPE.getKey(type)
}
