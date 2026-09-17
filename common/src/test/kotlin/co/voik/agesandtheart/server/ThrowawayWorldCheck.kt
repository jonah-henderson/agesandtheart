package co.voik.agesandtheart.server

import co.voik.agesandtheart.preview.authoring.PreviewServer
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Files

/**
 * The fence every throwaway world is removed past — the checks' `checks-` worlds and the workshop's
 * `workshop-` ones.
 *
 * **Asked rather than read**, and of both prefixes, because it stands between a hundred worlds made in an
 * afternoon and somebody's save. It needs no server, so it runs in the loop everyone runs.
 */
class ThrowawayWorldCheck : FunSpec({

    for (prefix in listOf(DrivenServer.CHECKS_WORLD_PREFIX, PreviewServer.WORKSHOP_WORLD_PREFIX)) {
        test("only a '$prefix' world, where it was put, is ever removed") {
            val runDirectory = File(System.getProperty("java.io.tmpdir"), "fencing-${System.nanoTime()}")
            try {
                val ours = runDirectory.resolve("${prefix}abc").also { it.mkdirs() }
                val theirs = runDirectory.resolve("my-survival-world").also { it.mkdirs() }
                val absent = runDirectory.resolve("${prefix}never-made")
                val linked = runDirectory.resolve("${prefix}link")
                Files.createSymbolicLink(linked.toPath(), theirs.toPath())

                check(ServerLaunch.isOursToRemove(ours, prefix)) { "would not remove a world it made itself" }
                check(!ServerLaunch.isOursToRemove(theirs, prefix)) { "would remove a world somebody else named" }
                check(!ServerLaunch.isOursToRemove(absent, prefix)) { "would remove a directory that is not there" }
                check(!ServerLaunch.isOursToRemove(linked, prefix)) { "would follow a link out of the run directory" }
            } finally {
                runDirectory.deleteRecursively()
            }
        }
    }
})
