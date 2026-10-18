package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A book left lying where somebody linked from is destroyed as a dropped item is — by lava, by a blast — and not by
 * a player's blow. `/damage` asks the entity directly, so this needs nothing to tick.
 */
@Tags(NEEDS_SERVER)
class LeftBookOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    val loaded by lazy {
        inWorld("forceload add $X $Z")
        server.waitUntil("the book's chunk loading") { inWorld("execute if loaded $X $Y $Z").startsWith("Test passed") }
    }

    /** Lays a linking book down at the check's spot, tagged [tag], having cleared any left by another test. */
    fun leaveABook(tag: String) {
        loaded
        inWorld("kill @e[type=agesandtheart:descriptive_book]")
        inWorld("summon agesandtheart:descriptive_book $X $Y $Z {Tags:[\"$tag\"],book:{id:\"agesandtheart:linking_book\",count:1}}")
    }

    fun stillThere(tag: String) = inWorld("execute if entity @e[type=agesandtheart:descriptive_book,tag=$tag]").startsWith("Test passed")

    test("lava takes a left book") {
        leaveABook("burned")
        inWorld("damage @e[type=agesandtheart:descriptive_book,tag=burned,limit=1] 10 minecraft:lava")
        check(!stillThere("burned")) { "the book survived lava" }
    }

    test("a blast takes a left book") {
        leaveABook("blasted")
        inWorld("damage @e[type=agesandtheart:descriptive_book,tag=blasted,limit=1] 10 minecraft:explosion")
        check(!stillThere("blasted")) { "the book survived a blast" }
    }

    test("a player's blow does not") {
        leaveABook("struck")
        inWorld("damage @e[type=agesandtheart:descriptive_book,tag=struck,limit=1] 10 minecraft:player_attack")
        check(stillThere("struck")) { "a blow took the book" }
    }
})

private const val X = 4000
private const val Y = 120
private const val Z = 4000
