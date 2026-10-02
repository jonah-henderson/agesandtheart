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
// Two thirds of the server suite between this and its sibling — it builds and destroys whole worlds on disk.
@Tags(NEEDS_SERVER, NEEDS_TIME)
class DeletionCheck : FunSpec({
    val server = DrivenServer.shared

    test("a deleted Age takes its saved chunks with it") {
        server.run("age write gone age gentle landmass")
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

    test("a renamed Age takes its saved chunks with it and leaves nothing behind") {
        // The crystal viewer's preview, renamed after its book's title as the book takes it over.
        server.run("age write age_preview age gentle landmass")
        server.run("age gen age_preview")
        val before = server.savedChunksOf("age_preview")
        check(before.isDirectory) { "expected generated chunks at ${before.path}, so this check cannot see what it guards" }

        val renamed = server.run("age rename age_preview titled")
        check(renamed.contains("Renamed")) { "the rename was refused:\n$renamed" }

        // **Region files rather than a comparison with the old folder**: a generated chunk is held in memory
        // until the level saves, so the old folder can be empty when looked at. Region files under the new
        // id are what the rename's save-then-move puts there, and all a reopened Age has to read.
        val after = server.savedChunksOf("titled")
        check(!before.exists()) { "'/age rename' left ${before.path} behind" }
        val regions = after.resolve("region").listFiles { file -> file.name.endsWith(".mca") }.orEmpty()
        check(regions.isNotEmpty()) { "the renamed Age has no region files at ${after.path}: its chunks were lost" }
        val listed = server.run("age list")
        check(listed.contains("titled") && !listed.contains("age_preview")) { "the listing did not follow the rename:\n$listed" }
        server.run("age delete titled")
    }

    test("a rename onto a taken name is refused and moves nothing") {
        server.run("age write kept_a age gentle landmass")
        server.run("age write kept_b age gentle landmass")
        val refused = server.run("age rename kept_a kept_b")
        check(refused.contains("Could not rename")) { "a rename onto an existing Age went through:\n$refused" }
        val listed = server.run("age list")
        check(listed.contains("kept_a") && listed.contains("kept_b")) { "a refused rename lost an Age:\n$listed" }
        server.run("age delete kept_a")
        server.run("age delete kept_b")
    }

    test("the name comes free again, and the new Age is a new world") {
        server.run("age write reused age gentle landmass")
        server.run("age gen reused")
        server.run("age delete reused")

        // The same name, written again. What is being guarded is that this is not quietly the old one:
        // re-opening on top of orphaned region files is the failure the check above is really about.
        val written = server.run("age write reused age pillared landmass")
        check(written.contains("reused")) { "the name did not come free after deletion:\n$written" }
        check(server.run("age list").contains("reused")) { "the rewritten Age is not listed" }
    }
})
