package co.voik.agesandtheart.server

import co.voik.agesandtheart.age.word.JudgedBook
import co.voik.agesandtheart.age.word.problemsWith
import co.voik.agesandtheart.book.FoundBookKind
import com.google.gson.JsonObject
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * `BookCheck`'s rules asked of the books a server writes, where the pack's own worldgen — `algae`,
 * `torchflowers` — is words rather than pages nobody can read. Offline those books are skipped, so this is
 * the only place they are judged at all.
 */
@Tags(NEEDS_SERVER)
class BookOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("every book a server writes is one worth finding") {
        val problems = FoundBookKind.entries.flatMap { kind ->
            val answer = server.ask("books", "${kind.key} $BOOKS_DRAWN")
            check(!answer.has("error")) { "asking for ${kind.key} books failed: ${answer.get("error")}" }
            val books = answer.getAsJsonArray("book").map { judged(kind, it.asJsonObject) }
            check(books.size == BOOKS_DRAWN) { "asked for $BOOKS_DRAWN ${kind.key} books and read ${books.size}" }
            books.flatMap(::problemsWith)
        }
        check(problems.isEmpty()) { "${problems.size} problems, the first of them:\n  ${problems.take(10).joinToString("\n  ")}" }
    }
})

/** A book as `/age books json` reports it, in the shape `problemsWith` judges. */
private fun judged(kind: FoundBookKind, book: JsonObject): JudgedBook {
    fun strings(key: String) = book.getAsJsonArray(key).map { it.asString }
    val resolved = book.get("resolved").asBoolean
    return JudgedBook(
        kind = kind,
        seed = book.get("seed").asLong,
        pages = strings("pages"),
        unplaced = strings("unplaced"),
        supplied = strings("supplied"),
        modifiers = strings("modifiers"),
        hasAnAge = book.get("has_an_age").asBoolean,
        cost = if (resolved) book.get("cost").asInt else null,
        instability = if (resolved) book.get("instability").asInt else null,
        flaws = strings("flaws"),
        // A report writes an absent value as an empty string.
        failure = book.get("failure").asString.ifEmpty { null },
    )
}

/** Fewer than offline, to keep one answer a reasonable size; still enough to draw every alternative. */
private const val BOOKS_DRAWN = 200
