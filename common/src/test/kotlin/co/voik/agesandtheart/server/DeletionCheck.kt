package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether deleting an Age really deletes it (design §7.8's `/age delete`, and `Ages.delete`).
 *
 * **The regression this exists for is a delete that only forgets.** Dropping the recipe and taking the level
 * out of the server is the visible half, and an Age that has gone from `/age list` looks deleted from every
 * angle a player has — while its region files sit on disk forever, and the *next* Age of that name opens on
 * top of them. So the assertions here are about the folder rather than about the listing.
 *
 * It also guards the order that makes that possible: region files are held open by a running level, so a
 * delete that unlinks before closing succeeds and leaves the directory behind.
 */
@Tags(NEEDS_SERVER)
class DeletionCheck : FunSpec({
    val server = DrivenServer.shared

    test("a deleted Age takes its saved chunks with it") {
        server.run("age write gone age hills landmass")
        // Generated on purpose: an Age nobody has visited has nothing on disk, so deleting one proves
        // nothing about deleting one that does.
        server.run("age gen gone")
        val saved = server.savedChunksOf("gone")
        check(saved.isDirectory) {
            "expected generated chunks at ${saved.path}, so this check cannot see what it guards"
        }

        server.run("age delete gone")

        check(!saved.exists()) { "'/age delete' left ${saved.path} behind" }
        check(!server.run("age list").contains("gone")) { "a deleted Age is still listed" }
    }

    test("the name comes free again, and the new Age is a new world") {
        server.run("age write reused age hills landmass")
        server.run("age gen reused")
        server.run("age delete reused")

        // The same name, written again. What is being guarded is that this is not quietly the old one:
        // re-opening on top of orphaned region files is the failure the check above is really about.
        val written = server.run("age write reused age pillars landmass")
        check(written.contains("reused")) { "the name did not come free after deletion:\n$written" }
        check(server.run("age list").contains("reused")) { "the rewritten Age is not listed" }
    }
})
