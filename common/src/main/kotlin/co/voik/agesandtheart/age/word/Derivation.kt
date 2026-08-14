package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import com.google.gson.JsonElement
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature

/**
 * How one aspect's members are tagged from what the game already states — `art/derivation/<aspect>.json`.
 *
 * Two rules, and everything is one or the other (`notes/the-tag-layer.md` §4):
 *
 * - **[byTag]** keys on a registry tag the member carries, ours or vanilla's. `#minecraft:logs` is Mojang
 *   maintaining our `wooded` for us, and a tag file of our own may *include* theirs, so retuning what
 *   counts is editing data rather than code.
 * - **[byKind]** keys on a fact stated as a name — the feature a placed feature places, a creature's spawn
 *   category, the band a biome's temperature falls in. Facts rather than tags, so these are the half that
 *   survives offline where no tags are bound.
 *
 * Weights **take the highest claim** rather than summing: two rules both calling a thing `wooded` are two
 * reasons for one fact, not twice as much of it.
 */
data class Derivation(
    val byTag: Map<String, Map<String, Double>> = emptyMap(),
    val byKind: Map<String, Map<String, Double>> = emptyMap(),
) {
    /**
     * What a member carrying [tags] and answering to [kinds] comes out tagged.
     *
     * **A tag arrives with a confidence**, and a rule's weight is scaled by it. On a thing that carries its
     * own tags that is always one — a biome tagged `is_forest` *is* one. It is less where the tag was read
     * off something the member merely *contains*: a fallen birch names one mushroom among four blocks, and
     * scoring that as fully fungal put every tree in the game among the fungi.
     */
    fun profileFor(tags: Map<String, Double>, kinds: Collection<String>): Map<String, Double> {
        val fromTags = tags.mapNotNull { (tag, confidence) ->
            byTag[tag]?.mapValues { (_, weight) -> weight * confidence }
        }
        val claims = fromTags + kinds.mapNotNull(byKind::get)
        if (claims.isEmpty()) return emptyMap()
        return buildMap {
            for (claim in claims) {
                for ((tag, weight) in claim) put(tag, maxOf(getOrDefault(tag, 0.0), weight))
            }
        }
    }

    /** Tags a member carries outright, each believed entirely. */
    fun profileFor(tags: Collection<String>, kinds: Collection<String>): Map<String, Double> =
        profileFor(tags.associateWith { WHOLLY }, kinds)


    companion object {
        /** What a tag read off the member itself is worth: all of it. */
        private const val WHOLLY = 1.0

        val CODEC: Codec<Derivation> = RecordCodecBuilder.create { instance ->
            val weights = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
            instance.group(
                weights.optionalFieldOf("by_tag", emptyMap()).forGetter(Derivation::byTag),
                weights.optionalFieldOf("by_kind", emptyMap()).forGetter(Derivation::byKind),
            ).apply(instance, ::Derivation)
        }
    }
}

/**
 * **The tags the world carries, read off the world itself** — every member of every open population, run
 * through its aspect's [Derivation].
 *
 * What a member is asked for is only ever *what it says about itself*: the tags it carries, and a handful
 * of facts named as kinds. Nothing here decides that a jungle is lush — `art/derivation/biomes.json` does,
 * and it can be edited without a build.
 *
 * **Tags are bound on a server and absent offline**, so a corpus read without one keeps the kind half and
 * loses the tag half. That is a fact about our harness rather than about anyone's world (`Vocabulary.of`
 * loads long after datapacks bind), and it is why the fact-derived rules have to carry the coverage the
 * offline checks are allowed to assert — see `notes/the-tag-layer.md` §4.
 */
