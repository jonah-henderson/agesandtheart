package co.voik.agesandtheart.preview.meanings

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.language.WordFormat
import co.voik.agesandtheart.location
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** Every shipped word converts onto meanings and reads back (`notes/meanings-plan.md` phase 2). */
@Tags(NEEDS_REGISTRIES)
class WordConversionCheck : FunSpec({
    val art = Path.of("src/main/resources/data/agesandtheart/art")

    fun oldFile(directory: String, name: String): JsonObject =
        JsonParser.parseString(art.resolve(directory).resolve("$name.json").readText()).asJsonObject

    fun read(name: String, converted: JsonObject): Word =
        WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, converted)
            .resultOrPartial { check(false) { "$name does not read after converting: $it\n$converted" } }
            .orElseThrow()

    fun converted(name: String): Word = read(name, WordConversion.converted(name, oldFile("word", name)))

    fun Word.meaningSetting(parameter: String): List<Meaning> =
        meanings.filter { meaning -> meaning.effects.any { it is Effect.Sets && it.parameter.spelled == parameter } }

    test("every shipped word converts, reads back, and keeps nothing the old format had") {
        val files = art.resolve("word").listDirectoryEntries("*.json")
        check(files.size > 100) { "only ${files.size} word files were found" }
        for (file in files) {
            val name = file.nameWithoutExtension
            val written = WordConversion.converted(name, JsonParser.parseString(file.readText()).asJsonObject)
            val leftOver = written.keySet() - setOf("_comment", "speech", "meanings")
            check(leftOver.isEmpty()) { "$name kept $leftOver" }
            read(name, written)
        }
    }

    test("a word's own claims must always apply, and its alternatives become siblings") {
        val frozen = converted("frozen")
        check(frozen.meaningSetting("temperature").single().mustAlwaysApply) { "frozen is not always cold" }
        val skies = frozen.meaningSetting("sky.colour")
        check(skies.size == 4 && skies.none(Meaning::mustAlwaysApply)) { "frozen's skies came out as $skies" }
        val halos = frozen.meanings.filter { meaning -> meaning.effects.count { it is Effect.Sets } == 4 }
        check(halos.size == 2) { "the ice halo, white or blue, came out as ${halos.size} meanings" }
        check(halos.all { it.chanceToApply < 1.0 }) { "the ice halo applies whenever frozen is aimed" }
    }

    test("unaimed becomes a chance unaimed where it names every place a meaning could reach bare") {
        val tidal = converted("tidal").meaningSetting("pull").single()
        check(!tidal.mustAlwaysApply && tidal.unaimedChanceToApply == Meaning.NEVER) { "a bare tidal pulls: $tidal" }
        val stone = converted("red").meaningSetting("stone").single()
        check(stone.unaimedChanceToApply == 0.1) { "a bare red reddens the rock as $stone" }
        check(converted("red").meaningSetting("colour").single().mustAlwaysApply) { "red is not always red" }
        val path = converted("polar").meaningSetting("path").single()
        check(path.unaimedChanceToApply == 0.25) { "a bare polar sun comes as $path" }
    }

    test("a meaning that reaches a dial only an aimed word turns keeps applying elsewhere") {
        check(converted("colossal").meaningSetting("size").single().mustAlwaysApply) { "colossal lost its size laid bare" }
        check(converted("strong").meaningSetting("pull").single().mustAlwaysApply) { "strong lost its tides laid bare" }
    }

    test("a minting asserts, and so does a word that takes a body away") {
        val obelisks = converted("obelisks").meanings.single()
        check(obelisks.asserts && obelisks.effects.single() is Effect.Mints) { "obelisks came out as $obelisks" }
        check(converted("sunless").meanings.all(Meaning::asserts)) { "sunless needs something to take it" }
    }

    test("a quantifier becomes a word with one abundance") {
        val teeming = read("teeming", requireNotNull(WordConversion.quantifier(oldFile("grammar", "teeming"))))
        val abundance = teeming.meanings.single().effects.single()
        check(abundance == Effect.Abundance(4.0)) { "teeming came out as $abundance" }
        check(WordConversion.quantifier(oldFile("grammar", "and")) == null) { "`and` became a word" }
    }
})
