package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** The word model as files hold it: senses, offers, acceptances and kinds read, written back, and checked together. */
@Tags(NEEDS_REGISTRIES)
class WordModelCheck : FunSpec({

    fun <T> read(codec: Codec<T>, json: String): T =
        codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow { IllegalArgumentException("'$json' does not read: $it") }

    fun <T> refuses(codec: Codec<T>, json: String): Boolean = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).isError

    fun <T> roundTrips(codec: Codec<T>, value: T): Boolean {
        val written = codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow { IllegalStateException("could not write $value: $it") }
        return codec.parse(JsonOps.INSTANCE, written).result().orElse(null) == value
    }

    fun word(name: String, json: String): Word = read(Word.mapCodec(name.location()).codec(), json)

    val colour = Concept("colour".location(), ValueType.OPTION, JoinedPair.BLEND)
    val size = Concept("size".location(), ValueType.SPAN, JoinedPair.EITHER)
    val member = Concept("member".location(), ValueType.MEMBER, JoinedPair.DIVIDE)
    val concepts = listOf(colour, size, member)

    test("a word of one sense writes it at the top of its file, and a word of several lists them") {
        val red = word("red", """{ "speech": "adjective", "offers": [ { "concept": "colour", "value": "red" } ] }""")
        check(red.senses.size == 1) { "red read as ${red.senses.size} senses" }
        check(red.senses.single().offers.single() == Offer("colour".location(), Value.Text("red"))) { "red offers ${red.senses.single().offers}" }
        val beds = word(
            "beds",
            """{ "senses": [ { "terminal": true, "part": "features" }, { "terminal": true, "part": "features", "default": true } ] }""",
        )
        check(beds.senses.size == 2 && beds.defaultSense == beds.senses[1]) { "beds read as ${beds.senses}" }
        check(roundTrips(Word.mapCodec("red".location()).codec(), red)) { "red does not write back as it was read" }
        check(roundTrips(Word.mapCodec("beds".location()).codec(), beds)) { "beds does not write back as it was read" }
        check(refuses(Word.mapCodec("broken".location()).codec(), """{ "senses": [ { "means": [ { "chooses": { "nowhere": "x" } } ] } ] }""")) {
            "a list of senses that does not read was read as a word of one empty sense"
        }
    }

    test("an offer names who may take it: anyone, the Age, or one part") {
        val forTheMoon = read(Offer.CODEC, """{ "concept": "colour", "value": "dark_red", "for": "moon", "chance": 0.6 }""")
        check(forTheMoon.audience == Audience.Part(Aspect.MOON) && forTheMoon.chance == 0.6) { "read as $forTheMoon" }
        check(read(Offer.CODEC, """{ "concept": "template", "value": "nether", "for": "age" }""").audience == Audience.TheAge)
        check(read(Offer.CODEC, """{ "concept": "porous" }""").value == Value.None) { "a flag concept's offer carries a value" }
        check(forTheMoon.chanceFromWholeAge == 0.6) { "an offer's chance from the whole Age is not its own chance by default" }
        val incidental = read(Offer.CODEC, """{ "concept": "block", "value": "#red", "chance_from_whole_age": 0.1 }""")
        check(incidental.chance == 1.0 && incidental.chanceFromWholeAge == 0.1 && roundTrips(Offer.CODEC, incidental)) { "read as $incidental" }
        check(refuses(Offer.CODEC, """{ "concept": "colour", "value": "red", "chance": 1.5 }""")) { "a chance above one was read" }
        val reading = read(Offer.CODEC, """{ "for": "underground", "means": [ { "admits": { "features": [ "agesandtheart:magma_chamber" ] } } ] }""")
        check(reading.isAReading && reading.audience == Audience.Part(Aspect.UNDERGROUND) && roundTrips(Offer.CODEC, reading)) { "read as $reading" }
        check(refuses(Offer.CODEC, """{ "means": [ { "sets": { "colour": "red" } } ] }""")) { "a reading for no part was read" }
        check(refuses(Offer.CODEC, """{ "concept": "colour", "for": "sky", "means": [ { "sets": { "colour": "red" } } ] }""")) { "an offer of a concept and a reading was read" }
        check(refuses(Offer.CODEC, """{ "for": "sky" }""")) { "an offer of nothing was read" }
        check(refuses(Offer.CODEC, """{ "concept": "colour", "value": "red", "for": "nowhere" }""")) { "an audience that is no part was read" }
    }

    test("an acceptance has exactly one outcome") {
        val folds = read(Acceptance.CODEC, """{ "concept": "direction", "folds": true }""")
        check(folds.outcome == Outcome.Folds) { "read as $folds" }
        check(refuses(Acceptance.CODEC, """{ "concept": "colour" }""")) { "an acceptance with no outcome was read" }
        check(refuses(Acceptance.CODEC, """{ "concept": "colour", "folds": true, "drops": true }""")) { "an acceptance with two outcomes was read" }
        val group = read(Acceptance.CODEC, """{ "concept": "member", "only": { "woody": ">0.3" }, "becomes": [ { "chooses": "features" } ] }""")
        check(group.only == ValueFilter.Clearing(mapOf("woody" to 0.3))) { "a group's filter read as ${group.only}" }
        val whole = read(Acceptance.CODEC, """{ "concept": "block", "only": [ "#agesandtheart:whole_blocks" ], "becomes": [ { "made_of": "$" } ] }""")
        check(whole.only == ValueFilter.OneOf(setOf("#agesandtheart:whole_blocks"))) { "a block filter read as ${whole.only}" }
        check(roundTrips(Acceptance.CODEC, group) && roundTrips(Acceptance.CODEC, whole)) { "an acceptance does not write back as it was read" }
        check(group.chanceFromWholeAge == 1.0 && group.canResolveDanglingOffers) { "an acceptance refused the Age or what dangles unasked" }
        val closed = read(
            Acceptance.CODEC,
            """{ "concept": "pull", "becomes": [ { "sets": "pull" } ], "chance_from_whole_age": 0.0, "can_resolve_dangling_offers": false }""",
        )
        check(closed.chanceFromWholeAge == 0.0 && !closed.canResolveDanglingOffers && roundTrips(Acceptance.CODEC, closed)) { "read as $closed" }
    }

    test("a taking effect puts the value in, a bare parameter landing on the accepting sense's part") {
        val translation = read(EffectTemplate.LIST_CODEC, """[ { "sets": "colour" }, { "sets": "surface.bare" }, { "leans": "all" }, { "abundance": "$" } ]""")
        val red = Value.Text("red")
        check(translation[0].applied(red, Aspect.SKY) == Effect.Sets(ParameterKey("colour", Aspect.SKY), "red")) {
            "a bare colour said of the sky became ${translation[0].applied(red, Aspect.SKY)}"
        }
        check(translation[1].applied(Value.Text("true"), Aspect.CARVERS) == Effect.Sets(ParameterKey("bare", Aspect.SURFACE), "true")) {
            "a fenced parameter left its fence"
        }
        check(translation[2].applied(Value.Weights(mapOf("#bright" to 0.5)), Aspect.SKY) == Effect.LeansEverywhere(mapOf("#bright" to 0.5)))
        check(translation[3].applied(Value.Amount(4.0), null) == Effect.Abundance(4.0))
        check(translation[0].applied(Value.Amount(4.0), Aspect.SKY) == null) { "a colour was set from an amount" }
        check(refuses(EffectTemplate.LIST_CODEC, """[ { "chooses": "nowhere" } ]""")) { "a choice of no part was read" }
        check(refuses(EffectTemplate.LIST_CODEC, """[ { "abundance": "lots" } ]""")) { "an amount that is neither a number nor the value was read" }
    }

    test("what a sense means on its own is fixed, and is written back as it was read") {
        val villages = word("villages", """{ "terminal": true, "means": [ { "chooses": { "structures": "minecraft:villages" } } ] }""")
        check(villages.senses.single().means == listOf(Effect.Chooses(Aspect.STRUCTURES, "minecraft:villages")))
        check(roundTrips(Word.mapCodec("villages".location()).codec(), villages)) { "villages does not write back as it was read" }
        check(refuses(Word.mapCodec("taking".location()).codec(), """{ "means": [ { "sets": "colour" } ] }""")) {
            "a sense's own meaning was let take a value"
        }
    }

    test("a sense takes its kind's acceptances, part and ending, its own winning where both say") {
        val creature = Kind(
            "creature".location(),
            accepts = listOf(
                Acceptance("colour".location(), outcome = Outcome.Becomes(listOf(EffectTemplate.Taking(EffectTemplate.Verb.SETS, "colour")))),
                Acceptance("size".location(), outcome = Outcome.Drops),
            ),
            terminal = true,
            part = Aspect.SPAWNS,
        )
        val ownSize = Acceptance("size".location(), outcome = Outcome.Becomes(listOf(EffectTemplate.Taking(EffectTemplate.Verb.SETS, "herd"))))
        val herds = Sense(accepts = listOf(ownSize), kind = creature.id).withKind(creature)
        check(herds.endsAPhrase && herds.speaksFor == Aspect.SPAWNS) { "herds did not take its kind's ending or part" }
        check(herds.acceptanceOf("size".location()) == ownSize) { "the kind's size won over herds' own" }
        check(herds.acceptanceOf("colour".location()) != null) { "herds did not take its kind's colour" }
        check(Sense(terminal = false).withKind(creature).endsAPhrase.not()) { "a sense saying it ends no phrase took its kind's word" }
    }

    test("a load reports what does not fit, and gives each sense its kind") {
        val creature = Kind("creature".location(), terminal = true, part = Aspect.SPAWNS)
        val words = listOf(
            word("zombie", """{ "kind": "creature", "means": [ { "chooses": { "inhabitants": "minecraft:zombie" } } ] }"""),
            word("odd", """{ "offers": [ { "concept": "smell", "value": "sweet" } ] }"""),
            word("wrong", """{ "offers": [ { "concept": "size", "value": "huge" } ] }"""),
            word("lost", """{ "kind": "machine" }"""),
            word("joins", """{ "behaviour": "and" }"""),
            word("twice", """{ "senses": [ { "default": true }, { "default": true } ] }"""),
        )
        val loaded = Words.of(concepts, listOf(creature), words, behaviours = emptySet())
        fun reported(fragment: String) = loaded.problems.any { fragment in it }
        check(loaded.word("zombie")?.senses?.single()?.endsAPhrase == true) { "zombie was not given its kind" }
        check(reported("'smell'")) { "an unknown concept went unreported: ${loaded.problems}" }
        check(reported("wrong kind")) { "a size of 'huge' went unreported: ${loaded.problems}" }
        check(reported("'machine'")) { "an unknown kind went unreported: ${loaded.problems}" }
        check(reported("runs as 'and'")) { "an unknown behaviour went unreported: ${loaded.problems}" }
        check(reported("more than one sense as its default")) { "two defaults went unreported: ${loaded.problems}" }
        check(loaded.problems.size == 5) { "expected five problems, found ${loaded.problems}" }
    }

    test("a thing turns what it takes into effects, a describing word passes it on, and a flag's translation is fixed") {
        val porous = Concept("porous".location(), ValueType.FLAG, JoinedPair.EITHER)
        val words = listOf(
            word("describing", """{ "accepts": [ { "concept": "colour", "becomes": [ { "sets": "colour" } ] } ] }"""),
            word("thing", """{ "terminal": true, "accepts": [ { "concept": "colour", "folds": true } ] }"""),
            word("taking", """{ "terminal": true, "accepts": [ { "concept": "porous", "becomes": [ { "sets": "porosity" } ] } ] }"""),
            word("fixed", """{ "terminal": true, "accepts": [ { "concept": "porous", "becomes": [ { "sets": { "porosity": "0.5..1.0" } } ] } ] }"""),
        )
        val problems = Words.of(concepts + porous, emptyList(), words, behaviours = emptySet()).problems
        check(problems.any { "describing" in it && "only a thing" in it }) { "a describing word turning a colour into effects went unreported: $problems" }
        check(problems.any { "thing" in it && "ends the phrase" in it }) { "a thing folding a colour went unreported: $problems" }
        check(problems.any { "taking" in it && "carries none" in it }) { "a flag's taking translation went unreported: $problems" }
        check(problems.none { "agesandtheart:fixed" in it }) { "a flag's fixed translation was reported: $problems" }
    }

    test("a word costs its dearest sense: the concepts it offers for certain, and the parts its own meaning narrows") {
        val priced = listOf(
            Concept("colour".location(), ValueType.OPTION, JoinedPair.BLEND, price = 4),
            Concept("lean".location(), ValueType.WEIGHTS, JoinedPair.BLEND, price = 0),
        )
        val words = listOf(
            word("red", """{ "offers": [ { "concept": "colour", "value": "red" }, { "concept": "lean", "value": { "#red": 1.0 } } ] }"""),
            word("gloomy", """{ "offers": [ { "concept": "colour", "value": "grey", "chance": 0.5 } ] }"""),
            word("villages", """{ "terminal": true, "part": "structures", "means": [ { "chooses": { "structures": "minecraft:villages" } } ] }"""),
            word("beds", """{ "senses": [ { "offers": [ { "concept": "colour", "value": "red" } ] },
                { "default": true, "means": [ { "chooses": { "features": "minecraft:oak" } }, { "chooses": { "inhabitants": "minecraft:cow" } } ] } ] }"""),
            word("and", """{ "price": 2 }"""),
        )
        val loaded = Words.of(concepts + priced, emptyList(), words, behaviours = emptySet())
        fun price(name: String) = loaded.priceOf(loaded.word(name) ?: error("no $name"))
        check(price("red") == 4) { "red costs ${price("red")}" }
        check(price("gloomy") == Price.FLOOR_PRICE) { "a colour only likely to land cost ${price("gloomy")}" }
        check(price("villages") == Price.INK_PER_NARROWED_PART) { "villages costs ${price("villages")}" }
        check(price("beds") == 2 * Price.INK_PER_NARROWED_PART) { "beds costs ${price("beds")}, not its dearest sense" }
        check(price("and") == 2) { "a stated price was not kept" }
    }

    test("a concept made of parts takes its value a part at a time, fills the rest, and refuses a part it has not") {
        val wear = Concept(
            "wear".location(), ValueType.PARTS, JoinedPair.EITHER,
            parts = mapOf("way" to ConceptPart(ValueType.OPTION, "eroded", listOf("eroded", "porous")), "amount" to ConceptPart(ValueType.SPAN, "0.2..0.5")),
        )
        val porous = read(Offer.CODEC, """{ "concept": "wear", "value": { "way": "porous" } }""")
        check(porous.value == Value.Parts(mapOf("way" to "porous")) && roundTrips(Offer.CODEC, porous)) { "read as $porous" }
        check(read(Offer.CODEC, """{ "concept": "bar", "value": { "woody": ">0.3" } }""").value is Value.Bars) { "a bar was read as parts" }
        val filled = wear.filled(porous.value as Value.Parts)
        check(filled.parts == mapOf("way" to "porous", "amount" to "0.2..0.5") && filled.measure == "0.2..0.5") { "filled as $filled" }
        val words = listOf(
            word("melted", """{ "offers": [ { "concept": "wear", "value": { "way": "melted" } } ] }"""),
            word("shiny", """{ "offers": [ { "concept": "wear", "value": { "gloss": "high" } } ] }"""),
        )
        val problems = Words.of(concepts + wear, emptyList(), words, behaviours = emptySet()).problems
        check(problems.any { "melted" in it && "none of" in it } && problems.any { "shiny" in it && "no part" in it }) { "a part that does not fit went unreported: $problems" }
        val holes = read(EffectTemplate.LIST_CODEC, """[ { "sets": "porosity", "when": "porous" } ]""").single()
        check(holes.applied(filled, Aspect.SURFACE) == Effect.Sets(ParameterKey("porosity", Aspect.SURFACE), "0.2..0.5")) { "the porous way did not hole the ground" }
        check(holes.applied(wear.filled(Value.Parts(emptyMap())), Aspect.SURFACE) == null) { "the eroded way holed the ground" }
    }

    test("every shipped concept reads") {
        val directory = MinecraftRegistries.resourceRoot().resolve("data/agesandtheart/${Words.CONCEPT_DIRECTORY}")
        val files = directory.listDirectoryEntries().filter { it.extension == "json" }
        check(files.isNotEmpty()) { "no concepts are shipped under $directory" }
        for (file in files) {
            val codec = Concept.mapCodec(file.nameWithoutExtension.location()).codec()
            val parsed = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file)))
            check(parsed.isSuccess) { "${file.fileName} does not read: ${parsed.error().map { it.message() }.orElse("")}" }
        }
    }
})
