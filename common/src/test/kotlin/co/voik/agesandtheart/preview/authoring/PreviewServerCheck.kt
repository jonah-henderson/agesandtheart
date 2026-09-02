package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.server.LaunchSpec
import co.voik.agesandtheart.server.NEEDS_SERVER
import co.voik.agesandtheart.server.NEEDS_TIME
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Files

/**
 * The server the workshop opens an Age into.
 *
 * **What is checked here is the teardown**, more than the boot. A tool that stands a world up every time
 * somebody wants to look at a half-written book will do it a hundred times in an afternoon, so the world
 * going away again is not tidiness - it is the whole reason the feature is safe to use. The fencing that
 * makes the removal safe is exercised rather than read.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class PreviewServerCheck : FunSpec({

    test("a book becomes an Age you could stand in, and the world goes away after") {
        val draft = AgeDraft("workshop_preview", listOf("age", "hills", "landmass"))
        val world: File
        PreviewServer.boot { }.use { server ->
            world = worldOf()
            check(world.isDirectory) { "no world at ${world.absolutePath}" }
            val said = server.write(draft)
            check(said.contains(draft.name)) { "writing the Age said '$said'" }
            check(server.run("age list").contains(draft.name)) { "'${draft.name}' was not listed" }
            // Written twice, because the loop this exists for is change a page and press the key again.
            val again = server.write(draft.copy(pages = listOf("age", "flat", "landmass")))
            check(again.contains(draft.name)) { "rewriting an Age that already existed said '$again'" }
            check(server.playersOnline().isEmpty()) { "somebody was already logged in" }
            check(server.sendEveryoneTo(draft.name) == 0) { "said it sent players when there were none" }
        }
        check(!world.exists()) { "the workshop world was left behind at ${world.absolutePath}" }
    }

    /**
     * The fencing stands between a hundred throwaway worlds and somebody's save, so every way past it is
     * asked about rather than trusted.
     */
    test("only a world this tool named, where it put it, is ever removed") {
        val runDirectory = File(System.getProperty("java.io.tmpdir"), "workshop-fencing-${System.nanoTime()}")
        try {
            val ours = runDirectory.resolve("workshop-abc").also { it.mkdirs() }
            val theirs = runDirectory.resolve("my-survival-world").also { it.mkdirs() }
            val absent = runDirectory.resolve("workshop-never-made")
            val linked = runDirectory.resolve("workshop-link")
            Files.createSymbolicLink(linked.toPath(), theirs.toPath())

            check(PreviewServer.isOursToRemove(ours)) { "would not remove a world it made itself" }
            check(!PreviewServer.isOursToRemove(theirs)) { "would remove a world somebody else named" }
            check(!PreviewServer.isOursToRemove(absent)) { "would remove a directory that is not there" }
            check(!PreviewServer.isOursToRemove(linked)) { "would follow a link out of the run directory" }
        } finally {
            runDirectory.deleteRecursively()
        }
    }
})

/** Where the running server put its world, read back off the settings it was started with. */
private fun worldOf(): File {
    val launch = LaunchSpec.read()
    val level = launch.workingDirectory.resolve("server.properties").readLines()
        .first { it.startsWith("level-name=") }.substringAfter('=')
    return launch.workingDirectory.resolve(level)
}
