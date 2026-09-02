package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Claims
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Facets
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import net.minecraft.resources.Identifier

/**
 * A word being written — **the file's own fields, not the loaded word's**.
 *
 * The difference is the whole reason this type exists. [Word.aspects] is widened by [Word.reaching] from
 * everything the word claims, so a word that declared nothing comes back declaring eight aspects; writing
 * that back would pin into the file what the word derives for itself, and the next parameter it gained would
 * not reach. So the fields here are what the JSON says, and [asWord] is how they become a word.
 *
 * Immutable, and edited by copying. That gives the editor its undo for nothing: a stack of these is the
 * history, and going back is dropping one.
 */
data class Candidate(
    val name: String,
    val tier: Tier,
    /**
     * `_comment`, exactly as the file carried it — a string, an array of lines, or absent.
     *
     * Kept as the element rather than as text because the codec drops it and this tool is the only thing
     * that will ever put it back. Forty-two of the corpus's files carry one and they hold the reasoning,
     * which this project treats as the part worth keeping.
     */
    val comment: JsonElement? = null,
    /** The one member chosen outright in each part of the world — step one. See [Word.chooses]. */
    val chooses: Map<Aspect, String> = emptyMap(),
    /** Members put into the pool by name — step two. See [Word.admits]. */
    val admits: Map<Aspect, Set<String>> = emptyMap(),
    /** Members taken out, by key or `#tag` — step three. See [Word.excludes]. */
    val excludes: Map<Aspect, Set<String>> = emptyMap(),
    /** The tags the pool is narrowed to — step three's other half. See [Word.restricts]. */
    val restricts: Map<Aspect, Map<String, Double>> = emptyMap(),
    /** What the draw is leaned toward or away from, by key or `#tag` — step four. See [Word.biases]. */
    val biases: Map<Aspect, Map<String, Double>> = emptyMap(),
    /** The same, leaned on every part of the world at once, and only ever on a word that does not narrow. */
    val leansEverywhere: Map<String, Double> = emptyMap(),
    val sets: Map<String, String> = emptyMap(),
    /** Groups of facets an Age takes some of — see [Facets], and [Draws] for how many. */
    val pools: List<Facets> = emptyList(),
    /**
     * The requested half — what the word **offers** rather than insists on, laid under the sentence.
     *
     * The same record as the required side, which is why it is `Claims` rather than three more fields:
     * strength and certainty are separate questions, so either strength may be a plain claim or a pool.
     */
    val requests: Claims = Claims.NOTHING,
    val weights: Map<Aspect, Map<String, Double>> = emptyMap(),
    val template: String? = null,
    val mints: String? = null,
    val mintsSomethingThatFlows: Boolean = false,
    /**
     * The registry entry this word was read off, where it was read off one at all.
     *
     * A **derived** word has no file: it exists because the game has a block, a biome or a structure set
     * of that name. There is nothing about it to edit except how hard it is to find and what it costs to
     * write, and both of those are stored away from the word.
     */
    val derivedFrom: Identifier? = null,
    /**
     * Whether this word's ink is a **tag on the thing itself** rather than a name in `art/ink/`.
     *
     * True of a word that *is* a registry entry, which is what lets another mod's ore be worth the good
     * ink without anybody editing our files. **Not true of every derived word**: a landform's page is
     * minted from the landform (`AuthoredPreset.writtenWordFor`) and has no id in any registry to hang a
     * tag on, so it is listed by name exactly as an authored word is.
     */
    val inkedByTag: Boolean = false,
) {

    val id: Identifier get() = derivedFrom ?: Identifier.fromNamespaceAndPath(NAMESPACE, name)

    /** Whether this came from the game rather than from a file — see [derivedFrom]. */
    val isDerived: Boolean get() = derivedFrom != null

    /**
     * What the rarity and ink lists call this word.
     *
     * **The full id for an auto-generated word**, because that is what reaches it: the bare path is
     * ambiguous across registries and across mods, and `WordRarity` has to be able to take the word out
     * of the anonymous mass by the same string it was listed under.
     */
    val listingKey: String get() = derivedFrom?.toString() ?: name

    /** The comment as lines a person edits, however the file spelled it. */
    val commentLines: List<String>
        get() = when {
            comment == null -> emptyList()
            comment.isJsonArray -> comment.asJsonArray.map { it.asString }
            else -> listOf(comment.asString)
        }

    /** This word with [lines] as its reasoning — one line staying a string, as the corpus spells it. */
    fun commenting(lines: List<String>): Candidate {
        val kept = lines.dropLastWhile(String::isBlank)
        return copy(
            comment = when {
                kept.isEmpty() -> null
                kept.size == 1 -> JsonPrimitive(kept.single())
                else -> JsonArray().apply { kept.forEach(::add) }
            },
        )
    }

    /**
     * What the pack would write for this word.
     *
     * **Laid out field by field rather than encoded through the codec**, for the reason in the class
     * doc — and because the codec would drop `_comment` on the way past. [KNOWN_FIELDS] is the guard: it
     * is asserted against what the codec actually reads, so a field added to `Word` fails a check here
     * rather than going quietly missing from every word this tool ever writes.
     */
    fun asJson(): JsonObject = JsonObject().apply {
        comment?.let { add(COMMENT, it) }
        addProperty("tier", tier.key)
        // In pipeline order, which is the order they are read in and the order the screen shows them.
        if (chooses.isNotEmpty()) add("chooses", aspectTexts(chooses))
        if (admits.isNotEmpty()) add("admits", aspectLists(admits))
        if (excludes.isNotEmpty()) add("excludes", aspectLists(excludes))
        if (restricts.isNotEmpty()) add("restricts", perAspect(restricts))
        // One field, keyed by aspect page or by `all` — the whole of what a word leans by.
        val leaning = biases.entries.sortedBy { it.key.ordinal }
            .associate { (aspect, by) -> aspect.page to by } +
            (if (leansEverywhere.isEmpty()) emptyMap() else mapOf(Word.EVERYWHERE to leansEverywhere))
        if (leaning.isNotEmpty()) {
            add("biases", JsonObject().apply { leaning.forEach { (key, by) -> add(key, numbers(by)) } })
        }
        if (sets.isNotEmpty()) add("sets", texts(sets))
        if (pools.isNotEmpty()) add("pools", poolsOf(pools))
        if (!requests.isEmpty) add("requests", claimsOf(requests))
        template?.let { addProperty("template", it) }
        mints?.let { addProperty("mints", it) }
        if (mintsSomethingThatFlows) addProperty("mints_something_that_flows", true)
    }

    /**
     * The word the game would load from [asJson] — or what it would say instead.
     *
     * Through the real codec rather than through `Word`'s constructor, so what the tool previews is
     * provably the word the pack will hold: the reach is derived exactly as it will be, and a value the
     * codec refuses is refused here too.
     */
    fun asWord(): Result<Word> {
        val parsed = Word.mapCodec(id).codec().parse(JsonOps.INSTANCE, asJson())
        val word = parsed.result().orElse(null)
        return if (word != null) {
            Result.success(word)
        } else {
            Result.failure(IllegalArgumentException(parsed.error().map { it.message() }.orElse("unreadable")))
        }
    }

    private fun claimsOf(claims: Claims) = JsonObject().apply {
        if (claims.sets.isNotEmpty()) add("sets", texts(claims.sets))
        if (claims.pools.isNotEmpty()) add("pools", poolsOf(claims.pools))
    }

    /** A pool says how much of itself it is before it says what is in it — the count is the shorter half. */
    private fun poolsOf(pools: List<Facets>) = JsonArray().apply {
        pools.forEach { pool ->
            add(
                JsonObject().apply {
                    addProperty("draws", pool.draws.spelled)
                    add("facets", texts(pool.facets))
                },
            )
        }
    }

    private fun numbers(weights: Map<String, Double>) =
        JsonObject().apply { weights.forEach { (tag, weight) -> addProperty(tag, weight) } }

    private fun texts(parameters: Map<String, String>) =
        JsonObject().apply { parameters.forEach { (parameter, value) -> addProperty(parameter, value) } }

    private fun aspectLists(byAspect: Map<Aspect, Set<String>>) = JsonObject().apply {
        byAspect.entries.sortedBy { it.key.ordinal }.forEach { (aspect, keys) ->
            add(aspect.page, JsonArray().apply { keys.forEach(::add) })
        }
    }

    private fun aspectTexts(byAspect: Map<Aspect, String>) = JsonObject().apply {
        byAspect.entries.sortedBy { it.key.ordinal }.forEach { (aspect, key) -> addProperty(aspect.page, key) }
    }

    private fun perAspect(byAspect: Map<Aspect, Map<String, Double>>) = JsonObject().apply {
        byAspect.entries.sortedBy { it.key.ordinal }.forEach { (aspect, weights) ->
            add(aspect.page, numbers(weights))
        }
    }

    companion object {
        const val NAMESPACE = "agesandtheart"
        const val COMMENT = "_comment"

        /**
         * Every field [asJson] knows how to write, `_comment` aside.
         *
         * `AuthoringCheck` holds this against what `Word.mapCodec` actually reads. A field added to the
         * codec and not to that set would be silently dropped from every word written through this tool,
         * which is the failure `GrammarSources` guards the same way and for the same reason.
         */
        val KNOWN_FIELDS = setOf(
            "tier", "chooses", "admits", "excludes", "restricts", "biases", "sets", "pools", "requests",
            "template", "mints", "mints_something_that_flows",
        )

        /** A word the game gave us, opened so its rarity and ink can be set. */
        fun of(word: Word) = Candidate(
            name = word.name,
            tier = word.tier,
            chooses = word.chooses,
            admits = word.admits,
            excludes = word.excludes,
            restricts = word.restricts,
            biases = word.biases,
            leansEverywhere = word.leansEverywhere,
            sets = word.sets,
            pools = word.pools,
            requests = word.requests,
            template = word.template,
            mints = word.mints,
            mintsSomethingThatFlows = word.mintsSomethingThatFlows,
            derivedFrom = word.id,
            inkedByTag = word.entryOf != null,
        )

        /** A blank word, which is what `--new` starts from. */
        fun blank(name: String) = Candidate(name = name, tier = Tier.EXACT)

        /**
         * The candidate [json] describes — **read off the file rather than off a loaded [Word]**, so what
         * the codec drops survives the trip: the reasoning, and the layout the fields were written in.
         */
        fun read(name: String, json: JsonObject): Result<Candidate> = runCatching {
            val unknown = json.keySet() - KNOWN_FIELDS - COMMENT
            require(unknown.isEmpty()) { "'$name' carries fields nothing reads: ${unknown.joinToString()}" }
            Candidate(
                name = name,
                tier = tierNamed(json.get("tier")?.asString),
                comment = json.get(COMMENT),
                chooses = json.getAsJsonObject("chooses")?.let(::readAspectTexts).orEmpty(),
                admits = json.getAsJsonObject("admits")?.let(::readAspectLists).orEmpty(),
                excludes = json.getAsJsonObject("excludes")?.let(::readAspectLists).orEmpty(),
                restricts = json.getAsJsonObject("restricts")?.let(::readPerAspect).orEmpty(),
                leansEverywhere = json.getAsJsonObject("biases")
                    ?.getAsJsonObject(Word.EVERYWHERE)?.let(::readNumbers).orEmpty(),
                biases = json.getAsJsonObject("biases")?.let(::readPerAspect).orEmpty(),
                sets = json.getAsJsonObject("sets")?.let(::readTexts).orEmpty(),
                pools = json.getAsJsonArray("pools")?.let(::readPools).orEmpty(),
                requests = json.getAsJsonObject("requests")?.let(::readClaims) ?: Claims.NOTHING,
                template = json.get("template")?.asString,
                mints = json.get("mints")?.asString,
                mintsSomethingThatFlows = json.get("mints_something_that_flows")?.asBoolean ?: false,
            )
        }

        private fun tierNamed(spelled: String?): Tier {
            requireNotNull(spelled) { "a word must say its tier" }
            return Tier.entries.firstOrNull { it.key == spelled }
                ?: error("no tier is called '$spelled'")
        }

        private fun aspectPaged(page: String): Aspect =
            Aspect.entries.firstOrNull { it.page == page }
                ?: error("no part of the world is called '$page'")

        private fun readClaims(json: JsonObject): Claims {
            val unknown = json.keySet() - setOf("sets", "pools")
            require(unknown.isEmpty()) { "'requests' carries fields nothing reads: ${unknown.joinToString()}" }
            return Claims(
                sets = json.getAsJsonObject("sets")?.let(::readTexts).orEmpty(),
                pools = json.getAsJsonArray("pools")?.let(::readPools).orEmpty(),
            )
        }

        private fun readNumbers(json: JsonObject) =
            json.entrySet().associate { (key, value) -> key to value.asDouble }

        private fun readPools(json: JsonArray): List<Facets> = json.map { entry ->
            val pool = entry.asJsonObject
            val spelled = pool.get("draws")?.asString ?: error("a pool must say how many of itself it draws")
            requireNotNull(Draws.read(spelled)) { "'$spelled' is no count" }
            Facets(pool.getAsJsonObject("facets")?.let(::readTexts).orEmpty(), Draws(spelled))
        }

        private fun readTexts(json: JsonObject) =
            json.entrySet().associate { (key, value) -> key to value.asString }

        private fun readAspectLists(json: JsonObject) =
            json.entrySet().associate { (page, keys) ->
                aspectPaged(page) to keys.asJsonArray.map { it.asString }.toSet()
            }

        private fun readAspectTexts(json: JsonObject) =
            json.entrySet().associate { (page, key) -> aspectPaged(page) to key.asString }

        // `all` is not an aspect — it is read separately, into `everywhere`.
        private fun readPerAspect(json: JsonObject) =
            json.entrySet().filterNot { (page, _) -> page == Word.EVERYWHERE }
                .associate { (page, weights) -> aspectPaged(page) to readNumbers(weights.asJsonObject) }
    }
}
