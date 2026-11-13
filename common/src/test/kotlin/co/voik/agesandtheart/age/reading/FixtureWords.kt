package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.JoinedPair
import co.voik.agesandtheart.age.words.Kind
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps

/**
 * A small world of words in the new model, written to hold the hard cases the stress tests found: part pages, things,
 * groups and a formation, describing words, a word of two senses, and the structure words. Not the shipped corpus.
 */
object FixtureWords {

    private val CONCEPTS = mapOf(
        "colour" to ValueType.OPTION, "size" to ValueType.SPAN, "amount" to ValueType.AMOUNT, "pace" to ValueType.SPAN,
        "colour_pace" to ValueType.SPAN, "block" to ValueType.BLOCK, "direction" to ValueType.OPTION, "member" to ValueType.MEMBER,
        "lean" to ValueType.WEIGHTS, "template" to ValueType.OPTION, "ban" to ValueType.BLOCK, "absent" to ValueType.FLAG,
        "cratered" to ValueType.FLAG, "porous" to ValueType.FLAG, "rising" to ValueType.FLAG, "storm" to ValueType.FLAG,
        "colours_cycling" to ValueType.FLAG, "bare" to ValueType.FLAG,
    )

    private val CREATURE = """{ "terminal": true, "part": "inhabitants", "accepts": [
        { "concept": "colour", "becomes": [ { "sets": "colour" } ] }, { "concept": "size", "becomes": [ { "sets": "size" } ] } ] }"""

