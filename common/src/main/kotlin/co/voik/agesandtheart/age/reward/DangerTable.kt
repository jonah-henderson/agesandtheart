package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.entity.MobCategory

/**
 * What each thing an Age can be made of, hold or run is worth to [Danger] — one `art/danger.json`.
 *
 * **Content rather than config** (`notes/config-research.md`), on the same argument
 * [co.voik.agesandtheart.age.Price] is: a pack where lava is cheap is a pack where nothing has to be
 * committed to, which is a different mod rather than the same one run differently.
 *
 * **One file, not a directory**, unlike `art/rarity/` and `art/manifestation/`. Every number here is
 * meaningful only against the others — a material rating means nothing except beside the spawn ratings
 * and the weights — so the tuning surface §7.7 asks for is one thing to open, not seven.
 *
 * **Ids, never tags.** A tag binds on a server and this has to be readable offline, which is the same
 * bargain [co.voik.agesandtheart.age.aspect.Materials] already makes for its two exception tags.
 */
data class DangerTable(
    val weights: Weights,
    /** The score at or above which an Age pays at all — §7.7's one threshold, which also fences the ruins. */
    val paysAbove: Double,
    /**
     * How much asked-for hostile life counts as an Age's population being *full* of it — the divisor that
     * turns a sum of claims into a contributor on the same scale as the others.
     *
     * A scale rather than a cap: §7.7 says there is no ceiling, so an Age that asks for more than this
     * scores over one and is meant to.
     */
    val spawnsFull: Double,
    /** The same for what happens here. */
    val phenomenaFull: Double,
    /**
     * And the same for what the ground holds.
     *
     * **Arrived late, with the contributor it divides** (Jonah, 2026-09-11: *"those danger numbers need
     * fixing, I believe they should all be adding up"*). The features contributor was the worst entry
     * rather than the sum, so a volcanic Age with all four of its hazards scored exactly what one of them
     * did.
     *
     * Shipped at 1.0, which is the worst single entry in the table: one volcano *is* a world full of
     * hazardous ground. That also leaves every single-feature score exactly where the maximum left it, so
     * the only Ages the change moved are the ones asking for several.
     */
    val featuresFull: Double,
    /**
     * What a claim confined to one biome (§4.3.1's `in`) is worth against the same claim made of the whole
     * Age — the nearest thing a non-spatial aspect has to a share.
     */
    val confinedWeight: Double,
    /**
     * What a full reach of [co.voik.agesandtheart.age.Manifestation.WOUNDS] adds to the spawn contributor.
     *
     * **Wounds are counted as spawn pressure rather than as a contributor of their own**, because that is
     * what they physically are: `Hostility` makes what comes nastier and makes more of it come, and both
     * registers are the hostile population. §7.7 names four contributors and this is one of them arriving
     * by a second route.
     */
    val woundHostility: Double,
    private val materials: Map<String, Double>,
    private val spawns: Map<String, Double>,
    private val phenomena: Map<String, Double>,
    private val lighting: Map<String, Double>,
    private val features: Map<String, Double>,
) {
    /** What a block named as an Age's rock, sea or surface is worth. Nothing, for almost everything. */
    fun material(named: String): Double = materials[named] ?: NOTHING

    /**
     * What a thing the Age asked to have *placed* in it is worth.
     *
     * The channel that lets a hazard live in the ground rather than in the weather or the population. A
     * volcano is the first: it is not what an Age is made of, not what lives there and not what the sky
     * does, and without a line here the evaluator is blind to the most literal case of a world throwing
     * things at you. Ordinary features score nothing, as ordinary blocks do.
     */
    fun feature(named: String): Double = features[named] ?: NOTHING

    /**
     * What a creature asked for is worth — its own entry, else what any monster is worth, else nothing.
     *
     * **The category is vanilla's own classification and is why this table is short.** Every hostile mob
     * in the game already declares itself one, so only the creatures that are *not* ordinarily dangerous
     * for a monster need a line here.
     */
    fun spawn(named: String): Double {
        spawns[named]?.let { return it }
        return if (isAMonster(named)) spawns[MONSTERS] ?: NOTHING else NOTHING
    }

    /** What a phenomenon is worth — the hazards score, the sights do not. */
    fun phenomenon(key: String): Double = phenomena[key] ?: NOTHING

    /** What being shut overhead is worth, and what merely having nothing shine on you is. */
    val sealed: Double get() = lighting[SEALED] ?: NOTHING
    val lightless: Double get() = lighting[LIGHTLESS] ?: NOTHING

    /**
     * Everything a rating is written for, so a check can hold the file honest.
     *
     * A missing entry is worth nothing and reads exactly like an entry of nought, which is fine at a call
     * site and not fine in a content check: the question worth asking of the shipped file is whether every
     * phenomenon was *considered*, and only the keys can answer it.
     */
    val ratedMaterials: Set<String> get() = materials.keys
    val ratedSpawns: Set<String> get() = spawns.keys
    val ratedPhenomena: Set<String> get() = phenomena.keys

    private fun isAMonster(named: String): Boolean {
        val id = Identifier.tryParse(named) ?: return false
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return false
        return type.category == MobCategory.MONSTER
    }

    /** What share of the score each contributor carries. */
    data class Weights(
        val materials: Double,
        val spawns: Double,
        val phenomena: Double,
        val lighting: Double,
        val features: Double,
    ) {
        companion object {
            val CODEC: Codec<Weights> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("materials").forGetter(Weights::materials),
                    Codec.DOUBLE.fieldOf("spawns").forGetter(Weights::spawns),
                    Codec.DOUBLE.fieldOf("phenomena").forGetter(Weights::phenomena),
                    Codec.DOUBLE.fieldOf("lighting").forGetter(Weights::lighting),
                    Codec.DOUBLE.fieldOf("features").forGetter(Weights::features),
                ).apply(instance, ::Weights)
            }
        }
    }

    companion object {
        /** Where a pack puts it. */
        val FILE: Identifier = "art/danger.json".location()

        private const val MONSTERS = "monsters"
        private const val SEALED = "sealed"
        private const val LIGHTLESS = "lightless"
        private const val NOTHING = 0.0

        /**
         * What a pack shipping no table scores: nothing, anywhere.
         *
         * **Nothing rather than a built-in default**, so a pack that removed the file gets an economy that
         * plainly does not pay rather than one paying by numbers nobody can see. The weights are left
         * meaningful so the arithmetic is still well defined.
         */
        val NONE = DangerTable(
            weights = Weights(0.0, 0.0, 0.0, 0.0, 0.0),
            paysAbove = Double.MAX_VALUE,
            spawnsFull = 1.0,
            phenomenaFull = 1.0,
            featuresFull = 1.0,
            confinedWeight = 0.0,
            woundHostility = 0.0,
            materials = emptyMap(),
            spawns = emptyMap(),
            phenomena = emptyMap(),
            lighting = emptyMap(),
            features = emptyMap(),
        )

        private val RATINGS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

        val CODEC: Codec<DangerTable> = RecordCodecBuilder.create { instance ->
            instance.group(
                Weights.CODEC.fieldOf("weights").forGetter(DangerTable::weights),
                Codec.DOUBLE.fieldOf("pays_above").forGetter(DangerTable::paysAbove),
                Codec.DOUBLE.fieldOf("spawns_full").forGetter(DangerTable::spawnsFull),
                Codec.DOUBLE.fieldOf("phenomena_full").forGetter(DangerTable::phenomenaFull),
                Codec.DOUBLE.fieldOf("features_full").forGetter(DangerTable::featuresFull),
                Codec.DOUBLE.fieldOf("confined_weight").forGetter(DangerTable::confinedWeight),
                Codec.DOUBLE.fieldOf("wound_hostility").forGetter(DangerTable::woundHostility),
                RATINGS.fieldOf("materials").forGetter { it.materials },
                RATINGS.fieldOf("spawns").forGetter { it.spawns },
                RATINGS.fieldOf("phenomena").forGetter { it.phenomena },
                RATINGS.fieldOf("lighting").forGetter { it.lighting },
                RATINGS.fieldOf("features").forGetter { it.features },
            ).apply(instance, ::DangerTable)
        }

        /** The table this server is running, cached on the resource manager exactly as the corpus is. */
        fun of(server: MinecraftServer): DangerTable {
            val resources = server.resourceManager
            loaded?.let { (from, known) -> if (from === resources) return known }
            return load(resources).also { loaded = resources to it }
        }

        @Volatile
        private var loaded: Pair<ResourceManager, DangerTable>? = null

        /**
         * The table in [resources] — the whole of the loading, and usable offline.
         *
         * **The last pack to ship the file wins outright**, where a rarity bucket merges. A rating list is
         * a balance rather than a set of entries: a pack laying a second table over ours means the numbers
         * to use are theirs, and half of each would be a balance nobody chose.
         */
        fun load(resources: ResourceManager): DangerTable {
            val problems = mutableListOf<String>()
            val layers = resources.getResourceStack(FILE)
            val table = layers.lastNotNullOfOrNull { ResourceParsing.parse(it, FILE, CODEC, problems) }
            for (problem in problems) Constants.LOG.error("Danger table: {}", problem)
            if (table == null && layers.isNotEmpty()) {
                Constants.LOG.error("No pack ships a readable {}, so no Age can pay out", FILE)
            }
            return table ?: NONE
        }

        /** The last entry [read] answers for, so the highest-priority pack's table is the one that stands. */
        private fun <T, R> List<T>.lastNotNullOfOrNull(read: (T) -> R?): R? =
            asReversed().firstNotNullOfOrNull(read)
    }
}