object DerivedTags {
    /** Every open aspect's members, tagged — keyed the way `preset_tags` keys them, so the two can merge. */
    fun read(
        registries: HolderLookup.Provider,
        rules: Map<Aspect, Derivation>,
        problems: MutableList<String>,
    ): Map<Aspect, Map<String, Map<String, Double>>> {
        if (rules.isEmpty()) return emptyMap()
        val ops = RegistryOps.create(JsonOps.INSTANCE, registries)
        val seen = mutableSetOf<String>()
        fun watching(tags: Collection<String>): Collection<String> = tags.also(seen::addAll)
        val derived = buildMap {
            rules[Aspect.FEATURES]?.let { put(Aspect.FEATURES, features(registries, it, ops, ::watching)) }
            rules[Aspect.SPAWNS]?.let { put(Aspect.SPAWNS, spawns(it, ::watching)) }
            rules[Aspect.BIOMES]?.let { put(Aspect.BIOMES, biomes(registries, it, ::watching)) }
            rules[Aspect.STRUCTURES]?.let { put(Aspect.STRUCTURES, structures(registries, it, ::watching)) }
            rules[Aspect.SEA]?.let { put(Aspect.SEA, blocks(it, ::watching)) }
        }
        reportUnmatchedTags(rules, seen, problems)
        return derived
    }

    /**
     * **A rule keyed on a tag nothing carries is a tag that was renamed**, and saying nothing about it is
     * how a whole population quietly stops being reachable by description.
     *
     * Reported only where *some* tag matched, which is what tells a running server from an offline corpus
     * without asking: tags are bound in one and absent in the other, so on the corpus that has none this
     * would otherwise report every rule we ship. `VocabularyOnServerCheck` fails the build on any problem,
     * so the guard lands exactly where the answer is knowable.
     */
    private fun reportUnmatchedTags(
        rules: Map<Aspect, Derivation>,
        seen: Set<String>,
        problems: MutableList<String>,
    ) {
        val tagsAreBound = seen.isNotEmpty()
        if (!tagsAreBound) return
        for ((aspect, derivation) in rules) {
            val unmatched = derivation.byTag.keys
                .filterNot { it in seen }
                .filter(::weInsistOn)
                .sorted()
            if (unmatched.isNotEmpty()) {
                problems += "art/derivation/${aspect.key}.json keys on tags nothing carries: " +
                    unmatched.joinToString(" ")
            }
        }
    }

    /**
     * Whether a rule keyed on this tag is *expected* to match — which decides whether its silence is a bug.
     *
     * **Only the two namespaces we can answer for.** Vanilla's tags we build against and ours we ship, so
     * either going missing means one was renamed and a population has quietly stopped being reachable. A
     * tag in any other namespace belongs to content that may simply not be installed: `#c:ores` is what a
     * modded ore carries, and a world with no mods in it is not a world with a broken rule.
     */
    private fun weInsistOn(tag: String): Boolean =
        tag.removePrefix("#").substringBefore(':') in NAMESPACES_WE_ANSWER_FOR

    private val NAMESPACES_WE_ANSWER_FOR = setOf("minecraft", Constants.MOD_ID)

    /**
     * **A placed feature is what it places.** Vanilla tags nothing in `worldgen/placed_feature`, so the two
     * things a feature says about itself are the `Feature` it uses — `tree`, `ore`, `huge_fungus` — and the
     * blocks its configuration names, which *are* richly tagged.
     *
     * Both are needed and they cover each other's gaps: 210 of vanilla's 258 name a block, and the 48 that
     * do not are the hard-coded ones (`monster_room`, `desert_well`, `spike`) whose type is the whole story.
     */
    private fun features(
        registries: HolderLookup.Provider,
        rules: Derivation,
        ops: RegistryOps<JsonElement>,
        watching: (Collection<String>) -> Collection<String>,
    ): Map<String, Map<String, Double>> =
        registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList().associate { holder ->
            // Nested features included: a `random_selector` places whatever it selects between, and what it
            // selects between is the part with blocks in it.
            val leaves = holder.value().getFeatures().toList()
            // **The path, not the whole id, and that is deliberate**: a mod placing its ore with vanilla's
            // `minecraft:ore` matches the same rule, and one that registers a `mymod:ore` of its own
            // matches it too. What a feature *is* does not depend on who wrote it.
            val kinds = leaves.mapNotNull { BuiltInRegistries.FEATURE.getKey(it.value().feature())?.path }
            val blocks = leaves.flatMap { blocksNamedBy(it, ops) }.distinct()
            val share = shareOfBlocksCarrying(blocks)
            watching(share.keys)
            holder.key().identifier().toString() to rules.profileFor(share, kinds.distinct())
        }.filterValues { it.isNotEmpty() }

