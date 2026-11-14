package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.reading.Trace
import co.voik.agesandtheart.age.reading.Written
import co.voik.agesandtheart.age.readout.Readout
import co.voik.agesandtheart.age.readout.ReadoutWriting
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A book written with the word being edited standing in for its saved self — what `^t` reads. */
class Trying(private val corpus: WordCorpus) {

    /** What a book of [pages] came to: how it reads, what became of each page, the Age it made and its flaws. */
    data class Tried(
        val readout: List<String>,
        val trace: List<String>,
        val spelled: String,
        val cost: Int,
        val flaws: List<String>,
    )

    fun of(candidate: Candidate, pages: List<String>, seed: Long = SEED): Tried {
        val words = corpus.with(listOf(candidate.word))
        val written: Written = corpus.writing(words).written(pages, seed)
        val resolution = written.resolution
        val clauses = Readout.of(written.reading, written.drawn, resolution, words)
        return Tried(
            readout = ReadoutWriting.sentencesOf(clauses) { key -> language.get(key)?.asString },
            trace = Trace.of(written.reading, written.drawn),
            spelled = CompositionSpelling.spell(CompositionSpelling.Written(resolution.composition, resolution.template, resolution.instability)),
            cost = resolution.cost,
            flaws = resolution.instability.flaws.map { it.describe() },
        )
    }

    /** The pack's own language file: offline there is no running game to ask. */
    private val language: JsonObject by lazy {
        val file = MinecraftRegistries.resourceRoot().resolve("assets/${Constants.MOD_ID}/lang/en_us.json").toFile()
        JsonParser.parseString(file.readText()).asJsonObject
    }

    companion object {
        /** The seed a book is tried at. */
        const val SEED = 7L
    }
}
