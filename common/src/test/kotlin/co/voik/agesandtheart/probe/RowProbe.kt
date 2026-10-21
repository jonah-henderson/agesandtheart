package co.voik.agesandtheart.probe

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.language.Reader
import co.voik.agesandtheart.age.language.Writing
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

@Tags(NEEDS_REGISTRIES)
class RowProbe : FunSpec({
    test("probe") {
        val vocabulary = ShippedCorpus.vocabulary
        val lexicon = vocabulary.lexicon
        val rows = File(System.getenv("ROWS") ?: "/tmp/rows.txt").readLines().filter { it.isNotBlank() }
        val out = StringBuilder()
        for (row in rows) {
            val book = Reader.read(lexicon, row.split(" "), 0L)
            out.append("== $row\n${book?.readout ?: "does not read"}\n")
            if (book != null) {
                val r = Writing.resolve(lexicon, vocabulary, book, 0L)
                out.append("   => " + CompositionSpelling.spell(CompositionSpelling.Written(r.composition, r.template, r.instability)) + "\n")
            }
        }
        File(System.getenv("OUT") ?: "/tmp/rows-out.txt").writeText(out.toString())
    }
})
