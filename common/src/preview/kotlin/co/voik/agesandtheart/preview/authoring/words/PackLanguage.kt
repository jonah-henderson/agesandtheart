package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.reading.Written
import co.voik.agesandtheart.age.readout.Readout
import co.voik.agesandtheart.age.readout.ReadoutWriting
import co.voik.agesandtheart.age.words.Words
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The pack's own English, which a readout is written in offline, where there is no running game to ask. */
object PackLanguage {

    private val language: JsonObject by lazy {
        val file = MinecraftRegistries.resourceRoot().resolve("assets/${Constants.MOD_ID}/lang/en_us.json").toFile()
        JsonParser.parseString(file.readText()).asJsonObject
    }

    val said: (String) -> String? = { key -> language.get(key)?.asString }

    /** What a book [written] of [words] reads as, a sentence a thing. */
    fun readout(written: Written, words: Words): List<String> =
        ReadoutWriting.sentencesOf(Readout.of(written.reading, written.drawn, written.resolution, words), said)
}
