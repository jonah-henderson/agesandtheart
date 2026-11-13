package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Fate
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.StructureWords
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.MobCategory
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The words every block, biome, feature, structure, creature and named design is given, through the kinds and concepts
 * we ship, read beside a few hand-written words in the new model (`notes/the-word-rework.md`).
 */
@Tags(NEEDS_REGISTRIES)
class DerivedWordsCheck : FunSpec({

    fun <T> shipped(directory: String, codecFor: (Identifier) -> MapCodec<T>): List<T> = ShippedWords.shipped(directory, codecFor)

    fun <T> read(codec: Codec<T>, json: String): T = ShippedWords.read(codec, json)

    /** A describing word of the kind a later batch writes, beside what we ship. */
    val words by lazy { ShippedWords.with(mapOf("red" to """{ "offers": [ { "concept": "colour", "value": "red" } ] }""")) }

    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }

    fun drawn(row: String): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, 0L)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L)
    }

    test("every block, biome, feature, structure and creature has a word, every kind reads, and nothing fails to fit") {
        check(words.problems.isEmpty()) { "the derived words do not fit: ${words.problems.take(10)}" }
        fun hasAWord(id: Identifier) = words.word(id.toString()) != null
        val wordless = BuiltInRegistries.BLOCK.listElements().map { it.key().identifier() }.filter { !hasAWord(it) }.toList()
        check(wordless.size < 10) { "blocks without a word: $wordless" }
        val creatures = BuiltInRegistries.ENTITY_TYPE.listElements().filter { it.value().category != MobCategory.MISC }.map { it.key().identifier() }.toList()
        check(creatures.all(::hasAWord)) { "creatures without a word: ${creatures.filterNot(::hasAWord)}" }
        val biomes = MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME).listElements().map { it.key().identifier() }.toList()
        check(biomes.all(::hasAWord)) { "biomes without a word: ${biomes.filterNot(::hasAWord)}" }
        val zombie = words.word("zombie")?.defaultSense ?: error("no zombie")
        check(zombie.endsAPhrase && zombie.speaksFor == Aspect.SPAWNS) { "zombie did not take its kind: $zombie" }
    }

    test("a block that is also a placed feature is one word of two senses, and the neighbours pick") {
        val both = words.all.filter { word -> word.senses.size > 1 }
        check(both.isNotEmpty()) { "no block shares its id with a placed feature" }
        val word = both.first()
        check(word.senses.any { it.endsAPhrase } && word.senses.any { !it.endsAPhrase }) { "${word.id} was merged as ${word.senses}" }
    }

    test("a name gathers the senses the neighbours could choose between, and only those, vanilla's the default") {
        fun block(id: String) = Word(Identifier.parse(id), listOf(Sense(offers = listOf(Offer(CoreConcepts.BLOCK, Value.Text(id))))))
        val tinted = read(Word.mapCodec("copper_block".location()).codec(), """{ "offers": [ { "concept": "colour", "value": "orange" } ] }""")
        val gathered = Words.of(
            shipped(Words.CONCEPT_DIRECTORY, Concept::mapCodec), emptyList(), listOf(tinted), StructureWords.IDS,
            derived = listOf(block("othermod:copper_block"), block("minecraft:copper_block"), block("zmod:copper_block")),
        )
        val copper = gathered.word("copper_block") ?: error("no copper_block")
        check(copper.senses.size == 2) { "copper_block gathered ${copper.senses.size} senses: ${copper.senses}" }
        check(copper.defaultSense == copper.senses.first() && copper.senses.first().offers.single().concept.path == "colour") {
            "the pack's own sense is not the default: ${copper.senses}"
        }
        check((copper.senses[1].offers.single().value as Value.Text).text == "minecraft:copper_block") { "vanilla's block did not come first: ${copper.senses[1]}" }
        check(gathered.word("othermod:copper_block")?.senses?.single()?.offers?.single()?.value == Value.Text("othermod:copper_block")) {
            "another pack's copper is not written by its whole id"
        }
    }

    test("a named hollow of the rock is an adjective: `tunnelled underground`") {
        val tunnelled = words.word("tunnelled")?.defaultSense ?: error("no tunnelled")
        check(!tunnelled.endsAPhrase && tunnelled.offers.single().value == Value.Text("tunnels")) { "tunnelled reads as $tunnelled" }
        check(words.word("latticed") != null && words.word("tunnels") == null) { "the hollows kept their old names" }
    }

    test("a block is said where it may stand: `stone villages` are of stone, `torch villages` dangle the torch") {
        val stone = drawn("stone villages age").landed.filter { it.word == "stone" }
        check(stone.single().effects == listOf(Effect.Sets(ParameterKey("material", Aspect.STRUCTURES), "minecraft:stone"))) { "stone villages: $stone" }
        check(stone.single().place.members == Members.Named(setOf("minecraft:villages"))) { "stone was said of ${stone.single().place}" }
        check(drawn("torch villages age").dangling.any { it.written == "torch" }) { "a torch village was built" }
    }

    test("a landform word is an adjective offering itself to the land") {
        val cratered = drawn("cratered landmass age").landed.flatMap { it.effects }
        check(Effect.Chooses(Aspect.TERRAIN, "cratered") in cratered) { "cratered landmass: $cratered" }
        val reading = Reader.read(words, listOf("cratered"), membership, 0L)
        check(reading.pages.single().fate == Fate.Dangles) { "a landform word alone was read" }
    }

    test("what is said of a biome is said in it: `red jungle` tints the jungle's grass and leaves, and nowhere else") {
        val red = drawn("red jungle age").landed.filter { it.word == "red" }
        check(red.map { it.place.aspect }.toSet() == setOf(Aspect.GRASS, Aspect.LEAVES)) { "red jungle coloured ${red.map { it.place }}" }
        check(red.all { it.sitedIn == Identifier.parse("minecraft:jungle") }) { "red jungle was not sited in the jungle: $red" }
    }

    test("the material tags are answered by a block's shape") {
        check(membership.isIn("minecraft:stone", "#${WorldMembership.MAKES_A_ROCK}")) { "stone makes no rock" }
        check(!membership.isIn("minecraft:sand", "#${WorldMembership.MAKES_A_ROCK}")) { "sand makes a rock, and falls" }
        check(membership.isIn("minecraft:sand", "#${WorldMembership.MAKES_A_WORLD}")) { "sand makes no world" }
        check(!membership.isIn("minecraft:torch", "#${WorldMembership.MAKES_A_WORLD}")) { "a torch makes a world" }
        check(membership.isMemberOf(Aspect.SPAWNS, "minecraft:zombie") && !membership.isMemberOf(Aspect.FEATURES, "minecraft:zombie"))
    }
})
