package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.meanings.WordConversion
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.io.path.readText

/** The word on meanings: its file, the words derived for free, what each takes, and its price. */
@Tags(NEEDS_REGISTRIES)
class WordCheck : FunSpec({

    fun parsed(json: String) = WordFormat.mapCodec("example".location()).codec()
        .parse(JsonOps.INSTANCE, JsonParser.parseString(json))

    fun shipped(name: String): Word {
        val file = Path.of("src/main/resources/data/agesandtheart/art/word/$name.json")
        val converted = WordConversion.converted(name, JsonParser.parseString(file.readText()).asJsonObject)
        return WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, converted).orThrow
    }

    val tags = object : MemberTags {
        override fun availableToBroadWordsIn(aspect: Aspect): List<Taggable> =
            ShippedCorpus.vocabulary.availableToBroadWordsIn(aspect)

        override fun tagsOf(member: Taggable): Map<String, Double> = ShippedCorpus.vocabulary.tagsOf(member)
    }

    fun takes(word: Word) = WordAcceptance.of(word, tags, MinecraftRegistries.worldgen)

    test("a word file reads back exactly as it was written") {
        val word = Word(
            "example".location(),
            listOf(
                Meaning(listOf(Effect.Sets(ParameterKey("size"), "1.0..1.0")), mustAlwaysApply = true),
                Meaning(listOf(Effect.Restricts(Aspect.TERRAIN, mapOf("monumental" to 0.7))), chanceToApply = 0.5),
                Meaning(listOf(Effect.Sets(ParameterKey("colour", Aspect.SUN), "red")), unaimedChanceToApply = 0.0),
                Meaning(listOf(Effect.LeansEverywhere(mapOf("#lush" to 0.4)))),
            ),
        )
        val codec = WordFormat.mapCodec(word.id).codec()
        val written = codec.encodeStart(JsonOps.INSTANCE, word).orThrow
        check(codec.parse(JsonOps.INSTANCE, written).orThrow == word) { "the word changed on its way through: $written" }
    }

    test("a meaning that does nothing, or must always apply by chance, or by more than certainty, is refused") {
        check(parsed("""{"meanings": [{"chance_to_apply": 0.5}]}""").isError) { "a meaning with no effect read" }
        check(parsed("""{"meanings": [{"sets": {"size": "1..1"}, "must_always_apply": true, "chance_to_apply": 0.5}]}""").isError) {
            "a meaning that must always apply took a chance"
        }
        check(parsed("""{"meanings": [{"sets": {"size": "1..1"}, "chance_to_apply": 1.5}]}""").isError) { "a chance of 1.5 read" }
        check(parsed("""{"meanings": [{"leans": {"nowhere": {"#lush": 1}}}]}""").isError) { "a lean on no part of the world read" }
    }

    test("a block laid bare is the rock or the skin or both, a sea where it pours, structures only aimed, now and then a formation") {
        val blocks = DerivedWords.blocks().associateBy { it.id.toString() }
        fun Word.setting(spelled: String) =
            meanings.single { meaning -> meaning.effects.any { it is Effect.Sets && it.parameter.spelled == spelled } }
        fun Word.asTheSea() = meanings.single { meaning -> meaning.effects.any { it is Effect.Chooses } }
        val water = requireNotNull(blocks["minecraft:water"])
        val stone = requireNotNull(blocks["minecraft:stone"])
        check(water.asTheSea().mustAlwaysApply) { "water laid bare is not a sea" }
        check(stone.asTheSea().unaimedChanceToApply == Meaning.NEVER) { "stone laid bare floods the world" }
        val ground = stone.setting("landmass.stone")
        check(ground == stone.setting("surface.material") && ground.mustAlwaysApply) {
            "stone laid bare is not one meaning applying to the rock or the skin or both: $ground"
        }
        check(stone.setting("structures.material").unaimedChanceToApply == Meaning.NEVER) { "stone laid bare builds structures" }
        val formations = stone.meanings.filter { meaning -> meaning.effects.any { it is Effect.Mints } }
        check(formations.size == DerivedWords.FORMATIONS.size) { "stone raises ${formations.size} formations" }
        check(formations.all { it.chanceToApply == Meaning.NEVER && it.asserts }) { "a formation is raised where stone is aimed" }
        check(water.meanings.none { meaning -> meaning.effects.any { it is Effect.Mints } }) { "water is raised as an obelisk" }
    }

    test("the derived populations each give a word per entry, and every part of the world has its page") {
        val registries = MinecraftRegistries.worldgen
        for ((what, words) in listOf(
            "biomes" to DerivedWords.biomes(registries),
            "features" to DerivedWords.features(registries),
            "structures" to DerivedWords.structures(registries),
            "creatures" to DerivedWords.spawns(),
            "designs" to DerivedWords.designs(),
        )) {
            check(words.isNotEmpty()) { "no $what were derived" }
        }
        check(DerivedWords.aspectPages().map { it.name }.toSet() == Aspect.entries.map { it.page }.toSet()) {
            "some part of the world has no page"
        }
    }

    test("a page takes its part of the world, a member word takes its members, and an adjective takes nothing") {
        val climate = DerivedWords.aspectPages().single { it.name == "climate" }
        check(takes(climate)?.takesParameter("temperature") == true) { "the climate's page takes no temperature" }
        val villages = DerivedWords.structures(MinecraftRegistries.worldgen).single { it.id.toString() == "minecraft:villages" }
        check(takes(villages)?.takesParameter("abandoned") == true) { "`deserted villages` has nothing to land on" }
        val trees = takes(shipped("trees"))
        val named = trees?.place?.members as? Members.Named
        check(named != null && named.keys.size > 1) { "trees name ${trees?.place}" }
        check(trees.takesParameter("bare") && trees.takesParameter("size")) { "trees take ${trees.parameters}" }
        check(takes(shipped("obelisks"))?.takesSubstance == true) { "`gold_block obelisks` has no obelisks to make of gold" }
        check(takes(shipped("colossal")) == null) { "colossal takes pages" }
        check(takes(DerivedWords.blocks().single { it.id.toString() == "minecraft:stone" }) == null) { "a block takes pages" }
    }

    test("a word costs four for each part of the world it is certain to narrow where aimed, and leans cost nothing") {
        check(shipped("beautiful").price == Word.FLOOR_PRICE) { "beautiful costs ${shipped("beautiful").price}" }
        check(shipped("sunless").price == Word.INK_PER_NARROWED_PART) { "sunless costs ${shipped("sunless").price}" }
        val colossal = shipped("colossal")
        val sizedParts = Aspect.entries.count { it.ownsParameterNamed("size") }
        check(colossal.price == Word.INK_PER_NARROWED_PART * sizedParts) {
            "colossal costs ${colossal.price}, where its size reaches $sizedParts parts and its bar one of them"
        }
    }
})
