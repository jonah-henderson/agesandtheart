package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What a book reads as: each word said of the part it applied to, in the writer's words, and only what held — so a
 * player learns the grammar from it. In English, off this pack's language file; vanilla's names fall back to ids.
 */
@Tags(NEEDS_REGISTRIES)
class ProseCheck : FunSpec({

    val language by lazy {
        val file = ProseCheck::class.java.getResourceAsStream("/assets/agesandtheart/lang/en_us.json")
            ?: error("the language file is not on the classpath")
        JsonParser.parseString(file.reader().readText()).asJsonObject
    }

    fun readAs(row: String): String {
        val vocabulary = ShippedCorpus.vocabulary
        val reading = ShippedCorpus.read(row.split(" "))
        val clauses = vocabulary.prose(reading, ShippedCorpus.ANY_SEED)
        return ProseWriting.sentencesOf(clauses) { key -> language.get(key)?.asString }.joinToString(" ")
    }

    for ((row, expected) in BOOKS) {
        test("`$row` reads as \"$expected\"") {
            val said = readAs(row)
            check(said == expected) { "`$row` read as \"$said\"" }
        }
    }
}) {
    private companion object {
        val BOOKS = listOf(
            "age large insular landmass tunnels underground" to
                "An Age. Its landmass is large and insular. Its underground holds tunnels.",
            "age uncanny landmass" to "An Age. Its landmass is uncanny.",
            "age large spider large beasts" to "An Age. Its spiders are large. Its beasts are large.",
            "age teeming large spider" to "An Age. Its spiders are teeming and large.",
            "age frozen scorching climate" to "An Age. Its climate is frozen in places and scorching in others.",
            "age insular pillared landmass" to "An Age. Its landmass is insular in places and pillared in others.",
            "age insular and pillared landmass" to "An Age. Its landmass is insular and pillared.",
            "age herds of warden" to "An Age. Herds of wardens dwell in it.",
            "age teeming zombie no zombie" to "An Age. No zombies dwell in it.",
            "age no snow insular landmass" to "An Age without snow. Its landmass is insular.",
            "homely age gentle landmass" to "A homely Age. Its landmass is gentle.",
        )
    }
}
