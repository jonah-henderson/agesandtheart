package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import net.minecraft.resources.Identifier

/** The new model's words as we ship them ([WordCorpus]), with a spec's own words beside them where it needs some. */
object ShippedWords {
    /** What we ship, over the shipped vocabulary. */
    val corpus: WordCorpus by lazy { WordCorpus(ShippedCorpus.vocabulary) }

    fun <T> shipped(directory: String, codecFor: (Identifier) -> MapCodec<T>): List<T> = WordCorpus.shipped(directory, codecFor)

    fun <T> read(codec: Codec<T>, json: String): T = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()

    val written: List<Word> get() = corpus.written

    /** A setting turned, set or bent, with the value it was turned to. */
    fun turned(effect: Effect): Pair<ParameterKey, String>? = when (effect) {
        is Effect.Sets -> effect.parameter to effect.value
        is Effect.Bends -> effect.parameter to effect.value
        else -> null
    }

    /** Every word we ship, with [extra] written beside them, a name of theirs replacing ours. */
    fun with(extra: Map<String, String> = emptyMap()): Words =
        if (extra.isEmpty()) corpus.words else corpus.with(extra.map { (name, json) -> read(Word.mapCodec(name.location()).codec(), json) })
}