    private val WORDS = mapOf(
        // The structure words.
        "age" to """{ "behaviour": "age", "terminal": true, "accepts": [
            { "concept": "template", "becomes": [ { "template": "$" } ] }, { "concept": "ban", "becomes": [ { "bans": "$" } ] } ] }""",
        "and" to """{ "behaviour": "and" }""", "of" to """{ "behaviour": "of" }""", "in" to """{ "behaviour": "in" }""",
        "everywhere" to """{ "behaviour": "everywhere" }""", "link" to """{ "behaviour": "link" }""",
        "only" to """{ "behaviour": "only" }""", "no" to """{ "behaviour": "no" }""",

        // The parts' own pages.
        "sky" to """{ "terminal": true, "part": "sky", "accepts": [
            { "concept": "colour", "becomes": [ { "sets": "colour" } ] },
            { "concept": "storm", "becomes": [ { "sets": { "weather.frequency": "0.5..1.0" } } ] } ] }""",
        "landmass" to """{ "terminal": true, "part": "landmass", "accepts": [
            { "concept": "member", "becomes": [ { "chooses": "landmass" } ] },
            { "concept": "cratered", "becomes": [ { "chooses": { "landmass": "cratered" } } ] },
            { "concept": "size", "becomes": [ { "sets": "size" } ] } ] }""",
        "features" to """{ "terminal": true, "part": "features", "accepts": [
            { "concept": "member", "becomes": [ { "admits": "features" } ] },
            { "concept": "cratered", "becomes": [ { "admits": { "features": [ "agesandtheart:craters" ] } } ] },
            { "concept": "lean", "becomes": [ { "leans": "features" } ] } ] }""",
        "inhabitants" to """{ "terminal": true, "part": "inhabitants", "accepts": [
            { "concept": "member", "becomes": [ { "admits": "inhabitants" } ] }, { "concept": "amount", "becomes": [ { "abundance": "$" } ] } ] }""",
        "sun" to """{ "terminal": true, "part": "sun", "accepts": [
            { "concept": "colour", "becomes": [ { "sets": "colour" } ] }, { "concept": "pace", "becomes": [ { "sets": "pace" } ] },
            { "concept": "colours_cycling", "becomes": [ { "sets": { "sun.colours_cycling": "true" } } ] },
            { "concept": "colour_pace", "becomes": [ { "sets": "colour_cycle_pace" } ] },
            { "concept": "rising", "becomes": [ { "sets": { "sun.rises": "true" } } ] },
            { "concept": "direction", "becomes": [ { "sets": "rising_from" } ] }, { "concept": "absent", "becomes": [ { "sets": { "sun.absent": "true" } } ] } ] }""",
        "moon" to """{ "terminal": true, "part": "moon", "accepts": [
            { "concept": "colour", "becomes": [ { "sets": "colour" } ] }, { "concept": "absent", "becomes": [ { "sets": { "moon.absent": "true" } } ] } ] }""",
        "sea" to """{ "terminal": true, "part": "sea", "accepts": [ { "concept": "rising", "becomes": [ { "sets": { "sea.tide": "rising" } } ] } ] }""",
        "clouds" to """{ "terminal": true, "part": "clouds", "accepts": [ { "concept": "absent", "becomes": [ { "sets": { "clouds.absent": "true" } } ] } ] }""",

        // Things.
        "trees" to """{ "terminal": true, "part": "features", "means": [ { "restricts": { "features": { "woody": ">0.3" } } } ], "accepts": [
            { "concept": "member", "only": { "woody": ">0.3" }, "becomes": [ { "chooses": "features" } ] },
            { "concept": "colour", "becomes": [ { "sets": "leaves.colour" } ] }, { "concept": "size", "becomes": [ { "sets": "size" } ] },
            { "concept": "lean", "becomes": [ { "leans": "features" } ] } ] }""",
        "plants" to """{ "terminal": true, "part": "features", "means": [ { "restricts": { "features": { "plant": ">0.3" } } } ], "accepts": [
            { "concept": "lean", "becomes": [ { "leans": "features" } ] } ] }""",
        "oak" to """{ "terminal": true, "part": "features", "means": [ { "chooses": { "features": "minecraft:oak" } } ],
            "offers": [ { "concept": "member", "value": "minecraft:oak" } ], "accepts": [
            { "concept": "colour", "becomes": [ { "sets": "leaves.colour" } ] }, { "concept": "bare", "becomes": [ { "sets": { "bare": "true" } } ] } ] }""",
        "birch" to """{ "terminal": true, "part": "features", "means": [ { "chooses": { "features": "minecraft:birch" } } ],
            "offers": [ { "concept": "member", "value": "minecraft:birch" } ] }""",
        "arches" to """{ "terminal": true, "part": "features", "means": [ { "mints": { "pattern": "arch" } } ], "accepts": [
            { "concept": "block", "only": [ "#agesandtheart:whole" ], "becomes": [ { "made_of": "$" } ] } ] }""",
        "villages" to """{ "terminal": true, "part": "structures", "means": [ { "chooses": { "structures": "minecraft:villages" } } ], "accepts": [
            { "concept": "size", "becomes": [ { "sets": "size" } ] },
            { "concept": "block", "only": [ "#agesandtheart:whole" ], "becomes": [ { "sets": "material" } ] } ] }""",
        "jungle" to """{ "terminal": true, "part": "biomes", "means": [ { "chooses": { "biomes": "minecraft:jungle" } } ] }""",
        "zombie" to """{ "kind": "creature", "means": [ { "chooses": { "inhabitants": "minecraft:zombie" } } ],
            "offers": [ { "concept": "member", "value": "minecraft:zombie" } ] }""",
        "sheep" to """{ "kind": "creature", "means": [ { "chooses": { "inhabitants": "minecraft:sheep" } } ],
            "offers": [ { "concept": "member", "value": "minecraft:sheep" } ] }""",
        "warden" to """{ "kind": "creature", "means": [ { "chooses": { "inhabitants": "minecraft:warden" } } ],
            "offers": [ { "concept": "member", "value": "minecraft:warden" } ] }""",
        "herds" to """{ "terminal": true, "part": "inhabitants",
            "means": [ { "restricts": { "inhabitants": { "grazing": ">0.3" } } }, { "abundance": 2.0 } ], "accepts": [
            { "concept": "member", "becomes": [ { "chooses": "inhabitants" } ] }, { "concept": "size", "becomes": [ { "sets": "herd" } ] },
            { "concept": "amount", "becomes": [ { "abundance": "$" } ] } ] }""",
        "fliers" to """{ "terminal": true, "part": "inhabitants", "means": [ { "restricts": { "inhabitants": { "flying": ">0.5" } } } ], "accepts": [
            { "concept": "member", "only": { "flying": ">0.5" }, "becomes": [ { "chooses": "inhabitants" } ] } ] }""",
        "piles" to """{ "terminal": true, "part": "features", "means": [ { "mints": { "pattern": "pile" } } ], "accepts": [
            { "concept": "block", "becomes": [ { "made_of": "$" } ] } ] }""",
        "beds" to """{ "senses": [
            { "terminal": true, "part": "features", "means": [ { "mints": { "pattern": "bed" } } ], "accepts": [
                { "concept": "block", "becomes": [ { "made_of": "$" } ] } ] },
            { "terminal": true, "part": "features", "default": true, "offers": [ { "concept": "block", "value": "minecraft:red_bed" } ],
              "means": [ { "admits": { "features": [ "agesandtheart:scattered_beds" ] } } ],
              "accepts": [ { "concept": "colour", "becomes": [ { "sets": "bed_colour" } ] } ] } ] }""",

        // Describing words.
        "red" to """{ "offers": [ { "concept": "colour", "value": "red" }, { "concept": "block", "value": "#agesandtheart:red" },
            { "concept": "lean", "value": { "#red": 1.0 } } ] }""",
        "blue" to """{ "offers": [ { "concept": "colour", "value": "blue" } ] }""",
        "colossal" to """{ "offers": [ { "concept": "size", "value": "0.8..1.0" } ] }""",
        "teeming" to """{ "offers": [ { "concept": "amount", "value": 4.0 } ] }""",
        "swift" to """{ "offers": [ { "concept": "pace", "value": "0.7..1.0" } ] }""",
        "prismatic" to """{ "offers": [ { "concept": "colours_cycling" } ], "accepts": [ { "concept": "pace", "renames": "colour_pace" } ] }""",
        "north" to """{ "offers": [ { "concept": "direction", "value": "north" } ] }""",
        "rising" to """{ "offers": [ { "concept": "rising" } ], "accepts": [ { "concept": "direction", "folds": true } ] }""",
        "cratered" to """{ "offers": [ { "concept": "cratered" } ] }""",
        "stormy" to """{ "offers": [ { "concept": "storm", "for": "sky" } ] }""",
        "sunless" to """{ "offers": [ { "concept": "absent", "for": "sun" } ] }""",
        "moonless" to """{ "offers": [ { "concept": "absent", "for": "moon" } ] }""",
        "nether" to """{ "offers": [ { "concept": "template", "value": "nether", "for": "age" } ] }""",
        "foreboding" to """{ "offers": [ { "concept": "colour", "value": "dark_red", "for": "moon" }, { "concept": "colour", "value": "grey", "for": "sky" } ] }""",
        "bare" to """{ "offers": [ { "concept": "bare" }, { "concept": "absent", "for": "clouds" } ] }""",
        "beautiful" to """{ "offers": [ { "concept": "lean", "value": { "#beautiful": 1.0 } } ] }""",

        // Blocks: materials.
        "stone" to """{ "offers": [ { "concept": "block", "value": "minecraft:stone" } ] }""",
        "gold_block" to """{ "offers": [ { "concept": "block", "value": "minecraft:gold_block" } ] }""",
        "clay" to """{ "offers": [ { "concept": "block", "value": "minecraft:clay" } ] }""",
        "torch" to """{ "offers": [ { "concept": "block", "value": "minecraft:torch" } ] }""",
        "snow" to """{ "offers": [ { "concept": "block", "value": "minecraft:snow" } ] }""",
        "powder_snow" to """{ "offers": [ { "concept": "block", "value": "minecraft:powder_snow" } ] }""",
    )

