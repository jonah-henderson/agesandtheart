package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Villagers leave a lectern holding a book of ours alone (`VillagersAtOurLecterns`): an unemployed one does not take
 * it, and a librarian gives it up when a linking book is laid on it. Each check is a walled pen of its own in the sky,
 * far enough from the others that no villager's job-site search reaches another's lectern.
 */
@Tags(NEEDS_SERVER)
class VillagerLecternsOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun passes(test: String) = inWorld("execute $test").startsWith("Test passed")

    /** A pen at [x] with an unemployed villager tagged [tag] in it and a lectern holding [book] three blocks off. */
    fun penWith(tag: String, x: Int, book: String) {
        inWorld("forceload add $x $Z")
        server.waitUntil("the pen's chunk loading") { passes("if loaded $x $Y $Z") }
        inWorld("fill ${x - PEN} ${Y - 1} ${Z - PEN} ${x + PEN} ${Y + 3} ${Z + PEN} minecraft:barrier hollow")
        inWorld("fill ${x - PEN + 1} ${Y - 1} ${Z - PEN + 1} ${x + PEN - 1} ${Y - 1} ${Z + PEN - 1} minecraft:stone")
        inWorld("setblock ${x + LECTERN_OFF} $Y $Z minecraft:lectern[has_book=true]{Book:{id:\"$book\",count:1}}")
        inWorld(
            "summon minecraft:villager $x $Y $Z {Tags:[\"$tag\"]," +
                "VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}",
        )
    }

    fun villager(tag: String) = "@e[type=minecraft:villager,tag=$tag,limit=1]"

    fun isLibrarian(tag: String) = passes("if data entity ${villager(tag)} VillagerData{profession:\"minecraft:librarian\"}")

    fun holdsAJobSite(tag: String) = passes("if data entity ${villager(tag)} Brain.memories.\"minecraft:job_site\"")

    /** Sprints until [tag] is a librarian or [MOST_TICKS] have passed, answering which. */
    fun becomesALibrarian(tag: String): Boolean {
        repeat(MOST_TICKS / TICKS_PER_LOOK) {
            server.sprint(TICKS_PER_LOOK)
            if (isLibrarian(tag)) return true
        }
        return false
    }

    test("a villager takes a lectern holding a book and quill") {
        penWith("quill", QUILL_X, "minecraft:writable_book")
        check(becomesALibrarian("quill")) { "the villager never took the lectern, so nothing below shows anything" }
    }

    test("a villager does not take a lectern holding a linking book") {
        penWith("linking", LINKING_X, "agesandtheart:linking_book")
        check(!becomesALibrarian("linking")) { "the villager took a lectern holding a linking book" }
        check(!holdsAJobSite("linking")) { "the villager holds the lectern as its job site" }
    }

    test("a librarian gives up its lectern when a descriptive book is laid on it") {
        penWith("leaving", LEAVING_X, "minecraft:writable_book")
        check(becomesALibrarian("leaving")) { "the villager never took the lectern" }
        inWorld("data modify block ${LEAVING_X + LECTERN_OFF} $Y $Z Book set value {id:\"agesandtheart:descriptive_book\",count:1}")
        server.sprint(LETTING_GO_TICKS)
        check(!holdsAJobSite("leaving")) { "the librarian kept a lectern holding a descriptive book" }
    }
})

// Out of every other check's way: the shared server's forced chunks and wanderers stay put between specs, and a
// villager beside the warden's box kept it from ever digging down.
private const val Y = 200
private const val Z = -7000
private const val QUILL_X = -7000
private const val LINKING_X = -7300
private const val LEAVING_X = -7600

/** Half the pen's width, walls included. */
private const val PEN = 5
private const val LECTERN_OFF = 3

private const val TICKS_PER_LOOK = 100
private const val MOST_TICKS = 1200

/** A villager looks once a second, staggered; two seconds covers it. */
private const val LETTING_GO_TICKS = 40
