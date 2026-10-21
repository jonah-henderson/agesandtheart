package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the transcriber copies a bound linking book into the book under it over time, spending that book and keeping
 * the original, and waits with a copy untaken: two books under one original make one copy until it is taken.
 * A notebook is written over as a book is, and a book with no original over it is left alone.
 *
 * On a server because the copying is the block entity's own ticking.
 */
@Tags(NEEDS_SERVER)
class TranscriberOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun transcriberAt(x: Int, original: Boolean, destination: String) {
        server.run("forceload add $x 0")
        server.waitUntil("the chunk at ($x, 0) loading") { server.run("execute if loaded $x 0 0").startsWith("Test passed") }
        server.run("setblock $x $TRANSCRIBER_Y 0 agesandtheart:transcriber")
        if (original) server.run("item replace block $x $TRANSCRIBER_Y 0 container.$ORIGINAL with $BOUND_BOOK")
        server.run("item replace block $x $TRANSCRIBER_Y 0 container.$DESTINATION with $destination")
    }

    fun holdsIn(x: Int, slot: Int, item: String) =
        server.run("execute if items block $x $TRANSCRIBER_Y 0 container.$slot $item").startsWith("Test passed")

    fun holds(x: Int): String = server.run("data get block $x $TRANSCRIBER_Y 0 Items")

    test("a bound book is copied into the book under it, which is spent, and the original kept") {
        transcriberAt(BOOKS_X, original = true, destination = "minecraft:book 2")
        transcriberAt(NOTEBOOK_X, original = true, destination = "agesandtheart:notebook")
        transcriberAt(NO_ORIGINAL_X, original = false, destination = "minecraft:book")
        server.run("tick sprint $ONE_COPY_AND_A_LITTLE")

        val copied = server.untilPasses(
            "execute if items block $BOOKS_X $TRANSCRIBER_Y 0 container.$COPY $COPY_OF_THE_BOUND_BOOK",
            tries = COPY_TRIES,
            pauseMillis = COPY_WAIT_MILLIS,
        )
        check(copied.startsWith("Test passed")) { "no copy was made from two books: ${holds(BOOKS_X)}" }
        check(holdsIn(BOOKS_X, ORIGINAL, COPY_OF_THE_BOUND_BOOK)) { "the original was not kept: ${holds(BOOKS_X)}" }
        check(holdsIn(BOOKS_X, DESTINATION, "minecraft:book[count=1]")) {
            "one copy waiting should have spent one book of two, and no more: ${holds(BOOKS_X)}"
        }
        val writtenOverTheNotebook = server.untilPasses(
            "execute if items block $NOTEBOOK_X $TRANSCRIBER_Y 0 container.$COPY $COPY_OF_THE_BOUND_BOOK",
            tries = COPY_TRIES,
            pauseMillis = COPY_WAIT_MILLIS,
        )
        check(writtenOverTheNotebook.startsWith("Test passed")) { "a notebook was not written over: ${holds(NOTEBOOK_X)}" }
        check(!holdsIn(NOTEBOOK_X, DESTINATION, "*")) { "the notebook was not spent: ${holds(NOTEBOOK_X)}" }
        check(holdsIn(NO_ORIGINAL_X, DESTINATION, "minecraft:book")) { "a book with nothing to copy was spent: ${holds(NO_ORIGINAL_X)}" }
    }
}) {
    private companion object {
        const val TRANSCRIBER_Y = 250
        const val BOOKS_X = 0
        const val NOTEBOOK_X = 4
        const val NO_ORIGINAL_X = 8

        const val ORIGINAL = 0
        const val DESTINATION = 1
        const val COPY = 2

        const val BOUND_BOOK = "agesandtheart:linking_book[agesandtheart:link_target=" +
            "{dimension:\"minecraft:overworld\",position:[0.5d,80d,0.5d],name:\"Home\"},max_stack_size=1]"
        const val COPY_OF_THE_BOUND_BOOK = "agesandtheart:linking_book[agesandtheart:link_target]"

        /** One copy at 400 ticks, and a margin over. */
        const val ONE_COPY_AND_A_LITTLE = 600

        const val COPY_TRIES = 40
        const val COPY_WAIT_MILLIS = 250L
    }
}
