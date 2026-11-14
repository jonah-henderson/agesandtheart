package co.voik.agesandtheart.preview.authoring.words

import io.kotest.core.spec.style.FunSpec

/** The new model's word files, as Scrivener reads and writes them. */
class WordFileCheck : FunSpec({

    /** A file the tool writes back unchanged, so opening a word and saving it moves nothing. */
    test("every authored word is written back exactly as its file holds it") {
        val names = WordFile.authoredNames()
        check(names.isNotEmpty()) { "no word files were found" }
        val moved = names.filter { name -> WordFile.textOf(WordFile.read(name).getOrThrow()) != WordFile.fileFor(name).readText() }
        check(moved.isEmpty()) {
            "these would change on being opened and saved, so `--rewrite` has not been run since they were edited: $moved"
        }
    }
})