    /**
     * The blocks a configured feature's own data names, found by **encoding it and reading the ids back**.
     *
     * A `FeatureConfiguration` is thirty-odd unrelated shapes with no accessor in common — an ore holds
     * target states, a tree holds trunk and foliage providers, a spring holds a fluid — so asking each in
     * turn is thirty branches that fall behind the day Mojang adds the thirty-first. The serialised form is
     * the one thing every configuration has, and reading it asks the data what it says rather than
     * asserting what it must contain.
     *
     * A configuration that will not encode contributes nothing rather than failing the corpus: its type
     * still speaks for it.
     */
    private fun blocksNamedBy(feature: Holder<ConfiguredFeature<*, *>>, ops: RegistryOps<JsonElement>): List<Identifier> =
        ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, feature.value())
            .result()
            .map(::blockIdsIn)
            .orElse(emptyList())

    private fun blockIdsIn(json: JsonElement): List<Identifier> = buildList {
        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
                element.isJsonArray -> element.asJsonArray.forEach(::walk)
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    val id = Identifier.tryParse(element.asString) ?: return
                    if (BuiltInRegistries.BLOCK.containsKey(id)) add(id)
                }
            }
        }
        walk(json)
    }

    /**
     * For each tag any of [blocks] carries, **how much of the feature it speaks for** — the share of the
     * named blocks carrying it.
     *
     * A mushroom patch names one block and it is a mushroom, so `fungal` is the whole of it. A fallen
     * birch names a log, leaves and a mushroom, so it is a quarter fungal and mostly wood — which is the
     * truth about it, and what stops a restrictive `fungi` from keeping every tree that ever sprouted one.
     */
    private fun shareOfBlocksCarrying(blocks: List<Identifier>): Map<String, Double> {
        if (blocks.isEmpty()) return emptyMap()
        val carrying = blocks.flatMap(::tagsOnBlock).groupingBy { it }.eachCount()
        return carrying.mapValues { (_, many) -> many.toDouble() / blocks.size }
    }

    private fun tagsOnBlock(id: Identifier): List<String> =
        BuiltInRegistries.BLOCK.get(id).map(::tagsOn).orElse(emptyList())

    /**
     * The tags [holder] carries, or none where nothing has bound any.
     *
     * **`Holder.tags()` throws rather than answering empty** when a registry was stood up without
     * datapacks — the same trap `DerivedWords.struckOutBy` records, one method along. A corpus read
     * offline therefore derives from facts alone, which is the split `notes/the-tag-layer.md` §4 accepts
     * and `VocabularyOnServerCheck` holds the other half of.
     */
    private fun tagsOn(holder: Holder<*>): List<String> = try {
        holder.tags().map { spelled(it.location) }.toList()
    } catch (tagsAreNotBound: IllegalStateException) {
        emptyList()
    }

    /**
     * A creature is its **spawn category** and its tags. The category is the fact that survives offline and
     * is the one that matters most anyway: `MobCategory.MONSTER` is vanilla saying what hunts you.
     */
    private fun spawns(
        rules: Derivation,
        watching: (Collection<String>) -> Collection<String>,
    ): Map<String, Map<String, Double>> =
        BuiltInRegistries.ENTITY_TYPE.listElements().toList()
            // Misc entities are not creatures a world grows, and `DerivedWords.spawns` already refuses to
            // make a word of one — so a vague word must not find one either.
            .filter { it.value().category != MobCategory.MISC }
            .associate { holder ->
            val kinds = listOf(holder.value().category.getName())
            holder.key().identifier().toString() to rules.profileFor(watching(tagsOn(holder)), kinds)
        }.filterValues { it.isNotEmpty() }

    /**
     * A biome is its tags — `is_forest`, `is_ocean` — and the **bands its own climate falls in**, which are
     * the facts an offline corpus still has. A biome that never rains is dry whatever anybody tagged it.
     */
    private fun biomes(
        registries: HolderLookup.Provider,
        rules: Derivation,
        watching: (Collection<String>) -> Collection<String>,
    ): Map<String, Map<String, Double>> =
        registries.lookupOrThrow(Registries.BIOME).listElements().toList().associate { holder ->
            holder.key().identifier().toString() to
                rules.profileFor(watching(tagsOn(holder)), climateBandsOf(holder.value()))
        }.filterValues { it.isNotEmpty() }

    /** The named bands a biome's temperature and rainfall fall in — facts said as kinds. */
    private fun climateBandsOf(biome: Biome): List<String> = buildList {
        val temperature = biome.baseTemperature
        add(
            when {
                temperature <= FREEZING -> "freezing"
                temperature < TEMPERATE -> "cold"
                temperature < WARM -> "temperate"
                temperature < SCALDING -> "warm"
                else -> "scalding"
            },
        )
        add(if (biome.hasPrecipitation()) "rainy" else "rainless")
    }

    private const val FREEZING = 0.0f
    private const val TEMPERATE = 0.5f
    private const val WARM = 0.9f
    private const val SCALDING = 1.5f

    /**
     * A structure set is the structures in it, and **those** are what vanilla tags — `#minecraft:village`,
     * `#mineshaft`, `#ocean_ruin`. The set is our unit because it is what a writer can ask for
     * (`Structures`), so its tags are the union of what it holds.
     */
    private fun structures(
        registries: HolderLookup.Provider,
        rules: Derivation,
        watching: (Collection<String>) -> Collection<String>,
    ): Map<String, Map<String, Double>> =
        registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList().associate { holder ->
            val tags = holder.value().structures().flatMap { entry -> tagsOn(entry.structure()) }
            holder.key().identifier().toString() to
                rules.profileFor(watching(tags.distinct()), emptyList())
        }.filterValues { it.isNotEmpty() }

    /**
     * What a **block** may be as a sea: its tags, and two facts about it — whether it gives light, and
     * whether it pours. Only a block some rule speaks to gets a profile, which is what keeps the sea's
     * candidate pool the handful a writer might mean rather than every block in the game.
     */
    private fun blocks(
        rules: Derivation,
        watching: (Collection<String>) -> Collection<String>,
    ): Map<String, Map<String, Double>> =
        BuiltInRegistries.BLOCK.listElements().toList().associate { holder ->
            val tags = watching(tagsOn(holder))
            val state = holder.value().defaultBlockState()
            val kinds = buildList {
                if (state.lightEmission > 0) add("lit")
                if (state.fluidState.isSource) add("pours")
            }
            holder.key().identifier().toString() to rules.profileFor(tags, kinds)
        }.filterValues { it.isNotEmpty() }

    /** A tag as a rule spells it: `#minecraft:logs`, which is how a datapack writes one. */
    private fun spelled(id: Identifier): String = "#$id"

    /** Which registry each aspect's members live in — for the checks, and for saying what a rule may key on. */
    val REGISTRIES: Map<Aspect, ResourceKey<out net.minecraft.core.Registry<*>>> = mapOf(
        Aspect.FEATURES to Registries.PLACED_FEATURE,
        Aspect.SPAWNS to Registries.ENTITY_TYPE,
        Aspect.BIOMES to Registries.BIOME,
        Aspect.STRUCTURES to Registries.STRUCTURE_SET,
        Aspect.SEA to Registries.BLOCK,
    )
}
