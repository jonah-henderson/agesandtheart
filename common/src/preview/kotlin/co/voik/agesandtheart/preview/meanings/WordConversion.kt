package co.voik.agesandtheart.preview.meanings

import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.aspect.Aspect
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.roundToInt

/**
 * The one-off conversion of the authored words onto meanings (`notes/meanings-plan.md` phase 2): run once at the
 * cutover and deleted with it. It reads the files' JSON rather than the old word, so every `_comment` survives.
 *
 * - A word's own claims must always apply, one meaning per parameter or part of the world.
 * - A pool's offers, and a request's, apply by chance: a pool's at the share of its offers it drew, a request's
 *   setting always where aimed. A group of settings stays one meaning.
 * - `a|b` becomes sibling meanings, alternatives by construction.
 * - `unaimed` becomes a chance unaimed on each meaning whose every bare place it names, the dials only an aimed
 *   word turns left out, since a broad meaning laid bare never reaches them.
 * - A minting asserts, and so do `sunless`, `moonless` and `starless`.
 * - A quantifier in `art/grammar/` becomes a word with one abundance.
 */
object WordConversion {

    private val ASSERTING = setOf("sunless", "moonless", "starless")
    private const val ALTERNATIVE = '|'
    private const val THROUGH = ".."
    private const val EVERYWHERE = "all"
    private const val HUNDREDTHS = 100.0

    /** One meaning on its way to the file: its effects by kind, and how it applies. */
    private data class Draft(
        val effects: JsonObject,
        val mustAlwaysApply: Boolean,
        val chanceToApply: Double = 1.0,
        val unaimedChanceToApply: Double? = null,
        val asserts: Boolean = false,
    )

    /** What the word file [name] holds, rewritten as meanings. */
    fun converted(name: String, old: JsonObject): JsonObject {
        val unaimed = old.getAsJsonObject("unaimed")?.entrySet()?.associate { (page, chance) ->
            requireNotNull(Aspect.byPage(page)) { "$name: unaimed names '$page'" } to chance.asDouble
        }.orEmpty()
        val drafts = ownClaims(name, old) + offers(old.getAsJsonArray("pools")) + requested(old)
        val meanings = JsonArray().apply {
            drafts.map { withUnaimed(it, unaimed) }.forEach { add(written(it)) }
        }
        return JsonObject().apply {
            old.get("_comment")?.let { add("_comment", it) }
            old.get("speech")?.let { add("speech", it) }
            add("meanings", meanings)
        }
    }