    val words: Words by lazy {
        val concepts = CONCEPTS.map { (name, values) -> Concept(name.location(), values, JoinedPair.EITHER) }
        val creature = read(Kind.mapCodec("creature".location()).codec(), CREATURE)
        val words = WORDS.map { (name, json) -> read(Word.mapCodec(name.location()).codec(), json) }
        Words.of(concepts, listOf(creature), words, StructureWords.IDS).also {
            check(it.problems.isEmpty()) { "the fixture words do not load: ${it.problems}" }
        }
    }

    /** What the fixture world knows of its blocks and members. */
    val membership: Membership = object : Membership {
        private val tags = mapOf("#agesandtheart:whole" to setOf("minecraft:stone", "minecraft:gold_block", "minecraft:clay"))
        private val members = mapOf(
            Aspect.FEATURES to setOf("minecraft:oak", "minecraft:birch"),
            Aspect.SPAWNS to setOf("minecraft:zombie", "minecraft:sheep", "minecraft:warden"),
            Aspect.BIOMES to setOf("minecraft:jungle"),
        )
        private val memberTags = mapOf(
            "minecraft:oak" to mapOf("woody" to 1.0), "minecraft:birch" to mapOf("woody" to 1.0),
            "minecraft:sheep" to mapOf("grazing" to 1.0), "minecraft:zombie" to mapOf("undead" to 1.0),
        )

        override fun isIn(value: String, keyOrTag: String): Boolean = value == keyOrTag || value in tags[keyOrTag].orEmpty()

        override fun isMemberOf(part: Aspect, member: String): Boolean = member in members[part].orEmpty()

        override fun clears(member: String, bars: Map<String, Double>): Boolean = Bars.cleared(memberTags[member].orEmpty(), bars)
    }

    private fun <T> read(codec: com.mojang.serialization.Codec<T>, json: String): T =
        codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow { IllegalArgumentException("fixture does not read: $it") }
}
