package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * The world the tool answers from — **offline, and exact as far as it goes**.
 *
 * `Vocabulary.load` with vanilla's worldgen registries is the same corpus `VocabularyCheck` reads: every
 * authored word, every tag table, every preset, and vanilla's biomes, features and structure sets. It
 * costs a second and needs no build and no server.
 *
 * What it cannot see is [Vocabulary.tagsOnlyAServerGrants] — a tag no authored table and no derivation
 * fact grants, `ore` being the case — and any content a modpack adds. That is what [snapshot] is for: a
 * server refresh is remembered rather than required, so every later run is offline again.
 */
class Corpus(val vocabulary: Vocabulary, val snapshot: ServerSnapshot?) {

    /** Every word but [name], which is what a candidate must be compared against rather than itself. */
    fun otherThan(name: String): List<Word> = vocabulary.words.filterNot { it.name == name }

    /**
     * This corpus with a snapshot taken from a server now, and written down for every later run — the one
     * way a screen refreshes, so none of them goes on reading the snapshot it had.
     *
     * [progress] is how the minutes of a server booting reach whoever asked.
     */
    fun withFreshSnapshot(progress: (ServerSnapshot.Progress) -> Unit): Corpus {
        val taken = ServerSnapshot.refresh(attach = null, vocabulary.tagsOnlyAServerGrants, say = progress)
        taken.write()
        return Corpus(vocabulary, taken)
    }

    /**
     * Which registry a derived word was read off, as the tag directory that holds its tags.
     *
     * **Asked of the registries rather than guessed from the id**, which cannot be done: `the_end` is a
     * biome and `end_stone` a block and nothing in either string says so. Null where nothing in the game
     * has that id, which for a word the corpus produced should not happen.
     */
    fun registryOf(id: Identifier): String? = when {
        BuiltInRegistries.BLOCK.containsKey(id) -> "block"
        holds(Registries.BIOME, id) -> "worldgen/biome"
        holds(Registries.STRUCTURE_SET, id) -> "worldgen/structure_set"
        else -> null
    }

    private fun <T : Any> holds(registry: ResourceKey<Registry<T>>, id: Identifier): Boolean =
        MinecraftRegistries.worldgen.lookup(registry).orElse(null)
            ?.get(ResourceKey.create(registry, id))?.isPresent ?: false

    /**
     * The corpus with [candidate] in it, for the questions that need a whole vocabulary rather than one
     * word — reading a sentence, and resolving it.
     *
     * **Built by laying a one-file pack over the source tree** and loading again, rather than by reaching
     * into `Vocabulary`'s own maps. It is the path the game takes, so a candidate that would collide with
     * something, or be refused, says so here exactly as it would there. About a second, which is why it
     * is asked for by a keystroke and not on every edit.
     */
    fun spliced(candidate: Candidate): Vocabulary {
        val overlay = Files.createTempDirectory("art-candidate-").toFile()
        val word = overlay.resolve("data/${Constants.MOD_ID}/${Vocabulary.WORD_DIRECTORY}")
        word.mkdirs()
        word.resolve("${candidate.name}.json").writeText(WordFile.textOf(candidate))
        return try {
            Vocabulary.load(resourcesOver(overlay), MinecraftRegistries.worldgen)
        } finally {
            overlay.deleteRecursively()
        }
    }

    private fun resourcesOver(overlay: File) = MultiPackResourceManager(
        PackType.SERVER_DATA,
        listOf(
            packAt(MinecraftRegistries.resourceRoot(), "agesandtheart"),
            packAt(overlay.toPath(), "agesandtheart-candidate"),
        ),
    )

    private fun packAt(root: Path, named: String) = PathPackResources(
        PackLocationInfo(named, Component.literal(named), PackSource.BUILT_IN, Optional.empty()),
        root,
    )

    companion object {
        /**
         * The corpus, stood up. Slow once — Minecraft's registries are the only expensive thing here —
         * and then every question is a map lookup.
         */
        fun load(): Corpus {
            // **The real streams, taken back.** `Bootstrap.bootStrap()` redirects System.out into log4j,
            // which reprints every line with a timestamp and an `[STDOUT]` prefix — fatal to a frame that
            // has to redraw in place. Held across the call rather than restored globally somewhere, so
            // nothing can bootstrap without this happening.
            val console = System.out
            val complaints = System.err
            MinecraftRegistries.ensureStoodUp()
            System.setOut(console)
            System.setErr(complaints)
            val vocabulary = Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen)
            return Corpus(vocabulary, ServerSnapshot.read())
        }
    }
}