    /** A structural word with a rung, rewritten as a word whose one meaning is that abundance; null for any other. */
    fun quantifier(old: JsonObject): JsonObject? {
        val rung = old.get("rung")?.asDouble ?: return null
        return JsonObject().apply {
            old.get("_comment")?.let { add("_comment", it) }
            add("meanings", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("abundance", rung)
                    addProperty("must_always_apply", true)
                })
            })
        }
    }

    private fun ownClaims(name: String, old: JsonObject): List<Draft> = buildList {
        val asserts = name in ASSERTING
        fun own(kind: String, key: String, value: JsonElement) =
            Draft(single(kind, key, value), mustAlwaysApply = true, asserts = asserts)
        for (kind in listOf("sets", "bends")) {
            old.getAsJsonObject(kind)?.entrySet()?.forEach { (key, value) ->
                alternativesOf(value.asString).forEach { add(own(kind, key, JsonPrimitive(it))) }
            }
        }
        for (kind in listOf("chooses", "admits", "excludes", "restricts")) {
            old.getAsJsonObject(kind)?.entrySet()?.forEach { (aspect, value) -> add(own(kind, aspect, value)) }
        }
        old.getAsJsonObject("biases")?.entrySet()?.forEach { (aspect, weights) -> add(own("leans", aspect, weights)) }
        old.get("template")?.let { add(own("template", "", it)) }
        old.get("mints")?.let { pattern ->
            val mints = JsonObject().apply {
                add("pattern", pattern)
                old.get("unstated")?.let { add("unstated", it) }
                if (old.get("mints_something_that_flows")?.asBoolean == true) addProperty("flows", true)
            }
            add(Draft(JsonObject().apply { add("mints", mints) }, mustAlwaysApply = true, asserts = true))
        }
    }

    private fun requested(old: JsonObject): List<Draft> {
        val requests = old.getAsJsonObject("requests") ?: return emptyList()
        val settings = requests.getAsJsonObject("sets")?.entrySet().orEmpty().flatMap { (key, value) ->
            alternativesOf(value.asString).map { Draft(single("sets", key, JsonPrimitive(it)), mustAlwaysApply = false) }
        }
        return settings + offers(requests.getAsJsonArray("pools"))
    }

    /** Every offer of every pool, applying at the share of its pool's offers the pool drew. */
    private fun offers(pools: JsonArray?): List<Draft> = pools.orEmpty().flatMap { element ->
        val pool = element.asJsonObject
        val facets = pool.get("facets")
        val groups: List<JsonObject> = if (facets.isJsonArray) {
            facets.asJsonArray.map { it.asJsonObject }
        } else {
            facets.asJsonObject.entrySet().map { (key, value) -> JsonObject().apply { add(key, value) } }
        }
        val chance = (averageDraws(pool.get("draws").asString) / groups.size).coerceAtMost(1.0).hundredths()
        groups.flatMap(::eachAlternative).map { settings ->
            Draft(JsonObject().apply { add("sets", settings) }, mustAlwaysApply = false, chanceToApply = chance)
        }
    }

    /** [draft] with the chance unaimed its every bare place shares in [unaimed], where it shares one. */
    private fun withUnaimed(draft: Draft, unaimed: Map<Aspect, Double>): Draft {
        val places = barePlacesOf(draft.effects)
        val named = places.mapNotNull(unaimed::get)
        val everyPlaceIsNamed = places.isNotEmpty() && named.size == places.size
        if (!everyPlaceIsNamed) return draft
        val chance = named.max()
        if (named.distinct().size > 1) println("unaimed differs across ${draft.effects}: took $chance")
        return draft.copy(mustAlwaysApply = false, unaimedChanceToApply = chance)
    }

    /** The parts of the world [effects] could land on laid bare, or none where they lean on everything. */
    private fun barePlacesOf(effects: JsonObject): Set<Aspect> = effects.entrySet().flatMap { (kind, value) ->
        when (kind) {
            "sets", "bends" -> value.asJsonObject.keySet().flatMap(::barePlacesOfParameter)
            "chooses", "admits", "excludes", "restricts", "leans" -> value.asJsonObject.keySet().mapNotNull(Aspect::byPage)
            "mints" -> listOf(Aspect.FEATURES)
            else -> emptyList()
        }
    }.toSet()

    private fun barePlacesOfParameter(spelled: String): List<Aspect> {
        val key = ParameterKey.read(spelled)
        fun turnedOnlyWhenAimed(aspect: Aspect) =
            key.fencedTo == null && aspect.understood.any { it.name == key.name && it.turnsOnlyWhenAimed }
        return Aspect.entries.filter { key.landsOn(it) && !turnedOnlyWhenAimed(it) }
    }

    private fun written(draft: Draft): JsonObject = JsonObject().apply {
        draft.effects.entrySet().forEach { (kind, value) -> add(kind, value) }
        if (draft.mustAlwaysApply) addProperty("must_always_apply", true)
        if (!draft.mustAlwaysApply && draft.chanceToApply != 1.0) addProperty("chance_to_apply", draft.chanceToApply)
        draft.unaimedChanceToApply?.let { addProperty("unaimed_chance_to_apply", it) }
        if (draft.asserts) addProperty("asserts", true)
    }

    private fun single(kind: String, key: String, value: JsonElement): JsonObject = JsonObject().apply {
        if (kind == "template") add(kind, value) else add(kind, JsonObject().apply { add(key, value) })
    }

    private fun alternativesOf(value: String): List<String> =
        value.split(ALTERNATIVE).map(String::trim).filter(String::isNotEmpty)

    /** A group of settings once for each choice among its alternatives — `white|blue` is two groups. */
    private fun eachAlternative(group: JsonObject): List<JsonObject> =
        group.entrySet().fold(listOf(JsonObject())) { built, (key, value) ->
            built.flatMap { partial ->
                alternativesOf(value.asString).map { alternative ->
                    partial.deepCopy().apply { addProperty(key, alternative) }
                }
            }
        }

    private fun averageDraws(spelled: String): Double {
        val counts = spelled.split(ALTERNATIVE).flatMap { one ->
            if (THROUGH in one) (one.substringBefore(THROUGH).trim().toInt()..one.substringAfter(THROUGH).trim().toInt()).toList()
            else listOf(one.trim().toInt())
        }
        return counts.average()
    }

    private fun Double.hundredths(): Double = (this * HUNDREDTHS).roundToInt() / HUNDREDTHS

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

    /** Rewrites `art/word/` and the quantifiers in `art/grammar/` under [art], in place. */
    @JvmStatic
    fun main(args: Array<String>) {
        val art = Path.of(requireNotNull(args.firstOrNull()) { "name the art directory" })
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        for (file in art.resolve("word").listDirectoryEntries("*.json")) {
            val old = JsonParser.parseString(file.readText()).asJsonObject
            file.writeText(gson.toJson(converted(file.nameWithoutExtension, old)) + "\n")
        }
        for (file in art.resolve("grammar").listDirectoryEntries().filter { it.extension == "json" }) {
            val word = quantifier(JsonParser.parseString(file.readText()).asJsonObject) ?: continue
            art.resolve("word").resolve(file.fileName).writeText(gson.toJson(word) + "\n")
            Files.delete(file)
        }
    }
}
